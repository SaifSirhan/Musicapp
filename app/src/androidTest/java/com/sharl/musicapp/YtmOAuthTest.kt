package com.sharl.musicapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sharl.musicapp.data.YtmImporter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TVHTML5 Device Flow (RFC 8628) for YouTube Music import.
 * Tests verify the device code request and polling behavior.
 */
@RunWith(AndroidJUnit4::class)
class YtmOAuthTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun startDeviceFlowReturnsValidCodes() = runBlocking {
        YtmImporter.clearError()
        val result = YtmImporter.startDeviceFlow()

        assertNotNull("device_code must not be null", result.deviceCode)
        assertNotNull("user_code must not be null", result.userCode)
        assertNotNull("verification_url must not be null", result.verificationUrl)
        assertTrue("verification_url should contain google.com",
            result.verificationUrl.contains("google.com"))
        assertTrue("user_code should be alphanumeric with dash",
            result.userCode.matches(Regex("[A-Z0-9-]+")))
    }

    @Test
    fun deviceCodeHasRequiredFields() = runBlocking {
        val result = YtmImporter.startDeviceFlow()

        assertTrue("device_code should not be empty", result.deviceCode.isNotEmpty())
        assertTrue("user_code should be 4-10 chars", result.userCode.length in 4..10)
        assertTrue("verification_url should be a valid URL",
            result.verificationUrl.startsWith("https://"))
    }
}
