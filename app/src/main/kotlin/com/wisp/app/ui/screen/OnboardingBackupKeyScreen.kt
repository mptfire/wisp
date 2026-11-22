package com.wisp.app.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.wisp.app.auth.NostrCredentialSaver
import com.wisp.app.nostr.Nip49
import com.wisp.app.nostr.Nip19
import com.wisp.app.viewmodel.AuthViewModel
import kotlinx.coroutines.launch

/**
 * Mandatory post-signup key backup step (P1): the new nsec is shown with
 * copy / encrypted-backup / password-manager actions and an explicit
 * "I have safely saved my key" confirmation before onboarding continues.
 */
@Composable
fun OnboardingBackupKeyScreen(
    authViewModel: AuthViewModel,
    onContinue: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val npub by authViewModel.npub.collectAsState()
    val nsec = remember { authViewModel.getCurrentNsec() }
    var revealed by remember { mutableStateOf(false) }
    var confirmedSaved by remember { mutableStateOf(false) }
    var showEncryptDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp)
    ) {
        Text(
            text = "Back up your secret key",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.W600),
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "This secret key is both your Nostr account and your built-in Lightning wallet. " +
                "If you lose it, nobody can recover your account — not even Wisp Fork. Save it somewhere safe now.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = nsec ?: "",
            onValueChange = { },
            label = { Text("Your secret key") },
            readOnly = true,
            singleLine = false,
            maxLines = 4,
            visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                OutlinedButton(onClick = { revealed = !revealed }) {
                    Text(if (revealed) "Hide" else "Reveal")
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = {
                if (nsec != null) {
                    val clip = ClipData.newPlainText("", nsec)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        clip.description.extras = PersistableBundle().apply {
                            putBoolean("android.content.extra.IS_SENSITIVE", true)
                        }
                    }
                    val cm = context.getSystemService(ClipboardManager::class.java)
                    cm.setPrimaryClip(clip)
                    Toast.makeText(context, "Secret key copied", Toast.LENGTH_SHORT).show()
                }
            }) { Text("Copy secret key") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { showEncryptDialog = true }) { Text("Encrypted backup") }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = {
            val activity = context as? android.app.Activity
            if (activity != null && nsec != null && npub != null) {
                scope.launch {
                    NostrCredentialSaver.saveNsec(activity, npub!!, nsec)
                    Toast.makeText(context, "Saved to password manager (if you confirmed there)", Toast.LENGTH_SHORT).show()
                }
            }
        }) { Text("Save to password manager") }

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = confirmedSaved, onCheckedChange = { confirmedSaved = it })
            Text("I have safely saved my key", style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onContinue,
            enabled = confirmedSaved,
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) { Text("Continue") }
    }

    if (showEncryptDialog) {
        NcryptsecBackupDialog(
            nsec = nsec,
            onDismiss = { showEncryptDialog = false },
        )
    }
}

@Composable
private fun NcryptsecBackupDialog(nsec: String?, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var passphrase by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Encrypted backup (ncryptsec)") },
        text = {
            Column {
                if (result == null) {
                    Text("Choose a passphrase. You will need it to restore this key on any device. There is no way to recover it if you forget it.")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        label = { Text("Passphrase (min 8 characters)") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = confirm,
                        onValueChange = { confirm = it },
                        label = { Text("Confirm passphrase") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (error != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(error!!, color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    Text("Encrypted backup created. Copy it or scan its QR from another device:")
                    Spacer(Modifier.height(8.dp))
                    Text(result!!, style = MaterialTheme.typography.bodySmall, maxLines = 6)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = {
                        val clip = ClipData.newPlainText("", result)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            clip.description.extras = PersistableBundle().apply {
                                putBoolean("android.content.extra.IS_SENSITIVE", true)
                            }
                        }
                        val cm = context.getSystemService(ClipboardManager::class.java)
                        cm.setPrimaryClip(clip)
                        Toast.makeText(context, "Encrypted backup copied", Toast.LENGTH_SHORT).show()
                    }) { Text("Copy") }
                }
            }
        },
        confirmButton = {
            if (result == null) {
                Button(enabled = passphrase.length >= 8 && passphrase == confirm, onClick = {
                    scope.launch {
                        try {
                            val privkey = com.wisp.app.nostr.Nip19.nsecDecode(nsec!!)
                            result = Nip49.encrypt(privkey, passphrase)
                            error = null
                        } catch (e: Exception) {
                            error = "Could not create backup: " + (e.message ?: "unknown error")
                        }
                    }
                }) { Text("Create") }
            } else {
                Button(onClick = onDismiss) { Text("Done") }
            }
        },
        dismissButton = {
            if (result == null) {
                OutlinedButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}
