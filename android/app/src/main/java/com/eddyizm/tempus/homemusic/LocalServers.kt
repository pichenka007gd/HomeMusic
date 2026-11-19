package com.eddyizm.tempus.homemusic

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class HubLoginTarget(val music: String, val base: String, val username: String, val password: String) {
    override fun toString(): String = "HubLoginTarget(credentials withheld)"
}

/** Endpoints explicitly selected on the first screen, with the default Compose LAN ports. */
object LocalServers {
    fun loginTarget(base: String, boundMusic: String?, activeMusic: String?, hub: String?, local: String?,
                    user: String?, password: String?): HubLoginTarget? {
        if (!same(boundMusic, activeMusic) || !same(base, hub) && !same(base, local) || user.isNullOrEmpty() || password.isNullOrEmpty()) return null
        return HubLoginTarget(activeMusic!!, base, user, password)
    }

    fun music(input: String): String? = endpoint(input, 4533)

    fun hub(input: String): String? = endpoint(input, 8080)

    fun suggestedHub(music: String): String? = music.toHttpUrlOrNull()?.newBuilder()
        ?.port(8080)?.encodedPath("/")?.query(null)?.fragment(null)?.build()?.toString()?.trimEnd('/')

    fun same(a: String?, b: String?): Boolean {
        val first = a?.toHttpUrlOrNull() ?: return false
        val second = b?.toHttpUrlOrNull() ?: return false
        return first == second
    }

    private fun endpoint(input: String, port: Int): String? {
        val raw = input.trim()
        if (raw.isEmpty() || raw.any { it.isWhitespace() || it.code > 127 }) return null
        val bare = "://" !in raw
        val url = (if (bare) "http://$raw" else raw).toHttpUrlOrNull() ?: return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) return null
        val authority = raw.substringBefore('/')
        val explicitPort = if (authority.startsWith('[')) authority.substringAfter(']', "").startsWith(':')
        else ':' in authority
        val selected = if (bare && !explicitPort) url.newBuilder().port(port).build() else url
        return selected.toString().trimEnd('/')
    }
}
