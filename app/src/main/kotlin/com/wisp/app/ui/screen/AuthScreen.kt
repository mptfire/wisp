package com.wisp.app.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wisp.app.R
import com.wisp.app.nostr.ExternalSignerProtocol
import com.wisp.app.nostr.InstalledSignerApp
import com.wisp.app.nostr.Nip19
import com.wisp.app.nostr.Nip46AuthBridge
import com.wisp.app.nostr.RemoteSignerBridge
import com.wisp.app.nostr.toHex
import com.wisp.app.ui.component.AppNostrConnectDialog
import com.wisp.app.ui.component.BunkerLoginDialog
import com.wisp.app.ui.component.Nip46AuthUrlDialog
import com.wisp.app.ui.component.NsecPasteGuard
import com.wisp.app.ui.component.QrScanner
import com.wisp.app.ui.component.SignerPickerDialog
import com.wisp.app.ui.component.TorCornerButton
import com.wisp.app.viewmodel.AuthViewModel
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

@Composable
fun AuthScreen(
    viewModel: AuthViewModel,
    showSignUp: Boolean = true,
    onAuthenticated: (isNewAccount: Boolean) -> Unit,
    onToggleTor: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val nsecInput by viewModel.nsecInput.collectAsState()
    val error by viewModel.error.collectAsState()
    var nsecVisible by remember { mutableStateOf(false) }
    var showQrScanner by remember { mutableStateOf(false) }
    val availableSigners = remember { RemoteSignerBridge.listAvailableSigners(context) }
    val signerAvailable = availableSigners.isNotEmpty()
    var showSignerPicker by remember { mutableStateOf(false) }
    var pendingSelectedSignerPackage by remember { mutableStateOf<String?>(null) }
    var pendingNip46App by remember { mutableStateOf<InstalledSignerApp?>(null) }

    var signerLoginComplete by remember { mutableStateOf(false) }
    if (signerLoginComplete) {
        LaunchedEffect(Unit) {
            signerLoginComplete = false
            onAuthenticated(false)
        }
    }

    val signerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { activityResult ->
        val selectedPkg = pendingSelectedSignerPackage
        pendingSelectedSignerPackage = null
        val data = activityResult.data ?: return@rememberLauncherForActivityResult
        val rawResult = data.getStringExtra("result")?.trim() ?: return@rememberLauncherForActivityResult
        val pkg = RemoteSignerBridge.resolveSignedPackage(
            returnedPackage = data.getStringExtra("package"),
            selectedPackage = selectedPkg,
            context = context
        )
        val pubkeyHex = if (rawResult.startsWith("npub1")) {
            try { Nip19.npubDecode(rawResult).toHex() } catch (_: Exception) { return@rememberLauncherForActivityResult }
        } else if (rawResult.length == 64 && rawResult.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            rawResult.lowercase()
        } else {
            return@rememberLauncherForActivityResult
        }
        viewModel.loginWithSigner(pubkeyHex, pkg)
        signerLoginComplete = true
    }

    val pendingBunkerUri by viewModel.pendingBunkerUri.collectAsState()
    val bunkerStatus by viewModel.bunkerStatus.collectAsState()
    val pendingNip46AuthUrl by Nip46AuthBridge.pendingAuthUrl.collectAsState()
    var showBunkerDialog by remember { mutableStateOf(false) }

    LaunchedEffect(pendingBunkerUri) {
        val uri = pendingBunkerUri ?: return@LaunchedEffect
        val ok = viewModel.connectWithBunkerUri(uri)
        if (ok) onAuthenticated(false)
    }

    if (showQrScanner) {
        QrScanner(
            onResult = { raw ->
                showQrScanner = false
                viewModel.updateNsecInput(raw.trim())
                if (viewModel.logIn()) onAuthenticated(false)
            },
            modifier = androidx.compose.ui.Modifier.fillMaxSize(),
            promptText = "Scan nsec, npub, bunker://, or nprofile QR"
        )
        return
    }

    DisposableEffect(Unit) {
        NsecPasteGuard.nsecPasteAllowed = true
        onDispose { NsecPasteGuard.nsecPasteAllowed = false }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_wisp_logo),
            contentDescription = stringResource(R.string.onboarding_wisp_logo),
            tint = androidx.compose.ui.graphics.Color.Unspecified,
            modifier = Modifier
                .size(108.dp)
                .drawBehind {
                    drawCircle(
                        brush = androidx.compose.ui.graphics.Brush.radialGradient(
                            colors = listOf(
                                androidx.compose.ui.graphics.Color.Black,
                                androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f),
                                androidx.compose.ui.graphics.Color.Transparent
                            ),
                            radius = size.minDimension * 0.65f
                        ),
                        radius = size.minDimension * 0.65f
                    )
                }
        )
        Text(
            text = stringResource(R.string.auth_wisp),
            style = MaterialTheme.typography.titleLarge.copy(
                fontFamily = FontFamily.SansSerif,
                fontSize = 36.sp,
                fontWeight = FontWeight.W500
            ),
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.auth_tagline),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(16.dp))

        if (showSignUp) {
            Button(
                onClick = {
                    if (viewModel.signUp()) onAuthenticated(true)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.auth_sign_up))
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.auth_or_log_in_with_key),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
        } else {
            Spacer(Modifier.height(12.dp))
        }


        val pendingNcryptsec by viewModel.pendingNcryptsec.collectAsState()
        val scope = rememberCoroutineScope()
        var passphrase by remember { mutableStateOf("") }
        var isUnlocking by remember { mutableStateOf(false) }

        if (pendingNcryptsec != null) {
            Text(
                text = "This is an encrypted key backup. Enter its passphrase to unlock it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = passphrase,
                onValueChange = { passphrase = it },
                label = { Text("Backup passphrase") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { passphrase = ""; viewModel.clearPendingNcryptsec() },
                    enabled = !isUnlocking,
                    modifier = Modifier.weight(1f)
                ) { Text("Back") }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (isUnlocking) return@Button
                        isUnlocking = true
                        scope.launch {
                            val ok = viewModel.completeNcryptsecLogin(passphrase)
                            isUnlocking = false
                            if (ok) {
                                passphrase = ""
                                onAuthenticated(false)
                            }
                        }
                    },
                    enabled = passphrase.isNotBlank() && !isUnlocking,
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    if (isUnlocking) {
                        CircularProgressIndicator(modifier = Modifier.height(20.dp).width(20.dp), strokeWidth = 2.dp)
                    } else Text("Unlock")
                }
            }
        } else OutlinedTextField(
            value = nsecInput,
            onValueChange = { viewModel.updateNsecInput(it) },
            label = { Text("nsec, npub, bunker://, phrase, or ncryptsec") },
            singleLine = false,
            maxLines = 3,
            visualTransformation = if (nsecVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                Row {
                    IconButton(onClick = { nsecVisible = !nsecVisible }) {
                        Icon(
                            imageVector = if (nsecVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = if (nsecVisible) stringResource(R.string.auth_hide_key) else stringResource(R.string.auth_show_key)
                        )
                    }
                    IconButton(onClick = { showQrScanner = true }) {
                        Icon(
                            imageVector = Icons.Outlined.QrCodeScanner,
                            contentDescription = "Scan QR code"
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(12.dp))

        if (pendingNcryptsec == null) {
            OutlinedButton(
                onClick = {
                    if (viewModel.logIn()) onAuthenticated(false)
                },
                enabled = bunkerStatus == null,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (bunkerStatus != null && !showBunkerDialog) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(bunkerStatus ?: stringResource(R.string.auth_bunker_connecting))
                } else {
                    Text(stringResource(R.string.auth_log_in))
                }
            }
        }

        fun launchInstalledSigner(signerApp: InstalledSignerApp) {
            when (signerApp.protocol) {
                ExternalSignerProtocol.NIP55 -> {
                    pendingSelectedSignerPackage = signerApp.packageName
                    signerLauncher.launch(
                        RemoteSignerBridge.buildGetPublicKeyIntent(packageName = signerApp.packageName)
                    )
                }
                ExternalSignerProtocol.NIP46 -> {
                    pendingNip46App = signerApp
                }
            }
        }

        if (signerAvailable) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    if (availableSigners.size == 1) {
                        launchInstalledSigner(availableSigners[0])
                    } else {
                        showSignerPicker = true
                    }
                },
                enabled = bunkerStatus == null,
                modifier = Modifier.fillMaxWidth()
            ) {
                val label = if (availableSigners.size == 1) {
                    stringResource(R.string.auth_login_with_specific_signer, availableSigners[0].label)
                } else {
                    stringResource(R.string.auth_login_with_signer)
                }
                Text(label)
            }
        }

        pendingNip46App?.let { targetApp ->
            AppNostrConnectDialog(
                signerApp = targetApp,
                viewModel = viewModel,
                onConnected = {
                    pendingNip46App = null
                    onAuthenticated(false)
                },
                onDismiss = {
                    pendingNip46App = null
                    viewModel.clearPendingBunkerUri()
                }
            )
        }

        Spacer(Modifier.height(4.dp))
        TextButton(
            onClick = { showBunkerDialog = true },
            enabled = bunkerStatus == null
        ) {
            Text(
                text = stringResource(R.string.auth_show_pairing_qr),
                style = MaterialTheme.typography.bodySmall
            )
        }

        if (showBunkerDialog) {
            BunkerLoginDialog(
                viewModel = viewModel,
                onConnected = {
                    showBunkerDialog = false
                    onAuthenticated(false)
                },
                onDismiss = {
                    showBunkerDialog = false
                    viewModel.clearPendingBunkerUri()
                }
            )
        }

        pendingNip46AuthUrl?.let { authUrl ->
            Nip46AuthUrlDialog(authUrl = authUrl)
        }

        if (showSignerPicker) {
            SignerPickerDialog(
                signers = availableSigners,
                onSelect = { signerApp ->
                    showSignerPicker = false
                    launchInstalledSigner(signerApp)
                },
                onDismiss = { showSignerPicker = false }
            )
        }

        error?.let {
            Spacer(Modifier.height(16.dp))
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
    TorCornerButton(
        onToggle = onToggleTor,
        modifier = Modifier
            .align(Alignment.TopEnd)
            .statusBarsPadding()
            .padding(8.dp)
    )
    }
}

/** Superellipse-based squircle shape (n≈5) for smooth continuous-curvature corners. */
private val SquircleShape = object : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = Path()
        val w = size.width
        val h = size.height
        val n = 5.0 // superellipse exponent — higher = more square, lower = more round
        val steps = 360
        for (i in 0 until steps) {
            val angle = 2.0 * Math.PI * i / steps
            val cosA = cos(angle)
            val sinA = sin(angle)
            val x = (w / 2) * abs(cosA).pow(2.0 / n) * sign(cosA) + w / 2
            val y = (h / 2) * abs(sinA).pow(2.0 / n) * sign(sinA) + h / 2
            if (i == 0) path.moveTo(x.toFloat(), y.toFloat())
            else path.lineTo(x.toFloat(), y.toFloat())
        }
        path.close()
        return Outline.Generic(path)
    }
}
