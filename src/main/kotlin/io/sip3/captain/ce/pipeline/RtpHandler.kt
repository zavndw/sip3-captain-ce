/*
 * Copyright 2018-2021 SIP3.IO, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.sip3.captain.ce.pipeline

import io.netty.buffer.ByteBuf
import io.sip3.captain.ce.RoutesCE
import io.sip3.captain.ce.domain.Packet
import io.sip3.captain.ce.recording.RecordingManager
import io.sip3.commons.PacketTypes
import io.sip3.commons.domain.payload.Encodable
import io.sip3.commons.domain.payload.RtpHeaderPayload
import io.sip3.commons.util.toIntRange
import io.sip3.commons.vertx.util.localSend
import io.vertx.core.Context
import kotlin.experimental.and

/**
 * Handles RTP packets
 */
class RtpHandler(context: Context, bulkOperationsEnabled: Boolean) : Handler(context, bulkOperationsEnabled) {

    private val packets = mutableMapOf<Long, MutableList<Packet>>()
    private var bulkSize = 1

    private var instances: Int = 1
    private var payloadTypes = mutableSetOf<Byte>()
    private var collectorEnabled = false

    private val vertx = context.owner()
    private val recordingManager = RecordingManager.getInstance(vertx)

    init {
        context.config().getJsonObject("vertx")?.getInteger("instances")?.let {
            instances = it
        }
        context.config().getJsonObject("rtp")?.let { config ->
            if (bulkOperationsEnabled) {
                config.getInteger("bulk-size")?.let {
                    bulkSize = it
                }
            }

            config.getJsonArray("payload-types")?.forEach { payloadType ->
                when (payloadType) {
                    is Int -> payloadTypes.add(payloadType.toByte())
                    is String -> {
                        payloadType.toIntRange().forEach { payloadTypes.add(it.toByte()) }
                    }
                }
            }

            config.getJsonObject("collector")?.getBoolean("enabled")?.let {
                collectorEnabled = it
            }
        }
    }

    override fun onPacket(packet: Packet) {
        // Retrieve RTP packet buffer and mark it for further usage in `RecordingHandler`
        val buffer = (packet.payload as Encodable).encode()
        packet.apply {
            protocolCode = PacketTypes.RTP
            recordingMark = buffer.readerIndex()
        }

        // Read RTP header
        val header = readRtpHeader(buffer)

        // Filter non-RTP packets
        if (header.ssrc <= 0 || header.payloadType < 0) {
            return
        }

        // Filter packets by payloadType
        if (payloadTypes.isNotEmpty() && !payloadTypes.contains(header.payloadType)) {
            return
        }

        if (recordingManager.check(packet)) {
            recordingManager.record(packet.copy())
        }

        if (collectorEnabled) {
            val index = header.ssrc % instances

            val packetsByIndex = packets.getOrPut(index) { mutableListOf() }
            packet.apply {
                payload = header
            }
            packetsByIndex.add(packet)

            if (packetsByIndex.size >= bulkSize) {
                vertx.eventBus().localSend(RoutesCE.rtp + "_$index", packetsByIndex.toList())
                packetsByIndex.clear()
            }
        }
    }

    private fun readRtpHeader(buffer: ByteBuf): RtpHeaderPayload {
        return RtpHeaderPayload().apply {
            // Version & P & X & CC
            val flags = buffer.readByte()
            val x = (flags.and(16) == 16.toByte())
            val cc = flags.and(15)
            // Marker & Payload Type
            buffer.readUnsignedByte().let { byte ->
                marker = (byte.and(128) == 128.toShort())
                payloadType = byte.and(127).toByte()
            }
            // Sequence Number
            sequenceNumber = buffer.readUnsignedShort()
            // Timestamp
            timestamp = buffer.readUnsignedInt()
            // SSRC
            ssrc = buffer.readUnsignedInt()
            // CSRC
            if (cc > 0) buffer.skipBytes(cc * 4)
            // Header Extension
            if (x) {
                // Profile-Specific Identifier
                buffer.skipBytes(2)
                // Length
                val length = buffer.readUnsignedShort()
                // Extension Header
                buffer.skipBytes(4 * length)
            }
        }
    }
}