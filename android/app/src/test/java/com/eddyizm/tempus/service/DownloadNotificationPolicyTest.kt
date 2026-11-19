package com.eddyizm.tempus.service

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadNotificationPolicyTest {
    @Test
    fun automaticOriginSurvivesStoringTheRequest() {
        val stored = DownloaderManager.requestData("track-id", true).copyOf()

        assertTrue(DownloaderManager.isAutomatic(stored))
    }

    @Test
    fun manualAndOlderRequestsStillShowNotifications() {
        val legacyData = "track-id".encodeToByteArray()
        val manualData = DownloaderManager.requestData("track-id", false)

        assertArrayEquals(legacyData, manualData)
        assertFalse(DownloaderManager.isAutomatic(manualData))
        assertFalse(DownloaderManager.isAutomatic(byteArrayOf()))
    }
}
