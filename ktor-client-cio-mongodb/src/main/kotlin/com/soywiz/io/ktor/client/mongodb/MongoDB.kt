package com.soywiz.io.ktor.client.mongodb

import com.soywiz.io.ktor.client.mongodb.bson.*
import com.soywiz.io.ktor.client.mongodb.util.*
import com.soywiz.io.ktor.client.util.*
import io.ktor.network.sockets.*
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.io.*
import kotlinx.io.core.*
import kotlinx.io.core.ByteOrder
import java.net.*

// https://docs.mongodb.com/manual/reference/mongodb-wire-protocol/
fun MongoDB(host: String = "127.0.0.1", port: Int = 27017) {
}

data class MongoPacket(
    val opcode: Int,
    val requestId: Int,
    val responseTo: Int,
    val payload: ByteArray
)

private val MONGO_MSG_HEAD_SIZE = 4 * 4

suspend fun ByteReadChannel.readRawMongoPacket(): MongoPacket {
    val head: ByteReadPacket = readPacket(MONGO_MSG_HEAD_SIZE).withByteOrder(ByteOrder.LITTLE_ENDIAN)
    val messageLength = head.readInt()
    val requestId = head.readInt()
    val responseTo = head.readInt()
    val opcode = head.readInt()
    val payload = readPacket(messageLength - MONGO_MSG_HEAD_SIZE)
    return MongoPacket(opcode, requestId, responseTo, payload.readBytes())
}

interface MongoResponsePacket
data class MongoReply(
    val responseFlags: Int,
    val cursorID: Long,
    val startingFrom: Int,
    val documents: List<Map<String, Any?>>
) : MongoResponsePacket

suspend fun ByteReadChannel.readParsedMongoPacket(): MongoResponsePacket {
    val packet = readRawMongoPacket()
    val pp = packet.payload.asReadPacket(ByteOrder.LITTLE_ENDIAN)
    when (packet.opcode) {
        MongoOps.OP_REPLY -> {
            val responseFlags = pp.readInt()
            val cursorID = pp.readLong()
            val startingFrom = pp.readInt()
            val numberReturned = pp.readInt()
            val documents = (0 until numberReturned).map { Bson.read(pp) }
            return MongoReply(responseFlags, cursorID, startingFrom, documents)
        }
        else -> error("Packet ${packet.opcode}")
    }
}

suspend fun ByteWriteChannel.writeRawMongoPacket(packet: MongoPacket) {
    writeByteOrder = ByteOrder.LITTLE_ENDIAN
    val packetBytes = buildPacket(byteOrder = ByteOrder.LITTLE_ENDIAN) {
        writeInt(MONGO_MSG_HEAD_SIZE + packet.payload.size)
        writeInt(packet.requestId)
        writeInt(packet.responseTo)
        writeInt(packet.opcode)
        writeFully(packet.payload)
    }.readBytes()
    println(packetBytes.hex)
    writeFully(packetBytes)
    flush()
    /*
    writeInt(MONGO_MSG_HEAD_SIZE + packet.payload.size)
    writeInt(packet.requestId)
    writeInt(packet.responseTo)
    writeInt(packet.opcode)
    writeFully(packet.payload)
    flush()
    */
}

suspend fun ByteWriteChannel.writeMongoOpQuery(
    requestId: Int,
    responseTo: Int,
    flags: Int,
    fullCollectionName: String,
    numberToSkip: Int,
    numberToReturn: Int,
    query: Map<String, Any?>,
    returnFieldsSelector: Map<String, Any?>? = null
) {
    writeRawMongoPacket(
        MongoPacket(
            opcode = MongoOps.OP_QUERY,
            requestId = requestId,
            responseTo = responseTo,
            payload = buildPacket(byteOrder = ByteOrder.LITTLE_ENDIAN) {
                Bson.apply {
                    writeInt(flags)
                    writeBsonCString(fullCollectionName)
                    writeInt(numberToSkip)
                    writeInt(numberToReturn)
                    writeBsonDocument(query)
                    if (returnFieldsSelector != null) {
                        writeBsonDocument(returnFieldsSelector)
                    }
                }
            }.readBytes()
        )
    )
}

object MongoOps {
    val OP_REPLY = 1
    val OP_UPDATE = 2001
    val OP_INSERT = 2002
    val RESERVED = 2003
    val OP_QUERY = 2004
    val OP_GET_MORE = 2005
    val OP_DELETE = 2006
    val OP_KILL_CURSORS = 2007
    val OP_COMMAND = 2010
    val OP_COMMANDREPLY = 2011
    val OP_MSG = 2013
}

object QueryFlags {
    val TAILABLE_CURSOR = (1 shl 1)
    val SLAVE_OK = (1 shl 2)
    val OP_LOG_REPLY = (1 shl 3)
    val NO_CURSOR_TIMEOUT = (1 shl 4)
    val AWAIT_DATA = (1 shl 5)
    val EXHAUST = (1 shl 6)
    val PARTIAL = (1 shl 7)
}

object MongoSandbox {
    @JvmStatic
    fun main(args: Array<String>) {
        runBlocking {
            val socket = aSocket().tcp().connect(InetSocketAddress("127.0.0.1", 27017))
            val read = socket.openReadChannel()
            val write = socket.openWriteChannel(autoFlush = true)
            write.writeMongoOpQuery(
                0, 0, 0, "admin.\$cmd", 0, 1, mapOf(
                    "isMaster" to true,
                    "client" to mapOf(
                        "application" to mapOf("name" to "ktor-client-cio-mongodb"),
                        //"application" to mapOf("name" to "MongoDB Shell"),
                        "driver" to mapOf(
                            "name" to "ktor-client-cio-mongodb",
                            "version" to "0.0.1"
                            //"name" to "MongoDB Internal Client",
                            //"version" to "3.6.4"
                        ),
                        "os" to mapOf(
                            "type" to "Darwin",
                            "name" to "Mac OS X",
                            "architecture" to "x86_64",
                            "version" to "17.5.0"
                        )
                    )
                )
            )
            val response = read.readParsedMongoPacket()
            println(response)
        }
    }
}