package com.eddyizm.tempus.homemusic

/** Answer of the Google account picker (Credential Manager, homemusic flavor: `Flavors.googleSignIn`). */
interface GoogleIdCallback {
    /** [email] — the chosen account, to suggest a user name. */
    fun onToken(idToken: String, email: String?)

    /** [message] is shown to the user; null — the picker was closed. */
    fun onError(message: String?)
}
