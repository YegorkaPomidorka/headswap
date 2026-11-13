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

    private WifiManager.WifiLock wifiLock;
    private PowerManager.WakeLock wakeLock;
    private volatile Thread relay;

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
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        UsbAccessory acc = intent == null ? null : (UsbAccessory) intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
        UsbManager usb = (UsbManager) getSystemService(Context.USB_SERVICE);
        if (acc == null) {
            UsbAccessory[] list = usb.getAccessoryList();
            if (list != null && list.length > 0 && usb.hasPermission(list[0])) acc = list[0];
        }
        if (acc == null) {
            Log.i("старт без аксессуара, жду ГУ");
        } else if (relay != null && relay.isAlive()) {
            Log.i("ретранслятор уже работает, " + acc);
        } else {
            final UsbAccessory a = acc;
            relay = new Thread(() -> runRelay(usb, a), "relay");
            relay.start();
        }
        return START_STICKY;
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
                usbOut.write(head);
                int left = len;
                while (left > 0) {
                    int n = Math.min(left, CHUNK);
                    in.readFully(body, 0, n);
                    usbOut.write(body, 0, n);
                    left -= n;
                }
                frames++;
            }
        } catch (IOException e) {
            Log.i("вниз остановлено после " + frames + " кадров: " + e);
        } finally {
            // Рвём и сокет, и USB-сторону: pumpUp выйдет по ошибке
            closeQuietly(s);
            try { usbOut.close(); } catch (IOException ignored) { }
        }
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
