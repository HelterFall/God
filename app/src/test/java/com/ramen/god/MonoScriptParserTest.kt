package com.ramen.god

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream

class MonoScriptParserTest {
    @Test
    fun parsesLittleEndianMonoScriptPayload() {
        val payload = buildPayload(true)
        val parsed = MonoScriptParser.parse(payload, true)
        requireNotNull(parsed)
        assertEquals("SpriteSkin", parsed.first)
        assertEquals(-15, parsed.second)
        assertEquals("SpriteSkin", parsed.third[0])
        assertEquals("UnityEngine.U2D.Animation", parsed.third[1])
        assertEquals("Unity.2D.Animation.Runtime", parsed.third[2])
    }

    @Test
    fun parsesBigEndianMonoScriptPayload() {
        val parsed = requireNotNull(MonoScriptParser.parse(buildPayload(false), false))
        assertEquals("SpriteSkin", parsed.first)
        assertEquals(-15, parsed.second)
        assertEquals("SpriteSkin", parsed.third[0])
        assertEquals("UnityEngine.U2D.Animation", parsed.third[1])
        assertEquals("Unity.2D.Animation.Runtime", parsed.third[2])
    }

    @Test
    fun readsBoundedObjectFromDataSectionStream() {
        val payload = buildPayload(true)
        val dataOffset = 16L
        val byteStart = 8L
        val prefix = ByteArray((dataOffset + byteStart).toInt())
        val fileBytes = prefix + payload
        val result = MonoScriptParser.readObjects(
            ByteArrayInputStream(fileBytes),
            dataOffset,
            fileBytes.size.toLong(),
            listOf(MonoScriptObjectTarget(123L, byteStart, payload.size.toLong())),
            true
        )
        assertEquals(1, result.scripts.size)
        assertEquals(123L, result.scripts.single().pathId)
        assertEquals("SpriteSkin", result.scripts.single().className)
        assertEquals(0, result.skippedCount)
    }

    @Test
    fun rejectsMalformedPayloadsAndOutOfRangeObjects() {
        val malformed = ByteArray(8)
        put32(malformed, 0, 0x7fffffffL, true)
        assertNull(MonoScriptParser.parse(malformed, true))

        val result = MonoScriptParser.readObjects(
            ByteArrayInputStream(ByteArray(32)),
            16L,
            32L,
            listOf(MonoScriptObjectTarget(1L, 100L, 12L)),
            true
        )
        assertTrue(result.scripts.isEmpty())
        assertEquals(1, result.skippedCount)
    }

    @Test
    fun skipsObjectTargetsBeyondTraversalLimit() {
        val result = MonoScriptParser.readObjects(
            ByteArrayInputStream(ByteArray(0)),
            0L,
            Long.MAX_VALUE,
            listOf(MonoScriptObjectTarget(1L, 64L * 1024L * 1024L + 1L, 10L)),
            true
        )
        assertTrue(result.scripts.isEmpty())
        assertEquals(1, result.skippedCount)
    }

    private fun buildPayload(little: Boolean): ByteArray {
        val output = ByteArrayOutputStream()
        writeString(output, "SpriteSkin", little, true)
        write32(output, -15, little)
        output.write(ByteArray(16) { it.toByte() }) // properties hash
        writeString(output, "SpriteSkin", little, true)
        writeString(output, "UnityEngine.U2D.Animation", little, true)
        writeString(output, "Unity.2D.Animation.Runtime", little, false)
        return output.toByteArray()
    }

    private fun writeString(output: ByteArrayOutputStream, value: String, little: Boolean, align: Boolean) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        write32(output, bytes.size, little)
        output.write(bytes)
        if (align) while (output.size() % 4 != 0) output.write(0)
    }

    private fun write32(output: ByteArrayOutputStream, value: Int, little: Boolean) {
        val bytes = ByteArray(4)
        for (i in 0 until 4) {
            val shift = if (little) i * 8 else (3 - i) * 8
            bytes[i] = (value ushr shift).toByte()
        }
        output.write(bytes)
    }

    private fun put32(bytes: ByteArray, offset: Int, value: Long, little: Boolean) {
        for (i in 0 until 4) {
            val shift = if (little) i * 8 else (3 - i) * 8
            bytes[offset + i] = (value ushr shift).toByte()
        }
    }
}
