package moe.crx.overport.commands

import kotlinx.coroutines.cancelAndJoin
import moe.crx.overport.cli.CliCommand
import moe.crx.overport.patches.PLATFORM_ICON_RESIZER
import moe.crx.overport.patching.PatchStore
import moe.crx.overport.patching.PatcherContext
import moe.crx.overport.utils.DesktopUtil.defaultWorkspace
import moe.crx.overport.utils.ImageIOIconResizer
import moe.crx.overport.utils.NameFormatter.DEFAULT_FORMAT
import moe.crx.overport.utils.PrintUtil.drawProgressBar
import moe.crx.overport.utils.PrintUtil.drawSpinner
import moe.crx.overport.utils.PrintUtil.updateLine
import moe.crx.overport.versions.VersionManager
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object PatchApplicationCommand : CliCommand() {
    override fun name() = "patch"
    override fun description() = "Patch provided APK file."

    override fun printHelp(): Boolean {
        println("usage: overport patch --input=<path> [--output=<path>]")
        println("    [--output-name=<format>] [--workspace=<path>] [--patches=<value>]")
        println("    [--extra-patches=<value>] [--version=<value>]")
        println()
        println("--input=<path>")
        println("Specifies input APK file to patch.")
        println()
        println("--output=<path>")
        println("Specifies output APK file directory path.")
        println("If not specified, uses same path as input file.")
        println("If specified path is a file, uses it's parent directory.")
        println()
        println("--output-name=<format>")
        println("Specifies output APK file name format string.")
        println("If not specified, uses name of input file but with \"output\" suffix appended to it.")
        println("See more information about name format below.")
        println()
        println("--workspace=<path>")
        println("Specifies workspace directory path.")
        println("If not specified, uses default one in user's home directory.")
        println()
        println("--patches=<value>")
        println("Specifies patches to apply. Value should be a semicolon-separated list of patch names.")
        println("If not specified, uses every recommended patch available. Use \"overport patches\" to see available patches.")
        println("You can also specify arguments for a specific patch using <patch name>=<comma-separated arguments>.")
        println()
        println("--extra-patches=<value>")
        println("Adds patches after the defaults or an explicit --patches selection.")
        println("Uses the same semicolon-separated patch and comma-separated argument syntax as --patches.")
        println("A patch cannot be named in both --patches and --extra-patches.")
        println()
        println("--version=<value>")
        println("Specifies which overport version to use for patching.")
        println("Value also could be \"experimental\" or \"latest\".")
        println("If not specified, uses latest one. Use \"overport install\" to see available versions.")
        println()
        println("About file name format:")
        println("File name format allows to specify pattern for automatic file name generation.")
        println("Default file name format is $DEFAULT_FORMAT.")
        println("Available format tags:")
        println("{package} - application package name")
        println("{label} - application label")
        println("{code} - application version code")
        println("{version} - application version name")
        println("{overport} - overport version used for patching")
        println("{filename} - input file name without extension")
        println("{ext} - input file extension")
        println("{filenameext} - input file name with extension")
        return true
    }

    internal data class ParsedArguments(
        var inputFile: String? = null,
        var outputFile: String? = null,
        var workspace: String? = null,
        var patches: Map<String, List<String>>? = null,
        var extraPatches: Map<String, List<String>> = emptyMap(),
        var nameFormat: String? = null,
        var overportVersion: String? = null,
    )

    internal fun parsePatchList(value: String): Map<String, List<String>> {
        require(value.isNotBlank()) { "Patch list cannot be empty." }
        val parsed = linkedMapOf<String, List<String>>()
        value.split(';').forEach { entry ->
            val trimmed = entry.trim()
            val name = trimmed.substringBefore('=').trim()
            require(name.isNotEmpty()) { "Patch name cannot be empty." }
            require(name !in parsed) { "Patch specified more than once: $name" }
            val arguments = if ('=' in trimmed) {
                trimmed.substringAfter('=').split(',').map(String::trim)
            } else {
                emptyList()
            }
            parsed[name] = arguments
        }
        return parsed
    }

    internal fun mergePatchSelections(
        patches: Map<String, List<String>>?,
        extraPatches: Map<String, List<String>>,
    ): Map<String, List<String>> {
        val base = patches ?: PatchStore.recommended().associateTo(linkedMapOf()) { it.name to emptyList() }
        val duplicate = base.keys.firstOrNull(extraPatches::containsKey)
        require(duplicate == null) { "Patch specified in both --patches and --extra-patches: $duplicate" }
        return linkedMapOf<String, List<String>>().apply {
            putAll(base)
            putAll(extraPatches)
            PatchStore.select(keys)
        }
    }

    internal fun scanArguments(args: List<String>): ParsedArguments {
        val parsed = ParsedArguments()
        val seen = mutableSetOf<String>()

        args.forEach { argument ->
            val key = argument.substringBefore('=')
            require(argument.startsWith("--") && '=' in argument) { "Invalid argument: $argument" }
            require(seen.add(key)) { "Argument specified more than once: $key" }
            val value = argument.substringAfter('=')
            when (key) {
                "--input" -> parsed.inputFile = value
                "--output" -> parsed.outputFile = value
                "--workspace" -> parsed.workspace = value
                "--patches" -> parsed.patches = parsePatchList(value)
                "--extra-patches" -> parsed.extraPatches = parsePatchList(value)
                "--output-name" -> parsed.nameFormat = value
                "--version" -> parsed.overportVersion = value
                else -> throw IllegalArgumentException("Unknown argument: $key")
            }
        }

        return parsed
    }

    override suspend fun execute(args: List<String>): Boolean {
        val parsed = scanArguments(args)
        val patches = mergePatchSelections(parsed.patches, parsed.extraPatches)

        val inputFile = parsed.inputFile?.let { File(it) } ?: return false

        require(inputFile.isFile) { "Input APK is not a file: ${inputFile.path}" }

        var outputDir = parsed.outputFile?.let { File(it) } ?: inputFile.parentFile

        if (outputDir.isFile) {
            outputDir = outputDir.parentFile
        }

        val workspace = parsed.workspace?.let { File(it) } ?: defaultWorkspace()


        val nameFormat = parsed.nameFormat ?: DEFAULT_FORMAT

        val version = parsed.overportVersion ?: "latest"

        println("overport cli utility, version ${VersionManager.VERSION}")
        println("Input APK: ${inputFile.absolutePath}")
        println("Output directory: ${outputDir.absolutePath}")
        println("Workspace directory: ${workspace.absolutePath}")

        PLATFORM_ICON_RESIZER = ImageIOIconResizer
        val patcher = PatcherContext(version, workspace, inputFile.name, nameFormat)

        val waitingForLibraries = updateLine {
            drawSpinner()
            print("Preparing libraries...")
        }
        patcher.checkout()
        waitingForLibraries.cancelAndJoin()

        println("Using overport version: ${patcher.overportVersion()}")

        val waitingForDecompilation = updateLine {
            drawSpinner()
            print("Decompiling APK...")
        }
        FileInputStream(inputFile).use { fis ->
            patcher.prepare(fis)
        }
        waitingForDecompilation.cancelAndJoin()

        println("Decompiled app: ${patcher.appName()}, package ${patcher.appPackage()}, version ${patcher.appVersionName()}")

        val totalPatches = patches.size
        var appliedPatches = 0
        var currentPatch = ""
        val waitingForPatching = updateLine {
            drawSpinner()
            drawProgressBar(appliedPatches.toLong(), totalPatches.toLong())
            print("Applying patch $currentPatch... ")
            print("($appliedPatches / $totalPatches)")
        }
        patcher.patch(patches) { index, patch ->
            appliedPatches = index
            currentPatch = patch.name
        }
        waitingForPatching.cancelAndJoin()

        outputDir.mkdirs()

        val outputFile = File(outputDir, patcher.outputFileName())

        println("Output file: ${outputFile.absolutePath}")

        val waitingForCompilation = updateLine {
            drawSpinner()
            print("Compiling APK... ")
        }
        FileOutputStream(outputFile).use { fos ->
            patcher.export(fos)
        }
        waitingForCompilation.cancelAndJoin()

        val waitingForCleaning = updateLine {
            drawSpinner()
            print("Cleaning up... ")
        }
        patcher.cleanup()
        waitingForCleaning.cancelAndJoin()

        println("Patching successful.")

        return true
    }
}