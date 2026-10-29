package dev.soulacy.android.api

import dev.soulacy.android.security.PinnedKeyTrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext

/** A gateway reply that was not a success, with the gateway's own message. */
class GatewayException(val status: Int, message: String) : IOException(message)

/**
 * Talks to one paired gateway. Every request carries the paired credential
 * as a bearer token; nothing else is ever contacted.
 */
class GatewayClient(
    baseUrl: String,
    private val token: String = "",
    fingerprint: String = "",
    http: OkHttpClient = defaultHttp(),
) {
    val base: HttpUrl = baseUrl.trimEnd('/').toHttpUrl()
    private val http: OkHttpClient = if (fingerprint.isNotEmpty() && base.isHttps) pinned(http, fingerprint) else http
    private val streamHttp: OkHttpClient = this.http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()

    suspend fun redeem(code: String): RedeemResponse {
        val body = GatewayJson.encodeToString(JsonObject.serializer(), buildJson("code" to code))
        val res = call(post("/api/v1/pairing/redeem", body, authenticated = false), allowError = true)
        val parsed = runCatching { GatewayJson.decodeFromString<RedeemResponse>(res.second) }.getOrNull()
        if (res.first !in 200..299 || parsed == null || !parsed.paired) {
            throw GatewayException(res.first, parsed?.reason?.ifBlank { null } ?: errorMessage(res.first, res.second))
        }
        return parsed
    }

    suspend fun agents(): AgentsResponse = get("/api/v1/agents?surface=chat")
    suspend fun approvals(): List<PendingApproval> = get<ApprovalsResponse>("/api/v1/approvals").approvals

    suspend fun decide(callId: String, approve: Boolean) {
        val verb = if (approve) "approve" else "deny"
        call(post("/api/v1/approvals/${enc(callId)}/$verb", "{}"))
    }

    suspend fun cancelRun(runId: String) {
        call(post("/api/v1/chat/cancel", encode(buildJson("run_id" to runId))), allowError = true)
    }

    suspend fun registerDevice(reg: DeviceRegistration) {
        call(post("/api/v1/mobile/devices", GatewayJson.encodeToString(DeviceRegistration.serializer(), reg)))
    }

    suspend fun deliveries(deviceId: String): DeliveriesResponse =
        get("/api/v1/mobile/deliveries?device_id=${enc(deviceId)}&limit=50")

    suspend fun markDeliveryRead(id: String, deviceId: String) {
        call(post("/api/v1/mobile/deliveries/${enc(id)}/read", encode(buildJson("device_id" to deviceId))), allowError = true)
    }

    suspend fun safeUndoJobs(agentId: String): List<SafeUndoJob> =
        get<SafeUndoJobsResponse>("/api/v1/agents/${enc(agentId)}/safe-undo/jobs").jobs

    suspend fun pairingStatus(): PairingStatus = get("/api/v1/pairing/status")

    suspend fun unpair() {
        call(post("/api/v1/pairing/unpair", "{}"))
    }

    /**
     * Sends [request] and streams the reply. The flow completes after the
     * gateway's `[DONE]`; cancelling the collector closes the connection.
     */
    fun streamChat(request: ChatRequest): Flow<ChatEvent> = flow {
        val body = GatewayJson.encodeToString(ChatRequest.serializer(), request)
        val req = post("/api/v1/chat/stream", body).newBuilder().header("Accept", "text/event-stream").build()
        val callObj = streamHttp.newCall(req)
        try {
            callObj.execute().use { res ->
                if (!res.isSuccessful) {
                    val text = res.body.string()
                    throw GatewayException(res.code, errorMessage(res.code, text))
                }
                val source = res.body.source()
                val parser = SseParser()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = source.readUtf8Line() ?: break
                    val ev = parser.feed(line) ?: continue
                    emit(ev)
                    if (ev is ChatEvent.Done) return@use
                }
                parser.finish()?.let { emit(it) }
            }
        } finally {
            callObj.cancel()
        }
    }.flowOn(Dispatchers.IO)

    // --- feature APIs (see Workspace) ---

    /** GET [path] and decode its JSON body. */
    suspend inline fun <reified T> fetch(path: String): T = GatewayJson.decodeFromString(send("GET", path).second)

    /** Any method, optional JSON body; returns status and body. Throws on non-2xx unless [allowError]. */
    suspend fun send(method: String, path: String, json: String? = null, allowError: Boolean = false): Pair<Int, String> {
        val body = json?.toRequestBody(JSON) ?: if (method == "GET" || method == "DELETE") null else "{}".toRequestBody(JSON)
        return call(request(path).method(method, body).build(), allowError)
    }

    // --- plumbing ---

    private suspend inline fun <reified T> get(path: String): T {
        val (_, text) = call(request(path).get().build())
        return GatewayJson.decodeFromString(text)
    }

    private fun request(path: String, authenticated: Boolean = true): Request.Builder {
        val b = Request.Builder().url(base.toString().trimEnd('/') + path).header("Accept", "application/json")
        if (authenticated && token.isNotEmpty()) b.header("Authorization", "Bearer $token")
        return b
    }

    private fun post(path: String, json: String, authenticated: Boolean = true): Request =
        request(path, authenticated).post(json.toRequestBody(JSON)).build()

    private suspend fun call(req: Request, allowError: Boolean = false): Pair<Int, String> = withContext(Dispatchers.IO) {
        http.newCall(req).execute().use { res: Response ->
            val text = res.body.string()
            if (!res.isSuccessful && !allowError) throw GatewayException(res.code, errorMessage(res.code, text))
            res.code to text
        }
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false) // a credential must never follow a redirect elsewhere
            .followSslRedirects(false)
            .build()

        private fun pinned(http: OkHttpClient, fingerprint: String): OkHttpClient {
            val tm = PinnedKeyTrustManager(fingerprint)
            val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), SecureRandom()) }
            // The pinned key is the identity check; the gateway's self-signed
            // certificate often names an IP or tailnet host, so the hostname
            // check is replaced by the key match rather than layered on it.
            return http.newBuilder().sslSocketFactory(ctx.socketFactory, tm).hostnameVerifier { _, _ -> true }.build()
        }

        internal fun errorMessage(status: Int, body: String): String {
            val obj = runCatching { GatewayJson.parseToJsonElement(body).jsonObject }.getOrNull()
            val msg = obj?.let { o ->
                listOf("error", "reason", "message").firstNotNullOfOrNull { k -> o[k]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() } }
            }
            return msg?.takeIf { it.isNotBlank() } ?: when (status) {
                401 -> "The gateway did not accept this phone's credential."
                403 -> "This phone is not allowed to do that."
                404 -> "Not found on the gateway."
                else -> "Gateway error $status"
            }
        }

        private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        private fun buildJson(vararg pairs: Pair<String, String>) =
            JsonObject(pairs.associate { (k, v) -> k to kotlinx.serialization.json.JsonPrimitive(v) })

        private fun encode(o: JsonObject) = GatewayJson.encodeToString(JsonObject.serializer(), o)
    }
}
