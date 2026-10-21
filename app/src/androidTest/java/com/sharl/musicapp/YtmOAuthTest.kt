package com.sharl.musicapp

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sharl.musicapp.data.YtmImporter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Browser OAuth for the import. The authorization page itself needs a person,
 * but everything around it is verifiable: PKCE, state binding, and the fact
 * that each flow only accepts its own redirect.
 */
@RunWith(AndroidJUnit4::class)
class YtmOAuthTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun authorizeStoresAFreshPkceVerifierPerAttempt() {
        YtmImporter.clearError()
        val prefs = context.getSharedPreferences("ytm_oauth", android.content.Context.MODE_PRIVATE)
        assertTrue("authorize() should report success, error=${YtmImporter.error.value}",
            YtmImporter.authorize(context))
        val first = prefs.getString("code_verifier", null)
        assertTrue("verifier must be 43-128 chars (RFC 7636), got ${first?.length}",
            first != null && first.length in 43..128)
        assertTrue("verifier must be URL-safe", first!!.matches(Regex("[A-Za-z0-9_-]+")))
        YtmImporter.authorize(context)
        assertTrue("a new attempt must not reuse the previous verifier",
            first != prefs.getString("code_verifier", null))
    }

    @Test
    fun redirectFromAnotherFlowIsNotClaimedAsGoogle() {
        // This is how MainActivity routes the shared callback: Spotify's state
        // must not be mistaken for a pending Google sign-in.
        YtmImporter.authorize(context)
        val spotifyUri = Uri.parse("com.sharl.musicapp://callback?code=abc&state=some-spotify-state")
        assertFalse(YtmImporter.matchesPendingState(context, spotifyUri))
    }

    @Test
    fun googleStateIsClaimedAndErrorSurfacesWithoutAToken() {
        YtmImporter.clearError()
        YtmImporter.authorize(context)
        val prefs = context.getSharedPreferences("ytm_oauth", android.content.Context.MODE_PRIVATE)
        val state = prefs.getString("state", null)!!
        val googleUri = Uri.parse("com.sharl.musicapp://callback?code=4/abc&state=$state")
        assertTrue(YtmImporter.matchesPendingState(context, googleUri))

        YtmImporter.handleRedirect(context, googleUri)
        // No client secret is configured in this build, so Google refuses the
        // exchange; the UI must receive that reason instead of a silent hang.
        val error = YtmImporter.error.value
        assertNull("no token may be produced from a refused exchange", YtmImporter.consumeToken())
        android.util.Log.i("YTM", "exchange result: $error")
        assertTrue("an error must be reported, got null", error != null)
    }

    @Test
    fun stateMismatchIsRejectedBeforeAnyExchange() {
        YtmImporter.clearError()
        YtmImporter.authorize(context)
        val uri = Uri.parse("com.sharl.musicapp://callback?code=4/abc&state=forged")
        YtmImporter.handleRedirect(context, uri)
        assertTrue(
            "state mismatch must fail: ${YtmImporter.error.value}",
            YtmImporter.error.value?.contains("state") == true
        )
    }
}
