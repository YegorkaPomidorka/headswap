package com.yegorkapomidorka.headswap.dongle;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;

import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Ретранслятор «ГУ по USB (AOA) ↔ Headswap по Wi-Fi».
 *
 * ГУ видит стик как телефон CarLife. Поток аксессуара идёт по одному TCP-сокету на телефон
 * (Headswap слушает порт {@link #PHONE_PORT} в режиме «Свисток»). Телефон раздаёт точку,
 * стик к ней подключён, поэтому адрес телефона = шлюз DHCP.
 *
 * ГУ → телефон: сырые байты, как пришли. Телефон → ГУ: по кадрам [chan u32][len u32] + тело,
 * заголовок отдельным write, тело кусками ≤ 16384, как Wire.writeSequence в Headswap (ADR 0009):
 * часть ГУ чувствительна к границам USB-передач, а TCP их стирает.
 */
public class RelayService extends Service {
    static final int PHONE_PORT = 7250;
    private static final int CHUNK = 16384;
    private static final int MAX_FRAME = 64 * 1024 * 1024;
    private static final java.io.File DEV_ACCESSORY = new java.io.File("/dev/usb_accessory");

    private WifiManager.WifiLock wifiLock;
    private PowerManager.WakeLock wakeLock;
    private volatile Thread relay;
    private volatile boolean silentReplugDone;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.init(this);
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        // FULL_HIGH_PERF выключает энергосбережение Wi-Fi: с ним пинг гулял до 1,2 с
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "dongle");
        wifiLock.setReferenceCounted(false);
        wifiLock.acquire();
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dongle");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
        Log.i("сервис создан, wifi high-perf, wakelock");
        startMonitor();
        // Прошлый процесс могли убить посреди передёргивания — гаджет остался выключенным
        if ("0".equals(readSys(USB_ENABLE))) {
            try { writeSys(USB_ENABLE, "1"); Log.i("USB был выключен, включила"); } catch (IOException ignored) { }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        UsbAccessory acc = intent == null ? null : (UsbAccessory) intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
        UsbManager usb = (UsbManager) getSystemService(Context.USB_SERVICE);
        if (acc == null) {
            UsbAccessory[] list = usb.getAccessoryList();
            if (list != null && list.length > 0 && usb.hasPermission(list[0])) acc = list[0];
        }
        if (relay != null && relay.isAlive()) {
            Log.i("ретранслятор уже работает");
        } else if (acc != null) {
            final UsbAccessory a = acc;
            relay = new Thread(() -> runRelay(usb, a), "relay");
            relay.start();
        } else if (DEV_ACCESSORY.canRead() && DEV_ACCESSORY.canWrite()) {
            relay = new Thread(this::runDirectLoop, "relay-dev");
            relay.start();
        } else {
            Log.i("старт без аксессуара, жду ГУ (" + DEV_ACCESSORY + " недоступен)");
        }
        return START_STICKY;
    }

    /**
     * Прямой режим: стик с загрузки в sys.usb.config=accessory,adb (18D1:2D01), ГУ рукопожатие не делает,
     * фреймворк аксессуар не видит. Читаем /dev/usb_accessory сами (права 666 ставит install-recovery.sh).
     * Чтение блокируется, пока хост не сконфигурирует устройство; телефон зовём только после первых байт от ГУ.
     */
    private void runDirectLoop() {
        Log.i("прямой режим " + DEV_ACCESSORY);
        while (true) {
            java.io.RandomAccessFile dev = null;
            FileInputStream usbIn = null;
            FileOutputStream usbOut = null;
            Socket sock = null;
            try {
                // Драйвер пускает только одно открытие (open_excl), поэтому один fd на чтение и запись
                dev = new java.io.RandomAccessFile(DEV_ACCESSORY, "rw");
                usbIn = new FileInputStream(dev.getFD());
                usbOut = new FileOutputStream(dev.getFD());
                byte[] buf = new byte[CHUNK];
                // Открытое до подключения хоста чтение может повиснуть на старом состоянии гаджета навсегда
                // (close() другой нитью его на 4.1 не будит). ГУ говорит сразу после подключения и шлёт пульс,
                // так что хост подключён (CONFIGURED), а 3 с тишины — передёргиваем USB: ядро обрывает чтение,
                // ГУ переподключается и начинает заново.
                final boolean[] gotFirst = {false};
                Thread watchdog = new Thread(() -> {
                    while (true) {
                        try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
                        synchronized (gotFirst) { if (gotFirst[0]) return; }
                        // Один раз на эпизод тишины: если хост молчит и после этого (ПК без моста) — ждём молча,
                        // иначе USB дёргается по кругу
                        if (!silentReplugDone && "CONFIGURED".equals(readSys(USB_STATE))) {
                            silentReplugDone = true;
                            Log.i("хост подключён, но молчит 3 с — передёргиваю USB (один раз)");
                            replugUsb();
                        }
                    }
                }, "first-read");
                watchdog.start();
                int n;
                try {
                    n = usbIn.read(buf);
                } finally {
                    synchronized (gotFirst) { gotFirst[0] = true; }
                    watchdog.interrupt();
                }
                if (n <= 0) throw new IOException("ГУ: пустое первое чтение " + n);
                silentReplugDone = false;
                Log.i("ГУ заговорило, первые " + n + " Б: " + hex(buf, Math.min(n, 32)));
                sock = connectPhone();
                if (sock == null) throw new IOException("нет телефона");
                OutputStream net = sock.getOutputStream();
                net.write(buf, 0, n);
                final Socket s = sock;
                final FileOutputStream out = usbOut;
                new Thread(() -> pumpDown(s, out), "down").start();
                pumpUp(usbIn, net);
            } catch (IOException e) {
                Log.i("прямой режим: " + e.getMessage());
            } finally {
                boolean hadPhone = sock != null;
                closeQuietly(sock);
                try { if (usbIn != null) usbIn.close(); } catch (IOException ignored) { }
                try { if (usbOut != null) usbOut.close(); } catch (IOException ignored) { }
                try { if (dev != null) dev.close(); } catch (IOException ignored) { }
                // Сессия с телефоном была и оборвалась: ГУ живёт в старой сессии и заново рукопожатие не начнёт.
                // Делаем вид, что кабель выдернули, — как при обрыве USB у обычного телефона.
                if (hadPhone) replugUsb();
            }
            try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
        }
    }

    private void runRelay(UsbManager usb, UsbAccessory acc) {
        Log.i("аксессуар " + acc.getManufacturer() + " / " + acc.getModel() + " / " + acc.getVersion());
        ParcelFileDescriptor pfd = usb.openAccessory(acc);
        if (pfd == null) {
            Log.i("openAccessory вернул null (нет разрешения?)");
            return;
        }
        Socket sock = null;
        try {
            sock = connectPhone();
            if (sock == null) return;
            FileInputStream usbIn = new FileInputStream(pfd.getFileDescriptor());
            FileOutputStream usbOut = new FileOutputStream(pfd.getFileDescriptor());
            final Socket s = sock;
            Thread down = new Thread(() -> pumpDown(s, usbOut), "down");
            down.start();
            pumpUp(usbIn, s.getOutputStream());
        } catch (IOException e) {
            Log.i("ошибка: " + e);
        } finally {
            closeQuietly(sock);
            try { pfd.close(); } catch (IOException ignored) { }
            Log.i("ретранслятор остановлен");
        }
    }

    /** Ждём телефон: шлюз точки доступа, порт PHONE_PORT. Пока аксессуар жив, пробуем раз в секунду до минуты. */
    private Socket connectPhone() {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        for (int attempt = 1; attempt <= 60; attempt++) {
            int gw = wm.getDhcpInfo() == null ? 0 : wm.getDhcpInfo().gateway;
            if (gw != 0) {
                String host = (gw & 0xff) + "." + ((gw >> 8) & 0xff) + "." + ((gw >> 16) & 0xff) + "." + ((gw >>> 24) & 0xff);
                Socket s = new Socket();
                try {
                    s.setTcpNoDelay(true);
                    s.setReceiveBufferSize(128 * 1024);
                    s.setSendBufferSize(128 * 1024);
                    s.connect(new InetSocketAddress(host, PHONE_PORT), 2000);
                    Log.i("телефон " + host + ":" + PHONE_PORT + " подключён с попытки " + attempt);
                    return s;
                } catch (IOException e) {
                    closeQuietly(s);
                    if (attempt == 1 || attempt % 10 == 0) Log.i("телефон " + host + " не отвечает: " + e.getMessage());
                }
            } else if (attempt == 1 || attempt % 10 == 0) {
                Log.i("нет шлюза Wi-Fi, стик не в сети телефона");
            }
            try { Thread.sleep(1000); } catch (InterruptedException e) { return null; }
        }
        Log.i("телефон так и не ответил");
        return null;
    }

    /** ГУ → телефон, сырые байты. Буфер 16384: меньшие чтения из fd аксессуара на старых Android теряют данные. */
    private void pumpUp(InputStream usbIn, OutputStream net) throws IOException {
        byte[] buf = new byte[CHUNK];
        long total = 0;
        while (true) {
            int n = usbIn.read(buf);
            if (n < 0) { Log.i("ГУ: EOF, передано вверх " + total + " Б"); return; }
            if (n == 0) continue;
            net.write(buf, 0, n);
            total += n;
            upBytes.addAndGet(n);
        }
    }

    /** Телефон → ГУ, кадрами: заголовок отдельно, тело кусками ≤ 16384. */
    private void pumpDown(Socket s, OutputStream usbOut) {
        long frames = 0;
        try {
            DataInputStream in = new DataInputStream(s.getInputStream());
            byte[] head = new byte[8];
            byte[] body = new byte[CHUNK];
            while (true) {
                in.readFully(head);
                int len = ((head[4] & 0xff) << 24) | ((head[5] & 0xff) << 16) | ((head[6] & 0xff) << 8) | (head[7] & 0xff);
                if (len < 0 || len > MAX_FRAME) throw new IOException("кривая длина кадра " + len);
                long t0 = android.os.SystemClock.uptimeMillis();
                usbOut.write(head);
                int left = len;
                while (left > 0) {
                    int n = Math.min(left, CHUNK);
                    in.readFully(body, 0, n);
                    usbOut.write(body, 0, n);
                    left -= n;
                }
                long dt = android.os.SystemClock.uptimeMillis() - t0;
                if (dt > maxWriteMs.get()) maxWriteMs.set(dt);
                if (frames < 8) {
                    int chan = ((head[0] & 0xff) << 24) | ((head[1] & 0xff) << 16) | ((head[2] & 0xff) << 8) | (head[3] & 0xff);
                    Log.i("вниз кадр #" + frames + ": канал " + chan + ", " + len + " Б, запись в USB " + dt + " мс");
                }
                frames++;
                downFrames.incrementAndGet();
                downBytes.addAndGet(8 + len);
            }
        } catch (IOException e) {
            Log.i("вниз остановлено после " + frames + " кадров: " + e);
        } finally {
            // Рвём и сокет, и USB-сторону: pumpUp выйдет по ошибке
            closeQuietly(s);
            try { usbOut.close(); } catch (IOException ignored) { }
        }
    }

    private static final java.io.File USB_ENABLE = new java.io.File("/sys/class/android_usb/android0/enable");
    private static final java.io.File USB_FUNCTIONS = new java.io.File("/sys/class/android_usb/android0/functions");

    // Счётчики для лога: ГУ→телефон байты, телефон→ГУ байты и кадры, самая долгая запись в USB
    private static final java.util.concurrent.atomic.AtomicLong upBytes = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong downBytes = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong downFrames = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong maxWriteMs = new java.util.concurrent.atomic.AtomicLong();

    /** Раз в 0,5 с смотрим состояние USB (пишем смены), раз в 5 с — трафик, если он шёл. */
    private void startMonitor() {
        Thread t = new Thread(() -> {
            String lastUsb = "";
            long lastUp = 0, lastDown = 0, tick = 0;
            while (true) {
                String usb = readSys(USB_STATE) + " en=" + readSys(USB_ENABLE) + " fn=" + readSys(USB_FUNCTIONS);
                if (!usb.equals(lastUsb)) { Log.i("USB: " + usb); lastUsb = usb; }
                if (++tick % 10 == 0) {
                    long up = upBytes.get(), down = downBytes.get();
                    if (up != lastUp || down != lastDown) {
                        Log.i("трафик 5 с: ГУ→тел " + (up - lastUp) + " Б, тел→ГУ " + (down - lastDown) + " Б, кадров вниз всего "
                                + downFrames.get() + ", макс запись в USB " + maxWriteMs.getAndSet(0) + " мс");
                        lastUp = up; lastDown = down;
                    }
                }
                android.os.SystemClock.sleep(500);
            }
        }, "monitor");
        t.setDaemon(true);
        t.start();
    }

    /** Отключить USB-гаджет на секунду: хост видит отсоединение и при возврате начинает сессию с нуля. */
    private static void replugUsb() {
        try {
            writeSys(USB_ENABLE, "0");
            // sleep без прерывания: оставить гаджет выключенным нельзя ни при каких условиях
            android.os.SystemClock.sleep(1000);
        } catch (IOException e) {
            Log.i("не вышло выключить USB: " + e);
        } finally {
            try {
                writeSys(USB_ENABLE, "1");
                Log.i("USB передёрнут: ГУ начнёт сессию заново");
            } catch (IOException e) {
                Log.i("не вышло включить USB обратно: " + e);
            }
        }
    }

    private static final java.io.File USB_STATE = new java.io.File("/sys/class/android_usb/android0/state");

    private static String hex(byte[] b, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(String.format("%02X ", b[i] & 0xff));
        return sb.toString().trim();
    }

    private static String readSys(java.io.File f) {
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f));
            try { String s = r.readLine(); return s == null ? "" : s.trim(); } finally { r.close(); }
        } catch (IOException e) {
            return "";
        }
    }

    private static void writeSys(java.io.File f, String v) throws IOException {
        FileOutputStream o = new FileOutputStream(f);
        try { o.write(v.getBytes()); } finally { o.close(); }
    }

    private static void closeQuietly(Socket s) {
        if (s == null) return;
        try { s.close(); } catch (IOException ignored) { }
    }

    @Override
    public void onDestroy() {
        Log.i("сервис уничтожен");
        if (wifiLock != null) wifiLock.release();
        if (wakeLock != null) wakeLock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
