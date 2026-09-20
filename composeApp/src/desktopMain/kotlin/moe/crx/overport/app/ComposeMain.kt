package moe.crx.overport.app

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.lifecycle.viewmodel.compose.viewModel
import com.formdev.flatlaf.util.SystemFileChooser
import moe.crx.overport.app.model.MainViewModel
import moe.crx.overport.app.theme.OverportTheme
import moe.crx.overport.utils.DesktopUtil.defaultWorkspace
import moe.crx.overport.utils.ImageIOIconResizer
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import overportapp.composeapp.generated.resources.*
import java.awt.Dimension
import java.io.File

fun main(args: Array<String>) = application {
    var busy by remember { mutableStateOf(false) }
    var closeWarning by remember { mutableStateOf(false) }
    Window(
        onCloseRequest = { if (busy) closeWarning = true else exitApplication() },
        title = "OVRPort",
        icon = painterResource(Res.drawable.window_icon),
        state = rememberWindowState(width = 1180.dp, height = 860.dp),
    ) {
        LaunchedEffect(Unit) { window.minimumSize = Dimension(900, 640) }
        val dataDir = remember { defaultWorkspace() }
        val viewModel: MainViewModel = viewModel { MainViewModel(dataDir, ImageIOIconResizer) }
        val selectFileTitle = stringResource(Res.string.select_a_file)
        DesktopPatcherScreen(
            viewModel = viewModel,
            initialInput = args.firstOrNull().orEmpty(),
            choosePath = { current, directory ->
                val path = File(current).absoluteFile
                val chooser = SystemFileChooser(selectFileTitle).apply {
                    fileSelectionMode = if (directory) SystemFileChooser.DIRECTORIES_ONLY else SystemFileChooser.FILES_ONLY
                    currentDirectory = if (path.isDirectory) path else path.parentFile
                    if (!directory && path.isFile) selectedFile = path
                }
                if (chooser.showOpenDialog(window) == SystemFileChooser.APPROVE_OPTION) chooser.selectedFile else null
            },
            onBusyChange = { busy = it },
        )
        if (closeWarning) OverportTheme(darkTheme = true) {
            AlertDialog(
                onDismissRequest = { closeWarning = false },
                title = { Text("OVRPort") },
                text = { Text(stringResource(Res.string.desktop_close_busy)) },
                confirmButton = { TextButton(onClick = { closeWarning = false }) { Text(stringResource(Res.string.versions_close)) } },
            )
        }
    }
}
