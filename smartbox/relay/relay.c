/*
 * Реле для своей прошивки бокса smartBox (Anyka AK3918, Linux 4.4).
 * Повторяет android/dongle/.../RelayService.java (режим runDirectLoop) на C.
 *
 * Бокс стоит AOA-аксессуаром перед ГУ и клиентом точки телефона. Поток ГУ идёт одним TCP-сокетом
 * на телефон (Headswap слушает PHONE_PORT в режиме «Свисток»).
 *   ГУ → телефон: сырые байты, как пришли.
 *   телефон → ГУ: кадрами [chan u32][len u32] + тело; заголовок отдельным write, тело кусками ≤ CHUNK
 *                 (часть ГУ чувствительна к границам USB-передач, TCP их стирает — ADR 0009).
 *
 * Драйвер аксессуара пускает только одно открытие (open_excl), поэтому один fd на чтение и запись.
 * Поднятие USB-гаджета и Wi-Fi-клиента — дело прошивки (start_accessory.sh, wpa_ctrl); тут только труба.
 *
 * Сборка статически под ARM EABI5 soft-float — см. Makefile. Параметры:
 *   relay [phone_host] [accessory_path]
 * По умолчанию phone_host = шлюз из /proc/net/route, accessory_path = /dev/usb_accessory.
 */
#include <stdio.h>
#include <stdlib.h>
#include <stdarg.h>
#include <string.h>
#include <stdint.h>
#include <unistd.h>
#include <errno.h>
#include <fcntl.h>
#include <time.h>
#include <poll.h>
#include <pthread.h>
#include <sys/socket.h>
#include <sys/select.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <arpa/inet.h>

#define PHONE_PORT   7250
#define CHUNK        16384
#define MAX_FRAME    (64u * 1024u * 1024u)
#define CONNECT_MS   2000
#define SOCK_BUF     (128 * 1024)

static const char *DEV_DEFAULT = "/dev/usb_accessory";

static void logts(const char *fmt, ...) {
    char ts[16];
    time_t t = time(NULL);
    struct tm tm;
    localtime_r(&t, &tm);
    strftime(ts, sizeof ts, "%H:%M:%S", &tm);
    fprintf(stderr, "%s ", ts);
    va_list ap;
    va_start(ap, fmt);
    vfprintf(stderr, fmt, ap);
    va_end(ap);
    fputc('\n', stderr);
    fflush(stderr);
}

/* Записать ровно len байт; устойчиво к частичным write и EINTR. 0 — ок, -1 — ошибка. */
static int writen(int fd, const unsigned char *buf, size_t len) {
    size_t off = 0;
    while (off < len) {
        ssize_t n = write(fd, buf + off, len - off);
        if (n < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        off += (size_t) n;
    }
    return 0;
}

/* Прочитать ровно len байт. 0 — ок, -1 — EOF или ошибка. */
static int readn(int fd, unsigned char *buf, size_t len) {
    size_t off = 0;
    while (off < len) {
        ssize_t n = read(fd, buf + off, len - off);
        if (n == 0) return -1;
        if (n < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        off += (size_t) n;
    }
    return 0;
}

/* Шлюз Wi-Fi из /proc/net/route (маршрут по умолчанию). Пустая строка, если не найден. */
static void default_gateway(char *out, size_t outlen) {
    out[0] = 0;
    FILE *f = fopen("/proc/net/route", "r");
    if (!f) return;
    char line[256], iface[64];
    unsigned long dest, gw;
    /* шапка */
    if (!fgets(line, sizeof line, f)) { fclose(f); return; }
    while (fgets(line, sizeof line, f)) {
        if (sscanf(line, "%63s %lx %lx", iface, &dest, &gw) != 3) continue;
        if (dest == 0 && gw != 0) {
            /* gw в файле — little-endian */
            snprintf(out, outlen, "%lu.%lu.%lu.%lu",
                     gw & 0xff, (gw >> 8) & 0xff, (gw >> 16) & 0xff, (gw >> 24) & 0xff);
            break;
        }
    }
    fclose(f);
}

/* connect с таймаутом CONNECT_MS. Возвращает fd или -1. */
static int connect_phone(const char *host) {
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) return -1;

    struct sockaddr_in a;
    memset(&a, 0, sizeof a);
    a.sin_family = AF_INET;
    a.sin_port = htons(PHONE_PORT);
    if (inet_pton(AF_INET, host, &a.sin_addr) != 1) { close(fd); return -1; }

    int fl = fcntl(fd, F_GETFL, 0);
    fcntl(fd, F_SETFL, fl | O_NONBLOCK);
    int r = connect(fd, (struct sockaddr *) &a, sizeof a);
    if (r < 0 && errno == EINPROGRESS) {
        fd_set w;
        FD_ZERO(&w);
        FD_SET(fd, &w);
        struct timeval tv = { CONNECT_MS / 1000, (CONNECT_MS % 1000) * 1000 };
        if (select(fd + 1, NULL, &w, NULL, &tv) <= 0) { close(fd); return -1; }
        int err = 0;
        socklen_t el = sizeof err;
        getsockopt(fd, SOL_SOCKET, SO_ERROR, &err, &el);
        if (err) { close(fd); return -1; }
    } else if (r < 0) {
        close(fd);
        return -1;
    }
    fcntl(fd, F_SETFL, fl);

    int one = 1, buf = SOCK_BUF;
    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof one);
    setsockopt(fd, SOL_SOCKET, SO_RCVBUF, &buf, sizeof buf);
    setsockopt(fd, SOL_SOCKET, SO_SNDBUF, &buf, sizeof buf);
    return fd;
}

struct dirn {
    int usb_fd;
    int sock;
    volatile int peer_dead;  /* ставит та нить, что умерла, чтобы разбудить другую */
};

/* телефон → ГУ, кадрами: заголовок отдельно, тело кусками ≤ CHUNK. */
static void *pump_down(void *arg) {
    struct dirn *d = arg;
    unsigned char head[8], body[CHUNK];
    long frames = 0;
    unsigned long long bytes = 0;
    while (1) {
        if (readn(d->sock, head, 8) < 0) break;
        uint32_t len = ((uint32_t) head[4] << 24) | ((uint32_t) head[5] << 16) |
                       ((uint32_t) head[6] << 8) | (uint32_t) head[7];
        if (len > MAX_FRAME) { logts("вниз: кривая длина кадра %u", len); break; }
        if (writen(d->usb_fd, head, 8) < 0) break;
        uint32_t left = len;
        while (left) {
            uint32_t n = left < CHUNK ? left : CHUNK;
            if (readn(d->sock, body, n) < 0) goto done;
            if (writen(d->usb_fd, body, n) < 0) goto done;
            left -= n;
        }
        if (frames < 8) {
            uint32_t chan = ((uint32_t) head[0] << 24) | ((uint32_t) head[1] << 16) |
                            ((uint32_t) head[2] << 8) | (uint32_t) head[3];
            logts("вниз кадр #%ld: канал %u, %u Б", frames, chan, len);
        }
        frames++;
        bytes += 8 + (long) len;
    }
done:
    logts("вниз остановлено после %ld кадров (%llu Б)", frames, bytes);
    /* будим pump_up: сокет закрываем, флаг — на случай тихой ГУ (read аксессуара не проснётся сам) */
    d->peer_dead = 1;
    shutdown(d->sock, SHUT_RDWR);
    return NULL;
}

/* ГУ → телефон, сырые байты. Выходит по EOF/ошибке ГУ или когда умерла нить вниз. */
static void pump_up(struct dirn *d) {
    unsigned char buf[CHUNK];
    unsigned long long total = 0;
    int first = 1;
    while (1) {
        /* не голый read: иначе при тихой ГУ зависнем навсегда, когда телефон уже отвалился */
        struct pollfd pfd = { d->usb_fd, POLLIN, 0 };
        int pr = poll(&pfd, 1, 1000);
        if (pr == 0) {
            if (d->peer_dead) { logts("нить вниз умерла, вверх закрываю (%llu Б)", total); return; }
            continue;
        }
        if (pr < 0) {
            if (errno == EINTR) continue;
            logts("ГУ: poll ошибка: %s", strerror(errno));
            return;
        }
        ssize_t n = read(d->usb_fd, buf, CHUNK);
        if (n == 0) { logts("ГУ: EOF, вверх %llu Б", total); return; }
        if (n < 0) {
            if (errno == EINTR) continue;
            logts("ГУ: ошибка чтения: %s, вверх %llu Б", strerror(errno), total);
            return;
        }
        if (first) {
            char hex[49];
            int k = n < 16 ? (int) n : 16, p = 0;
            for (int i = 0; i < k; i++) p += snprintf(hex + p, sizeof hex - p, "%02X ", buf[i]);
            logts("ГУ заговорило, первые %zd Б: %s", n, hex);
            first = 0;
        }
        if (writen(d->sock, buf, (size_t) n) < 0) {
            logts("ГУ→телефон: сокет закрыт, вверх %llu Б", total);
            return;
        }
        total += n;
    }
}

/* Одна сессия: телефон, затем аксессуар, затем труба в обе стороны. 0 — была связь с ГУ. */
static int run_session(const char *host, const char *dev) {
    int sock = -1;
    for (int attempt = 1; attempt <= 60; attempt++) {
        sock = connect_phone(host);
        if (sock >= 0) { logts("телефон %s:%d подключён с попытки %d", host, PHONE_PORT, attempt); break; }
        if (attempt == 1 || attempt % 10 == 0) logts("телефон %s не отвечает", host);
        sleep(1);
    }
    if (sock < 0) { logts("телефон так и не ответил"); return 0; }

    int usb_fd = open(dev, O_RDWR);
    if (usb_fd < 0) {
        logts("не открыть %s: %s", dev, strerror(errno));
        close(sock);
        return 0;
    }
    logts("аксессуар %s открыт, труба пошла", dev);

    struct dirn d = { usb_fd, sock, 0 };
    pthread_t down;
    pthread_create(&down, NULL, pump_down, &d);
    pump_up(&d);

    shutdown(sock, SHUT_RDWR);
    close(sock);
    close(usb_fd);
    pthread_join(down, NULL);
    return 1;
}

int main(int argc, char **argv) {
    const char *host = argc > 1 ? argv[1] : getenv("PHONE_HOST");
    const char *dev = argc > 2 ? argv[2] : getenv("ACCESSORY");
    if (!dev) dev = DEV_DEFAULT;

    char gw[32];
    if (!host || !host[0]) {
        default_gateway(gw, sizeof gw);
        if (!gw[0]) { logts("нет шлюза по умолчанию и не задан phone_host"); return 1; }
        host = gw;
    }

    logts("реле: телефон %s:%d, аксессуар %s", host, PHONE_PORT, dev);
    while (1) {
        run_session(host, dev);
        sleep(1);
    }
    return 0;
}
