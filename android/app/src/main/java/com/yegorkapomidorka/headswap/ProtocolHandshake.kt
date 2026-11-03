// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap

/**
 * A HU can restart its decoder/client while keeping the USB accessory open. Repeat the original
 * greeting for the same protocol version, without restarting the phone's display or video source.
 * Accessed only by the transport's serialized command reader.
 */
internal class ProtocolHandshake(
    private val screenOn: Boolean,
    private val deviceInfo: () -> DeviceInfo,
) {
    data class Reply(
        val major: Int,
        val minor: Int,
        val repeated: Boolean,
        val commands: List<OutCmd>,
        val mdInfoKey: String,
    )

    private var accepted: Reply? = null

    /** Invalid input or a different version cannot replace the live session's negotiated version. */
    fun accept(message: CmdMsg): Reply? {
        if (message.type != Msg.HU_PROTOCOL_VERSION || message.truncated) return null
        val fields = PbReader.parse(message.body) ?: return null
        fun versionField(number: Int): Int? {
            val field = fields.singleOrNull { it.number == number } ?: return null
            if (field.wire != 0 || field.num !in 0L..Int.MAX_VALUE.toLong()) return null
            return field.num.toInt()
        }
        val major = versionField(1)?.takeIf { it > 0 } ?: return null
        val minor = versionField(2) ?: return null
        accepted?.let {
            return if (it.major == major && it.minor == minor) it.copy(repeated = true) else null
        }
        val info = deviceInfo()
        return Reply(major, minor, false, Handshake.startBatch(screenOn, info), HuAuth.mdInfoKey(info))
            .also { accepted = it }
    }
}
