package moe.crx.overport.app.composables

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.crx.overport.versions.OverportRelease
import moe.crx.overport.versions.VersionManager
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import overportapp.composeapp.generated.resources.*

@Composable
fun VersionManagerDialog(
    manager: VersionManager,
    onSelected: (String) -> Unit,
    onDismiss: () -> Unit,
    onBusyChange: (Boolean) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var installed by remember(manager) { mutableStateOf(emptyList<OverportRelease>()) }
    var available by remember(manager) { mutableStateOf(emptyList<OverportRelease>()) }
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var version by remember { mutableStateOf("latest") }
    var removal by remember { mutableStateOf<OverportRelease?>(null) }
    val reportBusy by rememberUpdatedState(onBusyChange)
    SideEffect { reportBusy(busy) }
    DisposableEffect(Unit) { onDispose { reportBusy(false) } }
    suspend fun refresh() {
        installed = withContext(Dispatchers.IO) { manager.installed() }
        available = withContext(Dispatchers.IO) { manager.available() }
    }
    fun operate(action: suspend () -> Unit) {
        busy = true
        error = null
        status = null
        scope.launch {
            try {
                action()
                installed = withContext(Dispatchers.IO) { manager.installed() }
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                error = ex.message ?: ex.toString()
            } finally {
                busy = false
            }
        }
    }
    fun install(requested: String) = operate {
        val resolved = withContext(Dispatchers.IO) {
            manager.workingDirectory.mkdirs()
            manager.checkout(requested)
        }
        checkNotNull(resolved) { getString(Res.string.versions_install_failed) }
        status = getString(Res.string.versions_installed, resolved)
    }
    LaunchedEffect(manager) {
        try {
            refresh()
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            error = ex.message ?: ex.toString()
        } finally {
            busy = false
        }
    }
    val releases = (available + installed).distinctBy { it.version }
    val incompatible = releases.firstOrNull { it.version == version.trim() }?.let { VersionManager.isIncompatible(it) } == true
    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = !busy, dismissOnClickOutside = !busy),
    ) {
        Surface(modifier = Modifier.widthIn(max = 800.dp).fillMaxWidth(0.95f).heightIn(max = 720.dp), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(Res.string.versions_title), style = MaterialTheme.typography.headlineSmall)
                Text(manager.workingDirectory.absolutePath, style = MaterialTheme.typography.bodySmall)
                Text(stringResource(Res.string.versions_scope), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = version, onValueChange = { version = it }, enabled = !busy,
                        label = { Text(stringResource(Res.string.versions_requested)) },
                        singleLine = true, modifier = Modifier.weight(1f), isError = incompatible,
                    )
                    Button(enabled = !busy && version.isNotBlank() && !incompatible, onClick = { onSelected(version.trim()) }) {
                        Text(stringResource(Res.string.versions_use))
                    }
                    OutlinedButton(enabled = !busy && version.isNotBlank() && !incompatible, onClick = { install(version.trim()) }) {
                        Text(stringResource(Res.string.versions_install))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("latest", "experimental").forEach { alias ->
                        FilterChip(selected = version == alias, enabled = !busy, onClick = { version = alias }, label = { Text(alias) })
                    }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                if (!busy && available.isEmpty()) {
                    Text(stringResource(Res.string.versions_offline), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(releases, key = { it.version }) { release ->
                        val isInstalled = installed.any { it.version == release.version }
                        val compatible = !VersionManager.isIncompatible(release)
                        ListItem(
                            headlineContent = { Text(release.version) },
                            supportingContent = {
                                Column {
                                    Text(stringResource(if (isInstalled) Res.string.status_installed else Res.string.status_not_installed))
                                    if (release.isExperimental) Text(stringResource(Res.string.tag_experimental))
                                    if (release.isCustom) Text(stringResource(Res.string.tag_custom))
                                    if (!compatible) Text(stringResource(Res.string.versions_incompatible), color = MaterialTheme.colorScheme.error)
                                }
                            },
                            trailingContent = {
                                Row {
                                    TextButton(enabled = !busy && compatible, onClick = { onSelected(release.version) }) { Text(stringResource(Res.string.versions_use)) }
                                    if (isInstalled) {
                                        TextButton(enabled = !busy, onClick = { removal = release }) { Text(stringResource(Res.string.versions_remove)) }
                                    } else {
                                        TextButton(enabled = !busy && compatible, onClick = { install(release.version) }) { Text(stringResource(Res.string.versions_install)) }
                                    }
                                }
                            },
                        )
                        HorizontalDivider()
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(enabled = !busy, onClick = { operate { refresh() } }) { Text(stringResource(Res.string.versions_refresh)) }
                    TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(Res.string.versions_close)) }
                }
            }
        }
    }
    removal?.let { release ->
        AlertDialog(
            onDismissRequest = { removal = null },
            title = { Text(stringResource(Res.string.versions_remove_title, release.version)) },
            text = { Text(stringResource(Res.string.versions_remove_message)) },
            confirmButton = {
                TextButton(onClick = {
                    removal = null
                    operate { withContext(Dispatchers.IO) { manager.uninstall(release.version) } }
                }) { Text(stringResource(Res.string.versions_remove)) }
            },
            dismissButton = { TextButton(onClick = { removal = null }) { Text(stringResource(Res.string.action_cancel)) } },
        )
    }
}
