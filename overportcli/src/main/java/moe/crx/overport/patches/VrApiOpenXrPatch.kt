package moe.crx.overport.patches

import moe.crx.overport.patching.Patch
import java.io.File

internal const val VRAPI_OPENXR_RESOURCE = "/vrapi/arm64-v8a/libvrapi.bin"
private const val SUPPORTED_ABI = "arm64-v8a"
private object VrApiOpenXrResource

internal fun replaceVrApiWithOpenXr(
    workspace: File,
    loadPayload: () -> ByteArray?,
) {
    val abiDirectories = workspace.resolve("root/lib").listFiles()
        ?.filter { it.isDirectory }
        .orEmpty()
    val targets = abiDirectories.mapNotNull { abiDirectory ->
        abiDirectory.resolve("libvrapi.so").takeIf { it.isFile }
    }
    val unsupportedAbis = targets.map { it.parentFile.name }
        .filterNot { it == SUPPORTED_ABI }
        .sorted()
    val payload = loadPayload()?.takeIf { it.isNotEmpty() }

    val problems = buildList {
        if (targets.none { it.parentFile.name == SUPPORTED_ABI }) {
            add("No existing lib/$SUPPORTED_ABI/libvrapi.so was found.")
        }
        if (unsupportedAbis.isNotEmpty()) {
            add("VrApi libraries for unsupported ABIs were found: ${unsupportedAbis.joinToString()}.")
        }
        if (payload == null) {
            add("The experimental payload $VRAPI_OPENXR_RESOURCE is unavailable; use a distribution built with -PwithVrApi=true.")
        }
    }
    require(problems.isEmpty()) {
        "Cannot apply patch_vrapi_openxr: ${problems.joinToString(" ")}"
    }

    targets.single { it.parentFile.name == SUPPORTED_ABI }.writeBytes(payload!!)
}

val PATCH_VRAPI_OPENXR = Patch("patch_vrapi_openxr", false) {
    selectWorkspace {
        replaceVrApiWithOpenXr(file) {
            VrApiOpenXrResource::class.java.getResourceAsStream(VRAPI_OPENXR_RESOURCE)?.use { it.readBytes() }
        }
    }
}
