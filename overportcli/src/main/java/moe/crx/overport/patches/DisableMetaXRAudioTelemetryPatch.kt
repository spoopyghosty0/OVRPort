package moe.crx.overport.patches

import moe.crx.overport.patching.Patch

// Meta XR Audio's telemetry dispatcher dlopens libandroid.so, looks up
// JNI_GetCreatedJavaVMs and calls it. Android's ARM64 translation layer
// (libndk_translation) has no trampoline for that function, so on an x86_64
// emulator the call aborts the game with "Bad 'JNI_GetCreatedJavaVMs' call".
// When the dlopen returns null the dispatcher logs and carries on without
// telemetry, so replace the call with `mov x0, #0`. Audio itself is untouched.
// Pattern from libMetaXRAudioUnity.so as shipped in North Star 1.0.1; a
// library without it is left unchanged.
val PATCH_DISABLE_META_XR_AUDIO_TELEMETRY = Patch("patch_disable_meta_xr_audio_telemetry", false) {
    selectLibrary("libMetaXRAudioUnity.so") {
        replaceHex(
            "41 20 80 52 7F 7E 00 F9 7F 3A 01 B9 C0 02 00 AD C0 0A 80 3D ?? ?? ?? 94 60 7E 00 F9 60 02 00 B4",
            "41 20 80 52 7F 7E 00 F9 7F 3A 01 B9 C0 02 00 AD C0 0A 80 3D 00 00 80 D2 60 7E 00 F9 60 02 00 B4"
        )
    }
}
