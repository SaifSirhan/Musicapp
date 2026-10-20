package com.sharl.musicapp.data

import com.sharl.musicapp.data.SupabaseClientProvider
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

class AuthRepository {

    /**
     * Scope for YouTube Music account imports. The Credential Manager flow used
     * for Supabase sign-in only yields an ID token, so this scope is requested
     * separately through the Google Identity Authorization API (see
     * ImportAccountScreen) and its access token is kept in SettingsRepo.
     */
    val youtubeScope: String = YtmImporter.YOUTUBE_SCOPE

    /** False until SUPABASE_URL / SUPABASE_ANON_KEY are filled in (secrets.properties). */
    val configured: Boolean get() = SupabaseClientProvider.isConfigured

    val session: Flow<UserSession?> = if (!configured) {
        flowOf(null)
    } else {
        SupabaseClientProvider.client.auth.sessionStatus.map { status ->
            (status as? SessionStatus.Authenticated)?.session
        }
    }

    fun currentUserId(): String? =
        if (!configured) null else SupabaseClientProvider.client.auth.currentSessionOrNull()?.user?.id

    fun currentUserEmail(): String? =
        if (!configured) null else SupabaseClientProvider.client.auth.currentSessionOrNull()?.user?.email

    suspend fun signInWithGoogle(idToken: String, rawNonce: String?) {
        check(configured) { "Sync is not configured (missing SUPABASE_URL / SUPABASE_ANON_KEY)." }
        SupabaseClientProvider.client.auth.signInWith(IDToken) {
            this.idToken = idToken
            this.provider = Google
            // Null for the legacy Google Sign-In path: that token carries no nonce claim,
            // so Supabase must not be handed one to compare against.
            this.nonce = rawNonce
        }
    }

    suspend fun signOut() {
        if (configured) SupabaseClientProvider.client.auth.signOut()
    }
}
