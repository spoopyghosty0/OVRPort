package moe.crx.overport.commands

import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import moe.crx.overport.cli.CliCommand
import moe.crx.overport.patching.PatchStore

object ListPatchesCommand : CliCommand() {
    override fun name() = "patches"
    override fun description() = "Print list of available patches."

    override fun printHelp(): Boolean {
        println("usage: overport patches [--json]")
        println()
        println("--json")
        println("Print available patches as a single-line JSON array of objects")
        println("with \"name\" and \"recommended\" fields instead of human-readable text.")
        return true
    }

    override suspend fun execute(args: List<String>): Boolean {
        if (args.isEmpty()) {
            println("Available patches:")

            PatchStore.all().forEach {
                print("- ")
                println(it.name)
            }

            return true
        }

        if (args != listOf("--json")) {
            val bad = args.firstOrNull { it.substringBefore('=') != "--json" } ?: args.first()
            System.err.println("Unknown argument: ${bad.substringBefore('=')}")
            return false
        }

        val catalog = buildJsonArray {
            PatchStore.all().forEach { patch ->
                addJsonObject {
                    put("name", patch.name)
                    put("recommended", patch.isRecommended)
                }
            }
        }
        println(catalog.toString())
        return true
    }
}
