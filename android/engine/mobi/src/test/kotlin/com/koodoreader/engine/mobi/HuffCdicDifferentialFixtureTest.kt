package com.koodoreader.engine.mobi

import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File

class HuffCdicDifferentialFixtureTest {

    private data class Case(
        val name: String,
        val records: List<ByteArray>,
        val input: ByteArray,
    )

    @Test
    fun `write foliate differential fixture`() {
        val cases = listOf(
            Case("direct-table1", HuffCdicTestFixtures.twoSymbolBook(), byteArrayOf(0x80.toByte())),
            Case("table2-codeLength32", HuffCdicTestFixtures.longestCodeBook(), ByteArray(4)),
            Case(
                "nested-compressed-slice",
                HuffCdicTestFixtures.nestedSliceBook(),
                byteArrayOf(0xff.toByte()),
            ),
        )
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.write("HCDIF01\u0000".toByteArray(Charsets.US_ASCII))
            output.writeInt(cases.size)
            for (case in cases) {
                output.writeBlob(case.name)
                output.writeInt(case.records.size)
                for (record in case.records) output.writeBlob(record)
                output.writeBlob(case.input)
                output.writeBlob(HuffCdicDecoder(case.records).decompress(case.input))
            }
        }
        val fixture = File("build/huffcdic-diff-fixture.bin")
        fixture.parentFile.mkdirs()
        fixture.writeBytes(bytes.toByteArray())
    }

    private fun DataOutputStream.writeBlob(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataOutputStream.writeBlob(value: ByteArray) {
        writeInt(value.size)
        write(value)
    }
}
