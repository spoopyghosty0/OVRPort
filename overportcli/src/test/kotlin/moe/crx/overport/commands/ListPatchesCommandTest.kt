package moe.crx.overport.commands

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import moe.crx.overport.patching.PatchStore

class ListPatchesCommandTest {
    private fun captureStdout(block: () -> Boolean): Pair<Boolean, String> {
        val original = System.out
        val output = ByteArrayOutputStream()
        try {
            System.setOut(PrintStream(output))
            val result = block()
            System.out.flush()
            return result to output.toString()
        } finally {
            System.setOut(original)
        }
    }

    @Test
    fun `json lists every patch with recommended flags`() {
        val (result, stdout) = captureStdout {
            runBlocking { ListPatchesCommand.execute(listOf("--json")) }
        }

        assertTrue(result)
        val parsed = Json.parseToJsonElement(stdout.trim())
        assertTrue(parsed is JsonArray)
        val expected = PatchStore.all()
        assertEquals(expected.size, parsed.size)
        assertEquals(expected.map { it.name }, parsed.map { it.jsonObject.getValue("name").jsonPrimitive.content })
        parsed.forEachIndexed { index, element ->
            assertTrue(element is JsonObject)
            assertEquals(setOf("name", "recommended"), element.keys)
            assertEquals(expected[index].isRecommended, element.getValue("recommended").jsonPrimitive.boolean)
        }
    }

    @Test
    fun `json is a single clean line`() {
        val (_, stdout) = captureStdout {
            runBlocking { ListPatchesCommand.execute(listOf("--json")) }
        }

        assertEquals(1, stdout.lines().filter { it.isNotBlank() }.size)
        // Must parse without surrounding human-readable text.
        Json.parseToJsonElement(stdout.trim())
    }

    @Test
    fun `human output is unchanged without arguments`() {
        val (result, stdout) = captureStdout {
            runBlocking { ListPatchesCommand.execute(emptyList()) }
        }

        assertTrue(result)
        val lines = stdout.lines()
        assertEquals("Available patches:", lines.first())
        PatchStore.all().forEach { patch ->
            assertTrue(lines.contains("- ${patch.name}"), "missing human-readable entry for ${patch.name}")
        }
    }

    @Test
    fun `unknown option is rejected`() {
        val (result, _) = captureStdout {
            runBlocking { ListPatchesCommand.execute(listOf("--bogus")) }
        }

        assertFalse(result)
    }
}
