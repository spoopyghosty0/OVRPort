package moe.crx.overport.patching

import com.android.apksig.ApkVerifier
import com.reandroid.apk.ApkModule
import com.reandroid.archive.ByteInputSource
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PatcherContextTest {
    @TempDir
    lateinit var directory: File

    private val libraryBytes = ByteArray(4096) { (it % 127).toByte() }
    private val originalLibrary = "lib/arm64-v8a/liboriginal.so"
    private val addedLibrary = "lib/arm64-v8a/libadded.so"

    private fun exportWithAddedLibrary(extractNativeLibs: Boolean?): File {
        val workspace = directory.resolve("workspace-$extractNativeLibs")
        val input = directory.resolve("input-$extractNativeLibs.apk")
        ApkModule().use { module ->
            module.setManifest(AndroidManifestBlock().apply {
                getOrCreateElement("manifest")
                packageName = "com.example.nativepackaging"
                setMinSdkVersion(29)
                setTargetSdkVersion(35)
                setExtractNativeLibs(extractNativeLibs)
            })
            module.ensureTableBlock()
            module.add(ByteInputSource(libraryBytes, originalLibrary))
            module.uncompressedFiles.addPath(originalLibrary)
            module.writeApk(input)
        }

        val patcher = PatcherContext("test", workspace, input.name, "output.apk")
        val output = directory.resolve("output-$extractNativeLibs.apk")
        try {
            input.inputStream().use { patcher.prepare(it) }
            // New files have no entry in the original APK's uncompressed-file metadata.
            patcher.workingDir.resolve("root/$addedLibrary").apply {
                parentFile.mkdirs()
                writeBytes(libraryBytes)
            }
            output.outputStream().use { patcher.export(it) }
        } finally {
            patcher.cleanup()
        }
        return output
    }

    @Test
    fun `direct loading exports added libraries uncompressed before signing`() {
        val output = exportWithAddedLibrary(false)
        ZipFile(output).use { archive ->
            for (name in listOf(originalLibrary, addedLibrary)) {
                val entry = archive.getEntry(name)
                assertEquals(ZipEntry.STORED, entry.method, name)
                archive.getInputStream(entry).use {
                    assertContentEquals(libraryBytes, it.readBytes(), name)
                }
            }
        }
        ApkModule.loadApkFile(output).use {
            assertEquals(false, it.androidManifest.isExtractNativeLibs)
        }
        val verification = ApkVerifier.Builder(output).build().verify()
        assertTrue(verification.isVerified, verification.errors.toString())
    }

    @Test
    fun `extraction enabled or unspecified preserves compression and manifest policy`() {
        for (extractNativeLibs in listOf(true, null)) {
            val output = exportWithAddedLibrary(extractNativeLibs)
            ZipFile(output).use { archive ->
                assertEquals(ZipEntry.STORED, archive.getEntry(originalLibrary).method)
                assertEquals(ZipEntry.DEFLATED, archive.getEntry(addedLibrary).method)
            }
            ApkModule.loadApkFile(output).use {
                assertEquals(extractNativeLibs, it.androidManifest.isExtractNativeLibs)
            }
        }
    }
}
