package com.yegorkapomidorka.headswap.dex

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.launcher.NavigatorPick
import java.lang.reflect.InvocationTargetException

/**
 * Loopback AudioMix для медиа: системный AudioPolicy (SystemApi, через reflection - главный процесс запущен
 * `am instrument --no-hidden-api-checks`), право MODIFY_AUDIO_ROUTING из активации --audio.
 * Голый REMOTE_SUBMIX проигрывает маршрутизации по устройствам: при A2DP (Vezel «Honda HFT») медиа уходит
 * в Bluetooth, а захват читает нули. Микс же перехватывает поток раньше выбора устройства, телефон и BT молчат.
 * Политику система снимает сама, если процесс умрёт, - звук телефона не застрянет в мёртвом submix.
 *
 * h1318: голос навигатора - отдельный микс в той же политике, [navRecord]: его читает DesktopAudioCapture и отдаёт
 * в канал SPEECH (CarLife VR/TTS), крутилка ГУ получает отдельную громкость подсказок. Отбор по uid приложений-
 * навигаторов, а не по usage: Яндекс Навигатор (стенд 20.11, Redmi Note 15 Pro+) говорит подсказки как USAGE_MEDIA/
 * CONTENT_TYPE_SPEECH, NAVIGATION_GUIDANCE не ставит. Основной микс те же uid исключает (RULE_EXCLUDE_UID).
 * Не создался навигационный микс или навигаторов нет - один микс как раньше, голос внутри медиа, [navRecord] = null.
 */
internal class DesktopAudioLoopback private constructor(
    private val audio: AudioManager, private val policy: Any, val record: AudioRecord, val navRecord: AudioRecord?,
) {
    fun release() {
        runCatching { navRecord?.release() }
        runCatching { record.release() }
        unregister(audio, policy)
    }

    companion object {
        private const val RULE_MATCH_ATTRIBUTE_USAGE = 1 // AudioMixingRule.RULE_MATCH_ATTRIBUTE_USAGE
        private const val RULE_MATCH_UID = 4              // AudioMixingRule.RULE_MATCH_UID
        private const val ROUTE_FLAG_LOOP_BACK = 2       // AudioMix.ROUTE_FLAG_LOOP_BACK, без RENDER: на телефоне тишина
        // Всё, что берём с телефона. SONIFICATION (щелчки, звуки системы) не берём.
        // NAVIGATION_GUIDANCE (Google Карты и др.) без микса уходит в BT A2DP, и ГУ его не играет.
        private val MEDIA_USAGES = intArrayOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME, AudioAttributes.USAGE_UNKNOWN,
            AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
            // голос Gemini (стенд 07.11): обычный захват ASSISTANT не видит, системный AudioMix видит
            AudioAttributes.USAGE_ASSISTANT)
        /** Навигаторы, чей звук идёт в канал подсказок: всё из выбора навигатора лаунчера. */
        private val NAV_PACKAGES = (NavigatorPick.KNOWN + NavigatorPick.KNOWN_ZH).distinct()

        private fun navUids(context: Context): List<Int> = NAV_PACKAGES.mapNotNull { pkg ->
            runCatching { context.packageManager.getApplicationInfo(pkg, 0).uid }.getOrNull()
        }.distinct()

        /** Канальная маска выходная: createAudioRecordSink сам переводит её во входную. */
        fun open(context: Context, sampleRate: Int, withNav: Boolean = true): DesktopAudioLoopback {
            if (withNav) try {
                return unwrap { openWith(context, sampleRate, nav = true) }
            } catch (error: Throwable) {
                AppLog.i("DESKTOP h1318: микс голоса навигатора не создан: ${error.javaClass.simpleName}: ${error.message}, голос в MEDIA", false)
            }
            return unwrap { openWith(context, sampleRate, nav = false) }
        }

        private fun openWith(context: Context, sampleRate: Int, nav: Boolean): DesktopAudioLoopback {
            val format = AudioFormat.Builder().setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
            val uids = if (nav) navUids(context) else emptyList()
            if (nav) check(uids.isNotEmpty()) { "навигаторы не установлены" }
            val mainMix = buildMix(format, MEDIA_USAGES, excludeUids = uids)
            val navMix = if (nav) buildMix(format, IntArray(0), matchUids = uids) else null
            if (nav) AppLog.i("DESKTOP h1318: uid навигаторов ${uids.joinToString()}", false)

            val policyBuilderClass = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder")
            val policyBuilder = policyBuilderClass.getConstructor(Context::class.java).newInstance(context)
            val addMix = policyBuilderClass.getMethod("addMix", mainMix.javaClass)
            addMix.invoke(policyBuilder, mainMix)
            if (navMix != null) addMix.invoke(policyBuilder, navMix)
            val policy = policyBuilderClass.getMethod("build").invoke(policyBuilder)!!

            val audio = context.getSystemService(AudioManager::class.java)
            val result = AudioManager::class.java.getMethod("registerAudioPolicy", policy.javaClass).invoke(audio, policy) as Int
            check(result == 0) { "Android не зарегистрировал AudioPolicy: $result" }
            var record: AudioRecord? = null
            try {
                val sink = policy.javaClass.getMethod("createAudioRecordSink", mainMix.javaClass)
                record = sink.invoke(policy, mainMix) as AudioRecord
                if (record.state != AudioRecord.STATE_INITIALIZED) error("Android не создал запись микса")
                val navRecord = navMix?.let {
                    (sink.invoke(policy, it) as AudioRecord).also { r ->
                        if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); error("Android не создал запись навигационного микса") }
                    }
                }
                if (navRecord != null) AppLog.i("DESKTOP h1318: голос навигатора отдельным миксом", false)
                return DesktopAudioLoopback(audio, policy, record, navRecord)
            } catch (e: Throwable) {
                runCatching { record?.release() }
                unregister(audio, policy)
                throw e
            }
        }

        private fun buildMix(format: AudioFormat, usages: IntArray, matchUids: List<Int> = emptyList(),
                             excludeUids: List<Int> = emptyList()): Any {
            val ruleBuilderClass = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")
            val ruleBuilder = ruleBuilderClass.getConstructor().newInstance()
            val addRule = ruleBuilderClass.getMethod("addRule", AudioAttributes::class.java, Int::class.javaPrimitiveType)
            for (usage in usages) addRule.invoke(ruleBuilder, AudioAttributes.Builder().setUsage(usage).build(), RULE_MATCH_ATTRIBUTE_USAGE)
            if (matchUids.isNotEmpty() || excludeUids.isNotEmpty()) {
                // исключение - отдельный метод excludeMixRule с базовым правилом (addMixRule 0x8004 не принимает)
                val addMixRule = ruleBuilderClass.getMethod("addMixRule", Int::class.javaPrimitiveType, Any::class.java)
                val excludeMixRule = ruleBuilderClass.getMethod("excludeMixRule", Int::class.javaPrimitiveType, Any::class.java)
                for (uid in matchUids) addMixRule.invoke(ruleBuilder, RULE_MATCH_UID, uid)
                for (uid in excludeUids) excludeMixRule.invoke(ruleBuilder, RULE_MATCH_UID, uid)
            }
            val rule = ruleBuilderClass.getMethod("build").invoke(ruleBuilder)!!

            val mixBuilderClass = Class.forName("android.media.audiopolicy.AudioMix\$Builder")
            val mixBuilder = mixBuilderClass.getConstructor(rule.javaClass).newInstance(rule)
            mixBuilderClass.getMethod("setFormat", AudioFormat::class.java).invoke(mixBuilder, format)
            mixBuilderClass.getMethod("setRouteFlags", Int::class.javaPrimitiveType).invoke(mixBuilder, ROUTE_FLAG_LOOP_BACK)
            return mixBuilderClass.getMethod("build").invoke(mixBuilder)!!
        }

        private fun unregister(audio: AudioManager, policy: Any) {
            runCatching { AudioManager::class.java.getMethod("unregisterAudioPolicy", policy.javaClass).invoke(audio, policy) }
        }

        private inline fun <T> unwrap(block: () -> T): T = try { block() } catch (e: InvocationTargetException) { throw e.targetException ?: e }
    }
}
