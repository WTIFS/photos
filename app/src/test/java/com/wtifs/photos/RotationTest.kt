package com.wtifs.photos

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class RotationTest {
    @Test fun orientationPatchPreservesJpegAndMotionTail() {
        for (order in listOf(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
            val tiff = ByteBuffer.allocate(26).order(order)
            tiff.put(if (order == ByteOrder.LITTLE_ENDIAN) 73.toByte() else 77.toByte())
            tiff.put(if (order == ByteOrder.LITTLE_ENDIAN) 73.toByte() else 77.toByte())
            tiff.putShort(42).putInt(8).putShort(1)
            tiff.putShort(274).putShort(3).putInt(1).putShort(1).putShort(0).putInt(0)
            val bytes = byteArrayOf(-1, -40, -1, -31, 0, 34) +
                byteArrayOf(69, 120, 105, 102, 0, 0) + tiff.array() +
                byteArrayOf(-1, -38, 12, 34, -1, -39, 0, 0, 0, 24, 102, 116, 121, 112)
            val file = File.createTempFile("rotate-test", ".jpg")
            try {
                file.writeBytes(bytes)
                assertTrue(patchJpegOrientation(file, 8))
                val expected = bytes.copyOf()
                val value = ByteBuffer.allocate(2).order(order).putShort(8).array()
                expected[30] = value[0]
                expected[31] = value[1]
                assertArrayEquals(expected, file.readBytes())
            } finally { file.delete() }
        }
    }

    @Test fun missingOrientationDoesNotModifyFile() {
        val file = File.createTempFile("rotate-test", ".jpg")
        val bytes = byteArrayOf(-1, -40, -1, -38, 12, 34)
        try {
            file.writeBytes(bytes)
            assertFalse(patchJpegOrientation(file, 8))
            assertArrayEquals(bytes, file.readBytes())
        } finally { file.delete() }
    }
}
