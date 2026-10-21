package com.sharl.musicapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sharl.musicapp.data.DiagnosticsManager
import com.sharl.musicapp.data.InnerTubeHelper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Live probe of the resolution path the diagnostics screen reports: each
 * attempt must land in [DiagnosticsManager] with a per-client breakdown, and a
 * failure must not be silent (the 403 / LOGIN_REQUIRED / ciphered causes are
 * what decide whether the embedded client gets tried).
 */
@RunWith(AndroidJUnit4::class)
class DiagnosticsPrefetchTest {

    @Test
    fun innerTubeResultIsRecordedWithClientBreakdown() = runBlocking {
        DiagnosticsManager.reset()
        // Midnight City, the same track the mix test uses.
        val url = InnerTubeHelper.getStreamUrl("dX3k_QDnzHE", immediateUse = true)
        val state = DiagnosticsManager.state.value
        val log = state.lastInnerTube?.text.orEmpty()
        println("InnerTube -> ${if (url != null) "ok" else "null"} [$log]")
        assertTrue("attempt must be recorded, got '${state.lastInnerTube}'", state.lastInnerTube != null)
        assertTrue("breakdown must name a client, got '$log'", log.contains("IOS"))
        if (url != null) {
            assertTrue("retrieved URL should be https, got '$url'", url.startsWith("https://"))
        }
    }

    @Test
    fun reportContainsEverySection() {
        DiagnosticsManager.reset()
        DiagnosticsManager.recordInnerTube(false, 120, "IOS HTTP 403")
        DiagnosticsManager.recordYtdlp(true, 4200)
        DiagnosticsManager.recordCacheHit("dX3k_QDnzHE")
        DiagnosticsManager.recordPrefetch("Queued 4 tracks")
        val report = DiagnosticsManager.report()
        println(report)
        listOf("InnerTube:", "yt-dlp:", "PlaybackCache hits: 1", "Cache hit", "Queued 4 tracks")
            .forEach { assertTrue("report missing '$it':\n$report", report.contains(it)) }
    }
}
