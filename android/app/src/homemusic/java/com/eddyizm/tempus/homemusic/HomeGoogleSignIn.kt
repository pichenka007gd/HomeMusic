package com.eddyizm.tempus.homemusic

import android.app.Activity
import android.os.CancellationSignal
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialManagerCallback
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import com.eddyizm.tempus.R
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException

/**
 * «Sign in with Google» button flow: Google's account picker gives an ID token for the Hub's Web client id
 * ([clientId], from `GET /api/auth/options`); the Hub checks it. The app itself holds no Google secret.
 */
object HomeGoogleSignIn {
    private const val TAG = "HomeGoogleSignIn"

    @JvmStatic
    fun request(activity: Activity, clientId: String, callback: GoogleIdCallback) {
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(clientId).build())
            .build()
        CredentialManager.create(activity).getCredentialAsync(
            activity, request, CancellationSignal(), ContextCompat.getMainExecutor(activity),
            object : CredentialManagerCallback<GetCredentialResponse, GetCredentialException> {
                override fun onResult(result: GetCredentialResponse) {
                    val credential = result.credential
                    if (credential !is CustomCredential ||
                        credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                        callback.onError(activity.getString(R.string.homemusic_google_failed, credential.type))
                        return
                    }
                    try {
                        val google = GoogleIdTokenCredential.createFrom(credential.data)
                        callback.onToken(google.idToken, google.id)
                    } catch (e: GoogleIdTokenParsingException) {
                        Log.w(TAG, "Bad Google credential", e)
                        callback.onError(activity.getString(R.string.homemusic_google_failed, e.message ?: "?"))
                    }
                }

                override fun onError(e: GetCredentialException) {
                    Log.w(TAG, "Google sign-in failed: ${e.type}", e)
                    callback.onError(when (e) {
                        is GetCredentialCancellationException -> null
                        is NoCredentialException -> activity.getString(R.string.homemusic_google_no_account)
                        is GetCredentialProviderConfigurationException, is GetCredentialUnsupportedException ->
                            activity.getString(R.string.homemusic_google_unavailable)
                        // e.g. «Developer console is not set up correctly»: the Android client is missing in Google Cloud
                        else -> activity.getString(R.string.homemusic_google_failed, e.errorMessage ?: e.type)
                    })
                }
            },
        )
    }
}
