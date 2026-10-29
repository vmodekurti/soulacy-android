package dev.soulacy.android.websites

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI

/**
 * Which hosts a website sign-in may touch, mirroring the iOS
 * WebsiteAccessView boundary and the gateway's own validation: https only,
 * no user info, and a host inside one of the connection's allowed domains.
 * Local and private hosts are refused outright.
 */
class WebsiteBoundary(allowedDomains: List<String>) {
    val domains: List<String> = allowedDomains.mapNotNull(::normalize).distinct()

    fun allows(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.rawUserInfo != null) return false
        return allowsHost(uri.host ?: return false)
    }

    fun allowsHost(rawHost: String): Boolean {
        val host = normalize(rawHost) ?: return false
        return domains.any { host == it || host.endsWith(".$it") }
    }

    /** "https://host[:port]" for a URL inside the boundary, or null. */
    fun origin(url: String): String? {
        if (!allows(url)) return null
        val uri = URI(url)
        val port = if (uri.port > 0 && uri.port != 443) ":${uri.port}" else ""
        return "https://${uri.host.lowercase()}$port"
    }

    companion object {
        private val LABEL = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?$")

        fun normalize(raw: String): String? {
            val h = raw.trim().lowercase().removePrefix("*.").trim('.')
            if (h.isEmpty() || !h.contains('.')) return null // single-label names
            if (h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local")) return null
            if (isPrivateIpv4(h)) return null
            if (h.split('.').any { !LABEL.matches(it) }) return null
            return h
        }

        private fun isPrivateIpv4(h: String): Boolean {
            val p = h.split('.').map { it.toIntOrNull() ?: return false }
            if (p.size != 4) return false
            return p[0] == 10 || p[0] == 127 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168) ||
                (p[0] == 169 && p[1] == 254) || p[0] == 0
        }
    }
}

@Serializable
data class StoredCookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String = "/",
    val expires: Double = -1.0,
    val httpOnly: Boolean = false,
    val secure: Boolean = true,
    val sameSite: String = "Lax",
)

@Serializable
data class StorageItem(val name: String, val value: String)

@Serializable
data class OriginStorage(val origin: String, val localStorage: List<StorageItem>)

/** Playwright storage-state JSON, the shape PUT authenticated-connections/:id/session expects. */
@Serializable
data class StorageState(val cookies: List<StoredCookie>, val origins: List<OriginStorage>)

/**
 * Collects what a sign-in leaves behind and turns it into storage state.
 *
 * Android's CookieManager only reveals `name=value` for a URL, not the
 * cookie's domain, path, expiry or flags. So each cookie is recorded for the
 * exact host it was read from, path "/", as a secure session cookie. That
 * is enough for the gateway's browser to be signed in; a cookie the site set
 * for a parent domain is replayed on the host the person actually used.
 */
class SessionCollector(private val boundary: WebsiteBoundary) {
    private val cookies = linkedMapOf<Pair<String, String>, StoredCookie>()
    private val storage = linkedMapOf<String, List<StorageItem>>()

    /** [header] is CookieManager.getCookie(url): "a=1; b=2". */
    fun addCookies(url: String, header: String?) {
        if (header.isNullOrBlank() || !boundary.allows(url)) return
        val host = URI(url).host.lowercase()
        header.split(';').map { it.trim() }.filter { it.isNotEmpty() }.forEach { pair ->
            val i = pair.indexOf('=')
            val name = if (i < 0) pair else pair.substring(0, i)
            val value = if (i < 0) "" else pair.substring(i + 1)
            if (name.isNotBlank()) cookies[host to name] = StoredCookie(name = name, value = value, domain = host)
        }
    }

    /** Latest localStorage snapshot for the page's origin replaces the previous one. */
    fun setLocalStorage(url: String, items: List<StorageItem>) {
        val origin = boundary.origin(url) ?: return
        storage[origin] = items.sortedBy { it.name }
    }

    fun build(): StorageState = StorageState(
        cookies = cookies.values.sortedWith(compareBy({ it.domain }, { it.path }, { it.name })),
        origins = storage.filterValues { it.isNotEmpty() }.map { (o, items) -> OriginStorage(o, items) },
    )

    /** The JSON to send, or a reason it can't be sent (the same limits as iOS and the gateway). */
    fun encode(json: Json): Result<String> {
        val state = build()
        if (state.cookies.isEmpty() && state.origins.isEmpty()) return Result.failure(IllegalStateException("Nothing to save yet. Finish signing in first."))
        if (state.cookies.size > MAX_COOKIES || state.origins.size > MAX_ORIGINS) return Result.failure(IllegalStateException("This site stores too much to save (over $MAX_COOKIES cookies or $MAX_ORIGINS sites)."))
        val text = json.encodeToString(StorageState.serializer(), state)
        if (text.toByteArray().size + 20 > MAX_BYTES) return Result.failure(IllegalStateException("This site's sign-in is larger than 1 MB, so it can't be saved."))
        return Result.success(text)
    }

    companion object {
        const val MAX_COOKIES = 500
        const val MAX_ORIGINS = 50
        const val MAX_BYTES = 1_048_576
    }
}
