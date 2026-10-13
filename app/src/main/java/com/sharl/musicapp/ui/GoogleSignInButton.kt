package com.sharl.musicapp.ui

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
    onIdToken: (idToken: String, rawNonce: String) -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember { CredentialManager.create(context) }

    Button(
        onClick = {
            scope.launch {
                try {
                    val rawNonce = generateNonce()
                    val hashedNonce = sha256(rawNonce)

                    val option = GetGoogleIdOption.Builder()
                        .setServerClientId(BuildConfig.GOOGLE_WEB_CLIENT_ID)
                        .setNonce(hashedNonce)
                        .setFilterByAuthorizedAccounts(false)
                        .setAutoSelectEnabled(false)
                        .build()

                    val request = GetCredentialRequest.Builder()
                        .addCredentialOption(option)
                        .build()

                    val result = credentialManager.getCredential(context, request)
                    val credential = result.credential
                    if (credential is CustomCredential &&
                        credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                    ) {
                        val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                        onIdToken(idToken, rawNonce)
                    } else {
                        onError("Unexpected credential type returned.")
                    }
                } catch (e: GetCredentialException) {
                    onError(e.message ?: "Google sign-in failed.")
                } catch (e: Exception) {
                    onError(e.message ?: "Google sign-in failed.")
                }
            }
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Sign in with Google")
    }
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
