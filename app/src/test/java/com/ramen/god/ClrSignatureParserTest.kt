package com.ramen.god

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClrSignatureParserTest {
    @Test
    fun parsesPrimitiveFieldSignature() {
        val result = ClrSignatureParser.parseField(byteArrayOf(0x06, 0x08))
        assertEquals("Int32", result?.fieldType)
    }

    @Test
    fun parsesInstanceMethodWithPrimitiveParameter() {
        val result = ClrSignatureParser.parseMethod(byteArrayOf(0x20, 0x01, 0x01, 0x08))
        requireNotNull(result)
        assertEquals("Void", result.returnType)
        assertEquals(listOf("Int32"), result.parameterTypes)
        assertEquals("default", result.callingConvention)
        assertEquals(true, result.hasThis)
        assertEquals("instance Void TakeDamage(Int32)", result.display("TakeDamage"))
    }

    @Test
    fun parsesSingleDimensionalArrayParameter() {
        val result = ClrSignatureParser.parseMethod(byteArrayOf(0x00, 0x01, 0x01, 0x1d, 0x08))
        assertEquals(listOf("Int32[]"), result?.parameterTypes)
    }

    @Test
    fun parsesGenericMethodVariable() {
        // GENERIC method, one generic parameter, zero method parameters, return type MVAR 0 (!!0).
        val result = ClrSignatureParser.parseMethod(byteArrayOf(0x10, 0x01, 0x00, 0x1e, 0x00))
        requireNotNull(result)
        assertEquals("!!0", result.returnType)
        assertEquals(1, result.genericParameterCount)
    }

    @Test
    fun parsesGenericTypeVariable() {
        // A method signature whose return type is VAR 0 (!0), a generic parameter of the declaring type.
        val result = ClrSignatureParser.parseMethod(byteArrayOf(0x00, 0x00, 0x13, 0x00))
        assertEquals("!0", result?.returnType)
    }

    @Test
    fun rejectsTruncatedAndUnknownSignatures() {
        assertNull(ClrSignatureParser.parseField(byteArrayOf(0x06)))
        assertNull(ClrSignatureParser.parseMethod(byteArrayOf(0x00, 0x02, 0x01, 0x08)))
        assertNull(ClrSignatureParser.parseMethod(byteArrayOf(0x00, 0x00, 0x01, 0x50)))
        // A zero-parameter signature cannot contain a trailing parameter type after its return type.
        assertNull(ClrSignatureParser.parseMethod(byteArrayOf(0x10, 0x01, 0x00, 0x01, 0x13, 0x00)))
    }
}
