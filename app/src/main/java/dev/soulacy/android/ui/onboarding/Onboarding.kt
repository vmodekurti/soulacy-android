package dev.soulacy.android.ui.onboarding

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.soulacy.android.data.Profile
import dev.soulacy.android.pairing.PairingLink
import dev.soulacy.android.pairing.Reachability
import dev.soulacy.android.ui.QrScanner
import dev.soulacy.android.ui.components.BrandMark
import dev.soulacy.android.ui.components.Pill
import dev.soulacy.android.ui.theme.Soulacy

private enum class Step { WELCOME, CONNECT, SCAN, CONFIRM, VERIFY, REACH, NOTIFY, DONE }

/**
 * First run, step for step like the iOS OnboardingFlow. Notification
 * permission is asked with its reason on screen, after the first successful
 * connection, never at launch.
 */
@Composable
fun Onboarding(
    profile: Profile?,
    pairing: Boolean,
    pairError: String?,
    agentCount: Int,
    canNotify: Boolean,
    onPair: (PairingLink) -> Unit,
    onClearError: () -> Unit,
    onReachability: (Reachability) -> Unit,
    onRequestNotifications: () -> Unit,
    onFinish: () -> Unit,
) {
    var step by rememberSaveable { mutableStateOf(if (profile != null) Step.REACH else Step.WELCOME) }
    var candidate by rememberSaveable { mutableStateOf<String?>(null) }
    val link = candidate?.let(PairingLink::parse)

    // Pairing finished: move on. A failure stays on Verify with its message.
    LaunchedEffect(profile, pairing) {
        if (step == Step.VERIFY && profile != null && !pairing) step = Step.REACH
    }
    val back: (() -> Unit)? = when (step) {
        Step.CONNECT -> { { step = Step.WELCOME } }
        Step.SCAN, Step.CONFIRM -> { { step = Step.CONNECT } }
        Step.VERIFY -> if (!pairing) { { onClearError(); step = Step.CONNECT } } else null
        else -> null
    }
    if (back != null) BackHandler(onBack = back)

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        if (back != null && step != Step.SCAN) {
            IconButton(onClick = back, modifier = Modifier.padding(4.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        } else Spacer(Modifier.height(56.dp))
        when (step) {
            Step.WELCOME -> Welcome { step = Step.CONNECT }
            Step.CONNECT -> Connect(
                onScan = { step = Step.SCAN },
                onLink = { text -> candidate = text; step = Step.CONFIRM },
            )
            Step.SCAN -> Scan(onCode = { candidate = it; step = Step.CONFIRM }, onCancel = { step = Step.CONNECT })
            Step.CONFIRM -> if (link == null) {
                Problem("That isn't a Soulacy pairing code", "A pairing link looks like https://your-gateway/mobile?pair=ABCD... Get one from the Mobile page.") { step = Step.CONNECT }
            } else Confirm(link) { onPair(link); step = Step.VERIFY }
            Step.VERIFY -> if (pairError != null) {
                Problem("Couldn't connect", pairError) { onClearError(); step = Step.CONNECT }
            } else Verifying()
            Step.REACH -> Reach { onReachability(it); step = if (canNotify) Step.DONE else Step.NOTIFY }
            Step.NOTIFY -> Notify(onAllow = { onRequestNotifications(); step = Step.DONE }, onSkip = { step = Step.DONE })
            Step.DONE -> Done(profile, agentCount, onFinish)
        }
    }
}

@Composable
private fun Page(content: @Composable () -> Unit, actions: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) { content() }
        Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) { actions() }
    }
}

@Composable
private fun PrimaryButton(text: String, icon: ImageVector? = null, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Soulacy.colors
    Button(
        onClick = onClick, enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, disabledContainerColor = Color.Transparent),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
    ) {
        Box(
            Modifier.fillMaxSize().then(if (enabled) Modifier else Modifier.alpha(0.35f)).background(Brush.horizontalGradient(listOf(c.brandDeep, c.brand))),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) { Icon(icon, null, tint = Color.White); Spacer(Modifier.width(10.dp)) }
                Text(text, color = Color.White, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun Title(text: String, sub: String? = null) {
    Text(text, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    if (sub != null) Text(sub, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Welcome(onNext: () -> Unit) {
    val uri = LocalUriHandler.current
    Page(
        content = {
            Spacer(Modifier.height(64.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { BrandMark(96) }
            Text("Your agents,\nwithin reach.", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Text(
                "Connect securely to the Soulacy gateway you control, wherever it runs.",
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                Pill("Private", Soulacy.colors.brand, Icons.Default.Lock)
                Pill("Real-time", Soulacy.colors.brandSecondary, Icons.Default.Bolt)
                Pill("No tracking", Soulacy.colors.mango)
            }
        },
        actions = {
            PrimaryButton("Connect a gateway", Icons.AutoMirrored.Filled.ArrowForward, onClick = onNext)
            TextButton(onClick = { uri.openUri("https://docs.soulacy.io/") }) { Text("Learn about self-hosting", color = Soulacy.colors.brand) }
        },
    )
}

@Composable
private fun Connect(onScan: () -> Unit, onLink: (String) -> Unit) {
    var pasted by rememberSaveable { mutableStateOf("") }
    Page(
        content = {
            Title("Choose how to connect", "On your computer, open Soulacy and choose Mobile, then Pair a phone.")
            Choice(Icons.Default.QrCodeScanner, "Scan pairing code", "Point the camera at the code. It works once and lasts two minutes.", Soulacy.colors.brand, onScan)
            Text("Or paste the pairing link", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            OutlinedTextField(
                value = pasted, onValueChange = { pasted = it },
                placeholder = { Text("https://your-gateway/mobile?pair=...") },
                leadingIcon = { Icon(Icons.Default.ContentPaste, null) },
                singleLine = true, shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        actions = { PrimaryButton("Continue", enabled = pasted.isNotBlank()) { onLink(pasted.trim()) } },
    )
}

@Composable
private fun Choice(icon: ImageVector, title: String, body: String, tint: Color, onClick: () -> Unit) {
    val c = Soulacy.colors
    Surface(
        onClick = onClick, shape = RoundedCornerShape(20.dp),
        color = if (c.dark) c.raised.copy(alpha = 0.72f) else Color.White,
        border = BorderStroke(1.dp, tint.copy(alpha = 0.45f)), modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(tint.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = tint) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Scan(onCode: (String) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    var granted by rememberSaveable { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var refused by rememberSaveable { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> granted = ok; refused = !ok }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }
    BackHandler(onBack = onCancel)
    if (!granted) {
        Problem(
            if (refused) "Camera access is off" else "Camera access",
            "Soulacy uses the camera only to read the pairing code, on this phone. You can paste the pairing link instead.",
            onBack = onCancel,
        )
        return
    }
    Box(Modifier.fillMaxSize()) {
        QrScanner(Modifier.fillMaxSize()) { onCode(it) }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Point the camera at the pairing code", color = Color.White, style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onCancel) { Text("Cancel", color = Color.White) }
        }
    }
}

@Composable
private fun Confirm(link: PairingLink, onConnect: () -> Unit) {
    val insecure = link.baseUrl.startsWith("http://")
    Page(
        content = {
            Title("Is this your gateway?", "Check the address before connecting. The phone will only ever talk to this address.")
            dev.soulacy.android.ui.components.SoulCard(accent = if (insecure) Soulacy.colors.mango else Soulacy.colors.brand) {
                Text(link.baseUrl, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                if (link.fingerprint.isNotEmpty()) Text("Its key is pinned (${link.fingerprint.take(12)}...), so a different server can't pretend to be it.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (insecure) Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.Warning, null, tint = Soulacy.colors.mango, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("This address isn't encrypted. Use it only on a network you trust, such as home Wi-Fi or a VPN.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        actions = { PrimaryButton("Connect", Icons.AutoMirrored.Filled.ArrowForward, onClick = onConnect) },
    )
}

@Composable
private fun Verifying() {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator(color = Soulacy.colors.brand)
        Spacer(Modifier.height(20.dp))
        Text("Verifying your gateway", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text("Checking identity, access, and compatibility…", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Problem(title: String, body: String, onBack: () -> Unit) {
    Page(
        content = {
            Spacer(Modifier.height(32.dp))
            Icon(Icons.Default.Warning, null, tint = Soulacy.colors.coral, modifier = Modifier.size(44.dp))
            Title(title)
            Text(body, style = MaterialTheme.typography.bodyLarge)
        },
        actions = { OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(18.dp)) { Text("Try again") } },
    )
}

@Composable
private fun Reach(onPick: (Reachability) -> Unit) {
    var picked by rememberSaveable { mutableStateOf<Reachability?>(null) }
    Page(
        content = {
            Title("Where can you reach this gateway from?", "This helps Soulacy explain connection problems clearly when your network changes.")
            Reachability.values().forEach { r ->
                val icon = when (r) { Reachability.HOME -> Icons.Default.Home; Reachability.VPN -> Icons.Default.VpnLock; Reachability.ANYWHERE -> Icons.Default.Public }
                val selected = picked == r
                Surface(
                    onClick = { picked = r }, shape = RoundedCornerShape(18.dp),
                    color = if (selected) Soulacy.colors.brand.copy(alpha = 0.14f) else if (Soulacy.colors.dark) Soulacy.colors.raised.copy(alpha = 0.72f) else Color.White,
                    border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) Soulacy.colors.brand else MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(icon, null, tint = Soulacy.colors.brand)
                        Spacer(Modifier.width(14.dp))
                        Text(r.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        if (selected) Icon(Icons.Default.CheckCircle, null, tint = Soulacy.colors.brand)
                    }
                }
            }
            picked?.let { Text(it.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        },
        actions = { PrimaryButton("Continue", enabled = picked != null) { picked?.let(onPick) } },
    )
}

@Composable
private fun Notify(onAllow: () -> Unit, onSkip: () -> Unit) {
    Page(
        content = {
            Spacer(Modifier.height(32.dp))
            Icon(Icons.Default.NotificationsActive, null, tint = Soulacy.colors.brand, modifier = Modifier.size(48.dp))
            Title("Let approvals reach you", "Enable notifications so approvals and finished work can reach you when the app is closed.")
            Text("Notifications only say which agent wants what. The details stay on your gateway until you open the app.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        actions = {
            PrimaryButton("Turn on notifications", onClick = onAllow)
            TextButton(onClick = onSkip) { Text("Not now") }
        },
    )
}

@Composable
private fun Done(profile: Profile?, agents: Int, onFinish: () -> Unit) {
    Page(
        content = {
            Spacer(Modifier.height(48.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Icon(Icons.Default.CheckCircle, null, tint = Soulacy.colors.success, modifier = Modifier.size(72.dp)) }
            Text("You're connected", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Text("Your inbox, agents and Genie are ready in one private workspace.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            if (profile != null) dev.soulacy.android.ui.components.SoulCard {
                Text(profile.baseUrl, fontWeight = FontWeight.SemiBold)
                Text("Signed in as ${profile.displayName.ifBlank { profile.subject.ifBlank { "you" } }}" + if (agents > 0) " · $agents agents" else "", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        actions = { PrimaryButton("Open Soulacy", onClick = onFinish) },
    )
}
