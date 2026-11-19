package com.eddyizm.tempus.homemusic

import io.sigpipe.jbsdiff.Patch
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Pure rules of the delta self-update: which published variant to take, how a downloaded patch is validated,
 * how the exact APK is rebuilt, and what happens after a failed patch. Kept free of Android classes so the
 * rules run in plain JVM tests; [HubUpdater] only wires them to the UI and the network.
 */
internal object UpdateDelta {
    const val FORMAT = "bsdiff40-v1"
    const val MAX_APK = 128L * 1024 * 1024
    const val MAX_PATCH = 16L * 1024 * 1024
    const val MAX_DELTA_APK = 32L * 1024 * 1024
    private val hash = Regex("[0-9a-f]{64}")

    /** What to do when applying a patch (download, header, restore or verification) has failed. */
    enum class Fallback { FAIL, FULL_NOW, ASK_FULL }

    /**
     * A failed patch never silently costs a full APK on a metered network: there the user decides.
     * Without a patch in play a failure is final (the full download itself failed).
     */
    fun fallback(patchWasUsed: Boolean, metered: Boolean): Fallback = when {
        !patchWasUsed -> Fallback.FAIL
        metered -> Fallback.ASK_FULL
        else -> Fallback.FULL_NOW
    }

    fun validFile(file: HubUpdateFile?, kind: String): Boolean {
        if (file == null || file.size <= 0 || file.size > if (kind == "apk") MAX_APK else MAX_PATCH) return false
        val digest = file.sha256 ?: return false
        if (!hash.matches(digest)) return false
        val suffix = if (kind == "apk") "apk" else "bsdiff"
        return file.url == "/app/artifacts/$kind-$digest.$suffix"
    }

    /** Smallest valid patch made for exactly the installed APK (version code and SHA-256) that saves traffic. */
    fun select(release: HubAppRelease, baseCode: Long, baseHash: String): HubUpdatePatch? {
        val full = release.full
        if (!validFile(full, "apk") || full!!.size > MAX_DELTA_APK) return null
        return release.patches.orEmpty().filter {
            it.format == FORMAT && it.baseVersionCode == baseCode && it.baseSha256 == baseHash &&
                validFile(it, "patch") && it.size < full.size
        }.minByOrNull { it.size }
    }

    private fun number(bytes: ByteArray, offset: Int): Long {
        var value = (bytes[offset + 7].toInt() and 0x7f).toLong()
        for (index in 6 downTo 0) value = (value shl 8) or (bytes[offset + index].toLong() and 0xff)
        if (bytes[offset + 7].toInt() and 0x80 != 0) throw IOException("Negative patch header")
        return value
    }

    fun validateHeader(bytes: ByteArray, targetSize: Long) {
        if (bytes.size < 32 || !bytes.copyOfRange(0, 8).contentEquals("BSDIFF40".toByteArray())) throw IOException("Invalid patch header")
        val control = number(bytes, 8)
        val diff = number(bytes, 16)
        val output = number(bytes, 24)
        if (targetSize <= 0 || targetSize > MAX_DELTA_APK || output != targetSize ||
            control < 4 || diff < 4 || control > bytes.size - 32L || diff > bytes.size - 32L - control ||
            bytes.size - 32L - control - diff < 4) throw IOException("Invalid patch sizes")
        for (start in listOf(32, 32 + control.toInt(), 32 + control.toInt() + diff.toInt())) {
            if (bytes[start] != 'B'.code.toByte() || bytes[start + 1] != 'Z'.code.toByte() ||
                bytes[start + 2] != 'h'.code.toByte() || bytes[start + 3].toInt() !in '1'.code..'9'.code) {
                throw IOException("Unsupported patch compression")
            }
        }
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Rebuilds the published APK into [output] from the installed [source] and a downloaded [patchFile]. The patch
     * must be the one chosen for exactly this base, and the result must equal [full] byte for byte (size and SHA-256).
     */
    @Throws(IOException::class)
    fun reconstruct(source: File, patchFile: File, output: File, patch: HubUpdatePatch, full: HubUpdateFile) {
        if (!validFile(full, "apk") || full.size > MAX_DELTA_APK) throw IOException("Invalid target description")
        if (source.length() !in 1..MAX_DELTA_APK || !sha256(source).equals(patch.baseSha256, true)) throw IOException("База обновления изменилась")
        if (patchFile.length() != patch.size || patch.size > MAX_PATCH) throw IOException("Неверный размер обновления")
        if (!sha256(patchFile).equals(patch.sha256, true)) throw IOException("Контрольная сумма не совпала")
        val patchBytes = patchFile.readBytes()
        validateHeader(patchBytes, full.size)
        try {
            output.outputStream().use { Patch.patch(source.readBytes(), patchBytes, it) }
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("Обновление не применилось", e)
        }
        if (output.length() != full.size || !sha256(output).equals(full.sha256, true)) throw IOException("Повреждённый APK")
    }
}
