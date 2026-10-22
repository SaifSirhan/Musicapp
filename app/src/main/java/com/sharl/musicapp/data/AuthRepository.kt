package com.sharl.musicapp.data

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import com.google.android.gms.auth.GoogleAuthUtil
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AuthRepository {

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

    /**
     * Lists the Google accounts on this device. Used to drive an explicit
     * chooser: [AuthorizationRequest.Builder] in play-services-auth 21.2.0 has
     * no filter/auto-select switches (those belong to GetGoogleIdOption), and
     * an unspecified request is served by the last-authorized account.
     */
    suspend fun googleAccounts(context: Context): List<Account> {
        val manager = AccountManager.get(context.applicationContext)
        // Deprecated since API 26 but still the only synchronously accessible
        // account list for the current profile; Workspace may return null.
        @Suppress("DEPRECATION")
        val accounts = manager.getAccountsByType(GoogleAuthUtil.GOOGLE_ACCOUNT_TYPE)
        // Only accounts that still have the app's Play Services binding are
        // usable; stale shadow accounts would fail during authorization.
        return accounts.filter {
            runCatching { GoogleAuthUtil.getAccountId(context, it.name) }.isSuccess
        }
    }

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

    private companion object {
        const val TAG = "AUTH"
    }
}
