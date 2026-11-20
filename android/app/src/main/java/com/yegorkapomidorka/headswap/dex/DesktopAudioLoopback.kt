package com.yegorkapomidorka.headswap.dex

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import com.yegorkapomidorka.headswap.AppLog
import java.lang.reflect.InvocationTargetException

/**
 * Loopback AudioMix для медиа: системный AudioPolicy (SystemApi, через reflection - главный процесс запущен
 * `am instrument --no-hidden-api-checks`), право MODIFY_AUDIO_ROUTING из активации --audio.
 * Голый REMOTE_SUBMIX проигрывает маршрутизации по устройствам: при A2DP (Vezel «Honda HFT») медиа уходит
 * в Bluetooth, а захват читает нули. Микс же перехватывает поток раньше выбора устройства, телефон и BT молчат.
 * Политику система снимает сама, если процесс умрёт, - звук телефона не застрянет в мёртвом submix.
 *
 * h1318: голос навигатора (NAVIGATION_GUIDANCE) - отдельный микс в той же политике, [navRecord]: его читает
 * DesktopAudioCapture и отдаёт в канал SPEECH (CarLife VR/TTS), крутилка ГУ получает отдельную громкость подсказок.
 * Не создался навигационный микс - один микс как раньше, голос навигатора внутри медиа, [navRecord] = null.
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
        private const val ROUTE_FLAG_LOOP_BACK = 2       // AudioMix.ROUTE_FLAG_LOOP_BACK, без RENDER: на телефоне тишина
        // Основной микс: медиа без голоса навигатора. SONIFICATION (щелчки, звуки системы) не берём.
        private val MEDIA_USAGES = intArrayOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME, AudioAttributes.USAGE_UNKNOWN,
            // голос Gemini (стенд 07.11): обычный захват ASSISTANT не видит, системный AudioMix видит
            AudioAttributes.USAGE_ASSISTANT)
        // Голос навигатора (Яндекс: NAVIGATION_GUIDANCE) без микса уходит в BT A2DP, и ГУ его не играет.
        private val NAV_USAGES = intArrayOf(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)

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
            val mainUsages = if (nav) MEDIA_USAGES else MEDIA_USAGES + NAV_USAGES
            val mainMix = buildMix(format, mainUsages)
            val navMix = if (nav) buildMix(format, NAV_USAGES) else null

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

        private fun buildMix(format: AudioFormat, usages: IntArray): Any {
            val ruleBuilderClass = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")
            val ruleBuilder = ruleBuilderClass.getConstructor().newInstance()
            val addRule = ruleBuilderClass.getMethod("addRule", AudioAttributes::class.java, Int::class.javaPrimitiveType)
            for (usage in usages) addRule.invoke(ruleBuilder, AudioAttributes.Builder().setUsage(usage).build(), RULE_MATCH_ATTRIBUTE_USAGE)
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
