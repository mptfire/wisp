package com.wisp.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import com.wisp.app.relay.HttpClientFactory
import com.wisp.app.relay.LocalRelayConfig
import com.wisp.app.relay.LocalRelayStatus
import com.wisp.app.relay.LocalRelayWritePolicy
import com.wisp.app.relay.RelayConfig
import com.wisp.app.relay.TorManager
import kotlinx.coroutines.flow.MutableStateFlow
import com.wisp.app.ui.util.relayMatchesQuery
import com.wisp.app.ui.util.relaySearchQuery
import com.wisp.app.relay.RelayPool
import com.wisp.app.relay.RelaySetType
import com.wisp.app.R
import com.wisp.app.ui.component.NsecPasteGuard
import com.wisp.app.viewmodel.RelayViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelayScreen(
    viewModel: RelayViewModel,
    relayPool: RelayPool? = null,
    onBack: () -> Unit,
    signer: com.wisp.app.nostr.NostrSigner? = null
) {
    val context = LocalContext.current
    val selectedTab by viewModel.selectedTab.collectAsState()
    val relays by viewModel.relays.collectAsState()
    val dmRelays by viewModel.dmRelays.collectAsState()
    val searchRelays by viewModel.searchRelays.collectAsState()
    val blockedRelays by viewModel.blockedRelays.collectAsState()
    val localRelay by viewModel.localRelay.collectAsState()
    val localRelayError by viewModel.localRelayError.collectAsState()
    val newRelayUrl by viewModel.newRelayUrl.collectAsState()

    val tabs = RelaySetType.entries

    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_relays)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            ScrollableTabRow(
                selectedTabIndex = tabs.indexOf(selectedTab),
                edgePadding = 0.dp
            ) {
                tabs.forEach { tab ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        text = { Text(tab.displayName) }
                    )
                }
            }

            if (selectedTab == RelaySetType.LOCAL) {
                LocalRelayTab(
                    config = localRelay,
                    newRelayUrl = newRelayUrl,
                    hasError = localRelayError,
                    relayPool = relayPool,
                    viewModel = viewModel
                )
            } else Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = newRelayUrl,
                        onValueChange = { new -> if (!NsecPasteGuard.blockIfNsec(newRelayUrl, new)) viewModel.updateNewRelayUrl(new) },
                        label = { Text(stringResource(R.string.placeholder_relay_url)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = { viewModel.addRelay() }) {
                        Icon(Icons.Default.Add, stringResource(R.string.cd_add_relay))
                    }
                }

                Spacer(Modifier.height(8.dp))

                if (relayPool != null) {
                    val buttonLabel = when (selectedTab) {
                        RelaySetType.GENERAL -> stringResource(R.string.broadcast_nip65)
                        RelaySetType.DM -> stringResource(R.string.broadcast_dm_relays)
                        RelaySetType.SEARCH -> stringResource(R.string.broadcast_search_relays)
                        RelaySetType.BLOCKED -> stringResource(R.string.broadcast_blocked_relays)
                        RelaySetType.LOCAL -> ""
                    }
                    val successMsg = stringResource(R.string.error_relay_broadcast)
                    val failureMsg = stringResource(R.string.error_broadcast_failed)
                    Button(
                        onClick = {
                            val ok = viewModel.publishRelayList(relayPool, signer = signer)
                            val msg = if (ok) successMsg else failureMsg
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(buttonLabel)
                    }
                }

                Spacer(Modifier.height(16.dp))

                // The add field also filters the list below it
                val query = relaySearchQuery(newRelayUrl)
                val shown = when (selectedTab) {
                    RelaySetType.GENERAL -> relays.map { it.url }
                    RelaySetType.DM -> dmRelays
                    RelaySetType.SEARCH -> searchRelays
                    RelaySetType.BLOCKED -> blockedRelays
                    RelaySetType.LOCAL -> emptyList()
                }
                if (query.isNotEmpty() && shown.none { relayMatchesQuery(it, query) }) {
                    Text(
                        stringResource(R.string.relay_list_no_match),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                when (selectedTab) {
                    RelaySetType.GENERAL -> GeneralRelayList(relays.filter { relayMatchesQuery(it.url, query) }, viewModel)
                    RelaySetType.DM -> SimpleRelayList(dmRelays.filter { relayMatchesQuery(it, query) }, viewModel)
                    RelaySetType.SEARCH -> SimpleRelayList(searchRelays.filter { relayMatchesQuery(it, query) }, viewModel)
                    RelaySetType.BLOCKED -> SimpleRelayList(blockedRelays.filter { relayMatchesQuery(it, query) }, viewModel)
                    RelaySetType.LOCAL -> {}
                }
            }
        }
    }
}

@Composable
private fun GeneralRelayList(relays: List<RelayConfig>, viewModel: RelayViewModel) {
    LazyColumn {
        items(items = relays.distinctBy { it.url }, key = { it.url }) { relay ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = relay.url,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = relay.read,
                            onClick = { viewModel.toggleRead(relay.url) },
                            label = { Text(stringResource(R.string.relay_read)) }
                        )
                        FilterChip(
                            selected = relay.write,
                            onClick = { viewModel.toggleWrite(relay.url) },
                            label = { Text(stringResource(R.string.relay_write)) }
                        )
                        FilterChip(
                            selected = relay.auth,
                            onClick = { viewModel.toggleAuth(relay.url) },
                            label = { Text(stringResource(R.string.relay_auth)) }
                        )
                    }
                }
                IconButton(onClick = { viewModel.removeRelay(relay.url) }) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.cd_remove_relay),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LocalRelayTab(
    config: LocalRelayConfig?,
    newRelayUrl: String,
    hasError: Boolean,
    relayPool: RelayPool?,
    viewModel: RelayViewModel
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.local_relay_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val torState by TorManager.state.collectAsState()
        if (torState !is TorManager.State.Off || HttpClientFactory.isTorActive()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.local_relay_tor_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(Modifier.height(12.dp))

        if (config == null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = newRelayUrl,
                    onValueChange = { new ->
                        if (!NsecPasteGuard.blockIfNsec(newRelayUrl, new)) {
                            viewModel.updateNewRelayUrl(new)
                        }
                    },
                    label = { Text(stringResource(R.string.local_relay_url_hint)) },
                    isError = hasError,
                    supportingText = if (hasError) {
                        { Text(stringResource(R.string.local_relay_invalid_url)) }
                    } else null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { viewModel.addRelay() }) {
                    Icon(Icons.Default.Add, stringResource(R.string.cd_add_relay))
                }
            }

            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = { viewModel.setLocalRelayUrl(RelayConfig.CITRINE_DEFAULT_URL) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.local_relay_citrine_preset))
            }
            return
        }

        // Configured local relay controls — reactive connection state
        LaunchedEffect(config.url, config.enabled, relayPool) {
            if (config.enabled) viewModel.syncLocalRelayIfNeeded()
        }
        val fallbackStatusFlow = remember { MutableStateFlow<LocalRelayStatus>(LocalRelayStatus.Disabled) }
        val status by (relayPool?.localRelayStatus ?: fallbackStatusFlow).collectAsState()
        val effectiveStatus = when {
            !config.enabled -> LocalRelayStatus.Disabled
            status is LocalRelayStatus.Disabled && relayPool?.isRelayConnected(config.url) == true -> LocalRelayStatus.Connected
            status is LocalRelayStatus.Disabled -> LocalRelayStatus.Connecting
            else -> status
        }
        val isConnected = effectiveStatus is LocalRelayStatus.Connected
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(if (isConnected) Color(0xFF4CAF50) else Color(0xFFE53935))
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = config.url,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = when (effectiveStatus) {
                        LocalRelayStatus.Disabled -> stringResource(R.string.local_relay_status_paused)
                        LocalRelayStatus.Connected -> stringResource(R.string.local_relay_status_connected)
                        LocalRelayStatus.Connecting -> stringResource(R.string.local_relay_status_connecting)
                        is LocalRelayStatus.Error -> stringResource(R.string.local_relay_status_error, effectiveStatus.message)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = when (effectiveStatus) {
                        LocalRelayStatus.Connected -> Color(0xFF4CAF50)
                        is LocalRelayStatus.Error -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            if (config.enabled && !isConnected) {
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = { viewModel.reconnectLocalRelay() },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text(
                        text = stringResource(R.string.local_relay_reconnect),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = config.enabled,
                onCheckedChange = { viewModel.toggleLocalRelayEnabled() }
            )
        }

        if (config.enabled && effectiveStatus is LocalRelayStatus.Error) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.local_relay_error_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.local_relay_write_policy),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        val policies = listOf(
            LocalRelayWritePolicy.OWN_NOTES to stringResource(R.string.local_relay_own_notes),
            LocalRelayWritePolicy.TAGGED to stringResource(R.string.local_relay_tagged),
            LocalRelayWritePolicy.ALL_NOTES to stringResource(R.string.local_relay_all_notes)
        )
        policies.forEach { (policy, label) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = config.writePolicy == policy,
                        onClick = { viewModel.updateLocalRelayPolicy(policy) },
                        role = Role.RadioButton
                    )
                    .padding(vertical = 4.dp)
            ) {
                RadioButton(
                    selected = config.writePolicy == policy,
                    onClick = null
                )
                Spacer(Modifier.width(8.dp))
                Text(text = label, style = MaterialTheme.typography.bodyMedium)
            }
        }

        Spacer(Modifier.height(12.dp))

        Text(
            text = stringResource(R.string.local_relay_kinds),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = 1 in config.kinds,
                onClick = {
                    val updated = if (1 in config.kinds) config.kinds - 1 else config.kinds + 1
                    viewModel.updateLocalRelayKinds(updated)
                },
                label = { Text(stringResource(R.string.local_relay_kind_notes)) }
            )
            FilterChip(
                selected = 1059 in config.kinds,
                onClick = {
                    val updated = if (1059 in config.kinds) config.kinds - 1059 else config.kinds + 1059
                    viewModel.updateLocalRelayKinds(updated)
                },
                label = { Text(stringResource(R.string.local_relay_kind_dms)) }
            )
            FilterChip(
                selected = 9735 in config.kinds,
                onClick = {
                    val updated = if (9735 in config.kinds) config.kinds - 9735 else config.kinds + 9735
                    viewModel.updateLocalRelayKinds(updated)
                },
                label = { Text(stringResource(R.string.local_relay_kind_zaps)) }
            )
        }

        var customKindInput by remember { mutableStateOf("") }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 4.dp)
        ) {
            OutlinedTextField(
                value = customKindInput,
                onValueChange = { customKindInput = it.filter { c -> c.isDigit() } },
                label = { Text(stringResource(R.string.local_relay_add_kind)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = {
                val kind = customKindInput.toIntOrNull()
                if (kind != null && kind !in config.kinds) {
                    viewModel.updateLocalRelayKinds(config.kinds + kind)
                    customKindInput = ""
                }
            }) {
                Icon(Icons.Default.Add, stringResource(R.string.cd_add_relay))
            }
        }

        val customKinds = config.kinds - setOf(1, 1059, 9735)
        if (customKinds.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                customKinds.sorted().forEach { kind ->
                    FilterChip(
                        selected = true,
                        onClick = { viewModel.updateLocalRelayKinds(config.kinds - kind) },
                        label = { Text("Kind $kind") }
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        TextButton(
            onClick = { viewModel.removeRelay(config.url) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                stringResource(R.string.local_relay_remove),
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun SimpleRelayList(urls: List<String>, viewModel: RelayViewModel) {
    LazyColumn {
        items(items = urls.distinct(), key = { it }) { url ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Text(
                    text = url,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { viewModel.removeRelay(url) }) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.cd_remove_relay),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}
