package dev.soulacy.android.ui.workspace

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.soulacy.android.api.GatewayJson
import dev.soulacy.android.api.WebsiteConnection
import dev.soulacy.android.api.Workspace
import dev.soulacy.android.ui.components.Pill
import dev.soulacy.android.ui.components.SoulCard
import dev.soulacy.android.ui.theme.Soulacy
import dev.soulacy.android.websites.SessionCollector
import dev.soulacy.android.websites.StorageItem
import dev.soulacy.android.websites.WebsiteBoundary
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import java.time.Instant

/** Health label, as iOS computes it from kind, status, has_secret and expires_at. */
fun connectionHealth(c: WebsiteConnection, now: Instant = Instant.now()): Pair<String, Boolean> = when {
    c.kind != "browser_session" -> "Set up on desktop" to false
    c.status == "revoked" -> "Revoked" to false
    c.status == "expired" || c.expiresAt?.let { runCatching { Instant.parse(it) }.getOrNull()?.isBefore(now) } == true -> "Expired" to false
    c.status == "ready" && c.hasSecret -> "Ready" to true
    else -> "Sign in" to false
}

/** Website Access: the sites agents may use with your sign-in (iOS Settings › Website Access). */
@Composable
fun WebsiteAccessPage(ws: Workspace, address: String, onBack: () -> Unit, onSignIn: (WebsiteConnection) -> Unit) {
    val list = rememberLoadable(address) { ws.connections() }
    SubPage("Website access", onBack, onRefresh = list::refresh) { pad ->
        LoadableList(list, pad, header = {
            item {
                Text(
                    "Sites your agents can use while signed in as you. Sign in here and the gateway keeps the session, encrypted. Agents never see your password.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
                )
            }
        }) { conns ->
            if (conns.isEmpty()) item { Notice("No website connections yet. An agent asks for one when it needs a site, or add one in the web workspace.") }
            items(conns, key = { it.id }) { c ->
                val (label, ok) = connectionHealth(c)
                val canSignIn = c.kind == "browser_session" && c.status != "revoked" && !c.baseUrl.isNullOrBlank()
                SoulCard(if (canSignIn) Modifier.clickable { onSignIn(c) } else Modifier) {
                    Row {
                        Column(Modifier.weight(1f)) {
                            Text(c.name.ifBlank { c.id }, fontWeight = FontWeight.SemiBold)
                            Text(c.allowedDomains.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (c.agentIds.isNotEmpty()) Text("Used by " + c.agentIds.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Pill(label, if (ok) Soulacy.colors.success else Soulacy.colors.mango)
                    }
                    if (canSignIn) Text(if (ok) "Tap to sign in again" else "Tap to sign in", color = Soulacy.colors.brand, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}

/**
 * Sign in to a site inside the connection's allowed domains, then hand the
 * session to the gateway. Navigation outside the boundary is blocked, the
 * screen can't be captured, and the browser's data is wiped afterwards.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebsiteSignInPage(ws: Workspace, address: String, connection: WebsiteConnection, onBack: () -> Unit, onSaved: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val boundary = remember(connection.id) { WebsiteBoundary(connection.allowedDomains) }
    val collector = remember(connection.id) { SessionCollector(boundary) }
    val start = connection.baseUrl.orEmpty()
    var blocked by remember { mutableStateOf<String?>(if (!boundary.allows(start)) "This connection's start page isn't inside its allowed domains, so it can't be opened here." else null) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var web by remember { mutableStateOf<WebView?>(null) }
    val activity = LocalActivity.current

    fun wipe() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
    }

    fun capture(view: WebView, url: String, then: () -> Unit = {}) {
        collector.addCookies(url, CookieManager.getInstance().getCookie(url))
        if (!boundary.allows(url)) { then(); return }
        view.evaluateJavascript(
            "(function(){try{return JSON.stringify(Object.keys(window.localStorage).map(function(k){return {name:k,value:window.localStorage.getItem(k)};}));}catch(e){return '[]';}})()",
        ) { raw ->
            runCatching {
                val json = GatewayJson.decodeFromString<String>(raw ?: "\"[]\"")
                collector.setLocalStorage(url, GatewayJson.decodeFromString(ListSerializer(StorageItem.serializer()), json))
            }
            then()
        }
    }

    // A fresh, private browser for every sign-in, and no screenshots while it is open.
    DisposableEffect(connection.id) {
        wipe()
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            web?.destroy()
            wipe()
        }
    }

    SubPage(connection.name.ifBlank { "Sign in" }, onBack, snack) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                Pill(boundary.domains.joinToString(", "), Soulacy.colors.brand, Icons.Default.Lock)
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = Soulacy.colors.brand)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val reason = blocked
                if (reason != null) {
                    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                        Text("Stopped", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(reason, modifier = Modifier.padding(top = 8.dp))
                    }
                } else AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.javaScriptCanOpenWindowsAutomatically = false
                        settings.setSupportMultipleWindows(false)
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val url = request.url.toString()
                                if (!request.isForMainFrame) return !(boundary.allows(url) || url.startsWith("about:") || url.startsWith("data:") || url.startsWith("blob:"))
                                if (boundary.allows(url)) return false
                                blocked = "The sign-in tried to leave ${boundary.domains.joinToString(", ")} for ${request.url.host ?: "another site"}, so it was stopped. Nothing was saved."
                                return true
                            }
                            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) { loading = true }
                            override fun onPageFinished(view: WebView, url: String) { loading = false; capture(view, url) }
                            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                                blocked = "The browser stopped. Go back and try again."
                                return true
                            }
                        }
                        web = this
                        loadUrl(start)
                    }
                })
            }
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Sign in as you normally would, including any code sent to you. Then save.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(
                    onClick = {
                        val view = web ?: return@Button
                        saving = true
                        capture(view, view.url.orEmpty()) {
                            scope.launch {
                                collector.encode(GatewayJson).fold(
                                    onSuccess = { json ->
                                        try {
                                            ws.saveSession(connection.id, json)
                                            view.stopLoading(); wipe()
                                            onSaved()
                                        } catch (e: Exception) {
                                            snack.showSnackbar(Loadable.describe(e, address))
                                        }
                                    },
                                    onFailure = { snack.showSnackbar(it.message ?: "Nothing to save yet.") },
                                )
                                saving = false
                            }
                        }
                    },
                    enabled = blocked == null && !saving,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Soulacy.colors.brand),
                ) { Text(if (saving) "Saving…" else "Save secure access") }
            }
        }
    }
}
