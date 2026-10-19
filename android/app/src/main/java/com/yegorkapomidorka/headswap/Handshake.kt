package com.yegorkapomidorka.headswap

import com.yegorkapomidorka.headswap.aa.FpsSetting

/** Исходящее CMD-сообщение: тип и тело (null = пустое). */
class OutCmd(val type: Int, val body: ByteArray?)

/**
 * Ответ на HU_AUTHEN_REQUEST, как PrivateKeyManager (Baidu CarLife 8.6.7): randomValue после versionVerify
 * подписывается (RSA/ECB/PKCS1Padding, ENCRYPT_MODE с приватным ключом), результат в Base64 NO_WRAP.
 * Чистая JVM-логика: java.util.Base64 и javax.crypto есть и в тестах, и на Android (minSdk 29).
 */
object HuAuth {
    private const val ERROR_VERSION = "_00.00.00"
    private const val TRANSFORMATION = "RSA/ECB/PKCS1Padding"

    /** PRIVATE_KEY из PrivateKeyManager (PKCS8, Base64). */
    private const val PRIVATE_KEY =
        "MIICdwIBADANBgkqhkiG9w0BAQEFAASCAmEwggJdAgEAAoGBALzoBR3Io5wxQH+IMy0ekUE50s9ZOEPOI/y3bSwXSZEqU0EgDGVRnFBYhr8MSEkv2THTn5HKsUM3gHd3rpQhRG2JrcaFY+iIEJs2iueXvfKd8UqTUFQOZrc7xCz1npg4KResY4VAEN+W19ez/DsKiCS5LaxmwE844xWibTgjspY9AgMBAAECgYA7C/cVcTKaztZPRr5gWw6iKbXYNBYfM58SDi+kuX64TleJor/dJ55JivJLY0ZxAfDM304gXw/7Z6zTKui5ypA9duYu1Pohk/e5UUMFr0+cEyWzTjCJp/XaA/gEhH8g311qeWcALSo6q55vYtTJpc/OUn98nlsrOMv4XLgaBI2fwQJBAPBw7TkR4Xuvue2tfxh2Fn2x2ONJSRjckS7EipfdLSsgZCbwdQ2XL/k6VX0lMfQaLQQ6YCF/vrALlgkXFBuzFpECQQDJIWJGcoM95ms546hHBzu7DONmEpEh9b9nMEqRmOkzkitxvGApv1VjvZaX6gMrSANADmoz4lSTGXFea4nviJLtAkEA29v3H4NZMveJxWsrV5vLjx5MG/FMdP5jh2dS7/DgN5pD2lNwRYAk7vnHaErVtccluMMEWj1siZ/ejutaiMWm8QJBAIMM+4nHZ3hXwJoRmj3dmq/AMBL8GhC0nShRMOU5awmtPh13jnjlMHAywgLt+W6kF2oPemegG1dVhqbtDw1CQekCQH3xugE28LS6e29233PUBdipwoyRcbDeth70WDS3l4bl7XlWbTXOWF/D28ZZ49NSr1dRhAQwM8VVC/gHdvNpcqA="

    val privateKey: java.security.PrivateKey by lazy {
        java.security.KeyFactory.getInstance("RSA")
            .generatePrivate(java.security.spec.PKCS8EncodedKeySpec(java.util.Base64.getDecoder().decode(PRIVATE_KEY)))
    }

    /** Соль MD5_HEAD из libencryption.so Baidu 8.6.7. */
    private const val V2_SALT = "CARLIFE"

    /** Ветка "2.0;<rnd>" (EncryptionUtils.getVerifyCode, нативная либа Baidu). Условие как у Baidu. */
    fun isV2Branch(random: String): Boolean {
        val parts = javaSplit(random, ";")
        return parts.size >= 2 && parts[0] == "2.0" && parts[1].isNotEmpty()
    }

    /** String.split(regex) из Java: хвостовые пустые элементы отбрасываются. */
    private fun javaSplit(s: String, sep: String): List<String> {
        val p = s.split(sep)
        return if (p.size == 1) p else p.dropLastWhile { it.isEmpty() }
    }

    /**
     * PrivateKeyManager.versionVerify при versionName = Handshake.MD_VERSION. Без "_" у Baidu падает
     * (substring(-1)), здесь строка остаётся как есть. Нечисловые части пропускаются (у Baidu ловится исключение).
     */
    fun versionVerify(str: String, mdVersion: String = Handshake.MD_VERSION): String {
        val idx = str.indexOf('_')
        if (idx < 0) return str
        val suffix = str.substring(idx)
        val stripped = str.replace(suffix, "")
        val theirs = javaSplit(suffix.replace("_", ""), ".")
        val ours = javaSplit(mdVersion, ".")
        if (ours.size != theirs.size) return stripped + ERROR_VERSION
        for (i in ours.indices) {
            val a = theirs[i].toIntOrNull() ?: continue
            val b = ours[i].toIntOrNull() ?: continue
            if (a > b) return stripped + ERROR_VERSION
            if (a < b) return str
        }
        return str
    }

    /**
     * Строка CONNECT_ENCRYPTION_MD_INFO у Baidu: brand + model + sdk из отправленного MD_INFO
     * (CarlifeDeviceInfoManager :174). ГУ берёт её из того же MD_INFO, поэтому поля должны совпадать байт в байт.
     */
    fun mdInfoKey(info: DeviceInfo) = info.brand + info.model + info.sdk

    /**
     * getVerifyCode из libencryption.so: md5(MD5_HEAD + str) строчным hex (класс MD5 из md5.cpp, HEX_NUMBERS
     * "0123456789abcdef"). Проверку подписи APK (APP_SIGNATURE_CARLIFE) пропускаем: у Baidu она при
     * чужой подписи просто отдаёт null. ГУ сверяет getVerifyResult: тот же md5 и strcmp.
     */
    fun verifyCode(str: String): String {
        val d = java.security.MessageDigest.getInstance("MD5").digest((V2_SALT + str).toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    /**
     * encryptValue для MD_AUTHEN_RESPONSE. Ветка 2.0: verifyCode(mdInfo + часть после ";"), как
     * PrivateKeyManager.encryptContentProcess; иначе RSA-подпись.
     */
    fun encryptValue(random: String, mdInfo: String = ""): String {
        if (isV2Branch(random)) return verifyCode(mdInfo + javaSplit(random, ";")[1])
        val cipher = javax.crypto.Cipher.getInstance(TRANSFORMATION)
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, privateKey)
        val sig = cipher.doFinal(versionVerify(random).toByteArray(Charsets.UTF_8))
        return java.util.Base64.getEncoder().encodeToString(sig)
    }
}

/**
 * Последовательности MD -> HU для CarLife 2.0, повторяющие MD Baidu CarLife 8.6.7.
 * Чистая логика без android.*, чтобы проверять порядок и байты офлайн.
 */
object Handshake {
    /** MODULE_STATUS уходит через 500 мс после стартовой пачки (CarlifeConnectHsgHandler.boxDelayProtocolMatchStatus, :752-758). */
    const val MODULE_STATUS_DELAY_MS = 500L

    /**
     * Нет VIDEO_ENCODER_START через столько мс после INIT_DONE -> видео стартует само. VW Tharu XR 2026 (CarLife 2.1) START
     * не шлёт и рвёт USB через ~5.5 с без видео; Baidu HU шлёт START сразу после initDecoder (CarlifeActivity :486-497).
     */
    const val START_FALLBACK_MS = 1500L

    const val MD_VERSION = "8.6.7"

    /** carlifeType: 1 = приложение CarLife (MixConfig.isCarLifeApp, CarlifeDeviceInfoManager :109-113). */
    const val MD_TYPE_CARLIFE_APP = 1

    /**
     * Стартовая пачка сразу после ответа на HU_PROTOCOL_VERSION, без ожидания чего-либо от ГУ:
     * MATCH_STATUS, FOREGROUND, SCREEN_ON (если экран включён), MD_INFO
     * (CarlifeConnectHsgHandler.boxDelayProtocolMatchStatus :743-751 + sendPhoneStatusMsg :995-1021).
     */
    fun startBatch(screenOn: Boolean, info: DeviceInfo): List<OutCmd> {
        val out = ArrayList<OutCmd>()
        out.add(OutCmd(Msg.PROTOCOL_VERSION_MATCH_STATUS, Msgs.matchStatus(1)))
        out.add(OutCmd(Msg.FOREGROUND, null))
        if (screenOn) out.add(OutCmd(Msg.SCREEN_ON, null))
        out.add(OutCmd(Msg.MD_INFO, Msgs.deviceInfo(info)))
        return out
    }

    fun moduleStatus(huMajor: Int) = OutCmd(Msg.MODULE_STATUS, Msgs.moduleStatusList(huMajor))

    /**
     * Ответ на STATISTIC_INFO (CarlifeConnectHsgHandler :434-499): MD_AUTHEN_RESULT{true}, затем запрос
     * FEATURE_CONFIG (0x10051, пусто), затем CAR_DATA_SUBSCRIBE (0x10031, пусто).
     */
    fun onStatisticInfo(sendFeatureConfigRequest: Boolean): List<OutCmd> {
        val out = ArrayList<OutCmd>()
        out.add(OutCmd(Msg.MD_AUTHEN_RESULT, Msgs.authenResult(true)))
        if (sendFeatureConfigRequest) out.add(OutCmd(Msg.MD_FEATURE_CONFIG_REQUEST, null))
        out.add(OutCmd(Msg.CAR_DATA_SUBSCRIBE, null))
        return out
    }

    /** randomValue из HU_AUTHEN_REQUEST вида "2.0;<rnd>" (v2-вариант запроса). */
    fun isAuthV2(random: String?) = random != null && random.startsWith("2.0;")

    /** Допустимый ответ на VIDEO_ENCODER_FRAME_RATE_CHANGE: 3..30 (VideoMsgHandler.changeFrameRate). */
    fun frameRateChangeAllowed(fps: Int) = fps in 3..30

    const val DEFAULT_FPS = 30

    /** Нижняя граница частоты, которую применяем по FRAME_RATE_CHANGE (ниже энкодер/ГУ уже не успевают обновлять статику). */
    const val MIN_APPLIED_FPS = 10

    /**
     * fps для INIT_DONE. Авто: как у Baidu (fps из INIT, если в 1..30, иначе 30); 15 и 30 - фиксированно.
     * Honda шлёт fps=0, Kia 15, Mazda 30. [huMaxFps] - потолок под слабое ГУ (Pioneer RM56E8B, h277), null = нет.
     */
    fun chooseFps(setting: FpsSetting, requested: Int, huMaxFps: Int? = null): Int =
        capFps(setting.fixed ?: if (requested in 1..30) requested else DEFAULT_FPS, huMaxFps)

    /** Потолок fps под конкретное ГУ (null = без ограничения). */
    fun capFps(fps: Int, huMaxFps: Int?): Int = huMaxFps?.let { minOf(fps, it) } ?: fps
}
