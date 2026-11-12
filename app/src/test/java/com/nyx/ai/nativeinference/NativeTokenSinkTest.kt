package com.nyx.ai.nativeinference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NativeTokenSinkTest {
    @Test fun everyByteBoundaryPreservesMultilingualText() {
        val text = "Hello café 世界 🦉\u0000!"
        val bytes = text.toByteArray(Charsets.UTF_8)
        for (split in 0..bytes.size) {
            val streamed = StringBuilder()
            val sink = NativeTokenSink { streamed.append(it) }
            sink.onBytes(bytes.copyOfRange(0, split))
            sink.onBytes(bytes.copyOfRange(split, bytes.size))
            assertEquals(text, sink.finish())
            assertEquals(text, streamed.toString())
        }
    }
    @Test fun byteAtATimePreservesEmoji() {
        val sink = NativeTokenSink { }
        "🦉界".toByteArray(Charsets.UTF_8).forEach { sink.onBytes(byteArrayOf(it)) }
        assertEquals("🦉界", sink.finish())
    }
    @Test fun malformedTailReplacedAtEndOnly() {
        val emitted = StringBuilder()
        val sink = NativeTokenSink { emitted.append(it) }
        sink.onBytes(byteArrayOf(0xe2.toByte()))
        assertEquals("", emitted.toString())
        assertEquals("\ufffd", sink.finish())
    }
    @Test fun callbackFailurePropagates() {
        val sink = NativeTokenSink { throw IllegalStateException("callback-test") }
        assertThrows(IllegalStateException::class.java) { sink.onBytes("x".toByteArray()) }
    }
}
