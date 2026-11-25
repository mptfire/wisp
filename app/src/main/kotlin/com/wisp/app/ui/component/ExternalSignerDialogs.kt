package com.wisp.app.ui.component

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.wisp.app.R
import com.wisp.app.nostr.ExternalSignerProtocol
import com.wisp.app.nostr.InstalledSignerApp
import com.wisp.app.nostr.Keys
import com.wisp.app.nostr.Nip46
import com.wisp.app.nostr.Nip46AuthBridge
import com.wisp.app.nostr.RemoteSignerBridge
import com.wisp.app.nostr.toHex
import com.wisp.app.viewmodel.AuthViewModel
import kotlinx.coroutines.launch

@Composable
fun SignerPickerDialog(
    signers: List<InstalledSignerApp>,
    onSelect: (InstalledSignerApp) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.auth_choose_signer_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.auth_choose_signer_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                signers.forEach { signerApp ->
                    val appIconBitmap = remember(signerApp.packageName) {
                        runCatching {
                            context.packageManager.getApplicationIcon(signerApp.packageName)
                                .toBitmap(width = 72, height = 72)
                                .asImageBitmap()
                        }.getOrNull()
                    }
                    val protocolTag = when (signerApp.protocol) {
                        ExternalSignerProtocol.NIP55 -> stringResource(R.string.auth_signer_protocol_nip55)
                        ExternalSignerProtocol.NIP46 -> stringResource(R.string.auth_signer_protocol_nip46)
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onSelect(signerApp) }
                            .padding(horizontal = 8.dp, vertical = 10.dp)
                    ) {
                        if (appIconBitmap != null) {
                            Image(
                                bitmap = appIconBitmap,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(8.dp))
                            )
                            Spacer(Modifier.width(12.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = signerApp.label,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "$protocolTag · ${signerApp.packageName}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_cancel))
            }
        }
    )
}

@Composable
fun AppNostrConnectDialog(
    signerApp: InstalledSignerApp,
    viewModel: AuthViewModel,
    onConnected: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val bunkerStatus by viewModel.bunkerStatus.collectAsState()
    val error by viewModel.error.collectAsState()

    val ncKeypair = remember(signerApp.packageName) { Keys.generate() }
    val ncSecret = remember(signerApp.packageName) { Nip46.generateSecret() }
    val ncRelays = remember { Nip46.DEFAULT_NOSTRCONNECT_RELAYS }
    var relaysReady by remember { mutableStateOf(false) }
    val ncUri = remember(ncKeypair, ncSecret) {
        Nip46.buildNostrConnectUri(
            clientPubkeyHex = ncKeypair.pubkey.toHex(),
            relays = ncRelays,
            secret = ncSecret
        )
    }

    fun openSignerApp() {
        runCatching {
            context.startActivity(
                RemoteSignerBridge.buildNostrConnectIntent(ncUri, packageName = signerApp.packageName)
            )
        }
    }

    DisposableEffect(ncKeypair) {
        onDispose { ncKeypair.wipe() }
    }

    LaunchedEffect(signerApp.packageName, ncUri) {
        val ok = viewModel.connectWithNostrConnect(
            clientKeypair = ncKeypair,
            relays = ncRelays,
            secret = ncSecret,
            onRelaysReady = {
                relaysReady = true
                openSignerApp()
            }
        )
        if (ok) onConnected()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.auth_nip46_app_title, signerApp.label)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.auth_nip46_app_desc, signerApp.label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = bunkerStatus ?: stringResource(R.string.auth_bunker_connecting),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedButton(
                        onClick = { openSignerApp() },
                        enabled = relaysReady,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.auth_nip46_reopen_app, signerApp.label))
                    }
                    OutlinedButton(
                        onClick = {
                            val cm = context.getSystemService(ClipboardManager::class.java)
                            cm.setPrimaryClip(ClipData.newPlainText("nostrconnect", ncUri))
                            Toast.makeText(context, context.getString(R.string.auth_bunker_link_copied), Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.btn_copy))
                    }
                }
                if (!error.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = error!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_cancel))
            }
        }
    )
}

@Composable
fun BunkerLoginDialog(
    viewModel: AuthViewModel,
    onConnected: () -> Unit,
    onDismiss: () -> Unit,
    initialTab: Int = 1
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bunkerStatus by viewModel.bunkerStatus.collectAsState()
    val error by viewModel.error.collectAsState()

    var selectedTab by remember { mutableStateOf(initialTab) }
    var bunkerInput by remember { mutableStateOf("") }
    var isConnecting by remember { mutableStateOf(false) }

    val ncKeypair = remember { Keys.generate() }
    val ncSecret = remember { Nip46.generateSecret() }
    val ncRelays = remember { Nip46.DEFAULT_NOSTRCONNECT_RELAYS }
    val ncUri = remember(ncKeypair, ncSecret) {
        Nip46.buildNostrConnectUri(
            clientPubkeyHex = ncKeypair.pubkey.toHex(),
            relays = ncRelays,
            secret = ncSecret
        )
    }
    val ncQrBitmap = remember(ncUri) {
        runCatching {
            val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
            val matrix = QRCodeWriter().encode(ncUri, BarcodeFormat.QR_CODE, 480, 480, hints)
            val bmp = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.RGB_565)
            for (x in 0 until matrix.width) {
                for (y in 0 until matrix.height) {
                    bmp.setPixel(x, y, if (matrix[x, y]) AndroidColor.BLACK else AndroidColor.WHITE)
                }
            }
            bmp.asImageBitmap()
        }.getOrNull()
    }

    DisposableEffect(ncKeypair) {
        onDispose { ncKeypair.wipe() }
    }

    LaunchedEffect(selectedTab) {
        if (selectedTab == 1) {
            isConnecting = true
            val ok = viewModel.connectWithNostrConnect(ncKeypair, ncRelays, ncSecret)
            isConnecting = false
            if (ok) onConnected()
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isConnecting || selectedTab == 1) onDismiss() },
        title = { Text(stringResource(R.string.auth_bunker_dialog_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { if (!isConnecting) selectedTab = 0 },
                        text = { Text(stringResource(R.string.auth_bunker_tab_uri)) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text(stringResource(R.string.auth_bunker_tab_qr)) }
                    )
                }

                Spacer(Modifier.height(12.dp))

                if (selectedTab == 0) {
                    Text(
                        text = stringResource(R.string.auth_bunker_uri_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = bunkerInput,
                        onValueChange = { bunkerInput = it },
                        label = { Text("bunker://…") },
                        singleLine = false,
                        maxLines = 3,
                        enabled = !isConnecting,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (bunkerStatus != null) {
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = bunkerStatus ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    Text(
                        text = stringResource(R.string.auth_bunker_qr_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    if (ncQrBitmap != null) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .size(210.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.White)
                                .padding(8.dp)
                        ) {
                            Image(
                                bitmap = ncQrBitmap,
                                contentDescription = "nostrconnect QR",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedButton(
                            onClick = {
                                val cm = context.getSystemService(ClipboardManager::class.java)
                                cm.setPrimaryClip(ClipData.newPlainText("nostrconnect", ncUri))
                                Toast.makeText(context, context.getString(R.string.auth_bunker_link_copied), Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.btn_copy))
                        }
                        OutlinedButton(
                            onClick = {
                                val launched = runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ncUri)))
                                }.isSuccess
                                if (!launched) {
                                    Toast.makeText(context, context.getString(R.string.auth_bunker_no_handler), Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.auth_bunker_open_signer))
                        }
                    }
                    if (bunkerStatus != null) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = bunkerStatus ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (!error.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = error!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            if (selectedTab == 0) {
                Button(
                    onClick = {
                        if (isConnecting) return@Button
                        isConnecting = true
                        scope.launch {
                            val ok = viewModel.connectWithBunkerUri(bunkerInput.trim())
                            isConnecting = false
                            if (ok) onConnected()
                        }
                    },
                    enabled = bunkerInput.isNotBlank() && !isConnecting
                ) {
                    Text(stringResource(R.string.auth_bunker_connect))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_cancel))
            }
        }
    )
}

@Composable
fun Nip46AuthUrlDialog(authUrl: String) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = { Nip46AuthBridge.dismissAuthUrl() },
        title = { Text(stringResource(R.string.nip46_auth_url_title)) },
        text = { Text(stringResource(R.string.nip46_auth_url_body, authUrl)) },
        confirmButton = {
            TextButton(
                onClick = {
                    Nip46AuthBridge.dismissAuthUrl()
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)))
                    }
                }
            ) {
                Text(stringResource(R.string.nip46_auth_url_open))
            }
        },
        dismissButton = {
            TextButton(onClick = { Nip46AuthBridge.dismissAuthUrl() }) {
                Text(stringResource(R.string.btn_cancel))
            }
        }
    )
}
