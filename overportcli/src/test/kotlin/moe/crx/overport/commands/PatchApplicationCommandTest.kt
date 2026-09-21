package moe.crx.overport.commands

import moe.crx.overport.patching.PatchStore
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PatchApplicationCommandTest {
    @Test
    fun `recommended patches precede extras`() {
        val selected = PatchApplicationCommand.mergePatchSelections(
            patches = null,
            extraPatches = linkedMapOf("patch_vrapi_openxr" to emptyList()),
        )

        assertEquals(
            PatchStore.recommended().map { it.name } + "patch_vrapi_openxr",
            selected.keys.toList(),
        )
    }

    @Test
    fun `explicit patches precede extras and preserve arguments`() {
        val selected = PatchApplicationCommand.mergePatchSelections(
            patches = linkedMapOf("patch_fix_min_android_sdk" to listOf("29")),
            extraPatches = linkedMapOf("patch_vrapi_openxr" to emptyList()),
        )

        assertEquals(listOf("patch_fix_min_android_sdk", "patch_vrapi_openxr"), selected.keys.toList())
        assertEquals(listOf("29"), selected.getValue("patch_fix_min_android_sdk"))
    }

    @Test
    fun `patch in base and extras is rejected`() {
        val error = assertFailsWith<IllegalArgumentException> {
            PatchApplicationCommand.mergePatchSelections(
                patches = mapOf("patch_vrapi_openxr" to emptyList()),
                extraPatches = mapOf("patch_vrapi_openxr" to emptyList()),
            )
        }

        assertTrue(error.message.orEmpty().contains("both --patches and --extra-patches"))
    }

    @Test
    fun `unknown patch is rejected`() {
        val error = assertFailsWith<IllegalArgumentException> {
            PatchApplicationCommand.mergePatchSelections(
                patches = mapOf("patch_does_not_exist" to emptyList()),
                extraPatches = emptyMap(),
            )
        }

        assertEquals("Unknown patch: patch_does_not_exist", error.message)
    }

    @Test
    fun `conflicting patches are rejected`() {
        val error = assertFailsWith<IllegalArgumentException> {
            PatchApplicationCommand.mergePatchSelections(
                patches = mapOf("patch_remove_vrapi" to emptyList()),
                extraPatches = mapOf("patch_vrapi_openxr" to emptyList()),
            )
        }

        assertTrue(error.message.orEmpty().contains("conflicts with"))
    }

    @Test
    fun `help documents additive patch option`() {
        val original = System.out
        val output = ByteArrayOutputStream()
        try {
            System.setOut(PrintStream(output))
            PatchApplicationCommand.printHelp()
        } finally {
            System.setOut(original)
        }

        assertTrue(output.toString().contains("--extra-patches=<value>"))
    }

    @Test
    fun `unknown command option is rejected`() {
        val error = assertFailsWith<IllegalArgumentException> {
            PatchApplicationCommand.scanArguments(listOf("--extra-patch=patch_vrapi_openxr"))
        }

        assertEquals("Unknown argument: --extra-patch", error.message)
    }
}
