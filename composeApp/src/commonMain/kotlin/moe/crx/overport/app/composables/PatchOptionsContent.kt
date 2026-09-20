package moe.crx.overport.app.composables

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import moe.crx.overport.app.util.PatchLocalizer.localizedPatch
import moe.crx.overport.patches.PATCH_REMOVE_VRAPI
import moe.crx.overport.patches.PATCH_REPLACE_ICON_LABEL
import moe.crx.overport.patches.PATCH_VRAPI_OPENXR
import moe.crx.overport.patching.PatchStore
import org.jetbrains.compose.resources.stringResource
import overportapp.composeapp.generated.resources.*

@Composable
fun PatchOptionsContent(
    selectedPatches: Map<String, List<String>>,
    onSelectionChange: (Map<String, List<String>>) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val patches = remember { PatchStore.all() }
    val hasVrApiAdapter = remember { PatchStore.javaClass.getResource("/vrapi/arm64-v8a/libvrapi.bin") != null }
    var query by remember { mutableStateOf("") }
    val labels = patches.associate { it.name to localizedPatch(it) }
    val filtered = patches.filter {
        it.name.contains(query, ignoreCase = true) || labels.getValue(it.name).contains(query, ignoreCase = true)
    }
    val recommended = patches.filter { it.isRecommended }.map { it.name }.toSet()
    fun select(name: String, checked: Boolean) {
        val selection = selectedPatches.toMutableMap()
        if (checked) {
            selection[name] = emptyList()
            if (name == PATCH_REMOVE_VRAPI.name) selection.remove(PATCH_VRAPI_OPENXR.name)
            if (name == PATCH_VRAPI_OPENXR.name) selection.remove(PATCH_REMOVE_VRAPI.name)
        } else {
            selection.remove(name)
        }
        // Dependency-sensitive patches must execute in registry order, not click order.
        onSelectionChange(patches.filter { it.name in selection }.associate { it.name to selection.getValue(it.name) })
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(Res.string.options_patches), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Text(stringResource(Res.string.options_selected, selectedPatches.size, patches.size), style = MaterialTheme.typography.labelLarge)
        }
        Text(
            stringResource(if (selectedPatches.keys == recommended) Res.string.options_recommended_active else Res.string.options_custom_active),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = enabled, onClick = {
                onSelectionChange(patches.filter { it.isRecommended }.associate { it.name to emptyList() })
            }) { Text(stringResource(Res.string.options_recommended)) }
            TextButton(enabled = enabled && selectedPatches.isNotEmpty(), onClick = { onSelectionChange(emptyMap()) }) {
                Text(stringResource(Res.string.options_clear))
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(Res.string.options_search)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        LazyColumn(modifier = Modifier.weight(1f)) {
            if (filtered.isEmpty()) item { Text(stringResource(Res.string.options_no_matches), Modifier.padding(16.dp)) }
            items(filtered, key = { it.name }) { patch ->
                val selected = patch.name in selectedPatches
                val available = patch != PATCH_VRAPI_OPENXR || hasVrApiAdapter
                ListItem(
                    modifier = Modifier.toggleable(
                        value = selected, enabled = enabled && available, role = Role.Checkbox,
                        onValueChange = { select(patch.name, it) },
                    ),
                    headlineContent = { Text(labels.getValue(patch.name)) },
                    supportingContent = {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(patch.name, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                            if (!patch.isRecommended) {
                                Text(stringResource(Res.string.options_optional), color = MaterialTheme.colorScheme.tertiary)
                            }
                            if (patch == PATCH_VRAPI_OPENXR) {
                                Text(stringResource(if (hasVrApiAdapter) Res.string.options_vrapi_experimental else Res.string.options_vrapi_unavailable))
                            }
                            if (patch == PATCH_VRAPI_OPENXR || patch == PATCH_REMOVE_VRAPI) {
                                Text(stringResource(Res.string.options_vrapi_conflict))
                            }
                        }
                    },
                    trailingContent = { Checkbox(checked = selected, onCheckedChange = null, enabled = enabled && available) },
                )
                if (patch == PATCH_REPLACE_ICON_LABEL && selected) {
                    Row(Modifier.padding(start = 16.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val useCover = selectedPatches[patch.name]?.firstOrNull() == "cover"
                        listOf("icon", "cover").forEach { argument ->
                            FilterChip(
                                selected = (argument == "cover") == useCover,
                                enabled = enabled,
                                onClick = { onSelectionChange(selectedPatches + (patch.name to listOf(argument))) },
                                label = { Text(stringResource(if (argument == "cover") Res.string.replace_icon_with_cover else Res.string.replace_icon_with_icon)) },
                            )
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            }
        }
    }
}
