package com.yegorkapomidorka.headswap.iccoa

import android.content.Context
import java.security.KeyFactory
import java.security.KeyPair
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Locale

/** Что телефон помнит между подключениями ICCOA: свою идентичность и ключи машин для входа без PIN. */
interface IccoaTrust {
    /** Стабилен между запусками: машина узнаёт по нему телефон при быстром входе. */
    val clientId: String
    fun identity(): KeyPair
    /** authPub машины (64 байта X‖Y) или null, если с ней ещё не знакомились. */
    fun carKey(car: String): ByteArray?
    fun saveCarKey(car: String, key: ByteArray)
}

/** Отдельные prefs `iccoa_trust`, исключены из бэкапа (res/xml/backup_rules.xml, data_extraction_rules.xml). */
class IccoaTrustPrefs(ctx: Context) : IccoaTrust {
    private val sp = ctx.applicationContext.getSharedPreferences("iccoa_trust", Context.MODE_PRIVATE)

    override val clientId: String
        @Synchronized get() = sp.getString("id", null)
            // шесть hex-цифр, как у FT и XDL
            ?: "%06x".format(Locale.ROOT, SecureRandom().nextInt(0x1000000)).also { sp.edit().putString("id", it).commit() }

    // shortcut: ключ в prefs, не в AndroidKeyStore (им только подписываем, у FT он там с PURPOSE_SIGN), перенести в Э4
    @Synchronized override fun identity(): KeyPair {
        val private = sp.getString("key", null)
        val public = sp.getString("pub", null)
        if (private != null && public != null) {
            val factory = KeyFactory.getInstance("EC")
            return KeyPair(factory.generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(public))),
                factory.generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(private))))
        }
        val pair = IccoaAuth.newKeyPair()
        // Без сохранённого ключа машина при следующем входе нас не узнает: commit, не apply.
        check(sp.edit().putString("key", Base64.getEncoder().encodeToString(pair.private.encoded))
            .putString("pub", Base64.getEncoder().encodeToString(pair.public.encoded)).commit()) { "ICCOA identity not saved" }
        return pair
    }

    override fun carKey(car: String): ByteArray? =
        sp.getString("car:$car", null)?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }?.takeIf { it.size == 64 }

    override fun saveCarKey(car: String, key: ByteArray) {
        sp.edit().putString("car:$car", Base64.getEncoder().encodeToString(key)).apply()
    }
}
