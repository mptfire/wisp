package com.wisp.app.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.wisp.app.nostr.Nip49
import kotlinx.coroutines.launch

/**
 * Shared passphrase-encrypted backup dialog (NIP-49). [privkeyProvider]
 * must return a ByteArray the dialog may zero after use - pass a copy when
 * the underlying key is the live in-memory key.
 */
@Composable
fun NcryptsecBackupDialog(
    privkeyProvider: (() -> ByteArray)?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var passphrase by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var isEncrypting by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!isEncrypting) onDismiss() },
        title = { Text("Encrypted backup (ncryptsec)") },
        text = {
            Column {
                if (result == null) {
                    Text("Choose a passphrase. You will need it to restore this key on any device. It cannot be recovered if forgotten.")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        label = { Text("Passphrase (min 8 characters)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = confirm,
                        onValueChange = { confirm = it },
                        label = { Text("Confirm passphrase") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (error != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(error!!, color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    Text("Encrypted backup created. Store it anywhere - only this passphrase can unlock it.")
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
                Button(
                    enabled = privkeyProvider != null &&
                        passphrase.length >= 8 && passphrase == confirm && !isEncrypting,
                    onClick = {
                        if (isEncrypting) return@Button
                        val provider = privkeyProvider ?: return@Button
                        isEncrypting = true
                        scope.launch {
                            val pk = try {
                                provider.invoke()
                            } catch (e: Exception) {
                                error = "Could not read key: " + (e.message ?: "unknown error")
                                null
                            }
                            if (pk != null) {
                                try {
                                    result = Nip49.encrypt(pk, passphrase)
                                    error = null
                                } catch (e: Exception) {
                                    error = "Could not create backup: " + (e.message ?: "unknown error")
                                } finally {
                                    pk.fill(0)
                                }
                            }
                            isEncrypting = false
                        }
                    }
                ) {
                    if (isEncrypting) {
                        CircularProgressIndicator(modifier = Modifier.height(20.dp).width(20.dp), strokeWidth = 2.dp)
                    } else Text("Create")
                }
            } else {
                Button(onClick = onDismiss) { Text("Done") }
            }
        },
        dismissButton = {
            if (result == null && !isEncrypting) {
                OutlinedButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}
