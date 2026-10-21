package com.sharl.musicapp.data

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.util.Log
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.sharl.musicapp.BuildConfig
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AuthRepository {

    /**
     * Scope for YouTube Music account imports. The Credential Manager flow used
     * for Supabase sign-in only yields an ID token, so this scope is requested
     * separately through the Google Identity Authorization API and its access
     * token is kept in SettingsRepo.
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

    /**
     * Starts the YouTube authorization request for [account]. The account is
     * explicit so Play Services cannot silently reuse the previous one.
     */
    suspend fun authorizeYoutube(context: Context, account: Account): AuthorizationResult {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(youtubeScope)))
            .setAccount(account)
            .build()
        return suspendCancellableCoroutine { cont ->
            Identity.getAuthorizationClient(context).authorize(request)
                .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                .addOnFailureListener { e ->
                    if (cont.isActive) cont.resumeWithException(e)
                }
        }
    }

    /**
     * Disconnect must revoke, not just forget: an unrevoked grant is silently
     * reused by the next [authorizeYoutube] call, which is why the import was
     * stuck on one Google account. Revoking forces the chooser afterwards.
     */
    suspend fun revokeYoutubeAccess(context: Context): Boolean {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(BuildConfig.GOOGLE_WEB_CLIENT_ID)
            .requestEmail()
            .requestScopes(Scope(youtubeScope))
            .build()
        val client = GoogleSignIn.getClient(context.applicationContext, options)
        // Clearing the legacy client's cached account is best effort; the
        // revoke below is what actually frees the account for re-selection.
        runCatching { awaitTask(client.signOut()) }
            .onFailure { Log.w(TAG, "Google signOut failed", it) }
        return try {
            awaitTask(client.revokeAccess())
            Log.d(TAG, "Google access revoked; next connect will show the chooser")
            true
        } catch (e: ApiException) {
            Log.w(TAG, "revokeAccess failed status=${e.statusCode}", e)
            false
        } catch (e: Exception) {
            Log.w(TAG, "revokeAccess failed", e)
            false
        }
    }

    private suspend fun <T> awaitTask(task: com.google.android.gms.tasks.Task<T>): T =
        suspendCancellableCoroutine { cont ->
            task.addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                .addOnFailureListener { e -> if (cont.isActive) cont.resumeWithException(e) }
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
