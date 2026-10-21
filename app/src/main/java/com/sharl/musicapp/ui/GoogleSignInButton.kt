package com.sharl.musicapp.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.sharl.musicapp.BuildConfig
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Wires Google Sign-In through Credential Manager. A nonce is generated per attempt;
 * Supabase receives the raw nonce while Google receives its SHA-256 (replay protection).
 */
@Composable
fun GoogleSignInButton(
    onIdToken: (idToken: String, rawNonce: String?) -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember { CredentialManager.create(context) }

    val legacyClient: GoogleSignInClient = remember {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(BuildConfig.GOOGLE_WEB_CLIENT_ID)
            .requestEmail()
            .build()
        GoogleSignIn.getClient(context, options)
    }

    val legacyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            val token = account?.idToken
            if (token.isNullOrBlank()) {
                onError("Google sign-in returned no ID token.")
            } else {
                onIdToken(token, null)
            }
        } catch (e: ApiException) {
            android.util.Log.e("GSI", "legacy sign-in failed status=${e.statusCode}: ${e.message}", e)
            onError("Legacy sign-in failed (${e.statusCode}): ${e.message ?: "no message"}")
        }
    }

    Button(
        onClick = {
            scope.launch {
                try {
                    val rawNonce = generateNonce()
                    val hashedNonce = sha256(rawNonce)

                    // 1. Silent path for returning users: only accounts that
                    // already authorized this app, and no picker if there is
                    // exactly one. This is the common, frictionless case.
                    val credential = try {
                        credentialManager.getCredential(
                            context,
                            idTokenRequest(hashedNonce, filterByAuthorizedAccounts = true)
                        ).credential
                    } catch (e: NoCredentialException) {
                        // 2. Nothing authorized yet (new user, revoked grant,
                        // or a device/model whose Credential Manager filters
                        // them out — seen on some Honor builds). Retry with
                        // every Google account on the device, which forces the
                        // account picker.
                        android.util.Log.w(
                            "GSI",
                            "silent sign-in had no credential (${e.message}); showing all accounts"
                        )
                        e.cause?.let { android.util.Log.w("GSI", "  caused by ${it.javaClass.name}: ${it.message}", it) }
                        try {
                            credentialManager.getCredential(
                                context,
                                idTokenRequest(hashedNonce, filterByAuthorizedAccounts = false)
                            ).credential
                        } catch (retry: NoCredentialException) {
                            // 3. Still nothing: Credential Manager sees no Google
                            // account at all. Use the legacy Google Sign-In flow.
                            android.util.Log.e("GSI", "no Google accounts visible to Credential Manager", retry)
                            retry.cause?.let {
                                android.util.Log.e("GSI", "  caused by ${it.javaClass.name}: ${it.message}", it)
                            }
                            runCatching { legacyLauncher.launch(legacyClient.signInIntent) }
                                .onFailure { onError("Legacy sign-in could not start: ${it.message}") }
                            return@launch
                        }
                    }

                    if (credential is CustomCredential &&
                        credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                    ) {
                        val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                        onIdToken(idToken, rawNonce)
                    } else {
                        onError("Unexpected credential type returned.")
                    }
                } catch (e: GetCredentialException) {
                    // NoCredentialException is already handled by the silent
                    // attempt and its unfiltered retry above.
                    android.util.Log.e("GSI", "${e.javaClass.simpleName}: ${e.message}", e)
                    e.cause?.let { android.util.Log.e("GSI", "caused by ${it.javaClass.name}: ${it.message}", it) }
                    onError("${e.javaClass.simpleName}: ${e.message ?: "no message"}")
                } catch (e: Exception) {
                    android.util.Log.e("GSI", "unexpected ${e.javaClass.name}: ${e.message}", e)
                    onError("${e.javaClass.simpleName}: ${e.message ?: "Google sign-in failed."}")
                }
            }
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Sign in with Google")
    }
}

private fun idTokenRequest(
    hashedNonce: String,
    filterByAuthorizedAccounts: Boolean
): GetCredentialRequest {
    val option = GetGoogleIdOption.Builder()
        .setServerClientId(BuildConfig.GOOGLE_WEB_CLIENT_ID)
        .setNonce(hashedNonce)
        .setFilterByAuthorizedAccounts(filterByAuthorizedAccounts)
        // Auto-select defeats the point of the unfiltered picker.
        .setAutoSelectEnabled(false)
        .build()
    return GetCredentialRequest.Builder()
        .addCredentialOption(option)
        .build()
}

private fun generateNonce(): String {
    val random = ByteArray(16)
    SecureRandom().nextBytes(random)
    return android.util.Base64.encodeToString(
        random,
        android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE
    )
}

private fun sha256(input: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(input.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
