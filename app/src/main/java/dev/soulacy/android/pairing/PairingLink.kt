package dev.soulacy.android.pairing

import java.net.URI
import java.net.URLDecoder

/**
 * What a gateway's pairing QR code carries: `<base>/mobile?pair=<CODE>[&fp=<hex>]`.
 * [baseUrl] has no trailing slash; [fingerprint] is empty when the phone
 * should rely on normal certificate trust.
 */
data class PairingLink(val baseUrl: String, val code: String, val fingerprint: String = "") {
    companion object {
        private val CODE = Regex("^[A-Z2-7]{8,64}$")
        private val FINGERPRINT = Regex("^[0-9a-f]{64}$")

        /** Parses scanned or pasted text, or returns null with no side effects. */
        fun parse(raw: String): PairingLink? {
            val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase() ?: return null
            if (scheme != "http" && scheme != "https") return null
            val host = uri.host ?: return null
            if (uri.rawUserInfo != null) return null
            val path = uri.rawPath.orEmpty().trimEnd('/')
            if (!path.endsWith("/mobile")) return null
            val query = parseQuery(uri.rawQuery.orEmpty())
            val code = query["pair"]?.trim()?.uppercase() ?: return null
            if (!CODE.matches(code)) return null
            val fp = query["fp"]?.trim()?.lowercase().orEmpty()
            if (fp.isNotEmpty() && !FINGERPRINT.matches(fp)) return null
            if (fp.isNotEmpty() && scheme != "https") return null
            val port = if (uri.port > 0) ":${uri.port}" else ""
            val hostPart = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
            val prefix = path.removeSuffix("/mobile")
            return PairingLink("$scheme://$hostPart$port$prefix", code, fp)
        }

        /** A code typed by hand plus the gateway address it came from. */
        fun manual(baseUrl: String, code: String): PairingLink? =
            parse(baseUrl.trim().trimEnd('/') + "/mobile?pair=" + code.trim())

        private fun parseQuery(raw: String): Map<String, String> =
            raw.split('&').filter { it.isNotEmpty() }.associate { part ->
                val i = part.indexOf('=')
                val k = if (i < 0) part else part.substring(0, i)
                val v = if (i < 0) "" else part.substring(i + 1)
                URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
            }
    }
}
