package com.sharl.musicapp

import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Regression tests for the Phase 24b OTA hotfix: the FileProvider root must
 * contain the APK (this crashed 0.17.0 at 99%) and a missing file must yield
 * a message instead of an exception.
 */
@RunWith(AndroidJUnit4::class)
class OtaInstallTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun updateApkInsideUpdatesDirGetsAContentUri() {
        val dir = File(context.cacheDir, "updates")
        assertTrue(dir.isDirectory || dir.mkdirs())
        val apk = File(dir, "update.apk")
        apk.writeBytes(ByteArray(16) { it.toByte() })
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apk
            )
            assertNotNull(uri)
            assertEquals("content", uri.scheme)
            // Legacy layout (cache/update.apk) must no longer be exposed.
            val legacy = File(context.cacheDir, "update.apk")
            legacy.writeBytes(ByteArray(4))
            try {
                var threw = false
                try {
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        legacy
                    )
                } catch (e: IllegalArgumentException) {
                    threw = true
                }
                assertTrue("legacy cache/update.apk should be rejected", threw)
            } finally {
                legacy.delete()
            }
        } finally {
            apk.delete()
        }
    }

    @Test
    fun installUpdateReportsMissingFile() {
        val missing = File(context.cacheDir, "updates/does-not-exist.apk")
        val error = installUpdate(context, missing)
        assertNotNull(error)
    }

    @Test
    fun installUpdateRejectsEmptyFile() {
        // A zero-length placeholder must be rejected before reaching the installer.
        val dir = File(context.cacheDir, "updates")
        assertTrue(dir.isDirectory || dir.mkdirs())
        val empty = File(dir, "empty.apk")
        empty.createNewFile()
        try {
            assertNotNull("expected empty file to be reported", installUpdate(context, empty))
        } finally {
            empty.delete()
        }
    }
}
