package com.ramen.god

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClrMetadataParserTest {
    @Test
    fun readsAssemblyIdentityTypeNamesAndAssemblyReferences() {
        val metadata = syntheticMetadataRoot()
        val result = ClrMetadataParser.parse(
            metadata, 0, metadata.size.toLong(), runtimeVersion = "4.0", clrFlags = 1
        )

        assertNotNull(result)
        requireNotNull(result)
        assertEquals("Assembly-CSharp", result.name)
        assertEquals("1.2.3.4", result.version)
        assertEquals("Sample.dll", result.moduleName)
        assertEquals("4.0", result.runtimeVersion)
        assertEquals(1, result.typeCount)
        assertEquals("PlayerController", result.types.single().name)
        assertEquals("Game", result.types.single().namespace)
        assertEquals("health", result.fields.single().name)
        assertEquals(6, result.fields.single().flags)
        assertEquals("TakeDamage", result.methods.single().name)
        assertEquals(0x1234L, result.methods.single().relativeVirtualAddress)
        assertEquals(0x16, result.methods.single().flags)
        assertEquals("mscorlib", result.assemblyReferences.single().name)
        assertEquals("4.0.0.0", listOf(
            result.assemblyReferences.single().majorVersion,
            result.assemblyReferences.single().minorVersion,
            result.assemblyReferences.single().buildNumber,
            result.assemblyReferences.single().revisionNumber
        ).joinToString("."))
        assertEquals("parsed", result.status)
    }

    @Test
    fun reportsMetadataOutsideBoundedReadWindow() {
        val result = ClrMetadataParser.parse(ByteArray(32), 16, 1024, "4.0", 0)
        assertNotNull(result)
        assertEquals("metadata-outside-read-window", result?.status)
    }

    @Test
    fun rejectsInvalidMetadataSignatureWithoutThrowing() {
        val result = ClrMetadataParser.parse(ByteArray(64), 0, 64, "4.0", 0)
        assertNotNull(result)
        assertEquals("invalid-metadata-signature", result?.status)
    }

    private fun syntheticMetadataRoot(): ByteArray {
        val strings = ByteArrayOutputStream().apply {
            write(0)
        }
        fun addString(value: String): Int {
            val index = strings.size()
            strings.write(value.toByteArray(Charsets.UTF_8))
            strings.write(0)
            return index
        }
        val moduleName = addString("Sample.dll")
        val typeName = addString("PlayerController")
        val namespace = addString("Game")
        val assemblyName = addString("Assembly-CSharp")
        val referenceName = addString("mscorlib")
        val fieldName = addString("health")
        val methodName = addString("TakeDamage")
        val stringHeap = strings.toByteArray()

        val tables = ByteArrayOutputStream().apply {
            u32(0)
            u8(2); u8(0); u8(0); u8(1)
            u64((1L shl 0) or (1L shl 2) or (1L shl 4) or (1L shl 6) or (1L shl 32) or (1L shl 35))
            u64(0)
            u32(1); u32(1); u32(1); u32(1); u32(1); u32(1)

            // Module row
            u16(0); u16(moduleName); u16(0); u16(0); u16(0)

            // TypeDef row: public class Game.PlayerController
            u32(1); u16(typeName); u16(namespace); u16(0); u16(1); u16(1)

            // Field row: private Int32 health
            u16(6); u16(fieldName); u16(0)

            // MethodDef row: TakeDamage
            u32(0x1234); u16(0); u16(0x16); u16(methodName); u16(0); u16(1)

            // Assembly row: Assembly-CSharp, Version=1.2.3.4
            u32(0); u16(1); u16(2); u16(3); u16(4); u32(0)
            u16(0); u16(assemblyName); u16(0)

            // AssemblyRef row: mscorlib, Version=4.0.0.0
            u16(4); u16(0); u16(0); u16(0); u32(0)
            u16(0); u16(referenceName); u16(0); u16(0)
        }.toByteArray()

        val prefix = ByteArrayOutputStream().apply {
            u32(0x424a5342L)
            u16(1); u16(1); u32(0)
            u32(8)
            write(byteArrayOf('v'.code.toByte(), '4'.code.toByte(), '.'.code.toByte(), '0'.code.toByte(), 0, 0, 0, 0))
            u16(0); u16(2)
        }.toByteArray()

        fun paddedStreamHeaderLength(name: String): Int = 8 + (((name.length + 1 + 3) / 4) * 4)
        val headerLength = paddedStreamHeaderLength("#~") + paddedStreamHeaderLength("#Strings")
        val tablesOffset = prefix.size + headerLength
        val stringsOffset = tablesOffset + tables.size
        val headers = ByteArrayOutputStream().apply {
            streamHeader(tablesOffset, tables.size, "#~")
            streamHeader(stringsOffset, stringHeap.size, "#Strings")
        }.toByteArray()
        return prefix + headers + tables + stringHeap
    }

    private fun ByteArrayOutputStream.streamHeader(offset: Int, size: Int, name: String) {
        u32(offset.toLong())
        u32(size.toLong())
        write(name.toByteArray(Charsets.US_ASCII))
        write(0)
        while (this.size() % 4 != 0) write(0)
    }

    private fun ByteArrayOutputStream.u8(value: Int) = write(value and 0xff)
    private fun ByteArrayOutputStream.u16(value: Int) {
        u8(value); u8(value ushr 8)
    }
    private fun ByteArrayOutputStream.u32(value: Long) {
        u8(value.toInt()); u8((value ushr 8).toInt())
        u8((value ushr 16).toInt()); u8((value ushr 24).toInt())
    }
    private fun ByteArrayOutputStream.u64(value: Long) {
        u32(value); u32(value ushr 32)
    }
}
