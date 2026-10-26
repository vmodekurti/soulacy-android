package dev.soulacy.android.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.soulacy.android.pairing.PairingLink

@Composable
fun PairScreen(busy: Boolean, error: String?, onPair: (PairingLink) -> Unit) {
    val context = LocalContext.current
    var scanning by remember { mutableStateOf(false) }
    var pasted by remember { mutableStateOf("") }
    var candidate by remember { mutableStateOf<PairingLink?>(null) }
    var localError by remember { mutableStateOf<String?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scanning = true else localError = "Camera access is needed to scan. You can paste the pairing link instead."
    }

    fun accept(text: String) {
        scanning = false
        val link = PairingLink.parse(text)
        if (link == null) localError = "That is not a Soulacy pairing code." else { localError = null; candidate = link }
    }

    if (scanning) {
        Column(Modifier.fillMaxSize()) {
            QrScanner(Modifier.weight(1f).fillMaxWidth()) { accept(it) }
            TextButton(onClick = { scanning = false }, modifier = Modifier.padding(16.dp)) { Text("Cancel") }
        }
        return
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(32.dp))
        Text("Connect to your gateway", style = MaterialTheme.typography.headlineMedium)
        Text(
            "In the Soulacy web workspace open Mobile, choose Pair a phone and Get code. " +
                "Codes are single use and expire after two minutes.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(
            onClick = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) scanning = true
                else camera.launch(Manifest.permission.CAMERA)
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.QrCodeScanner, contentDescription = null)
            Text("  Scan pairing code")
        }
        Text("Or paste the pairing link", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = pasted,
            onValueChange = { pasted = it },
            label = { Text("https://gateway/mobile?pair=...") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("pairLink"),
        )
        OutlinedButton(onClick = { accept(pasted) }, enabled = !busy && pasted.isNotBlank(), modifier = Modifier.fillMaxWidth().testTag("pairContinue")) {
            Text("Continue")
        }
        if (busy) CircularProgressIndicator()
        (localError ?: error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }

    candidate?.let { link ->
        val insecure = link.baseUrl.startsWith("http://")
        AlertDialog(
            onDismissRequest = { candidate = null },
            title = { Text("Connect to this gateway?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(link.baseUrl, style = MaterialTheme.typography.titleMedium)
                    if (link.fingerprint.isNotEmpty()) Text("Its key is pinned: ${link.fingerprint.take(16)}...")
                    if (insecure) Text(
                        "This address is not encrypted. Use it only on a network you trust, such as your home network or a VPN.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { candidate = null; onPair(link) }) { Text("Connect") } },
            dismissButton = { TextButton(onClick = { candidate = null }) { Text("Cancel") } },
        )
    }
}
