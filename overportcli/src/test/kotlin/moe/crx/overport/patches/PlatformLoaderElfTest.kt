package moe.crx.overport.patches

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PlatformLoaderElfTest {
    @Test
    fun `defined default-visible dynamic export leaves loader untouched`() {
        val loader = elfFixture(exportMessageType = true, withSections = true)

        val result = patchPlatformLoader(loader)

        assertFalse(result.needsCompanion)
        assertSame(loader, result.bytes)
    }

    @Test
    fun `protected dynamic export remains available to external callers`() {
        val loader = elfFixture(exportMessageType = true, withSections = true)
        loader[SYMTAB_OFFSET + SYMBOL_SIZE + 5] = 3
        val original = loader.copyOf()

        val result = patchPlatformLoader(loader)

        assertFalse(result.needsCompanion)
        assertContentEquals(original, result.bytes)
    }

    @Test
    fun `stripped loader gains one idempotent dependency without changing existing segments`() {
        val loader = elfFixture(exportMessageType = false, withSections = false)
        val originalHeaders = programHeaders(loader)
        val originalLoads = originalHeaders.filter { it.type == PT_LOAD }
        val originalStack = originalHeaders.single { it.type == PT_GNU_STACK }
        val originalRelro = originalHeaders.single { it.type == PT_GNU_RELRO }

        val first = patchPlatformLoader(loader)
        val patchedHeaders = programHeaders(first.bytes)
        val second = patchPlatformLoader(first.bytes)

        assertTrue(first.needsCompanion)
        assertTrue(second.needsCompanion)
        assertSame(first.bytes, second.bytes)
        assertContentEquals(first.bytes, second.bytes)
        assertEquals(1, neededLibraries(first.bytes).count { it == COMPANION })
        assertContentEquals(loader.copyOfRange(64, loader.size), first.bytes.copyOfRange(64, loader.size))
        assertEquals(originalLoads, patchedHeaders.filter { it.type == PT_LOAD }.take(originalLoads.size))
        assertEquals(originalStack, patchedHeaders.single { it.type == PT_GNU_STACK })
        assertEquals(originalRelro, patchedHeaders.single { it.type == PT_GNU_RELRO })

        val appendedLoad = patchedHeaders.filter { it.type == PT_LOAD }.last()
        assertEquals(PF_R, appendedLoad.flags)
        assertTrue(appendedLoad.virtualAddress >= originalLoads.maxOf { it.virtualAddress + it.memorySize })
        assertEquals(appendedLoad.offset % appendedLoad.alignment, appendedLoad.virtualAddress % appendedLoad.alignment)
        val relocatedDynamic = patchedHeaders.single { it.type == PT_DYNAMIC }
        val relocatedPhdr = patchedHeaders.single { it.type == PT_PHDR }
        assertTrue(relocatedDynamic.isContainedBy(appendedLoad))
        assertTrue(relocatedPhdr.isContainedBy(appendedLoad))
    }

    @Test
    fun `relocated dynamic and string table sections describe appended metadata`() {
        val result = patchPlatformLoader(elfFixture(exportMessageType = false, withSections = true))
        val headers = programHeaders(result.bytes)
        val dynamicHeader = headers.single { it.type == PT_DYNAMIC }
        val entries = dynamicEntries(result.bytes, headers)
        val stringAddress = entries.single { it.first == DT_STRTAB }.second
        val stringSize = entries.single { it.first == DT_STRSZ }.second
        val sections = sectionHeaders(result.bytes)
        val dynamicSection = sections.single { it.type == SHT_DYNAMIC }
        val stringSection = sections.single { it.type == SHT_STRTAB }

        assertEquals(dynamicHeader.offset, dynamicSection.offset)
        assertEquals(dynamicHeader.virtualAddress, dynamicSection.address)
        assertEquals(dynamicHeader.fileSize, dynamicSection.size)
        assertEquals(stringAddress, stringSection.address)
        assertEquals(stringSize, stringSection.size)
        assertEquals(fileOffsetForAddress(headers, stringAddress), stringSection.offset)
        assertEquals(listOf("libc.so", COMPANION), neededLibraries(result.bytes))
    }

    @Test
    fun `out-of-range dynamic metadata fails before changing input`() {
        val loader = elfFixture(exportMessageType = false, withSections = false)
        val dynamic = programHeaders(loader).single { it.type == PT_DYNAMIC }
        val entries = dynamicEntriesWithOffsets(loader, dynamic)
        val stringTableEntryOffset = entries.single { it.second == DT_STRTAB }.first
        loader.writer().putLong(stringTableEntryOffset + 8, 0x7fff_ffffL)
        val malformed = loader.copyOf()

        assertFailsWith<IllegalArgumentException> { patchPlatformLoader(loader) }
        assertContentEquals(malformed, loader)
    }

    @Test
    fun `unchecked microphone buffer size returns zero before a stream is open`() {
        val loader = elfFixture(exportMessageType = true, withSections = true, otherSymbol = MICROPHONE_SYMBOL, code = UNCHECKED_BODY)
        val original = loader.copyOf()

        val result = patchPlatformLoader(loader)

        assertFalse(result.needsCompanion)
        assertContentEquals(GUARDED_BODY, codeWords(result.bytes, UNCHECKED_BODY.size))
        assertContentEquals(original.copyOfRange(0, CODE_OFFSET), result.bytes.copyOfRange(0, CODE_OFFSET))
        val codeEnd = CODE_OFFSET + UNCHECKED_BODY.size * 4
        assertContentEquals(original.copyOfRange(codeEnd, original.size), result.bytes.copyOfRange(codeEnd, result.bytes.size))
        assertContentEquals(original, loader)
    }

    @Test
    fun `microphone guard is applied together with the companion dependency`() {
        val loader = elfFixture(exportMessageType = false, withSections = true, otherSymbol = MICROPHONE_SYMBOL, code = UNCHECKED_BODY)

        val result = patchPlatformLoader(loader)

        assertTrue(result.needsCompanion)
        assertContentEquals(GUARDED_BODY, codeWords(result.bytes, GUARDED_BODY.size))
        assertEquals(listOf("libc.so", COMPANION), neededLibraries(result.bytes))
    }

    @Test
    fun `different microphone buffer size body is left untouched`() {
        val body = UNCHECKED_BODY.copyOf().apply { this[2] = 0xf940_1000L }
        val loader = elfFixture(exportMessageType = true, withSections = true, otherSymbol = MICROPHONE_SYMBOL, code = body)

        val result = patchPlatformLoader(loader)

        assertSame(loader, result.bytes)
        assertContentEquals(body, codeWords(result.bytes, body.size))
    }

    @Test
    fun `microphone guard is idempotent`() {
        val loader = elfFixture(exportMessageType = true, withSections = true, otherSymbol = MICROPHONE_SYMBOL, code = UNCHECKED_BODY)

        val first = patchPlatformLoader(loader)
        val second = patchPlatformLoader(first.bytes)

        assertSame(first.bytes, second.bytes)
        assertContentEquals(GUARDED_BODY, codeWords(second.bytes, GUARDED_BODY.size))
    }

    @Test
    fun `loader without the microphone export keeps an identical body`() {
        val loader = elfFixture(exportMessageType = true, withSections = true, code = UNCHECKED_BODY)

        val result = patchPlatformLoader(loader)

        assertSame(loader, result.bytes)
        assertContentEquals(UNCHECKED_BODY, codeWords(result.bytes, UNCHECKED_BODY.size))
    }

    private fun codeWords(bytes: ByteArray, count: Int): LongArray {
        val reader = bytes.reader()
        return LongArray(count) { index -> reader.getInt(CODE_OFFSET + index * 4).toLong() and 0xffff_ffffL }
    }

    private fun elfFixture(
        exportMessageType: Boolean,
        withSections: Boolean,
        otherSymbol: String = OTHER_SYMBOL,
        code: LongArray? = null,
    ): ByteArray {
        val strings = byteArrayOf(0) +
            MESSAGE_SYMBOL.toByteArray(Charsets.US_ASCII) + byteArrayOf(0) +
            otherSymbol.toByteArray(Charsets.US_ASCII) + byteArrayOf(0) +
            "libc.so".toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
        val messageNameOffset = 1
        val otherNameOffset = messageNameOffset + MESSAGE_SYMBOL.length + 1
        val neededNameOffset = otherNameOffset + otherSymbol.length + 1
        val sectionOffset = if (withSections) SECTION_TABLE_OFFSET else 0
        val fileSize = if (withSections) SECTION_TABLE_OFFSET + SECTION_COUNT * SECTION_HEADER_SIZE else BASE_FILE_SIZE
        val bytes = ByteArray(fileSize)
        val writer = bytes.writer()

        bytes[0] = 0x7f
        bytes[1] = 'E'.code.toByte()
        bytes[2] = 'L'.code.toByte()
        bytes[3] = 'F'.code.toByte()
        bytes[4] = 2
        bytes[5] = 1
        bytes[6] = 1
        writer.putShort(16, ET_DYN.toShort())
        writer.putShort(18, EM_AARCH64.toShort())
        writer.putInt(20, 1)
        writer.putLong(32, PROGRAM_TABLE_OFFSET.toLong())
        writer.putLong(40, sectionOffset.toLong())
        writer.putShort(52, ELF_HEADER_SIZE.toShort())
        writer.putShort(54, PROGRAM_HEADER_SIZE.toShort())
        writer.putShort(56, PROGRAM_COUNT.toShort())
        writer.putShort(58, (if (withSections) SECTION_HEADER_SIZE else 0).toShort())
        writer.putShort(60, (if (withSections) SECTION_COUNT else 0).toShort())
        writer.putShort(62, 0.toShort())

        val dynamicEntries = listOf(
            DT_NEEDED to neededNameOffset.toLong(),
            DT_STRTAB to STRTAB_OFFSET.toLong(),
            DT_STRSZ to strings.size.toLong(),
            DT_SYMTAB to SYMTAB_OFFSET.toLong(),
            DT_SYMENT to SYMBOL_SIZE.toLong(),
            DT_GNU_HASH to GNU_HASH_OFFSET.toLong(),
            DT_NULL to 0L,
        )
        val dynamicSize = dynamicEntries.size * DYNAMIC_ENTRY_SIZE
        val headers = listOf(
            Header(PT_PHDR, PF_R, PROGRAM_TABLE_OFFSET.toLong(), PROGRAM_TABLE_OFFSET.toLong(), PROGRAM_COUNT * PROGRAM_HEADER_SIZE.toLong(), PROGRAM_COUNT * PROGRAM_HEADER_SIZE.toLong(), 8),
            Header(PT_LOAD, PF_R or PF_X, 0, 0, FIRST_LOAD_SIZE.toLong(), FIRST_LOAD_SIZE.toLong(), PAGE_SIZE),
            Header(PT_LOAD, PF_R or PF_W, SECOND_LOAD_OFFSET.toLong(), SECOND_LOAD_ADDRESS, SECOND_LOAD_SIZE.toLong(), (SECOND_LOAD_SIZE + 0x80).toLong(), PAGE_SIZE),
            Header(PT_DYNAMIC, PF_R or PF_W, DYNAMIC_OFFSET.toLong(), DYNAMIC_OFFSET.toLong(), dynamicSize.toLong(), dynamicSize.toLong(), 8),
            Header(PT_GNU_STACK, PF_R or PF_W, 0, 0, 0, 0, 16),
            Header(PT_GNU_RELRO, PF_R, SECOND_LOAD_OFFSET.toLong(), SECOND_LOAD_ADDRESS, 0x80, 0x80, 1),
        )
        headers.forEachIndexed { index, header -> writeHeader(writer, PROGRAM_TABLE_OFFSET + index * PROGRAM_HEADER_SIZE, header) }

        strings.copyInto(bytes, STRTAB_OFFSET)
        writer.putInt(SYMTAB_OFFSET + SYMBOL_SIZE, messageNameOffset)
        bytes[SYMTAB_OFFSET + SYMBOL_SIZE + 4] = 0x12
        writer.putShort(
            SYMTAB_OFFSET + SYMBOL_SIZE + 6,
            (if (exportMessageType) 4 else 0).toShort(),
        )
        writer.putInt(SYMTAB_OFFSET + 2 * SYMBOL_SIZE, otherNameOffset)
        bytes[SYMTAB_OFFSET + 2 * SYMBOL_SIZE + 4] = 0x12
        writer.putShort(SYMTAB_OFFSET + 2 * SYMBOL_SIZE + 6, 4.toShort())
        writer.putLong(SYMTAB_OFFSET + 2 * SYMBOL_SIZE + 8, CODE_OFFSET.toLong())
        code?.forEachIndexed { index, word -> writer.putInt(CODE_OFFSET + index * 4, word.toInt()) }

        val hashedSymbols = if (exportMessageType) listOf(MESSAGE_SYMBOL, otherSymbol) else listOf(otherSymbol)
        val firstHashedSymbol = 3 - hashedSymbols.size
        val bloomShift = 5
        val hashes = hashedSymbols.map(::gnuHash)
        val bloom = hashes.fold(0L) { bits, hash ->
            val unsignedHash = hash.toLong() and 0xffff_ffffL
            bits or (1L shl (unsignedHash and 63).toInt()) or
                (1L shl (unsignedHash shr bloomShift and 63).toInt())
        }
        writer.putInt(GNU_HASH_OFFSET, 1)
        writer.putInt(GNU_HASH_OFFSET + 4, firstHashedSymbol)
        writer.putInt(GNU_HASH_OFFSET + 8, 1)
        writer.putInt(GNU_HASH_OFFSET + 12, bloomShift)
        writer.putLong(GNU_HASH_OFFSET + 16, bloom)
        writer.putInt(GNU_HASH_OFFSET + 24, firstHashedSymbol)
        hashes.forEachIndexed { index, hash ->
            val chainValue = if (index == hashes.lastIndex) hash or 1 else hash and -2
            writer.putInt(GNU_HASH_OFFSET + 28 + index * 4, chainValue)
        }

        dynamicEntries.forEachIndexed { index, entry ->
            writer.putLong(DYNAMIC_OFFSET + index * DYNAMIC_ENTRY_SIZE, entry.first)
            writer.putLong(DYNAMIC_OFFSET + index * DYNAMIC_ENTRY_SIZE + 8, entry.second)
        }

        if (withSections) {
            writeSection(writer, sectionOffset + SECTION_HEADER_SIZE, SHT_STRTAB, STRTAB_OFFSET, STRTAB_OFFSET, strings.size, 0, 0)
            writeSection(writer, sectionOffset + 2 * SECTION_HEADER_SIZE, SHT_DYNSYM, SYMTAB_OFFSET, SYMTAB_OFFSET, 3 * SYMBOL_SIZE, 1, SYMBOL_SIZE)
            writeSection(writer, sectionOffset + 3 * SECTION_HEADER_SIZE, SHT_DYNAMIC, DYNAMIC_OFFSET, DYNAMIC_OFFSET, dynamicSize, 1, DYNAMIC_ENTRY_SIZE)
            writeSection(writer, sectionOffset + 4 * SECTION_HEADER_SIZE, SHT_PROGBITS, 0x400, 0x400, 0x10, 0, 0, 6)
        }
        return bytes
    }

    private fun neededLibraries(bytes: ByteArray): List<String> {
        val headers = programHeaders(bytes)
        val entries = dynamicEntries(bytes, headers)
        val stringAddress = entries.single { it.first == DT_STRTAB }.second
        val stringSize = entries.single { it.first == DT_STRSZ }.second.toInt()
        val stringOffset = fileOffsetForAddress(headers, stringAddress).toInt()
        return entries.filter { it.first == DT_NEEDED }.map { (_, nameOffset) ->
            readString(bytes, stringOffset + nameOffset.toInt(), stringOffset + stringSize)
        }
    }

    private fun dynamicEntries(bytes: ByteArray, headers: List<Header>): List<Pair<Long, Long>> {
        val dynamic = headers.single { it.type == PT_DYNAMIC }
        return dynamicEntriesWithOffsets(bytes, dynamic).map { (_, tag, value) -> tag to value }
    }

    private fun dynamicEntriesWithOffsets(bytes: ByteArray, dynamic: Header): List<Triple<Int, Long, Long>> {
        val reader = bytes.reader()
        val entries = mutableListOf<Triple<Int, Long, Long>>()
        for (offset in dynamic.offset.toInt() until (dynamic.offset + dynamic.fileSize).toInt() step DYNAMIC_ENTRY_SIZE) {
            val tag = reader.getLong(offset)
            val value = reader.getLong(offset + 8)
            entries += Triple(offset, tag, value)
            if (tag == DT_NULL) break
        }
        return entries
    }

    private fun programHeaders(bytes: ByteArray): List<Header> {
        val reader = bytes.reader()
        val offset = reader.getLong(32).toInt()
        val count = reader.getShort(56).toInt() and 0xffff
        return List(count) { index ->
            val base = offset + index * PROGRAM_HEADER_SIZE
            Header(
                type = reader.getInt(base).toLong() and 0xffff_ffffL,
                flags = reader.getInt(base + 4).toLong() and 0xffff_ffffL,
                offset = reader.getLong(base + 8),
                virtualAddress = reader.getLong(base + 16),
                fileSize = reader.getLong(base + 32),
                memorySize = reader.getLong(base + 40),
                alignment = reader.getLong(base + 48),
            )
        }
    }

    private fun sectionHeaders(bytes: ByteArray): List<Section> {
        val reader = bytes.reader()
        val offset = reader.getLong(40).toInt()
        val count = reader.getShort(60).toInt() and 0xffff
        return List(count) { index ->
            val base = offset + index * SECTION_HEADER_SIZE
            Section(
                type = reader.getInt(base + 4).toLong() and 0xffff_ffffL,
                address = reader.getLong(base + 16),
                offset = reader.getLong(base + 24),
                size = reader.getLong(base + 32),
            )
        }
    }

    private fun fileOffsetForAddress(headers: List<Header>, address: Long): Long {
        val load = headers.single { it.type == PT_LOAD && address >= it.virtualAddress && address < it.virtualAddress + it.fileSize }
        return load.offset + address - load.virtualAddress
    }

    private fun writeHeader(writer: ByteBuffer, offset: Int, header: Header) {
        writer.putInt(offset, header.type.toInt())
        writer.putInt(offset + 4, header.flags.toInt())
        writer.putLong(offset + 8, header.offset)
        writer.putLong(offset + 16, header.virtualAddress)
        writer.putLong(offset + 24, header.virtualAddress)
        writer.putLong(offset + 32, header.fileSize)
        writer.putLong(offset + 40, header.memorySize)
        writer.putLong(offset + 48, header.alignment)
    }

    private fun writeSection(
        writer: ByteBuffer,
        offset: Int,
        type: Long,
        address: Int,
        fileOffset: Int,
        size: Int,
        link: Int,
        entrySize: Int,
        flags: Long = 0,
    ) {
        writer.putInt(offset + 4, type.toInt())
        writer.putLong(offset + 8, flags)
        writer.putLong(offset + 16, address.toLong())
        writer.putLong(offset + 24, fileOffset.toLong())
        writer.putLong(offset + 32, size.toLong())
        writer.putInt(offset + 40, link)
        writer.putLong(offset + 48, 1)
        writer.putLong(offset + 56, entrySize.toLong())
    }

    private fun readString(bytes: ByteArray, offset: Int, limit: Int): String {
        var end = offset
        while (end < limit && bytes[end] != 0.toByte()) end++
        return bytes.copyOfRange(offset, end).toString(Charsets.US_ASCII)
    }

    private fun gnuHash(value: String): Int {
        var hash = 5381
        value.forEach { character -> hash = hash * 33 + character.code }
        return hash
    }

    private fun ByteArray.reader(): ByteBuffer = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
    private fun ByteArray.writer(): ByteBuffer = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)

    private data class Header(
        val type: Long,
        val flags: Long,
        val offset: Long,
        val virtualAddress: Long,
        val fileSize: Long,
        val memorySize: Long,
        val alignment: Long,
    ) {
        fun isContainedBy(load: Header): Boolean =
            offset >= load.offset && offset + fileSize <= load.offset + load.fileSize &&
                virtualAddress >= load.virtualAddress && virtualAddress + memorySize <= load.virtualAddress + load.memorySize
    }

    private data class Section(
        val type: Long,
        val address: Long,
        val offset: Long,
        val size: Long,
    )

    private companion object {
        const val ELF_HEADER_SIZE = 64
        const val PROGRAM_HEADER_SIZE = 56
        const val SECTION_HEADER_SIZE = 64
        const val DYNAMIC_ENTRY_SIZE = 16
        const val SYMBOL_SIZE = 24
        const val PROGRAM_TABLE_OFFSET = 64
        const val PROGRAM_COUNT = 6
        const val STRTAB_OFFSET = 0x1a0
        const val SYMTAB_OFFSET = 0x240
        const val GNU_HASH_OFFSET = 0x2a0
        const val DYNAMIC_OFFSET = 0x300
        const val FIRST_LOAD_SIZE = 0x500
        const val SECOND_LOAD_OFFSET = 0x500
        const val SECOND_LOAD_ADDRESS = 0x1500L
        const val SECOND_LOAD_SIZE = 0x100
        const val BASE_FILE_SIZE = 0x600
        const val SECTION_TABLE_OFFSET = 0x600
        const val SECTION_COUNT = 5
        const val CODE_OFFSET = 0x400
        const val PAGE_SIZE = 0x1000L

        const val ET_DYN = 3
        const val EM_AARCH64 = 183
        const val PT_LOAD = 1L
        const val PT_DYNAMIC = 2L
        const val PT_PHDR = 6L
        const val PT_GNU_STACK = 0x6474e551L
        const val PT_GNU_RELRO = 0x6474e552L
        const val PF_X = 1L
        const val PF_W = 2L
        const val PF_R = 4L
        const val DT_NULL = 0L
        const val DT_NEEDED = 1L
        const val DT_STRTAB = 5L
        const val DT_SYMTAB = 6L
        const val DT_STRSZ = 10L
        const val DT_SYMENT = 11L
        const val DT_GNU_HASH = 0x6ffffef5L
        const val SHT_PROGBITS = 1L
        const val SHT_STRTAB = 3L
        const val SHT_DYNAMIC = 6L
        const val SHT_DYNSYM = 11L
        const val MESSAGE_SYMBOL = "ovrMessageType_ToString"
        const val OTHER_SYMBOL = "other_export"
        const val COMPANION = "libovrplatformcompat.so"
        const val MICROPHONE_SYMBOL = "ovr_Microphone_GetOutputBufferMaxSize"
        const val FRAMES_PER_BURST_CALL = 0x97ff_ff00L
        val UNCHECKED_BODY = longArrayOf(
            0xa9bf_7bfdL, 0x9100_03fdL, 0xf940_0c00L, FRAMES_PER_BURST_CALL, 0x9340_7c00L, 0xa8c1_7bfdL, 0xd65f_03c0L,
        )
        val GUARDED_BODY = longArrayOf(
            0xf940_0c00L, 0xb400_00a0L, 0xa9bf_7bfdL, FRAMES_PER_BURST_CALL, 0xa8c1_7bfdL, 0x9340_7c00L, 0xd65f_03c0L,
        )
    }
}
