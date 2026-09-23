package moe.crx.overport.patches

import moe.crx.overport.patching.Patch
import moe.crx.overport.patching.PatchExecutor
import java.io.File
import java.security.MessageDigest

private const val AC_NEXUS_PACKAGE = "com.Ubisoft.ACNexusVR"
private const val ORIGINAL_SHA256 = "5fa8036b2e6469298c0b3f024a8d89b9c5640233544ac23cd9b46278e5e9baf8"
private const val BRANCH_OFFSET = 0x4770b90
private const val FREQUENCY_OFFSET = 0x4770bcc
private val ORIGINAL_BRANCH = byteArrayOf(0xc9.toByte(), 0x01, 0x00, 0xb5.toByte())
private val NO_APP_SPACE_WARP_BRANCH = byteArrayOf(0x0e, 0x00, 0x00, 0x14)
private val ORIGINAL_72_HZ = byteArrayOf(0x08, 0x52, 0xa8.toByte(), 0x52)
private val TARGET_90_HZ = byteArrayOf(0x88.toByte(), 0x56, 0xa8.toByte(), 0x52)

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal fun patchAcNexusFrameRate(library: File, targetHz: Int) {
    require(targetHz == 72 || targetHz == 90)
    require(library.isFile) { "AC Nexus arm64 libil2cpp.so is missing." }
    require(sha256(library) == ORIGINAL_SHA256) {
        "Unsupported AC Nexus libil2cpp.so. This patch supports only versionCode 207706 (MAIN.450412.207706.final)."
    }
    // Keep these checks alongside the hash so offsets can never silently patch different code.
    java.io.RandomAccessFile(library, "rw").use { output ->
        fun replace(offset: Int, expected: ByteArray, replacement: ByteArray) {
            output.seek(offset.toLong())
            val found = ByteArray(expected.size)
            output.readFully(found)
            check(found.contentEquals(expected)) { "Unexpected AC Nexus instruction at 0x${offset.toString(16)}." }
            output.seek(offset.toLong())
            output.write(replacement)
        }
        // Force AppSWManager's normal-rendering branch, which calls SetSpaceWarp(false).
        replace(BRANCH_OFFSET, ORIGINAL_BRANCH, NO_APP_SPACE_WARP_BRANCH)
        if (targetHz == 90) {
            // The normal branch requests 72 Hz by default; set its float constant to 90 Hz.
            replace(FREQUENCY_OFFSET, ORIGINAL_72_HZ, TARGET_90_HZ)
        }
    }
}

private fun PatchExecutor.patchAcNexus(targetHz: Int, arguments: List<String>) {
    require(arguments.isEmpty()) { "AC Nexus frame-rate patches take no arguments." }
    require(applicationPackage() == AC_NEXUS_PACKAGE) { "This patch applies only to AC Nexus ($AC_NEXUS_PACKAGE)." }
    selectWorkspace {
        patchAcNexusFrameRate(file.resolve("root/lib/arm64-v8a/libil2cpp.so"), targetHz)
    }
}

val PATCH_AC_NEXUS_NO_APPSW_72 = Patch("patch_ac_nexus_no_appsw_72", false) { arguments ->
    patchAcNexus(72, arguments)
}

val PATCH_AC_NEXUS_NO_APPSW_90 = Patch("patch_ac_nexus_no_appsw_90", false) { arguments ->
    patchAcNexus(90, arguments)
}
