package moe.crx.overport.patches

import java.io.File

private const val PLATFORM_RESOURCE = "/platform/arm64-v8a/libovrplatformcompat.bin"
private object PlatformMessageResource

internal fun ensurePlatformMessageCompatibility(workspace: File) {
    val libraries = workspace.resolve("root/lib/arm64-v8a")
    val loader = libraries.resolve("libovrplatformloader.so")
    if (!loader.isFile) return

    val original = loader.readBytes()
    val patched = patchPlatformLoader(original)
    if (patched.needsCompanion) {
        val companion = libraries.resolve("libovrplatformcompat.so")
        val payload = PlatformMessageResource::class.java.getResourceAsStream(PLATFORM_RESOURCE)
            ?.use { it.readBytes() }
        require(payload != null && payload.isNotEmpty()) {
            "Platform message-type compatibility resource is missing or empty: $PLATFORM_RESOURCE"
        }
        companion.writeBytes(payload)
    }
    if (patched.bytes !== original) loader.writeBytes(patched.bytes)
}
