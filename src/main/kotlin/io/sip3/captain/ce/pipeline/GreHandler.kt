/*
 * Copyright 2018-2020 SIP3.IO, Inc.
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

import io.sip3.captain.ce.domain.Packet
import io.sip3.commons.domain.payload.Encodable
import io.vertx.core.Vertx

/**
 * Handles GRE(Generic Routing Encapsulation) packets
 */
class GreHandler(vertx: Vertx, bulkOperationsEnabled: Boolean) : Handler(vertx, bulkOperationsEnabled) {

    companion object {

        const val TYPE_ERSPAN = 0x88be
        const val TYPE_IPV4 = 0x0800
    }

    private var bulkSize = 1

    private val erspanHandler: ErspanHandler by lazy {
        ErspanHandler(vertx, bulkOperationsEnabled)
    }
    private val ipv4Handler: Ipv4Handler by lazy {
        Ipv4Handler(vertx, bulkOperationsEnabled)
    }

    init {
        if (bulkOperationsEnabled) {
            vertx.orCreateContext.config().getJsonObject("gre")?.let { config ->
                config.getInteger("bulk-size")?.let { bulkSize = it }
            }
        }
    }

    override fun onPacket(packet: Packet) {
        val buffer = (packet.payload as Encodable).encode()

        // Flags
        val flags = buffer.readByte().toInt()
        val checksumFlag = (flags and 0x80) > 0
        val keyFlag = (flags and 0x20) > 0
        val sequenceNumberFlag = (flags and 0x10) > 0

        // Reserved0 & Version
        buffer.skipBytes(1)

        // ProtocolType
        val protocolType = buffer.readUnsignedShort()

        // Checksum & Reserved1 & Key & Sequence Number
        if (checksumFlag) {
            buffer.skipBytes(4)
        }
        if (keyFlag) {
            buffer.skipBytes(4)
        }
        if (sequenceNumberFlag) {
            buffer.skipBytes(4)
        }

        when (protocolType) {
            TYPE_ERSPAN -> erspanHandler.handle(packet)
            TYPE_IPV4 -> ipv4Handler.handle(packet)
        }
    }
}