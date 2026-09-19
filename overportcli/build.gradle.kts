import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.tasks.PathSensitivity

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization") version "2.2.21"
    application
    id("com.gradleup.shadow") version "9.2.2"
}

val appVersion = providers.gradleProperty("appVersion").map { value ->
    require(value.matches(Regex("""\d+\.\d+\.\d+"""))) {
        "appVersion must be a numeric major.minor.patch version (received '$value')"
    }
    value
}

group = "moe.crx"
version = appVersion.get()

val generatedVersionDirectory = layout.buildDirectory.dir("generated/sources/appVersion/main/kotlin")
val generatedVersionFile = generatedVersionDirectory.map {
    it.file("moe/crx/overport/versions/GeneratedAppVersion.kt")
}
val generateAppVersion = tasks.register("generateAppVersion") {
    inputs.property("appVersion", appVersion)
    outputs.file(generatedVersionFile)

    doLast {
        val output = generatedVersionFile.get().asFile
        val source = """
            package moe.crx.overport.versions

            internal const val GENERATED_APP_VERSION = "${appVersion.get()}"
        """.trimIndent() + "\n"

        output.parentFile.mkdirs()
        if (!output.isFile || output.readText() != source) {
            output.writeText(source)
        }
    }
}

kotlin {
    sourceSets.main {
        kotlin.srcDir(generatedVersionDirectory)
    }
}

tasks.named("compileKotlin") {
    dependsOn(generateAppVersion)
}

dependencies {
    implementation(libs.android.tools.build)
    implementation(libs.bouncycastle.pkix)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(fileTree("libs"))
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass = "moe.crx.overport.cli.CliMainKt"
}

val withVrApi = providers.gradleProperty("withVrApi")
    .map { value ->
        value.toBooleanStrictOrNull()
            ?: throw GradleException("withVrApi must be either true or false, but was '$value'.")
    }
    .orElse(false)

if (withVrApi.get()) {
    val payloadPath = "vrapi/arm64-v8a/libvrapi.bin"
    val configuredResourceRoot = providers.gradleProperty("vrApiResourceRoot").orNull
    val resourceRoot = configuredResourceRoot
        ?.let(rootProject::file)
        ?: layout.buildDirectory.dir("generated/vrapiResources/main").get().asFile
    val payload = resourceRoot.resolve(payloadPath)
    val resourcePaths = listOf(
        payloadPath,
        "vrapi/licenses/OPENXR-SDK.txt",
        "vrapi/licenses/ANDROID-NDK.txt",
    )


    val prepareVrApiResource = if (configuredResourceRoot != null) {
        tasks.register("prepareVrApiResource") {
            description = "Validates the prebuilt experimental VrApi-to-OpenXR payload."
            inputs.files(resourcePaths.map(resourceRoot::resolve)).optional()
            doLast {
                for (path in resourcePaths) {
                    val resource = resourceRoot.resolve(path)
                    if (!resource.isFile || resource.length() == 0L) {
                        throw GradleException(
                            "VrApi OpenXR resource is missing or empty at ${resource.absolutePath}. " +
                                "vrApiResourceRoot must contain the complete output of native/vrapi/build.py."
                        )
                    }
                }
            }
        }
    } else {
        val nativeSourceDirectory = rootProject.layout.projectDirectory.dir("native/vrapi")
        val builder = nativeSourceDirectory.file("build.py")
        val pythonExecutable = providers.gradleProperty("pythonExecutable")
            .orElse(providers.environmentVariable("PYTHON"))
            .orElse(if (System.getProperty("os.name").startsWith("Windows", true)) "python" else "python3")

        tasks.register<Exec>("prepareVrApiResource") {
            description = "Builds the experimental VrApi-to-OpenXR payload."
            inputs.files(
                rootProject.fileTree(nativeSourceDirectory) {
                    exclude("build/**", "out/**", "__pycache__/**", "*.pyc")
                }
            ).withPathSensitivity(PathSensitivity.RELATIVE)
            inputs.property("pythonExecutable", pythonExecutable)
            inputs.property("androidNdkHome", providers.environmentVariable("ANDROID_NDK_HOME").orElse(""))
            inputs.property("androidHome", providers.environmentVariable("ANDROID_HOME").orElse(""))
            inputs.property("androidSdkRoot", providers.environmentVariable("ANDROID_SDK_ROOT").orElse(""))
            outputs.files(resourcePaths.map(resourceRoot::resolve))

            doFirst {
                if (!builder.asFile.isFile) {
                    throw GradleException("VrApi OpenXR builder is unavailable at ${builder.asFile.absolutePath}.")
                }
                resourceRoot.mkdirs()
            }
            commandLine(
                pythonExecutable.get(),
                builder.asFile.absolutePath,
                "--output",
                resourceRoot.absolutePath,
            )
            doLast {
                if (!payload.isFile || payload.length() == 0L) {
                    throw GradleException(
                        "VrApi OpenXR builder did not produce $payloadPath under ${resourceRoot.absolutePath}."
                    )
                }
            }
        }
    }

    // Keep the optional payload out of processResources' persistent output so
    // an experimental build cannot leak it into a later stable build.
    sourceSets.main {
        output.dir(mapOf("builtBy" to prepareVrApiResource), resourceRoot)
    }
}

tasks.named<ShadowJar>("shadowJar") {
    mergeServiceFiles()
}