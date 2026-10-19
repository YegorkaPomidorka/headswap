package com.yegorkapomidorka.headswap.link.car

/** Чистая логика автостарта по Bluetooth машины, без android.*: проверяется JVM-тестами. */
object CarMac {
    /** Один и тот же адрес? Регистр не важен (CDM отдаёт MAC строчными, BluetoothDevice - заглавными), null никогда не равен. */
    fun same(a: String?, b: String?): Boolean = a != null && b != null && a.equals(b, ignoreCase = true)
}

/**
 * Debounce отключения машины: ГУ при переподключении даёт всплески ACL_DISCONNECTED/ACL_CONNECTED (в логе Vezel 5 циклов за 7 с),
 * гасить ожидание сразу нельзя. [arm] запускает отсчёт (повторный - сдвигает), [cancel] снимает (пришёл ACL_CONNECTED),
 * [fire] по таймеру отвечает, пора ли гасить: срок вышел и отмены не было; срабатывает один раз. Нить одна (главная).
 */
class AutoOffDebounce(private val delayMs: Long, private val clock: () -> Long) {
    private var dueAt = -1L

    val pending: Boolean get() = dueAt >= 0

    fun arm() {
        dueAt = clock() + delayMs
    }

    fun cancel() {
        dueAt = -1L
    }

    fun fire(): Boolean {
        if (dueAt < 0 || clock() < dueAt) return false
        dueAt = -1L
        return true
    }
}

/** Не чаще раза в [intervalMs]: чужие устройства шлют ACL постоянно, лог не засоряем. */
class MinInterval(private val intervalMs: Long, private val clock: () -> Long) {
    private var last = -1L

    fun tryAcquire(): Boolean {
        val now = clock()
        if (last >= 0 && now - last < intervalMs) return false
        last = now
        return true
    }
}

/** Какой защитой входить в сеть машины. Удачная защита запоминается ([CarWifi.sec]), дальше без перебора: одна сеть - один MAC. */
object CarSecPolicy {
    fun first(remembered: String?, hasPass: Boolean): String = when {
        !hasPass -> "open"
        remembered == "wpa3" -> "wpa3"
        else -> "wpa2"
    }

    /** Повтор WPA3 после неудачи WPA2 - только пока удачная защита неизвестна (иначе машина просто не в эфире). */
    fun retryWpa3(remembered: String?, tried: String, hasPass: Boolean): Boolean =
        hasPass && tried == "wpa2" && remembered == null
}

/** Через сколько ожидания по Wi-Fi без 6/6 просим вход в сеть машины (до этого ГУ может успеть сама прийти в сеть телефона). */
const val CAR_NET_DELAY_MS = 15_000L

/**
 * Машина была на связи (сессия USB/воздух, сеть машины) не дольше этого назад: сеть машины просим сразу, без
 * [CAR_NET_DELAY_MS]. Выдернули кабель, оборвался Wi-Fi: машина рядом, ждать её в сети телефона незачем (лог Павла
 * 2026-10-17: 15 с тишины на каждом переключении провод -> воздух).
 */
const val CAR_RECENT_MS = 60_000L

/** Сколько система ищет сеть машины и ждёт одобрения; не нашла - подсказка про Wi-Fi вручную. */
const val CAR_NET_TIMEOUT_MS = 90_000L

/** Через сколько ожидания без accept на RFCOMM `carlife` подсказываем переподключить Bluetooth (ГУ открывает его только при подключении BT). */
const val BT_HINT_MS = 15_000L

/** Как выбранная машина подключается по воздуху; привязано к её Bluetooth-адресу (см. CarProfile). */
enum class CarConnMode {
    UNKNOWN,
    /** ГУ присылала TARGET_INFO по Bluetooth: Wi-Fi Direct, телефон входит в её группу. */
    BT_CARLIFE,
    /** 6/6 поднялись с пира из подсети маяка, без Wi-Fi Direct. */
    HOTSPOT,
}

/** События, при которых решается судьба запроса сети машины. */
enum class CarNetEvent { AUTO_OFF, STOP, USB_WINS, DESTROY, SESSION_START, SESSION_END }

/** Чистая логика запроса сети машины, без android.*: проверяется JVM-тестами. */
object CarNetPolicy {
    /**
     * Пора ли слать запрос: сеть машины задана, идёт ожидание по Wi-Fi (авто или ручное), никакой сессии (ни USB, ни
     * воздух) и 6/6 ещё нет, запрос не висит и не выдан, с начала ожидания прошло не меньше [delayMs].
     * Wi-Fi Direct-машине ([wifiDirectCar]) сеть не запрашиваем: телефон входит в группу ГУ, а запрос только мешает.
     */
    fun shouldRequest(
        carWifiSet: Boolean,
        autoOrManualWaiting: Boolean,
        sessionActive: Boolean,
        usbActive: Boolean,
        alreadyRequesting: Boolean,
        waitedMs: Long,
        sixOfSixReady: Boolean,
        wifiDirectCar: Boolean,
        delayMs: Long = CAR_NET_DELAY_MS,
    ): Boolean = carWifiSet && autoOrManualWaiting && !sessionActive && !usbActive && !alreadyRequesting &&
        !sixOfSixReady && !wifiDirectCar && waitedMs >= delayMs

    /**
     * Задержка запроса сети машины: 0, если машина была на связи меньше [CAR_RECENT_MS] назад ([sinceCarMs], null = не
     * была) или выбранная машина сейчас подключена по Bluetooth ([carBtNear]): она рядом и включена, ждать её в сети
     * телефона незачем (Павел 2026-10-18: первое нажатие плитки CarLife на ГУ падало «не найдено», пока шли 15 с).
     * Также 0, если 6/6 уже поднимались в сети этой машины ([carNetProven]): ГУ раздаёт свой Wi-Fi и в сеть телефона не
     * ходит, а выбрать машину по Bluetooth получается не у всех (Павел 2026-10-19: CDM падал, 15 с на каждом старте).
     */
    fun delayMs(sinceCarMs: Long?, carBtNear: Boolean = false, carNetProven: Boolean = false): Long =
        if (carBtNear || carNetProven || (sinceCarMs != null && sinceCarMs in 0 until CAR_RECENT_MS)) 0L else CAR_NET_DELAY_MS

    /**
     * Отпускать ли сеть. Остановка ожидания, кабель и смерть сервиса - да. Начало и конец беспроводной сессии - нет: сеть
     * машины держит её TCP, а после конца сессии ожидание обычно возобновляется на той же сети.
     */
    fun shouldRelease(event: CarNetEvent): Boolean = when (event) {
        CarNetEvent.AUTO_OFF, CarNetEvent.STOP, CarNetEvent.USB_WINS, CarNetEvent.DESTROY -> true
        CarNetEvent.SESSION_START, CarNetEvent.SESSION_END -> false
    }
}

/** Чистая логика подсказки «переподключи Bluetooth»: без android.*, проверяется JVM-тестами. */
object BtReconnectHint {
    /**
     * Показывать ли: идёт ожидание без сессии уже [BT_HINT_MS], ГУ ни разу не подключилась к RFCOMM `carlife`, хотя
     * телефон с машиной по Bluetooth соединён (Mazda открывает carlife только в момент подключения BT), а по хотспоту
     * эта машина раньше не подключалась. Один показ на ожидание. ГУ уже прислала TARGET_INFO ([targetInfoThisWait]) -
     * carlife она открыла, переподключать Bluetooth незачем.
     */
    fun shouldShow(
        waiting: Boolean,
        sessionActive: Boolean,
        waitedMs: Long,
        acceptsThisWait: Int,
        targetInfoThisWait: Boolean,
        carBtConnected: Boolean,
        mode: CarConnMode,
        alreadyShown: Boolean,
    ): Boolean = waiting && !sessionActive && waitedMs >= BT_HINT_MS && acceptsThisWait == 0 && !targetInfoThisWait &&
        carBtConnected && mode != CarConnMode.HOTSPOT && !alreadyShown
}
