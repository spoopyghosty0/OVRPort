package moe.crx.overport.patches

import com.reandroid.apk.ApkModule
import moe.crx.overport.patching.PatchExecutor
import moe.crx.overport.patching.PatchStore
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class VrApiOpenXrPatchTest {
    @TempDir
    lateinit var directory: File

    private val originalBytes = "original-vrapi".encodeToByteArray()
    private val replacementBytes by lazy {
        checkNotNull(javaClass.getResourceAsStream(VRAPI_OPENXR_RESOURCE)).use { it.readBytes() }
    }

    private fun addVrApi(abi: String, bytes: ByteArray = originalBytes): File {
        return directory.resolve("root/lib/$abi/libvrapi.so").apply {
            parentFile.mkdirs()
            writeBytes(bytes)
        }
    }

    private fun applyPatch() {
        val executor = PatchExecutor(directory.resolve("libraries"), directory, ApkModule())
        PATCH_VRAPI_OPENXR.executor(executor, emptyList())
    }

    @Test
    fun `default patch selection remains unchanged and leaves VrApi alone`() {
        val vrApi = addVrApi("arm64-v8a")
        val libraries = directory.resolve("libraries/lib/arm64-v8a").apply { mkdirs() }
        libraries.resolve("libOVRPlugin.so").writeText("ovrplugin")
        libraries.resolve("libopenxr_loader.so").writeText("openxr-loader")
        val executor = PatchExecutor(directory.resolve("libraries"), directory, ApkModule())
        val libraryPatches = setOf(
            PATCH_COPY_LIBRARIES.name,
            PATCH_COPY_OVRPLUGIN_VRAPI.name,
            PATCH_VRAPI_OPENXR.name,
        )
        PatchStore.select(PatchStore.recommended().map { it.name })
            .filter { it.name in libraryPatches }
            .forEach { it.executor(executor, emptyList()) }

        assertContentEquals(originalBytes, vrApi.readBytes())
    }

    @Test
    fun `opt-in patch replaces existing arm64 VrApi with packaged payload`() {
        val vrApi = addVrApi("arm64-v8a")

        applyPatch()

        assertContentEquals(replacementBytes, vrApi.readBytes())
    }

    @Test
    fun `unsupported participating ABI fails without replacing any library`() {
        val arm64VrApi = addVrApi("arm64-v8a")
        val arm32VrApi = addVrApi("armeabi-v7a", "original-arm32-vrapi".encodeToByteArray())
        val originalArm32Bytes = arm32VrApi.readBytes()

        val failure = assertFailsWith<IllegalArgumentException> { applyPatch() }

        assertContains(failure.message.orEmpty(), "unsupported ABIs")
        assertContains(failure.message.orEmpty(), "armeabi-v7a")
        assertContentEquals(originalBytes, arm64VrApi.readBytes())
        assertContentEquals(originalArm32Bytes, arm32VrApi.readBytes())
    }

    @Test
    fun `missing packaged payload fails without replacing VrApi`() {
        val vrApi = addVrApi("arm64-v8a")

        val failure = assertFailsWith<IllegalArgumentException> {
            replaceVrApiWithOpenXr(directory) { null }
        }

        assertContains(failure.message.orEmpty(), VRAPI_OPENXR_RESOURCE)
        assertContains(failure.message.orEmpty(), "-PwithVrApi=true")
        assertContentEquals(originalBytes, vrApi.readBytes())
    }

    @Test
    fun `conflicting VrApi patches are rejected before either can mutate files`() {
        val vrApi = addVrApi("arm64-v8a")

        for (selection in listOf(
            listOf(PATCH_VRAPI_OPENXR.name, PATCH_REMOVE_VRAPI.name),
            listOf(PATCH_REMOVE_VRAPI.name, PATCH_VRAPI_OPENXR.name),
        )) {
            val failure = assertFailsWith<IllegalArgumentException> {
                PatchStore.select(selection)
            }
            assertContains(failure.message.orEmpty(), PATCH_VRAPI_OPENXR.name)
            assertContains(failure.message.orEmpty(), PATCH_REMOVE_VRAPI.name)
            assertContentEquals(originalBytes, vrApi.readBytes())
        }
    }
}
