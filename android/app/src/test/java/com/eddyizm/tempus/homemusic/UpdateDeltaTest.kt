package com.eddyizm.tempus.homemusic

import io.sigpipe.jbsdiff.Diff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Random

class UpdateDeltaTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun file(hash: String, size: Long) = HubUpdateFile().apply {
        sha256 = hash; this.size = size; url = "/app/artifacts/apk-$hash.apk"
    }

    private fun patchEntry(hash: String, base: String, code: Long, size: Long) = HubUpdatePatch().apply {
        sha256 = hash; this.size = size; url = "/app/artifacts/patch-$hash.bsdiff"
        baseSha256 = base; baseVersionCode = code; format = UpdateDelta.FORMAT
    }

    private fun release(full: HubUpdateFile?, vararg patches: HubUpdatePatch) = HubAppRelease().apply {
        this.full = full; this.patches = patches.toList()
    }

    // ---- choosing the variant --------------------------------------------------------------------

    @Test fun chooses_the_smallest_patch_made_for_the_exact_installed_base() {
        val base = "a".repeat(64)
        val release = release(file("b".repeat(64), 1000),
            patchEntry("c".repeat(64), base, 3, 400), patchEntry("d".repeat(64), base, 3, 200),
            patchEntry("e".repeat(64), base, 4, 100), patchEntry("f".repeat(64), "b".repeat(64), 3, 10))
        assertEquals(200L, UpdateDelta.select(release, 3, base)?.size)
        assertNull(UpdateDelta.select(release, 5, base))
    }

    @Test fun same_version_code_with_another_apk_hash_gets_no_patch() {
        // A locally rebuilt or sideloaded APK with the published version code is not the published base.
        val release = release(file("b".repeat(64), 1000), patchEntry("c".repeat(64), "a".repeat(64), 3, 100))
        assertNull(UpdateDelta.select(release, 3, "9".repeat(64)))
    }

    @Test fun rejects_patches_that_do_not_save_traffic_or_point_elsewhere() {
        val base = "a".repeat(64)
        val full = file("b".repeat(64), 1000)
        assertNull(UpdateDelta.select(release(full, patchEntry("c".repeat(64), base, 3, 1000)), 3, base))
        val foreign = patchEntry("c".repeat(64), base, 3, 2).apply { url = "https://other.test/patch" }
        assertNull(UpdateDelta.select(release(full, foreign), 3, base))
        val wrongName = patchEntry("c".repeat(64), base, 3, 2).apply { url = "/app/artifacts/patch-${"d".repeat(64)}.bsdiff" }
        assertNull(UpdateDelta.select(release(full, wrongName), 3, base))
        val unknownFormat = patchEntry("c".repeat(64), base, 3, 2).apply { format = "bsdiff40-v2" }
        assertNull(UpdateDelta.select(release(full, unknownFormat), 3, base))
        val upper = patchEntry("C".repeat(64), base, 3, 2).apply { url = "/app/artifacts/patch-${"C".repeat(64)}.bsdiff" }
        assertNull(UpdateDelta.select(release(full, upper), 3, base))
    }

    @Test fun without_a_valid_full_apk_description_no_patch_is_offered() {
        val base = "a".repeat(64)
        val item = patchEntry("c".repeat(64), base, 3, 10)
        assertNull(UpdateDelta.select(release(null, item), 3, base))
        assertNull(UpdateDelta.select(release(file("b".repeat(64), UpdateDelta.MAX_DELTA_APK + 1), item), 3, base))
    }

    // ---- after a failed patch --------------------------------------------------------------------

    @Test fun failed_patch_costs_a_full_apk_only_with_consent_on_a_metered_network() {
        assertEquals(UpdateDelta.Fallback.ASK_FULL, UpdateDelta.fallback(patchWasUsed = true, metered = true))
        assertEquals(UpdateDelta.Fallback.FULL_NOW, UpdateDelta.fallback(patchWasUsed = true, metered = false))
        assertEquals(UpdateDelta.Fallback.FAIL, UpdateDelta.fallback(patchWasUsed = false, metered = true))
        assertEquals(UpdateDelta.Fallback.FAIL, UpdateDelta.fallback(patchWasUsed = false, metered = false))
    }

    // ---- patch header ----------------------------------------------------------------------------

    @Test fun header_is_validated_before_any_allocation() {
        val bytes = ByteArray(44)
        "BSDIFF40".toByteArray().copyInto(bytes)
        bytes[8] = 4; bytes[16] = 4; bytes[24] = 10
        "BZh9".toByteArray().copyInto(bytes, 32)
        "BZh9".toByteArray().copyInto(bytes, 36)
        "BZh9".toByteArray().copyInto(bytes, 40)
        UpdateDelta.validateHeader(bytes, 10)
        bytes[16] = 5
        assertThrows(IOException::class.java) { UpdateDelta.validateHeader(bytes, 10) }
        bytes[16] = 4; bytes[24] = 11
        assertThrows(IOException::class.java) { UpdateDelta.validateHeader(bytes, 10) }
        bytes[24] = 10; bytes[36] = 'X'.code.toByte()
        assertThrows(IOException::class.java) { UpdateDelta.validateHeader(bytes, 10) }
    }

    @Test fun huge_declared_output_is_refused() {
        val bytes = ByteArray(44)
        "BSDIFF40".toByteArray().copyInto(bytes)
        bytes[8] = 4; bytes[16] = 4
        bytes[27] = 0x7f   // declared output of ~2 GB
        assertThrows(IOException::class.java) { UpdateDelta.validateHeader(bytes, 10) }
    }

    // ---- restoring the exact APK -----------------------------------------------------------------

    private class Fixture(val base: File, val patchFile: File, val output: File, val patch: HubUpdatePatch, val full: HubUpdateFile, val target: ByteArray)

    private fun fixture(): Fixture {
        val random = Random(7)
        val old = ByteArray(300_000).also { random.nextBytes(it) }
        val target = old.copyOf(310_000).also { for (i in 150_000 until 160_000) it[i] = (i % 251).toByte() }
        val patchOut = ByteArrayOutputStream().also { Diff.diff(old, target, it) }
        val patchBytes = patchOut.toByteArray()
        val base = tmp.newFile().also { it.writeBytes(old) }
        val patchFile = tmp.newFile().also { it.writeBytes(patchBytes) }
        val full = file(sha(target), target.size.toLong())
        val patch = patchEntry(sha(patchBytes), sha(old), 3, patchBytes.size.toLong())
        return Fixture(base, patchFile, tmp.newFile(), patch, full, target)
    }

    @Test fun patch_restores_the_exact_published_apk() {
        val f = fixture()
        assertTrue("patch must save traffic", f.patch.size < f.full.size)
        UpdateDelta.reconstruct(f.base, f.patchFile, f.output, f.patch, f.full)
        assertTrue(f.target.contentEquals(f.output.readBytes()))
    }

    @Test fun patch_for_another_installed_apk_is_refused() {
        val f = fixture()
        f.base.writeBytes(f.base.readBytes().also { it[10] = (it[10] + 1).toByte() })
        val e = assertThrows(IOException::class.java) { UpdateDelta.reconstruct(f.base, f.patchFile, f.output, f.patch, f.full) }
        assertTrue(e.message, e.message!!.contains("База"))
    }

    @Test fun corrupted_patch_bytes_are_refused_by_the_checksum() {
        val f = fixture()
        f.patchFile.writeBytes(f.patchFile.readBytes().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() })
        assertThrows(IOException::class.java) { UpdateDelta.reconstruct(f.base, f.patchFile, f.output, f.patch, f.full) }
    }

    @Test fun truncated_or_padded_patch_is_refused_by_the_size() {
        val f = fixture()
        val bytes = f.patchFile.readBytes()
        f.patchFile.writeBytes(bytes.copyOf(bytes.size - 5))
        assertThrows(IOException::class.java) { UpdateDelta.reconstruct(f.base, f.patchFile, f.output, f.patch, f.full) }
        f.patchFile.writeBytes(bytes + ByteArray(5))
        assertThrows(IOException::class.java) { UpdateDelta.reconstruct(f.base, f.patchFile, f.output, f.patch, f.full) }
    }

    @Test fun patch_that_builds_a_different_apk_is_refused() {
        // The manifest hash of the patch is correct, but the result is not the published APK.
        val f = fixture()
        val wrongTarget = f.target.copyOf().also { it[0] = (it[0] + 1).toByte() }
        val other = ByteArrayOutputStream().also { Diff.diff(f.base.readBytes(), wrongTarget, it) }.toByteArray()
        f.patchFile.writeBytes(other)
        val patch = patchEntry(sha(other), f.patch.baseSha256!!, 3, other.size.toLong())
        val e = assertThrows(IOException::class.java) { UpdateDelta.reconstruct(f.base, f.patchFile, f.output, patch, f.full) }
        assertTrue(e.message, e.message!!.contains("Повреждённый"))
    }

    @Test fun patch_with_an_unusable_target_description_is_refused() {
        val f = fixture()
        f.full.url = "/app/latest.apk"
        assertThrows(IOException::class.java) { UpdateDelta.reconstruct(f.base, f.patchFile, f.output, f.patch, f.full) }
    }
}
