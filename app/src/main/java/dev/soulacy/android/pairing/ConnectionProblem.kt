package dev.soulacy.android.pairing

import dev.soulacy.android.api.GatewayException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/** Where the person said the gateway can be reached from (onboarding asks, like iOS). */
enum class Reachability(val label: String, val detail: String) {
    HOME("Only on my home or office network", "When this phone leaves that network, Soulacy can't reach the gateway. Approvals and results wait until you're back."),
    VPN("Through a VPN or tunnel", "Keep the VPN or tunnel app connected on this phone, or Soulacy can't reach the gateway."),
    ANYWHERE("From anywhere", "The gateway has a public address, so this phone reaches it on any network."),
}

/**
 * Names a connection failure precisely, the way iOS onboarding does: DNS,
 * timeout, refused, TLS, an expired code, or an occupied phone slot. "It
 * worked at home and not at work" is what makes people give up on
 * self-hosting, so the message says what to check.
 */
object ConnectionProblem {
    fun describe(e: Throwable, address: String, reachability: Reachability? = null): String {
        val host = runCatching { java.net.URI(address).host }.getOrNull() ?: address
        val base = when (val root = rootCause(e)) {
            is GatewayException -> when (root.status) {
                401 -> if (root.message.orEmpty().contains("pairing", true) || root.message.orEmpty().contains("code", true))
                    "That pairing code is invalid or has expired. Codes work once and last two minutes: get a new one on the Mobile page."
                else "The gateway no longer accepts this phone's credential. Pair it again from the Mobile page."
                403 -> "This phone isn't allowed to do that. Ask the gateway's owner for access."
                409 -> root.message ?: "Another phone is already paired for you. Unpair it first on the Mobile page."
                in 500..599 -> "The gateway hit an error (${root.status}). Check its logs, then try again."
                else -> root.message ?: "The gateway answered with an error (${root.status})."
            }
            is UnknownHostException -> "Can't find $host. Check the address, and your VPN if the gateway is on a private network."
            is SocketTimeoutException -> "$host didn't answer in time. The gateway may be offline, or this phone is on a different network."
            is ConnectException, is NoRouteToHostException -> "Nothing answered at $address. Is Soulacy running there, and is the port right?"
            is SSLPeerUnverifiedException, is CertificateException ->
                if (root.message.orEmpty().contains("fingerprint")) "The gateway's key doesn't match the one this phone paired with. If you replaced its certificate, pair again."
                else "This phone doesn't trust the gateway's certificate. Use the address from the pairing code, which carries its key."
            is SSLHandshakeException, is SSLException -> "A secure connection to $host failed: ${root.message ?: "handshake error"}."
            else -> root.message ?: "Couldn't reach the gateway."
        }
        val hint = when (reachability) {
            Reachability.HOME -> if (isNetworkProblem(e)) " You said this gateway is only reachable on your home or office network: is this phone on it?" else ""
            Reachability.VPN -> if (isNetworkProblem(e)) " You said this gateway is reached through a VPN or tunnel: is it connected?" else ""
            else -> ""
        }
        return base + hint
    }

    /**
     * An error the agent run reported, in words first and the gateway's
     * detail after it. Engine errors are written for operators
     * ("engine: llm call: ollama: stream unexpected status 400 ...").
     */
    fun agentError(raw: String): String {
        val detail = raw.trim().removePrefix("engine:").trim()
        val lead = when {
            detail.contains("llm call", true) || detail.contains("model", true) ->
                "The agent couldn't get an answer from its model. Check the model settings on the gateway."
            detail.contains("budget", true) || detail.contains("limit", true) ->
                "The agent stopped at one of its limits."
            detail.contains("denied", true) || detail.contains("not allowed", true) ->
                "The gateway didn't allow that."
            else -> return detail.ifBlank { "The agent stopped with an error." }
        }
        return "$lead\n\n> $detail"
    }

    fun isNetworkProblem(e: Throwable): Boolean = when (rootCause(e)) {
        is UnknownHostException, is SocketTimeoutException, is ConnectException, is NoRouteToHostException -> true
        else -> false
    }

    private fun rootCause(e: Throwable): Throwable {
        var t = e
        while (t.cause != null && t.cause !== t && t !is GatewayException) t = t.cause!!
        return t
    }
}
