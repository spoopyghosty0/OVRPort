package moe.crx.overport.app.composables

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import moe.crx.overport.patches.PATCH_REPLACE_ICON_LABEL
import moe.crx.overport.patching.PatchStore
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview
import overportapp.composeapp.generated.resources.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
@Preview
fun ApplicationInfoContent(
    applicationName: String,
    applicationPackage: String,
    applicationVersion: String,
    applicationIcon: ImageBitmap? = null,
    onCancel: () -> Unit = {},
    onConfirm: (Map<String, List<String>>) -> Unit = {}
) {
    var enabledPatches by remember(applicationPackage) {
        mutableStateOf(PatchStore.recommended().associate { it.name to emptyList<String>() })
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ApplicationInfo(
                applicationName,
                applicationPackage,
                applicationVersion,
                applicationIcon,
                !enabledPatches.containsKey(PATCH_REPLACE_ICON_LABEL.name),
                enabledPatches[PATCH_REPLACE_ICON_LABEL.name]?.firstOrNull() == "cover",
            )
        }

        PatchOptionsContent(
            selectedPatches = enabledPatches,
            onSelectionChange = { enabledPatches = it },
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                modifier = Modifier.weight(1f),
                onClick = onCancel
            ) {
                Text(stringResource(Res.string.action_cancel))
            }
            Button(
                modifier = Modifier.weight(1f),
                onClick = { onConfirm(enabledPatches) }
            ) {
                Text(stringResource(Res.string.action_confirm))
            }
        }
    }
}