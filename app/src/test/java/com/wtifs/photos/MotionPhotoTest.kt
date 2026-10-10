package com.wtifs.photos

import java.io.File
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class MotionPhotoTest {
    private fun box(type: String, payload: ByteArray = byteArrayOf()) =
        ByteBuffer.allocate(8 + payload.size).putInt(8 + payload.size)
            .put(type.toByteArray(Charsets.US_ASCII)).put(payload).array()

    // Metadata includes a fake end marker and ftyp; scan includes stuffed FF and a restart.
    private val jpeg = byteArrayOf(-1, -40, -1, -31, 0, 12, -1, -39) +
        "ftypmp42".toByteArray() + byteArrayOf(-1, -38, 0, 2, 1, -1, 0, 2, -1, -48, 3, -1, -39)
    private val video = box("ftyp", "mp42\u0000\u0000\u0000\u0000".toByteArray()) + box("moov") + box("mdat", byteArrayOf(1, 2, 3))

    @Test fun removesOnlyAppendedVideo() = withFile(jpeg + video) { file ->
        assertEquals(jpeg.size.toLong(), jpegEnd(file))
        removeMotionTail(file)
        assertArrayEquals(jpeg, file.readBytes())
    }

    @Test fun preservesPaddingBeforeVideo() = withFile(jpeg + byteArrayOf(0, 0) + video) { file ->
        removeMotionTail(file)
        assertArrayEquals(jpeg + byteArrayOf(0, 0), file.readBytes())
    }

    @Test fun rejectsStillTruncatedOrFakeVideoWithoutChangingFile() {
        for (bytes in listOf(jpeg, jpeg + video.copyOf(video.size - 1), jpeg + box("ftyp", ByteArray(8)), jpeg.copyOf(jpeg.size - 1) + video)) {
            withFile(bytes) { file ->
                assertThrows(IllegalArgumentException::class.java) { removeMotionTail(file) }
                assertArrayEquals(bytes, file.readBytes())
            }
        }
    }

    private fun withFile(bytes: ByteArray, check: (File) -> Unit) {
        val file = File.createTempFile("motion-test", ".jpg")
        try { file.writeBytes(bytes); check(file) } finally { file.delete() }
    }
}
