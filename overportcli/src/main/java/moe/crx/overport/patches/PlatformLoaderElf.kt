package moe.crx.overport.patches

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class PlatformLoaderPatch(
    val bytes: ByteArray,
    val needsCompanion: Boolean,
)

internal fun patchPlatformLoader(bytes: ByteArray): PlatformLoaderPatch {
    val original = PlatformLoaderElf(bytes)
    val guarded = original.guardMicrophoneBufferSize()
    val elf = if (guarded === bytes) original else PlatformLoaderElf(guarded)
    if (elf.definesMessageTypeToString()) {
        return PlatformLoaderPatch(guarded, false)
    }
    if (elf.needsCompanionAlready()) {
        return PlatformLoaderPatch(guarded, true)
    }
    return PlatformLoaderPatch(elf.addCompanionDependency(), true)
}

/*
 * The original load segments stay byte-for-byte and address-for-address intact. New program
 * headers, dynamic strings, and dynamic entries live in a final read-only PT_LOAD, so the patch
 * never consumes guessed padding, overwrites BSS, or changes permissions on existing mappings.
 */
private class PlatformLoaderElf(private val source: ByteArray) {
    private val reader = ElfReader(source)
    private val sectionTable: SectionTable?
    private val programHeaders: List<ProgramHeader>
    private val dynamicHeader: ProgramHeader
    private val phdrIndex: Int?
    private val dynamicIndex: Int
    private val dynamicEntries: List<DynamicEntry>
    private val stringTableAddress: Long
    private val stringTableOffset: Int
    private val stringTable: ByteArray
    private val symbolTableAddress: Long
    private val symbolTableOffset: Int
    private val symbolCount: Int
    private val dynamicSectionIndexes: Set<Int>
    private val dynamicStringSectionIndexes: Set<Int>
    private val dynamicSymbolSectionIndex: Int?

    init {
        validateIdentification()

        val sectionOffset = reader.u64(E_SHOFF, "section-header table offset")
        val sectionEntrySize = reader.u16(E_SHENTSIZE)
        val rawSectionCount = reader.u16(E_SHNUM)
        sectionTable = readSectionTable(sectionOffset, sectionEntrySize, rawSectionCount)

        val programOffset = reader.u64(E_PHOFF, "program-header table offset")
        val programEntrySize = reader.u16(E_PHENTSIZE)
        requireElf(programEntrySize == PROGRAM_HEADER_SIZE) {
            "unsupported program-header entry size $programEntrySize"
        }
        val rawProgramCount = reader.u16(E_PHNUM)
        val programCount = if (rawProgramCount == PN_XNUM) {
            sectionTable?.headers?.firstOrNull()?.info
                ?: failElf("extended program-header count has no section header zero")
        } else {
            rawProgramCount.toLong()
        }
        requireElf(programCount in 1 until PN_XNUM.toLong()) {
            "unsupported program-header count $programCount"
        }
        val programTableSize = checkedMultiply(programCount, PROGRAM_HEADER_SIZE.toLong(), "program-header table size")
        val programTableOffset = reader.fileRange(programOffset, programTableSize, "program-header table")
        programHeaders = List(programCount.toInt()) { index ->
            readProgramHeader(programTableOffset + index * PROGRAM_HEADER_SIZE)
        }
        phdrIndex = programHeaders.indexOfFirst { it.type == PT_PHDR }.takeIf { it >= 0 }
        requireElf(programHeaders.count { it.type == PT_PHDR } <= 1) {
            "multiple PT_PHDR segments"
        }
        validateProgramHeaders(programOffset, programTableSize)

        val dynamicHeaders = programHeaders.withIndex().filter { it.value.type == PT_DYNAMIC }
        requireElf(dynamicHeaders.size == 1) {
            "expected exactly one PT_DYNAMIC segment, found ${dynamicHeaders.size}"
        }
        dynamicIndex = dynamicHeaders.single().index
        dynamicHeader = dynamicHeaders.single().value
        requireElf(
            virtualFileRange(dynamicHeader.virtualAddress, dynamicHeader.fileSize, "PT_DYNAMIC segment").toLong() ==
                dynamicHeader.offset,
        ) { "PT_DYNAMIC file and virtual addresses do not describe the same bytes" }

        dynamicEntries = readDynamicEntries(dynamicHeader)
        stringTableAddress = uniqueDynamicValue(DT_STRTAB, "DT_STRTAB")
        val stringTableSize = uniqueDynamicValue(DT_STRSZ, "DT_STRSZ")
        requireElf(stringTableSize in 1..Int.MAX_VALUE.toLong()) {
            "invalid DT_STRSZ value $stringTableSize"
        }
        stringTableOffset = virtualFileRange(stringTableAddress, stringTableSize, "dynamic string table")
        stringTable = source.copyOfRange(stringTableOffset, stringTableOffset + stringTableSize.toInt())
        requireElf(stringTable[0] == 0.toByte()) { "dynamic string table does not begin with NUL" }
        dynamicEntries.filter { it.tag == DT_NEEDED }.forEach {
            dynamicString(it.value, "DT_NEEDED")
        }

        symbolTableAddress = uniqueDynamicValue(DT_SYMTAB, "DT_SYMTAB")
        val symbolEntrySize = uniqueDynamicValue(DT_SYMENT, "DT_SYMENT")
        requireElf(symbolEntrySize == SYMBOL_SIZE.toLong()) {
            "unsupported dynamic symbol entry size $symbolEntrySize"
        }

        val sectionMatches = findDynamicSections()
        dynamicSectionIndexes = sectionMatches.first
        dynamicStringSectionIndexes = sectionMatches.second
        dynamicSymbolSectionIndex = findDynamicSymbolSection()
        symbolCount = determineSymbolCount()
        val symbolBytes = checkedMultiply(symbolCount.toLong(), SYMBOL_SIZE.toLong(), "dynamic symbol table size")
        symbolTableOffset = virtualFileRange(symbolTableAddress, symbolBytes, "dynamic symbol table")
        validateDynamicSymbols()
    }

    fun definesMessageTypeToString(): Boolean = definedSymbolOffset(MESSAGE_TYPE_SYMBOL) != null

    /*
     * ovr_Microphone_Create only allocates the handle; the AAudio input stream is opened later by
     * ovr_Microphone_Start. Meta's loader allows asking for the buffer size in between (Unreal's
     * Oculus voice code does), but this loader passes the still-NULL stream straight to
     * AAudioStream_getFramesPerBurst, which crashes inside libaaudio. Only the exact unchecked body
     * is rewritten, in place and at the same size, so it returns 0 until a stream is open. The bl
     * stays at the same address, so its PC-relative target is unchanged.
     */
    fun guardMicrophoneBufferSize(): ByteArray {
        val symbol = definedSymbolOffset(MICROPHONE_BUFFER_SIZE_SYMBOL) ?: return source
        val address = reader.u64(symbol + 8, "dynamic symbol value")
        val size = UNCHECKED_BUFFER_SIZE_BODY.size * 4L
        val offset = loadedFileOffsetOrNull(address, size) ?: return source
        val words = LongArray(UNCHECKED_BUFFER_SIZE_BODY.size) { reader.u32(offset + it * 4) }
        val matches = words.indices.all { index ->
            if (index == BUFFER_SIZE_CALL_INDEX) {
                words[index] and AARCH64_BL_MASK == AARCH64_BL
            } else {
                words[index] == UNCHECKED_BUFFER_SIZE_BODY[index]
            }
        }
        if (!matches) return source

        val output = source.copyOf()
        val writer = ElfWriter(output)
        GUARDED_BUFFER_SIZE_BODY.forEachIndexed { index, word ->
            val value = if (index == BUFFER_SIZE_CALL_INDEX) words[BUFFER_SIZE_CALL_INDEX] else word
            writer.putU32(offset + index * 4, value)
        }
        return output
    }

    private fun definedSymbolOffset(name: String): Int? {
        for (index in 0 until symbolCount) {
            val offset = symbolTableOffset + index * SYMBOL_SIZE
            val nameIndex = reader.u32(offset).toLong()
            if (dynamicString(nameIndex, "dynamic symbol $index") != name) continue

            val binding = reader.u8(offset + 4) ushr 4
            val visibility = reader.u8(offset + 5) and 0x3
            if (binding != STB_GLOBAL && binding != STB_WEAK) continue
            if (visibility != STV_DEFAULT && visibility != STV_PROTECTED) continue

            val sectionIndex = reader.u16(offset + 6)
            val resolvedSectionIndex = if (sectionIndex == SHN_XINDEX) {
                extendedSymbolSectionIndex(index)
            } else {
                sectionIndex.toLong()
            }
            if (resolvedSectionIndex != SHN_UNDEF.toLong()) return offset
        }
        return null
    }

    fun needsCompanionAlready(): Boolean = dynamicEntries
        .asSequence()
        .filter { it.tag == DT_NEEDED }
        .any { dynamicString(it.value, "DT_NEEDED") == COMPANION_SONAME }

    fun addCompanionDependency(): ByteArray {
        val dependencyBytes = COMPANION_SONAME.toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
        val newStringTable = stringTable + dependencyBytes
        val dependencyNameOffset = stringTable.size.toLong()

        val newDynamicEntries = dynamicEntries.map { entry ->
            if (entry.tag == DT_STRSZ) entry.copy(value = newStringTable.size.toLong()) else entry
        }.toMutableList().apply {
            add(DynamicEntry(DT_NEEDED, dependencyNameOffset))
            add(DynamicEntry(DT_NULL, 0))
        }

        val loadHeaders = programHeaders.filter { it.type == PT_LOAD }
        val segmentAlignment = loadHeaders.maxOf { maxOf(it.alignment, MIN_LOAD_ALIGNMENT) }
        requireElf(isPowerOfTwo(segmentAlignment)) {
            "unsupported PT_LOAD alignment $segmentAlignment"
        }
        val maxLoadEnd = loadHeaders.maxOf {
            checkedAdd(it.virtualAddress, it.memorySize, "PT_LOAD virtual end")
        }
        val regionOffset = alignUp(source.size.toLong(), segmentAlignment, "appended segment file offset")
        val regionAddress = alignUp(maxLoadEnd, segmentAlignment, "appended segment virtual address")

        val newProgramCount = programHeaders.size + 1 + if (phdrIndex == null) 1 else 0
        requireElf(newProgramCount < PN_XNUM) { "program-header count cannot be extended safely" }
        val newProgramTableSize = checkedMultiply(
            newProgramCount.toLong(),
            PROGRAM_HEADER_SIZE.toLong(),
            "new program-header table size",
        )
        val newStringOffset = checkedAdd(regionOffset, newProgramTableSize, "new dynamic string table offset")
        val newStringAddress = checkedAdd(regionAddress, newProgramTableSize, "new dynamic string table address")
        val newDynamicOffset = alignUp(
            checkedAdd(newStringOffset, newStringTable.size.toLong(), "new dynamic segment offset"),
            8,
            "new dynamic segment offset",
        )
        val newDynamicAddress = checkedAdd(regionAddress, newDynamicOffset - regionOffset, "new dynamic segment address")
        val newDynamicSize = checkedMultiply(
            newDynamicEntries.size.toLong(),
            DYNAMIC_ENTRY_SIZE.toLong(),
            "new dynamic segment size",
        )
        val newSectionOffset = sectionTable?.let {
            alignUp(checkedAdd(newDynamicOffset, newDynamicSize, "new section-header table offset"), 8, "new section-header table offset")
        }
        val regionEnd = if (sectionTable != null) {
            checkedAdd(newSectionOffset!!, sectionTable.byteSize, "appended metadata end")
        } else {
            checkedAdd(newDynamicOffset, newDynamicSize, "appended metadata end")
        }
        requireElf(regionEnd <= Int.MAX_VALUE.toLong()) {
            "patched ELF is too large for an in-memory byte array"
        }

        val newLoad = ProgramHeader(
            type = PT_LOAD,
            flags = PF_R,
            offset = regionOffset,
            virtualAddress = regionAddress,
            physicalAddress = regionAddress,
            fileSize = regionEnd - regionOffset,
            memorySize = regionEnd - regionOffset,
            alignment = segmentAlignment,
        )
        val lastLoadIndex = programHeaders.indexOfLast { it.type == PT_LOAD }
        val rewrittenHeaders = ArrayList<ProgramHeader>(newProgramCount)
        if (phdrIndex == null) {
            rewrittenHeaders.add(
                ProgramHeader(
                    type = PT_PHDR,
                    flags = PF_R,
                    offset = regionOffset,
                    virtualAddress = regionAddress,
                    physicalAddress = regionAddress,
                    fileSize = newProgramTableSize,
                    memorySize = newProgramTableSize,
                    alignment = 8,
                ),
            )
        }
        programHeaders.forEachIndexed { index, original ->
            val rewritten = when (index) {
                phdrIndex -> original.copy(
                    offset = regionOffset,
                    virtualAddress = regionAddress,
                    physicalAddress = regionAddress,
                    fileSize = newProgramTableSize,
                    memorySize = newProgramTableSize,
                )
                dynamicIndex -> original.copy(
                    flags = PF_R,
                    offset = newDynamicOffset,
                    virtualAddress = newDynamicAddress,
                    physicalAddress = newDynamicAddress,
                    fileSize = newDynamicSize,
                    memorySize = newDynamicSize,
                )
                else -> original
            }
            rewrittenHeaders.add(rewritten)
            if (index == lastLoadIndex) rewrittenHeaders.add(newLoad)
        }

        val output = source.copyOf(regionEnd.toInt())
        val writer = ElfWriter(output)
        writer.putU64(E_PHOFF, regionOffset)
        writer.putU16(E_PHNUM, newProgramCount)
        writer.putU64(E_SHOFF, newSectionOffset ?: 0)

        rewrittenHeaders.forEachIndexed { index, header ->
            writeProgramHeader(writer, regionOffset.toInt() + index * PROGRAM_HEADER_SIZE, header)
        }
        output.copyFrom(newStringTable, newStringOffset.toInt())

        newDynamicEntries.forEachIndexed { index, entry ->
            val offset = newDynamicOffset.toInt() + index * DYNAMIC_ENTRY_SIZE
            writer.putI64(offset, entry.tag)
            writer.putU64(offset + 8, if (entry.tag == DT_STRTAB) newStringAddress else entry.value)
        }

        sectionTable?.let { sections ->
            val copiedSections = sections.bytes.copyOf()
            val sectionWriter = ElfWriter(copiedSections)
            dynamicSectionIndexes.forEach { index ->
                val offset = index * SECTION_HEADER_SIZE
                sectionWriter.putU64(offset + SH_FLAGS, sections.headers[index].flags and SHF_WRITE.inv())
                sectionWriter.putU64(offset + SH_ADDR, newDynamicAddress)
                sectionWriter.putU64(offset + SH_OFFSET, newDynamicOffset)
                sectionWriter.putU64(offset + SH_SIZE, newDynamicSize)
            }
            dynamicStringSectionIndexes.forEach { index ->
                val offset = index * SECTION_HEADER_SIZE
                sectionWriter.putU64(offset + SH_FLAGS, sections.headers[index].flags and SHF_WRITE.inv())
                sectionWriter.putU64(offset + SH_ADDR, newStringAddress)
                sectionWriter.putU64(offset + SH_OFFSET, newStringOffset)
                sectionWriter.putU64(offset + SH_SIZE, newStringTable.size.toLong())
            }
            output.copyFrom(copiedSections, newSectionOffset!!.toInt())
        }
        return output
    }

    private fun validateIdentification() {
        requireElf(source.size >= ELF_HEADER_SIZE) { "file is shorter than an ELF64 header" }
        requireElf(
            reader.u8(0) == 0x7f && reader.u8(1) == 'E'.code &&
                reader.u8(2) == 'L'.code && reader.u8(3) == 'F'.code,
        ) { "invalid ELF magic" }
        requireElf(reader.u8(EI_CLASS) == ELFCLASS64) { "only ELF64 is supported" }
        requireElf(reader.u8(EI_DATA) == ELFDATA2LSB) { "only little-endian ELF is supported" }
        requireElf(reader.u8(EI_VERSION) == EV_CURRENT) { "unsupported ELF identification version" }
        requireElf(reader.u16(E_TYPE) == ET_DYN) { "only shared objects (ET_DYN) are supported" }
        requireElf(reader.u16(E_MACHINE) == EM_AARCH64) { "only AArch64 ELF is supported" }
        requireElf(reader.u32(E_VERSION).toLong() == EV_CURRENT.toLong()) { "unsupported ELF version" }
        requireElf(reader.u16(E_EHSIZE) == ELF_HEADER_SIZE) { "invalid ELF header size" }
    }

    private fun readSectionTable(offset: Long, entrySize: Int, rawCount: Int): SectionTable? {
        if (offset == 0L) {
            requireElf(rawCount == 0 && reader.u16(E_SHSTRNDX) == SHN_UNDEF) {
                "section-header metadata is inconsistent with a stripped ELF"
            }
            return null
        }
        requireElf(entrySize == SECTION_HEADER_SIZE) {
            "unsupported section-header entry size $entrySize"
        }
        val firstOffset = reader.fileRange(offset, SECTION_HEADER_SIZE.toLong(), "section header zero")
        val extendedCount = reader.u64(firstOffset + SH_SIZE, "extended section-header count")
        val count = if (rawCount == 0) extendedCount else rawCount.toLong()
        requireElf(count in 1..Int.MAX_VALUE.toLong()) { "invalid section-header count $count" }
        val byteSize = checkedMultiply(count, SECTION_HEADER_SIZE.toLong(), "section-header table size")
        val tableOffset = reader.fileRange(offset, byteSize, "section-header table")
        val tableBytes = source.copyOfRange(tableOffset, tableOffset + byteSize.toInt())
        val headers = List(count.toInt()) { index ->
            val base = tableOffset + index * SECTION_HEADER_SIZE
            SectionHeader(
                type = reader.u32(base + SH_TYPE),
                flags = reader.u64(base + SH_FLAGS, "section flags"),
                address = reader.u64(base + SH_ADDR, "section address"),
                offset = reader.u64(base + SH_OFFSET, "section offset"),
                size = reader.u64(base + SH_SIZE, "section size"),
                link = reader.u32(base + SH_LINK),
                info = reader.u32(base + SH_INFO),
                entrySize = reader.u64(base + SH_ENTSIZE, "section entry size"),
            )
        }
        requireElf(headers.first().type == SHT_NULL) { "section header zero is not SHT_NULL" }
        headers.forEachIndexed { index, section ->
            if (index != 0 && section.type != SHT_NOBITS && section.size > 0) {
                reader.fileRange(section.offset, section.size, "section header $index contents")
            }
            requireElf(section.link < count || section.link == 0L) {
                "section header $index has an out-of-range link"
            }
        }
        val rawStringIndex = reader.u16(E_SHSTRNDX)
        val stringIndex = if (rawStringIndex == SHN_XINDEX) headers.first().link else rawStringIndex.toLong()
        requireElf(stringIndex == SHN_UNDEF.toLong() || stringIndex < count) {
            "section-name string table index is out of range"
        }
        if (stringIndex != SHN_UNDEF.toLong()) {
            requireElf(headers[stringIndex.toInt()].type == SHT_STRTAB) {
                "section-name string table does not reference SHT_STRTAB"
            }
        }
        return SectionTable(headers, tableBytes, byteSize)
    }

    private fun readProgramHeader(offset: Int): ProgramHeader = ProgramHeader(
        type = reader.u32(offset).toLong(),
        flags = reader.u32(offset + 4).toLong(),
        offset = reader.u64(offset + 8, "segment file offset"),
        virtualAddress = reader.u64(offset + 16, "segment virtual address"),
        physicalAddress = reader.u64(offset + 24, "segment physical address"),
        fileSize = reader.u64(offset + 32, "segment file size"),
        memorySize = reader.u64(offset + 40, "segment memory size"),
        alignment = reader.u64(offset + 48, "segment alignment"),
    )

    private fun validateProgramHeaders(programOffset: Long, programTableSize: Long) {
        val loads = programHeaders.filter { it.type == PT_LOAD }
        requireElf(loads.isNotEmpty()) { "ELF has no PT_LOAD segments" }
        var previousLoadAddress = -1L
        programHeaders.forEachIndexed { index, header ->
            if (header.fileSize > 0) {
                reader.fileRange(header.offset, header.fileSize, "program header $index contents")
            }
            if (header.type == PT_LOAD) {
                requireElf(header.fileSize <= header.memorySize) {
                    "PT_LOAD $index has p_filesz greater than p_memsz"
                }
                requireElf(header.alignment == 0L || header.alignment == 1L || isPowerOfTwo(header.alignment)) {
                    "PT_LOAD $index has invalid alignment ${header.alignment}"
                }
                if (header.alignment > 1) {
                    requireElf(header.offset % header.alignment == header.virtualAddress % header.alignment) {
                        "PT_LOAD $index has incongruent file and virtual addresses"
                    }
                }
                requireElf(header.virtualAddress >= previousLoadAddress) {
                    "PT_LOAD segments are not ordered by virtual address"
                }
                previousLoadAddress = header.virtualAddress
            }
        }
        phdrIndex?.let { index ->
            val header = programHeaders[index]
            requireElf(header.offset == programOffset && header.fileSize >= programTableSize && header.memorySize >= programTableSize) {
                "PT_PHDR does not describe the program-header table"
            }
            requireElf(
                virtualFileRange(header.virtualAddress, programTableSize, "PT_PHDR table").toLong() == programOffset,
            ) { "PT_PHDR file and virtual addresses do not describe the same bytes" }
        }
    }

    private fun readDynamicEntries(header: ProgramHeader): List<DynamicEntry> {
        requireElf(header.fileSize >= DYNAMIC_ENTRY_SIZE && header.fileSize % DYNAMIC_ENTRY_SIZE == 0L) {
            "PT_DYNAMIC has an invalid file size"
        }
        requireElf(header.fileSize <= header.memorySize) { "PT_DYNAMIC has p_filesz greater than p_memsz" }
        val offset = reader.fileRange(header.offset, header.fileSize, "PT_DYNAMIC")
        val entryCount = (header.fileSize / DYNAMIC_ENTRY_SIZE).toInt()
        val entries = ArrayList<DynamicEntry>(entryCount)
        for (index in 0 until entryCount) {
            val entryOffset = offset + index * DYNAMIC_ENTRY_SIZE
            val tag = reader.i64(entryOffset)
            if (tag == DT_NULL) return entries
            entries += DynamicEntry(tag, reader.u64(entryOffset + 8, "dynamic value"))
        }
        failElf("PT_DYNAMIC has no DT_NULL terminator")
    }

    private fun uniqueDynamicValue(tag: Long, name: String): Long {
        val values = dynamicEntries.filter { it.tag == tag }
        requireElf(values.size == 1) { "expected exactly one $name entry, found ${values.size}" }
        return values.single().value
    }

    private fun dynamicString(rawOffset: Long, owner: String): String {
        requireElf(rawOffset in 0 until stringTable.size.toLong()) {
            "$owner has an out-of-range dynamic string offset $rawOffset"
        }
        val offset = rawOffset.toInt()
        var end = offset
        while (end < stringTable.size && stringTable[end] != 0.toByte()) end++
        requireElf(end < stringTable.size) { "$owner references an unterminated dynamic string" }
        return stringTable.copyOfRange(offset, end).toString(Charsets.UTF_8)
    }

    private fun findDynamicSections(): Pair<Set<Int>, Set<Int>> {
        val sections = sectionTable?.headers ?: return emptySet<Int>() to emptySet()
        val dynamicIndexes = sections.indices.filterTo(linkedSetOf()) { index ->
            val section = sections[index]
            section.type == SHT_DYNAMIC &&
                (section.address == dynamicHeader.virtualAddress || section.offset == dynamicHeader.offset)
        }
        val stringIndexes = linkedSetOf<Int>()
        dynamicIndexes.forEach { index ->
            val linked = sections[index].link
            requireElf(linked >= 0 && linked < sections.size.toLong()) {
                "SHT_DYNAMIC section $index has no valid string-table link"
            }
            val linkedIndex = linked.toInt()
            requireElf(sections[linkedIndex].type == SHT_STRTAB) {
                "SHT_DYNAMIC section $index does not link to a string table"
            }
            stringIndexes += linkedIndex
        }
        sections.forEachIndexed { index, section ->
            if (section.type == SHT_STRTAB &&
                (section.address == stringTableAddress || section.offset == stringTableOffset.toLong())
            ) {
                stringIndexes += index
            }
        }
        return dynamicIndexes to stringIndexes
    }

    private fun findDynamicSymbolSection(): Int? {
        val sections = sectionTable?.headers ?: return null
        val matches = sections.indices.filter { index ->
            val section = sections[index]
            section.type == SHT_DYNSYM && section.address == symbolTableAddress
        }
        requireElf(matches.size <= 1) { "multiple section headers describe DT_SYMTAB" }
        return matches.singleOrNull()
    }

    private fun determineSymbolCount(): Int {
        val counts = mutableListOf<Pair<String, Long>>()
        val sysvHashes = dynamicEntries.filter { it.tag == DT_HASH }
        requireElf(sysvHashes.size <= 1) { "multiple DT_HASH entries" }
        sysvHashes.singleOrNull()?.let { entry ->
            val offset = virtualFileRange(entry.value, 8, "DT_HASH header")
            val bucketCount = reader.u32(offset)
            val chainCount = reader.u32(offset + 4)
            val tableWords = checkedAdd(2, checkedAdd(bucketCount, chainCount, "DT_HASH word count"), "DT_HASH word count")
            virtualFileRange(entry.value, checkedMultiply(tableWords, 4, "DT_HASH size"), "DT_HASH table")
            counts += "DT_HASH" to chainCount
        }
        val gnuHashes = dynamicEntries.filter { it.tag == DT_GNU_HASH }
        requireElf(gnuHashes.size <= 1) { "multiple DT_GNU_HASH entries" }
        gnuHashes.singleOrNull()?.let { entry ->
            counts += "DT_GNU_HASH" to gnuHashSymbolCount(entry.value)
        }
        dynamicSymbolSectionIndex?.let { index ->
            val section = sectionTable!!.headers[index]
            requireElf(section.entrySize == SYMBOL_SIZE.toLong() && section.size % section.entrySize == 0L) {
                "SHT_DYNSYM has an invalid entry size"
            }
            counts += "SHT_DYNSYM" to section.size / section.entrySize
        }
        requireElf(counts.isNotEmpty()) {
            "dynamic symbol count is unavailable (no DT_HASH, DT_GNU_HASH, or matching SHT_DYNSYM)"
        }
        val count = counts.first().second
        requireElf(counts.all { it.second == count }) {
            "dynamic symbol counts disagree: ${counts.joinToString { "${it.first}=${it.second}" }}"
        }
        requireElf(count in 1..Int.MAX_VALUE.toLong()) { "invalid dynamic symbol count $count" }
        return count.toInt()
    }

    private fun gnuHashSymbolCount(address: Long): Long {
        val headerOffset = virtualFileRange(address, 16, "DT_GNU_HASH header")
        val bucketCount = reader.u32(headerOffset)
        val symbolOffset = reader.u32(headerOffset + 4)
        val bloomCount = reader.u32(headerOffset + 8)
        requireElf(bucketCount in 1..Int.MAX_VALUE.toLong() && bloomCount > 0) {
            "DT_GNU_HASH has invalid buckets or bloom filter"
        }
        val bloomBytes = checkedMultiply(bloomCount, 8, "DT_GNU_HASH bloom size")
        val bucketsAddress = checkedAdd(address, checkedAdd(16, bloomBytes, "DT_GNU_HASH buckets address"), "DT_GNU_HASH buckets address")
        val bucketsBytes = checkedMultiply(bucketCount, 4, "DT_GNU_HASH buckets size")
        val bucketsOffset = virtualFileRange(bucketsAddress, bucketsBytes, "DT_GNU_HASH buckets")
        val chainsAddress = checkedAdd(bucketsAddress, bucketsBytes, "DT_GNU_HASH chains address")

        var count = symbolOffset
        for (bucketIndex in 0 until bucketCount.toInt()) {
            val firstSymbol = reader.u32(bucketsOffset + bucketIndex * 4)
            if (firstSymbol == 0L) continue
            requireElf(firstSymbol >= symbolOffset) {
                "DT_GNU_HASH bucket $bucketIndex precedes its symbol offset"
            }
            var symbol = firstSymbol
            while (true) {
                val chainIndex = symbol - symbolOffset
                val chainAddress = checkedAdd(
                    chainsAddress,
                    checkedMultiply(chainIndex, 4, "DT_GNU_HASH chain offset"),
                    "DT_GNU_HASH chain address",
                )
                val chainOffset = virtualFileRange(chainAddress, 4, "DT_GNU_HASH chain")
                val chainHash = reader.u32(chainOffset)
                symbol = checkedAdd(symbol, 1, "DT_GNU_HASH symbol count")
                if (symbol > count) count = symbol
                if (chainHash and 1L != 0L) break
            }
        }
        return count
    }

    private fun validateDynamicSymbols() {
        for (index in 0 until symbolCount) {
            val offset = symbolTableOffset + index * SYMBOL_SIZE
            dynamicString(reader.u32(offset).toLong(), "dynamic symbol $index")
        }
    }

    private fun extendedSymbolSectionIndex(symbolIndex: Int): Long {
        val dynsymIndex = dynamicSymbolSectionIndex
            ?: failElf("dynamic symbol $symbolIndex uses SHN_XINDEX without a matching SHT_DYNSYM")
        val sections = sectionTable!!.headers
        val matches = sections.indices.filter { index ->
            sections[index].type == SHT_SYMTAB_SHNDX && sections[index].link == dynsymIndex.toLong()
        }
        requireElf(matches.size == 1) {
            "dynamic symbol $symbolIndex uses SHN_XINDEX without exactly one SHT_SYMTAB_SHNDX"
        }
        val section = sections[matches.single()]
        requireElf(section.entrySize == 0L || section.entrySize == 4L) {
            "SHT_SYMTAB_SHNDX has an invalid entry size"
        }
        val requiredSize = checkedMultiply(symbolCount.toLong(), 4, "SHT_SYMTAB_SHNDX size")
        requireElf(section.size >= requiredSize) { "SHT_SYMTAB_SHNDX is too short" }
        val offset = reader.fileRange(section.offset, section.size, "SHT_SYMTAB_SHNDX")
        return reader.u32(offset + symbolIndex * 4).toLong()
    }

    private fun virtualFileRange(address: Long, size: Long, owner: String): Int {
        requireElf(size >= 0) { "$owner has a negative size" }
        val end = checkedAdd(address, size, "$owner virtual end")
        val matches = programHeaders.filter { header ->
            if (header.type != PT_LOAD) return@filter false
            val segmentEnd = checkedAdd(header.virtualAddress, header.fileSize, "PT_LOAD file-backed end")
            address >= header.virtualAddress && end <= segmentEnd
        }
        requireElf(matches.size == 1) { "$owner is not contained in exactly one file-backed PT_LOAD segment" }
        val header = matches.single()
        val offset = checkedAdd(header.offset, address - header.virtualAddress, "$owner file offset")
        return reader.fileRange(offset, size, owner)
    }

    private fun loadedFileOffsetOrNull(address: Long, size: Long): Int? {
        val header = programHeaders.singleOrNull {
            it.type == PT_LOAD && address >= it.virtualAddress && it.fileSize >= size &&
                address - it.virtualAddress <= it.fileSize - size
        } ?: return null
        val offset = header.offset + (address - header.virtualAddress)
        return if (offset <= source.size.toLong() - size) offset.toInt() else null
    }

    private fun writeProgramHeader(writer: ElfWriter, offset: Int, header: ProgramHeader) {
        writer.putU32(offset, header.type)
        writer.putU32(offset + 4, header.flags)
        writer.putU64(offset + 8, header.offset)
        writer.putU64(offset + 16, header.virtualAddress)
        writer.putU64(offset + 24, header.physicalAddress)
        writer.putU64(offset + 32, header.fileSize)
        writer.putU64(offset + 40, header.memorySize)
        writer.putU64(offset + 48, header.alignment)
    }
}

private data class ProgramHeader(
    val type: Long,
    val flags: Long,
    val offset: Long,
    val virtualAddress: Long,
    val physicalAddress: Long,
    val fileSize: Long,
    val memorySize: Long,
    val alignment: Long,
)

private data class DynamicEntry(val tag: Long, val value: Long)

private data class SectionHeader(
    val type: Long,
    val flags: Long,
    val address: Long,
    val offset: Long,
    val size: Long,
    val link: Long,
    val info: Long,
    val entrySize: Long,
)

private data class SectionTable(
    val headers: List<SectionHeader>,
    val bytes: ByteArray,
    val byteSize: Long,
)

private class ElfReader(private val bytes: ByteArray) {
    private val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    fun u8(offset: Int): Int {
        requireRange(offset.toLong(), 1, "byte")
        return bytes[offset].toInt() and 0xff
    }

    fun u16(offset: Int): Int {
        requireRange(offset.toLong(), 2, "16-bit value")
        return buffer.getShort(offset).toInt() and 0xffff
    }

    fun u32(offset: Int): Long {
        requireRange(offset.toLong(), 4, "32-bit value")
        return buffer.getInt(offset).toLong() and 0xffff_ffffL
    }

    fun i64(offset: Int): Long {
        requireRange(offset.toLong(), 8, "64-bit value")
        return buffer.getLong(offset)
    }

    fun u64(offset: Int, owner: String): Long {
        val value = i64(offset)
        requireElf(value >= 0) { "$owner exceeds the supported signed 64-bit range" }
        return value
    }

    fun fileRange(offset: Long, size: Long, owner: String): Int {
        requireElf(offset >= 0 && size >= 0 && offset <= bytes.size.toLong() && size <= bytes.size.toLong() - offset) {
            "$owner lies outside the file"
        }
        return offset.toInt()
    }

    private fun requireRange(offset: Long, size: Long, owner: String) {
        fileRange(offset, size, owner)
    }
}

private class ElfWriter(bytes: ByteArray) {
    private val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    fun putU16(offset: Int, value: Int) = buffer.putShort(offset, value.toShort())
    fun putU32(offset: Int, value: Long) = buffer.putInt(offset, value.toInt())
    fun putU64(offset: Int, value: Long) = buffer.putLong(offset, value)
    fun putI64(offset: Int, value: Long) = buffer.putLong(offset, value)
}

private fun ByteArray.copyFrom(source: ByteArray, offset: Int) {
    source.copyInto(this, offset)
}

private inline fun requireElf(condition: Boolean, message: () -> String) {
    if (!condition) failElf(message())
}

private fun failElf(message: String): Nothing =
    throw IllegalArgumentException("Cannot patch platform loader ELF: $message.")

private fun checkedAdd(left: Long, right: Long, owner: String): Long {
    requireElf(left >= 0 && right >= 0 && left <= Long.MAX_VALUE - right) { "$owner overflows" }
    return left + right
}

private fun checkedMultiply(left: Long, right: Long, owner: String): Long {
    requireElf(left >= 0 && right >= 0 && (left == 0L || right <= Long.MAX_VALUE / left)) { "$owner overflows" }
    return left * right
}

private fun alignUp(value: Long, alignment: Long, owner: String): Long {
    requireElf(isPowerOfTwo(alignment)) { "$owner uses invalid alignment $alignment" }
    val mask = alignment - 1
    return checkedAdd(value, mask, owner) and mask.inv()
}

private fun isPowerOfTwo(value: Long): Boolean = value > 0 && value and (value - 1) == 0L

private const val ELF_HEADER_SIZE = 64
private const val PROGRAM_HEADER_SIZE = 56
private const val SECTION_HEADER_SIZE = 64
private const val DYNAMIC_ENTRY_SIZE = 16
private const val SYMBOL_SIZE = 24
private const val MIN_LOAD_ALIGNMENT = 0x1000L

private const val EI_CLASS = 4
private const val EI_DATA = 5
private const val EI_VERSION = 6
private const val ELFCLASS64 = 2
private const val ELFDATA2LSB = 1
private const val EV_CURRENT = 1
private const val ET_DYN = 3
private const val EM_AARCH64 = 183
private const val E_TYPE = 16
private const val E_MACHINE = 18
private const val E_VERSION = 20
private const val E_PHOFF = 32
private const val E_SHOFF = 40
private const val E_EHSIZE = 52
private const val E_PHENTSIZE = 54
private const val E_PHNUM = 56
private const val E_SHENTSIZE = 58
private const val E_SHNUM = 60
private const val E_SHSTRNDX = 62
private const val PN_XNUM = 0xffff

private const val PT_LOAD = 1L
private const val PT_DYNAMIC = 2L
private const val PT_PHDR = 6L
private const val PF_R = 4L

private const val DT_NULL = 0L
private const val DT_NEEDED = 1L
private const val DT_HASH = 4L
private const val DT_STRTAB = 5L
private const val DT_SYMTAB = 6L
private const val DT_STRSZ = 10L
private const val DT_SYMENT = 11L
private const val DT_GNU_HASH = 0x6ffffef5L

private const val SHT_NULL = 0L
private const val SHT_STRTAB = 3L
private const val SHT_DYNAMIC = 6L
private const val SHT_NOBITS = 8L
private const val SHT_DYNSYM = 11L
private const val SHT_SYMTAB_SHNDX = 18L
private const val SH_TYPE = 4
private const val SH_FLAGS = 8
private const val SH_ADDR = 16
private const val SH_OFFSET = 24
private const val SH_SIZE = 32
private const val SH_LINK = 40
private const val SH_INFO = 44
private const val SH_ENTSIZE = 56
private const val SHF_WRITE = 1L
private const val SHN_UNDEF = 0
private const val SHN_XINDEX = 0xffff

private const val STB_GLOBAL = 1
private const val STB_WEAK = 2
private const val STV_DEFAULT = 0
private const val STV_PROTECTED = 3

private const val MESSAGE_TYPE_SYMBOL = "ovrMessageType_ToString"
private const val COMPANION_SONAME = "libovrplatformcompat.so"
private const val MICROPHONE_BUFFER_SIZE_SYMBOL = "ovr_Microphone_GetOutputBufferMaxSize"

private const val BUFFER_SIZE_CALL_INDEX = 3
private const val AARCH64_BL = 0x9400_0000L
private const val AARCH64_BL_MASK = 0xfc00_0000L

// stp x29, x30, [sp, #-16]!; mov x29, sp; ldr x0, [x0, #0x18]; bl AAudioStream_getFramesPerBurst;
// sxtw x0, w0; ldp x29, x30, [sp], #16; ret
private val UNCHECKED_BUFFER_SIZE_BODY = longArrayOf(
    0xa9bf_7bfdL, 0x9100_03fdL, 0xf940_0c00L, AARCH64_BL, 0x9340_7c00L, 0xa8c1_7bfdL, 0xd65f_03c0L,
)

// ldr x0, [x0, #0x18]; cbz x0, ret; stp x29, x30, [sp, #-16]!; bl AAudioStream_getFramesPerBurst;
// ldp x29, x30, [sp], #16; sxtw x0, w0; ret
private val GUARDED_BUFFER_SIZE_BODY = longArrayOf(
    0xf940_0c00L, 0xb400_00a0L, 0xa9bf_7bfdL, AARCH64_BL, 0xa8c1_7bfdL, 0x9340_7c00L, 0xd65f_03c0L,
)
