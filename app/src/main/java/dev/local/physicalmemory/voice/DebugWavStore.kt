package dev.local.physicalmemory.voice

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Called only after an explicit Debug opt-in. Audio never leaves app storage automatically. */
class DebugWavStore(private val directory: File) {
    fun save(sessionId: String, samples: ShortArray): String {
        require(sessionId.matches(Regex("[A-Za-z0-9_-]+")))
        check(directory.isDirectory || directory.mkdirs())
        val target = File(directory, "$sessionId.wav")
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples.size * 2); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(16_000); putInt(32_000)
            putShort(2); putShort(16); put("data".toByteArray()); putInt(samples.size * 2)
        }.array()
        val pcm = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach(pcm::putShort)
        val pending = File(directory, "$sessionId.tmp")
        pending.outputStream().use { it.write(header); it.write(pcm.array()) }
        check(pending.renameTo(target)) { "Could not commit debug WAV" }
        return target.absolutePath
    }
}
