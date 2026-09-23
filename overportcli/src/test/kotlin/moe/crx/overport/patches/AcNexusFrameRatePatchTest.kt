package moe.crx.overport.patches

import moe.crx.overport.patching.PatchStore
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AcNexusFrameRatePatchTest {
    @TempDir lateinit var directory: File

    @Test
    fun `both frame rate modes are optional and mutually exclusive`() {
        assertTrue(PatchStore.all().containsAll(listOf(PATCH_AC_NEXUS_NO_APPSW_72, PATCH_AC_NEXUS_NO_APPSW_90)))
        assertTrue(PatchStore.recommended().none { it == PATCH_AC_NEXUS_NO_APPSW_72 || it == PATCH_AC_NEXUS_NO_APPSW_90 })
        assertFailsWith<IllegalArgumentException> {
            PatchStore.select(listOf(PATCH_AC_NEXUS_NO_APPSW_72.name, PATCH_AC_NEXUS_NO_APPSW_90.name))
        }
    }

    @Test
    fun `unknown game library is rejected without modification`() {
        val library = directory.resolve("libil2cpp.so")
        library.writeBytes(ByteArray(64))
        assertFailsWith<IllegalArgumentException> { patchAcNexusFrameRate(library, 90) }
        assertTrue(library.readBytes().all { it == 0.toByte() })
    }
}
