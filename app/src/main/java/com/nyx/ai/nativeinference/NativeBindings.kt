package com.nyx.ai.nativeinference

import androidx.annotation.Keep
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

@Keep
internal object NativeBindings {
    private val library by lazy { System.loadLibrary("nyx_llama") }
    fun ensureLibrary() { library }
    external fun beginOperation()
    external fun cancel()
    external fun load(path: ByteArray, context: Int, threads: Int): Int
    external fun unload()
    external fun generate(roles: Array<ByteArray>, contents: Array<ByteArray>, temperature: Float, maxTokens: Int, seed: Int, sink: NativeTokenSink): Int
}

/** Native token pieces are raw UTF-8, not JNI modified UTF-8. A code point may span pieces. */
@Keep
internal class NativeTokenSink(private val emit: (String) -> Unit) {
    private val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var pending = ByteArray(0)
    private val result = StringBuilder()
    @Keep
    fun onBytes(piece: ByteArray) {
        require(piece.size <= 65536)
        decode(pending + piece, false)
    }
    fun finish(): String {
        decode(pending, true)
        val chars = CharBuffer.allocate(16)
        decoder.flush(chars)
        chars.flip()
        append(chars.toString())
        return result.toString()
    }
    private fun decode(bytes: ByteArray, end: Boolean) {
        val input = ByteBuffer.wrap(bytes)
        val chars = CharBuffer.allocate(bytes.size + 16)
        val state = decoder.decode(input, chars, end)
        check(!state.isError && !state.isOverflow) { "UTF-8 output decoding failed" }
        chars.flip()
        append(chars.toString())
        pending = ByteArray(input.remaining()).also { input.get(it) }
        check(pending.size <= 4) { "Invalid UTF-8 pending tail" }
    }
    private fun append(text: String) {
        if (text.isEmpty()) return
        check(result.length + text.length <= 1024 * 1024) { "Native output exceeds 1 MiB" }
        result.append(text)
        emit(text)
    }
}
