/*
 * Copyright 2018-2019 SIP3.IO, Inc.
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

import io.mockk.*
import io.mockk.junit5.MockKExtension
import io.netty.buffer.Unpooled
import io.sip3.captain.ce.VertxTest
import io.sip3.captain.ce.domain.Packet
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.sql.Timestamp

@ExtendWith(MockKExtension::class)
class FragmentHandlerTest : VertxTest() {

    companion object {

        // Payload: IPv4 (Fragment 1/2)
        val PACKET_1 = byteArrayOf(
                0x45.toByte(), 0xa0.toByte(), 0x00.toByte(), 0x1c.toByte(), 0xe8.toByte(), 0xdd.toByte(), 0x20.toByte(),
                0x00.toByte(), 0x3c.toByte(), 0x11.toByte(), 0x75.toByte(), 0x6e.toByte(), 0x0a.toByte(), 0xfa.toByte(),
                0xf4.toByte(), 0x05.toByte(), 0x0a.toByte(), 0xc5.toByte(), 0x15.toByte(), 0x75.toByte(), 0x32.toByte(),
                0x40.toByte(), 0xe8.toByte(), 0x3c.toByte(), 0x00.toByte(), 0xb4.toByte(), 0x13.toByte(), 0x0b.toByte()
        )

        // Payload: IPv4 (Fragment 2/2)
        val PACKET_2 = byteArrayOf(
                0x45.toByte(), 0xa0.toByte(), 0x00.toByte(), 0x1c.toByte(), 0xe8.toByte(), 0xdd.toByte(), 0x00.toByte(),
                0x01.toByte(), 0x3c.toByte(), 0x11.toByte(), 0x75.toByte(), 0x6e.toByte(), 0x0a.toByte(), 0xfa.toByte(),
                0xf4.toByte(), 0x05.toByte(), 0x0a.toByte(), 0xc5.toByte(), 0x15.toByte(), 0x75.toByte(), 0x32.toByte(),
                0x40.toByte(), 0xe8.toByte(), 0x3c.toByte(), 0x00.toByte(), 0xb4.toByte(), 0x13.toByte(), 0x0b.toByte()
        )
    }

    @Test
    fun `Assemble 2 fragments sent in order`() {
        val expectedTimestamp = Timestamp(System.currentTimeMillis() - 10000)
        val protocolNumberSlotSlot = slot<Int>()
        val packetSlot = slot<Packet>()
        runTest(
                deploy = {
                    mockkConstructor(Ipv4Handler::class)
                    every {
                        anyConstructed<Ipv4Handler>().onDefragmentedPacket(capture(protocolNumberSlotSlot), any(), capture(packetSlot))
                    } just Runs
                    vertx.deployTestVerticle(FragmentHandler::class)
                },
                execute = {
                    val ipv4Handler = Ipv4Handler(vertx, false)
                    val buffer1 = Unpooled.wrappedBuffer(PACKET_1)
                    val packet1 = Packet().apply {
                        timestamp = expectedTimestamp
                    }
                    ipv4Handler.handle(buffer1, packet1)
                    val buffer2 = Unpooled.wrappedBuffer(PACKET_2)
                    val packet2 = Packet().apply {
                        timestamp = Timestamp(System.currentTimeMillis())
                    }
                    ipv4Handler.handle(buffer2, packet2)
                },
                assert = {
                    vertx.executeBlocking<Any>({
                        context.verify {
                            verify(timeout = 10000) { anyConstructed<Ipv4Handler>().onDefragmentedPacket(any(), any(), any()) }
                            val protocolNumber = protocolNumberSlotSlot.captured
                            assertEquals(Ipv4Handler.TYPE_UDP, protocolNumber)
                            val packet = packetSlot.captured
                            assertEquals(expectedTimestamp, packet.timestamp)
                        }
                        context.completeNow()
                    }, {})
                }
        )
    }

    @Test
    fun `Assemble 2 fragments sent not in order`() {
        val expectedTimestamp = Timestamp(System.currentTimeMillis() - 10000)
        val protocolNumberSlotSlot = slot<Int>()
        val packetSlot = slot<Packet>()
        runTest(
                deploy = {
                    mockkConstructor(Ipv4Handler::class)
                    every {
                        anyConstructed<Ipv4Handler>().onDefragmentedPacket(capture(protocolNumberSlotSlot), any(), capture(packetSlot))
                    } just Runs
                    vertx.deployTestVerticle(FragmentHandler::class)
                },
                execute = {
                    val ipv4Handler = Ipv4Handler(vertx, false)
                    val buffer2 = Unpooled.wrappedBuffer(PACKET_2)
                    val packet2 = Packet().apply {
                        timestamp = expectedTimestamp
                    }
                    ipv4Handler.handle(buffer2, packet2)
                    val buffer1 = Unpooled.wrappedBuffer(PACKET_1)
                    val packet1 = Packet().apply {
                        timestamp = Timestamp(System.currentTimeMillis())
                    }
                    ipv4Handler.handle(buffer1, packet1)
                },
                assert = {
                    vertx.executeBlocking<Any>({
                        context.verify {
                            verify(timeout = 10000) { anyConstructed<Ipv4Handler>().onDefragmentedPacket(any(), any(), any()) }
                            val protocolNumber = protocolNumberSlotSlot.captured
                            assertEquals(Ipv4Handler.TYPE_UDP, protocolNumber)
                            val packet = packetSlot.captured
                            assertEquals(expectedTimestamp, packet.timestamp)
                        }
                        context.completeNow()
                    }, {})
                }
        )
    }

    @AfterEach
    fun `Unmock all`() {
        unmockkAll()
    }
}