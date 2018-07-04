package io.ktor.experimental.client.redis.protocol

import io.ktor.cio.*
import io.ktor.experimental.client.redis.*
import kotlinx.coroutines.experimental.io.*
import kotlinx.io.core.*
import kotlinx.io.pool.*
import java.nio.*
import java.nio.ByteBuffer
import java.nio.charset.*


internal suspend fun ByteReadChannel.readRedisMessage(
    decoder: CharsetDecoder
): Any? {
    val type = RedisType.fromCode(readByte())
    val line = readCRLFLine(decoder)
    return when (type) {
        RedisType.STRING -> line
        RedisType.ERROR -> throw RedisException(line.substring(1))
        RedisType.NUMBER -> line.toLong()
        RedisType.BULK -> {
            val size = line.toInt()
            if (size < 0) return null

            val data = readPacket(size).readBytes()
            readShort() // Skip CRLF
            decoder.decode(ByteBuffer.wrap(data))
        }
        RedisType.ARRAY -> {
            val arraySize = line.toInt()
            (0 until arraySize).map { readRedisMessage(decoder) }
        }
    }
}

private suspend fun ByteReadChannel.readCRLFLine(
    decoder: CharsetDecoder,
    pool: ObjectPool<ByteBuffer> = RedisBufferPool,
    charPool: ObjectPool<CharBuffer> = RedisCharBufferPool
): String {
    val EOL = ByteBuffer.wrap(REDIS_EOL)!!
    val result = StringBuilder()
    val buffer = pool.borrow()
    val charBuffer = charPool.borrow()

    decoder.reset()
    try {
        while (true) {
            buffer.clear()
            val count = readUntilDelimiter(EOL, buffer)

            if (count <= 0) {
                if (count == 0) readShort() // CRLF

                charBuffer.clear()
                decoder.decode(buffer, charBuffer, true)
                charBuffer.flip()
                result.append(charBuffer)
                break
            }

            while (buffer.hasRemaining()) {
                charBuffer.clear()
                decoder.decode(buffer, charBuffer, false)
                charBuffer.flip()
                result.append(charBuffer)
            }
        }

        return result.toString()
    } finally {
        pool.recycle(buffer)
        charPool.recycle(charBuffer)
    }
}

