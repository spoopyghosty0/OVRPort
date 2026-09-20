package moe.crx.overport.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import moe.crx.overport.app.composables.ErrorDialog
import moe.crx.overport.app.composables.PatchOptionsContent
import moe.crx.overport.app.composables.VersionManagerDialog
import moe.crx.overport.app.model.GithubRelease
import moe.crx.overport.app.model.MainViewModel
import moe.crx.overport.app.theme.OverportTheme
import moe.crx.overport.patching.PatchStore
import moe.crx.overport.utils.NameFormatter
import moe.crx.overport.versions.VersionManager
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import overportapp.composeapp.generated.resources.*
import java.awt.Desktop
import java.awt.datatransfer.DataFlavor
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun DesktopPatcherScreen(
    viewModel: MainViewModel,
    initialInput: String,
    choosePath: (String, Boolean) -> File?,
    onBusyChange: (Boolean) -> Unit,
) {
    var inputPath by rememberSaveable { mutableStateOf(initialInput) }
    var outputDirectory by rememberSaveable { mutableStateOf("") }
    var nameFormat by rememberSaveable { mutableStateOf(NameFormatter.DEFAULT_FORMAT) }
    var workspace by rememberSaveable { mutableStateOf(viewModel.dataDirectory.absolutePath) }
    var version by rememberSaveable { mutableStateOf("latest") }
    var selectedPatches by remember { mutableStateOf(PatchStore.recommended().associate { it.name to emptyList<String>() }) }
    var busy by remember { mutableStateOf(false) }
    var showVersions by remember { mutableStateOf(false) }
    var showNameHelp by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var showErrorDetails by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<File?>(null) }
    var applicationInfo by remember { mutableStateOf<String?>(null) }
    var overwrite by remember { mutableStateOf<Pair<File, CompletableDeferred<Boolean>>?>(null) }
    var update by remember { mutableStateOf<GithubRelease?>(null) }
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val manager = remember(workspace) { VersionManager(File(workspace)) }
    LaunchedEffect(Unit) { update = withContext(Dispatchers.IO) { viewModel.versionToUpdate() } }
    val input = remember(inputPath) { File(inputPath).absoluteFile }
    val inputValid = inputPath.isNotBlank() && input.isFile && input.extension.equals("apk", ignoreCase = true)
    val effectiveOutput = outputDirectory.ifBlank { input.parent ?: "" }
    val acceptDrop by rememberUpdatedState(!busy && !showVersions)
    val dropTarget = remember {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                if (!acceptDrop) return false
                val transfer = event.awtTransferable
                if (!transfer.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return false
                val file = (transfer.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>)?.singleOrNull() as? File ?: return false
                if (!file.isFile || !file.extension.equals("apk", ignoreCase = true)) return false
                inputPath = file.absolutePath
                result = null
                applicationInfo = null
                error = null
                return true
            }
        }
    }
    fun patch() {
        val selectedInput = input
        val selectedOutput = File(effectiveOutput)
        val selectedWorkspace = File(workspace)
        val selectedVersion = version.trim()
        val selectedFormat = nameFormat
        val patches = selectedPatches.toMap()
        busy = true
        onBusyChange(true)
        result = null
        error = null
        applicationInfo = null
        scope.launch {
            var temporaryOutput: File? = null
            try {
                val internalDirectory = withContext(Dispatchers.IO) { selectedWorkspace.resolve("dec").canonicalFile.toPath() }
                require(!selectedInput.canonicalFile.toPath().startsWith(internalDirectory)) { getString(Res.string.desktop_internal_path) }
                PatchStore.select(patches.keys)
                viewModel.checkout(selectedVersion, selectedInput.name, selectedWorkspace, selectedFormat)
                withContext(Dispatchers.IO) { selectedInput.inputStream().use { viewModel.prepare(it) } }
                applicationInfo = "${viewModel.currentAppName()} · ${viewModel.currentAppPackage()} · ${viewModel.currentAppVersion()}"
                viewModel.process(patches)
                val outputName = viewModel.patchedName()
                require(outputName.isNotBlank() && outputName != "." && outputName != ".." && '/' !in outputName && '\\' !in outputName) {
                    getString(Res.string.desktop_invalid_name)
                }
                val output = withContext(Dispatchers.IO) {
                    val directory = if (selectedOutput.isFile) selectedOutput.parentFile else selectedOutput
                    directory.mkdirs()
                    require(directory.isDirectory) { getString(Res.string.desktop_invalid_output) }
                    directory.resolve(outputName).canonicalFile
                }
                require(output != selectedInput.canonicalFile) { getString(Res.string.desktop_original_protected) }
                require(!output.toPath().startsWith(internalDirectory)) { getString(Res.string.desktop_internal_path) }
                val replaceExisting = output.exists()
                if (replaceExisting) {
                    val answer = CompletableDeferred<Boolean>()
                    overwrite = output to answer
                    if (!answer.await()) return@launch
                }
                withContext(Dispatchers.IO) {
                    val temporary = Files.createTempFile(output.parentFile.toPath(), ".ovrport-", ".apk").toFile()
                    temporaryOutput = temporary
                    temporary.outputStream().use { viewModel.export(it) }
                    if (replaceExisting) {
                        Files.move(temporary.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    } else {
                        Files.move(temporary.toPath(), output.toPath())
                    }
                }
                result = output
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                error = ex
            } finally {
                overwrite = null
                withContext(NonCancellable) {
                    try {
                        withContext(Dispatchers.IO) { temporaryOutput?.delete() }
                        viewModel.cancel()
                    } catch (ex: Exception) {
                        if (error == null) error = ex
                    } finally {
                        busy = false
                        onBusyChange(false)
                    }
                }
            }
        }
    }
    OverportTheme(darkTheme = true) {
        Surface(Modifier.fillMaxSize().dragAndDropTarget(shouldStartDragAndDrop = { acceptDrop }, target = dropTarget)) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(painterResource(Res.drawable.overport), contentDescription = "OVRPort", modifier = Modifier.size(126.dp, 28.dp))
                    Text(stringResource(Res.string.desktop_heading), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    update?.let { release ->
                        TextButton(onClick = { uriHandler.openUri(release.htmlUrl) }) { Text(stringResource(Res.string.update_available)) }
                    }
                    Text(VersionManager.VERSION, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        SettingsCard(stringResource(Res.string.desktop_input_title), stringResource(Res.string.desktop_input_hint)) {
                            PathField(inputPath, {
                                inputPath = it
                                result = null
                                applicationInfo = null
                                error = null
                            }, stringResource(Res.string.desktop_input), !busy, inputPath.isNotBlank() && !inputValid) {
                                choosePath(inputPath, false)?.let { inputPath = it.absolutePath; result = null; applicationInfo = null; error = null }
                            }
                            if (inputPath.isNotBlank() && !inputValid) Text(stringResource(Res.string.desktop_invalid_input), color = MaterialTheme.colorScheme.error)
                        }
                        SettingsCard(stringResource(Res.string.desktop_output_title), stringResource(Res.string.desktop_output_hint)) {
                            PathField(outputDirectory, { outputDirectory = it }, stringResource(Res.string.desktop_output_directory), !busy) {
                                choosePath(effectiveOutput, true)?.let { outputDirectory = it.absolutePath }
                            }
                            Text(stringResource(Res.string.desktop_output_default), style = MaterialTheme.typography.bodySmall)
                            OutlinedTextField(
                                value = nameFormat, onValueChange = { nameFormat = it }, enabled = !busy,
                                label = { Text(stringResource(Res.string.desktop_filename)) }, singleLine = true,
                                placeholder = { Text(NameFormatter.DEFAULT_FORMAT) }, modifier = Modifier.fillMaxWidth(),
                            )
                            TextButton(onClick = { showNameHelp = !showNameHelp }, contentPadding = PaddingValues(0.dp)) {
                                Text(stringResource(Res.string.desktop_filename_help))
                            }
                            if (showNameHelp) Text(stringResource(Res.string.desktop_filename_tags), style = MaterialTheme.typography.bodySmall)
                        }
                        SettingsCard(stringResource(Res.string.desktop_runtime_title), stringResource(Res.string.desktop_runtime_hint)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = version, onValueChange = { version = it }, enabled = !busy,
                                    label = { Text(stringResource(Res.string.versions_requested)) }, singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                                OutlinedButton(enabled = !busy && workspace.isNotBlank(), onClick = { showVersions = true }) {
                                    Text(stringResource(Res.string.desktop_manage))
                                }
                            }
                            Text(stringResource(Res.string.desktop_version_hint), style = MaterialTheme.typography.bodySmall)
                            PathField(workspace, { workspace = it }, stringResource(Res.string.desktop_workspace), !busy) {
                                choosePath(workspace, true)?.let { workspace = it.absolutePath }
                            }
                            TextButton(enabled = !busy, onClick = { workspace = viewModel.dataDirectory.absolutePath }, contentPadding = PaddingValues(0.dp)) {
                                Text(stringResource(Res.string.desktop_workspace_reset))
                            }
                        }
                    }
                    OutlinedCard(Modifier.weight(1.15f).fillMaxHeight()) {
                        PatchOptionsContent(selectedPatches, { selectedPatches = it }, Modifier.fillMaxSize().padding(20.dp), !busy)
                    }
                }
                HorizontalDivider()
                if (busy) LinearProgressIndicator(progress = { viewModel.currentProgressFloat ?: 0f }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            when {
                                busy -> viewModel.currentProgress ?: stringResource(Res.string.desktop_working)
                                error != null -> stringResource(Res.string.desktop_failed)
                                result != null -> stringResource(Res.string.apk_file_exported)
                                inputValid -> stringResource(Res.string.desktop_ready_to_patch, selectedPatches.size)
                                else -> stringResource(Res.string.desktop_ready)
                            },
                            style = MaterialTheme.typography.titleSmall,
                            color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        )
                        if (error != null) {
                            Text(error?.message ?: "", style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        } else {
                            (result?.absolutePath ?: applicationInfo)?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
                        }
                    }
                    if (error != null) TextButton(onClick = { showErrorDetails = true }) { Text(stringResource(Res.string.desktop_details)) }
                    if (result != null && !busy) OutlinedButton(onClick = {
                        try { Desktop.getDesktop().open(result!!.parentFile) } catch (ex: Exception) { error = ex }
                    }) { Text(stringResource(Res.string.desktop_reveal)) }
                    Button(enabled = !busy && inputValid && workspace.isNotBlank() && version.isNotBlank(), onClick = ::patch) {
                        Text(stringResource(Res.string.desktop_patch))
                    }
                }
            }
        }
        if (showVersions) VersionManagerDialog(
            manager,
            onSelected = { version = it; showVersions = false },
            onDismiss = { showVersions = false },
            onBusyChange = onBusyChange,
        )
        if (showErrorDetails) error?.let { ex ->
            ErrorDialog(stringResource(Res.string.desktop_failed), ex.stackTraceToString()) { showErrorDetails = false }
        }
        overwrite?.let { (file, answer) ->
            AlertDialog(
                onDismissRequest = { overwrite = null; answer.complete(false) },
                title = { Text(stringResource(Res.string.desktop_overwrite_title)) },
                text = { Text(stringResource(Res.string.desktop_overwrite_message, file.absolutePath)) },
                confirmButton = { TextButton(onClick = { overwrite = null; answer.complete(true) }) { Text(stringResource(Res.string.desktop_replace)) } },
                dismissButton = { TextButton(onClick = { overwrite = null; answer.complete(false) }) { Text(stringResource(Res.string.action_cancel)) } },
            )
        }
    }
}

@Composable
private fun SettingsCard(title: String, description: String, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable
private fun PathField(value: String, onChange: (String) -> Unit, label: String, enabled: Boolean, isError: Boolean = false, browse: () -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, enabled = enabled, isError = isError,
        label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        trailingIcon = {
            IconButton(enabled = enabled, onClick = browse) {
                Icon(Icons.Default.FolderOpen, contentDescription = stringResource(Res.string.desktop_browse, label))
            }
        },
    )
}
