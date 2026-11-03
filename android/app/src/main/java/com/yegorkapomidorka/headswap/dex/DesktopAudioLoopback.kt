package com.yegorkapomidorka.headswap.dex

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import java.lang.reflect.InvocationTargetException

/**
 * Loopback AudioMix для медиа: системный AudioPolicy (SystemApi, через reflection - главный процесс запущен
 * `am instrument --no-hidden-api-checks`), право MODIFY_AUDIO_ROUTING из активации --audio.
 * Голый REMOTE_SUBMIX проигрывает маршрутизации по устройствам: при A2DP (Vezel «Honda HFT») медиа уходит
 * в Bluetooth, а захват читает нули. Микс же перехватывает поток раньше выбора устройства, телефон и BT молчат.
 * Политику система снимает сама, если процесс умрёт, - звук телефона не застрянет в мёртвом submix.
 */
internal class DesktopAudioLoopback private constructor(
    private val audio: AudioManager, private val policy: Any, val record: AudioRecord,
) {
    fun release() {
        runCatching { record.release() }
        unregister(audio, policy)
    }

    companion object {
        private const val RULE_MATCH_ATTRIBUTE_USAGE = 1 // AudioMixingRule.RULE_MATCH_ATTRIBUTE_USAGE
        private const val ROUTE_FLAG_LOOP_BACK = 2       // AudioMix.ROUTE_FLAG_LOOP_BACK, без RENDER: на телефоне тишина
        private val USAGES = intArrayOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME, AudioAttributes.USAGE_UNKNOWN)

        /** Канальная маска выходная: createAudioRecordSink сам переводит её во входную. */
        fun open(context: Context, sampleRate: Int): DesktopAudioLoopback = unwrap {
            val format = AudioFormat.Builder().setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
            val ruleBuilderClass = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")
            val ruleBuilder = ruleBuilderClass.getConstructor().newInstance()
            val addRule = ruleBuilderClass.getMethod("addRule", AudioAttributes::class.java, Int::class.javaPrimitiveType)
            for (usage in USAGES) addRule.invoke(ruleBuilder, AudioAttributes.Builder().setUsage(usage).build(), RULE_MATCH_ATTRIBUTE_USAGE)
            val rule = ruleBuilderClass.getMethod("build").invoke(ruleBuilder)!!

            val mixBuilderClass = Class.forName("android.media.audiopolicy.AudioMix\$Builder")
            val mixBuilder = mixBuilderClass.getConstructor(rule.javaClass).newInstance(rule)
            mixBuilderClass.getMethod("setFormat", AudioFormat::class.java).invoke(mixBuilder, format)
            mixBuilderClass.getMethod("setRouteFlags", Int::class.javaPrimitiveType).invoke(mixBuilder, ROUTE_FLAG_LOOP_BACK)
            val mix = mixBuilderClass.getMethod("build").invoke(mixBuilder)!!

            val policyBuilderClass = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder")
            val policyBuilder = policyBuilderClass.getConstructor(Context::class.java).newInstance(context)
            policyBuilderClass.getMethod("addMix", mix.javaClass).invoke(policyBuilder, mix)
            val policy = policyBuilderClass.getMethod("build").invoke(policyBuilder)!!

            val audio = context.getSystemService(AudioManager::class.java)
            val result = AudioManager::class.java.getMethod("registerAudioPolicy", policy.javaClass).invoke(audio, policy) as Int
            check(result == 0) { "Android не зарегистрировал AudioPolicy: $result" }
            try {
                val record = policy.javaClass.getMethod("createAudioRecordSink", mix.javaClass).invoke(policy, mix) as AudioRecord
                if (record.state != AudioRecord.STATE_INITIALIZED) { record.release(); error("Android не создал запись микса") }
                DesktopAudioLoopback(audio, policy, record)
            } catch (e: Throwable) {
                unregister(audio, policy)
                throw e
            }
        }

        private fun unregister(audio: AudioManager, policy: Any) {
            runCatching { AudioManager::class.java.getMethod("unregisterAudioPolicy", policy.javaClass).invoke(audio, policy) }
        }

        private inline fun <T> unwrap(block: () -> T): T = try { block() } catch (e: InvocationTargetException) { throw e.targetException ?: e }
    }
}
