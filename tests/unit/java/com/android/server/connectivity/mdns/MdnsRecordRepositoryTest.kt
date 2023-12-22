/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.connectivity.mdns

import android.net.InetAddresses.parseNumericAddress
import android.net.LinkAddress
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.HandlerThread
import com.android.server.connectivity.mdns.MdnsAnnouncer.AnnouncementInfo
import com.android.server.connectivity.mdns.MdnsRecordRepository.Dependencies
import com.android.server.connectivity.mdns.MdnsRecordRepository.getReverseDnsAddress
import com.android.server.connectivity.mdns.MdnsServiceInfo.TextEntry
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import com.google.common.truth.Truth.assertThat
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.Collections
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

private const val TEST_SERVICE_ID_1 = 42
private const val TEST_SERVICE_ID_2 = 43
private const val TEST_SERVICE_ID_3 = 44
private const val TEST_PORT = 12345
private const val TEST_SUBTYPE = "_subtype"
private const val TEST_SUBTYPE2 = "_subtype2"
// RFC6762 10. Resource Record TTL Values and Cache Coherency
// The recommended TTL value for Multicast DNS resource records with a host name as the resource
// record's name (e.g., A, AAAA, HINFO) or a host name contained within the resource record's rdata
// (e.g., SRV, reverse mapping PTR record) SHOULD be 120 seconds. The recommended TTL value for
// other Multicast DNS resource records is 75 minutes.
private const val LONG_TTL = 4_500_000L
private const val SHORT_TTL = 120_000L
private val TEST_HOSTNAME = arrayOf("Android_000102030405060708090A0B0C0D0E0F", "local")
private val TEST_ADDRESSES = listOf(
        LinkAddress(parseNumericAddress("192.0.2.111"), 24),
        LinkAddress(parseNumericAddress("2001:db8::111"), 64),
        LinkAddress(parseNumericAddress("2001:db8::222"), 64))

private val TEST_SERVICE_1 = NsdServiceInfo().apply {
    serviceType = "_testservice._tcp"
    serviceName = "MyTestService"
    port = TEST_PORT
}

private val TEST_SERVICE_2 = NsdServiceInfo().apply {
    serviceType = "_testservice._tcp"
    serviceName = "MyOtherTestService"
    port = TEST_PORT
}

private val TEST_SERVICE_3 = NsdServiceInfo().apply {
    serviceType = "_TESTSERVICE._tcp"
    serviceName = "MyTESTSERVICE"
    port = TEST_PORT
}

@RunWith(DevSdkIgnoreRunner::class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.S_V2)
class MdnsRecordRepositoryTest {
    private val thread = HandlerThread(MdnsRecordRepositoryTest::class.simpleName)
    private val deps = object : Dependencies() {
        override fun getInterfaceInetAddresses(iface: NetworkInterface) =
                Collections.enumeration(TEST_ADDRESSES.map { it.address })
    }
    private val flags = MdnsFeatureFlags.newBuilder().build()

    @Before
    fun setUp() {
        thread.start()
    }

    @After
    fun tearDown() {
        thread.quitSafely()
        thread.join()
    }

    @Test
    fun testAddServiceAndProbe() {
        val repository = MdnsRecordRepository(thread.looper, deps, TEST_HOSTNAME, flags)
        assertEquals(0, repository.servicesCount)
        assertEquals(-1, repository.addService(TEST_SERVICE_ID_1, TEST_SERVICE_1))
        assertEquals(1, repository.servicesCount)

        val probingInfo = repository.setServiceProbing(TEST_SERVICE_ID_1)
        assertNotNull(probingInfo)
        assertTrue(repository.isProbing(TEST_SERVICE_ID_1))

        assertEquals(TEST_SERVICE_ID_1, probingInfo.serviceId)
        val packet = probingInfo.getPacket(0)

        assertEquals(0, packet.transactionId)
        assertEquals(MdnsConstants.FLAGS_QUERY, packet.flags)
        assertEquals(0, packet.answers.size)
        assertEquals(0, packet.additionalRecords.size)

        assertEquals(1, packet.questions.size)
        val expectedName = arrayOf("MyTestService", "_testservice", "_tcp", "local")
        assertEquals(MdnsAnyRecord(expectedName, false /* unicast */), packet.questions[0])

        assertEquals(1, packet.authorityRecords.size)
        assertEquals(MdnsServiceRecord(expectedName,
                0L /* receiptTimeMillis */,
                false /* cacheFlush */,
                SHORT_TTL /* ttlMillis */,
                0 /* servicePriority */, 0 /* serviceWeight */,
                TEST_PORT, TEST_HOSTNAME), packet.authorityRecords[0])

        assertContentEquals(intArrayOf(TEST_SERVICE_ID_1), repository.clearServices())
    }

    @Test
    fun testAddAndConflicts() {
        val repository = MdnsRecordRepository(thread.looper, deps, TEST_HOSTNAME, flags)
        repository.initWithService(TEST_SERVICE_ID_1, TEST_SERVICE_1)
        assertFailsWith(NameConflictException::class) {
            repository.addService(TEST_SERVICE_ID_2, TEST_SERVICE_1)
        }
        assertFailsWith(NameConflictException::class) {
            repository.addService(TEST_SERVICE_ID_3, TEST_SERVICE_3)
        }
    }

    @Test
    fun testAddAndUpdates() {
        val repository = MdnsRecordRepository(thread.looper, deps, TEST_HOSTNAME, flags)
        repository.initWithService(TEST_SERVICE_ID_1, TEST_SERVICE_1)

        assertFailsWith(IllegalArgumentException::class) {
            repository.updateService(TEST_SERVICE_ID_2, emptySet() /* subtype */)
        }

        repository.updateService(TEST_SERVICE_ID_1, setOf(TEST_SUBTYPE))

        val queriedName = arrayOf(TEST_SUBTYPE, "_sub", "_testservice", "_tcp", "local")
        val questions = listOf(MdnsPointerRecord(queriedName, false /* isUnicast */))
        val query = MdnsPacket(0 /* flags */, questions, listOf() /* answers */,
                listOf() /* authorityRecords */, listOf() /* additionalRecords */)
        val src = InetSocketAddress(parseNumericAddress("192.0.2.123"), 5353)
        val reply = repository.getReply(query, src)

        assertNotNull(reply)

        // TTLs as per RFC6762 10.
        val longTtl = 4_500_000L
        val serviceName = arrayOf("MyTestService", "_testservice", "_tcp", "local")

        assertEquals(listOf(
                MdnsPointerRecord(
                        queriedName,
                        0L /* receiptTimeMillis */,
                        false /* cacheFlush */,
                        longTtl,
                        serviceName),
        ), reply.answers)
    }

    @Test
    fun testInvalidReuseOfServiceId() {
        val repository = MdnsRecordRepository(thread.looper, deps, TEST_HOSTNAME, flags)
        repository.addService(TEST_SERVICE_ID_1, TEST_SERVICE_1)
        assertFailsWith(IllegalArgumentException::class) {
            repository.addService(TEST_SERVICE_ID_1, TEST_SERVICE_2)
        }
    }

    @Test
    fun testHasActiveService() {
        val repository = MdnsRecordRepository(thread.looper, deps, TEST_HOSTNAME, flags)
        assertFalse(repository.hasActiveService(TEST_SERVICE_ID_1))

        repository.addService(TEST_SERVICE_ID_1, TEST_SERVICE_1)
        assertTrue(repository.hasActiveService(TEST_SERVICE_ID_1))

        val probingInfo = repository.setServiceProbing(TEST_SERVICE_ID_1)
        repository.onProbingSucceeded(probingInfo)
        repository.onAdvertisementSent(TEST_SERVICE_ID_1, 2 /* sentPacketCount */)
        assertTrue(repository.hasActiveService(TEST_SERVICE_ID_1))

        repository.exitService(TEST_SERVICE_ID_1)
        assertFalse(repository.hasActiveService(TEST_SERVICE_ID_1))
    }

    @Test
    fun testExitAnnouncements() {
        val repository = MdnsRecordRepository(thread.looper, deps, TEST_HOSTNAME, flags)
        repository.initWithService(TEST_SERVICE_ID_1, TEST_SERVICE_1)
        repository.onAdvertisementSent(TEST_SERVICE_ID_1, 2 /* sentPacketCount */)

        val exitAnnouncement = repository.exitService(TEST_SERVICE_ID_1)
        assertNotNull(exitAnnouncement)
        assertEquals(1, repository.servicesCount)
        val packet = exitAnnouncement.getPacket(0)

        assertEquals(0, packet.transactionId)
        assertEquals(0x8400 /* response, authoritative */, packet.flags)
        assertEquals(0, packet.questions.size)
        assertEquals(0, packet.authorityRecords.size)
        assertEquals(0, packet.additionalRecords.size)

        assertContentEquals(listOf(
                MdnsPointerRecord(
                        arrayOf("_testservice", "_tcp", "local"),
                        0L /* receiptTimeMillis */,
                        false /* cacheFlush */,
                        0L /* ttlMillis */,
                        arrayOf("MyTestService", "_testservice", "_tcp", "local"))
        ), packet.answers)

        repository.removeService(TEST_SERVICE_ID_1)
        assertEquals(0, repository.servicesCount)
    }

    @Test
    fun testExitAnnouncements_WithSubtypes() {
        val repository = MdnsRecordRepository(thread.looper, deps, TEST_HOSTNAME, flags)
        repository.initWithService(TEST_SERVICE_ID_1, TEST_SERVICE_1,
                setOf(TEST_SUBTYPE, TEST_SUBTYPE2))
        repository.onAdvertisementSent(TEST_SERVICE_ID_1, 2 /* sentPacketCount */)

        val exitAnnouncement = repository.exitService(TEST_SERVICE_ID_1)
        assertNotNull(exitAnnouncement)
        assertEquals(1, repository.servicesCount)
        val packet = exitAnnouncement.getPacket(0)

        assertEquals(0, packet.transactionId)
        assertEquals(0x8400 /* response, authoritative */, packet.flags)
        assertEquals(0, packet.questions.size)
        assertEquals(0, packet.authorityRecords.size)
        assertEquals(0, packet.additionalRecords.size)

        assertThat(packet.answers).containsExactly(
                MdnsPointerRecord(
                        arrayOf("_testservice", "_tcp", "local"),
                        0L /* receiptTimeMillis */,
                        false /* cacheFlush */,
                        0L /* ttlMillis */,
                        arrayOf("MyTestService", "_testservice", "_tcp", "local")),
                MdnsPointerRecord(
                        arrayOf("_subtype", "_sub", "_testservice", "_tcp", "local"),
                        0L /* receiptTimeMillis */,
                        false /* cacheFlush */,
                        0L /* ttlMillis */,
                        arrayOf("MyTestService", "_testservice", "_tcp", "local")),
                MdnsPointerRecord(
                        arrayOf("_subtype2", "_sub", "_testservice", "_tcp", "local"),
                        0L /* receiptTimeMillis */,
                        false /* cacheFlush */,
                        0L /* ttlMillis */,
                        arrayOf("MyTestService", "_testservice", "_tcp", "local")))

        repository.removeService(TEST_SERVICE_ID_1)
        assertEquals(0, repository.servicesCount)
    }

    @Test
    fun testExitingServiceReAdded() {
        val repository = MdnsRecordRepository(thread.looper, deps, TEST_HOSTNAME, flags)
        repository.initWithService(TEST_SERVICE_ID_1, TEST_SERVICE_1)
        repository.onAdvertisementSent(TEST_SERVICE_ID_1, 2 /* sentPacketCount */)
        repository.exitService(TEST_SERVICE_ID_1)

        assertEquals(TEST_SERVICE_ID_1,
                repository.addService(TEST_SERVICE_ID_2, TEST_SERVICE_1))
        assertEquals(1, repository.servicesCount)

        repository.removeService(TEST_SERVICE_ID_2)
        assertEquals(0, repository.servicesCount)
    }

    @Test
    fun testOnProbingSucceeded() {
        val repository = MdnsRecordRepository(thread.looper, deps, TEST_HOSTNAME, flags)
        val announcementInfo = repository.initWithService(TEST_SERVICE_ID_1, TEST_SERVICE_1,
                setOf(TEST_SUBTYPE, TEST_SUBTYPE2))
        repository.onAdvertisementSent(TEST_SERVICE_ID_1, 2 /* sentPacketCount */)
        val packet = announcementInfo.getPacket(0)

        assertEquals(0, packet.transactionId)
        assertEquals(0x8400 /* response, authoritative */, packet.flags)
        assertEquals(0, packet.questions.size)
        assertEquals(0, packet.authorityRecords.size)

        val serviceType = arrayOf("_testservice", "_tcp", "local")
        val serviceSubtype = arrayOf(TEST_SUBTYPE, "_sub", "_testservice", "_tcp", "local")
        val serviceSubtype2 = arrayOf(TEST_SUBTYPE2, "_sub", "_testservice", "_tcp", "local")
        val serviceName = arrayOf("MyTestService", "_testservice", "_tcp", "local")
        val v4AddrRev = getReverseDnsAddress(TEST_ADDRESSES[0].address)
        val v6Addr1Rev = getReverseDnsAddress(TEST_ADDRESSES[1].address)
        val v6Addr2Rev = getReverseDnsAddress(TEST_ADDRESSES[2].address)

        assertThat(packet.answers).containsExactly(
                // Reverse address and address records for the hostname
                MdnsPointerRecord(v4AddrRev,
                        0L /* receiptTimeMillis */,
                        true /* cacheFlush */,
                        120000L /* ttlMillis */,
                        TEST_HOSTNAME),
                MdnsInetAddressRecord(TEST_HOSTNAME,
                        0L /* receiptTimeMillis */,
                        true /* cacheFlush */,
                        120000L /* ttlMillis */,
                        TEST_ADDRESSES[0].address),
                MdnsPointerRecord(v6Addr1Rev,
                        0L /* receiptTimeMillis */,
                        true /* cacheFlush */,
                        120000L /* ttlMillis */,
                        TEST_HOSTNAME),
                MdnsInetAddressRecord(TEST_HOSTNAME,
                        0L /* receiptTimeMillis */,
                        true /* cacheFlush */,
                        120000L /* ttlMillis */,
                        TEST_ADDRESSES[1].address),
                MdnsPointerRecord(v6Addr2Rev,
                        0L /* receiptTimeMillis */,
                        true /* cacheFlush */,
                        120000L /* ttlMillis */,
                        TEST_HOSTNAME),
                MdnsInetAddressRecord(TEST_HOSTNAME,
                        0L /* receiptTimeMillis */,
                        true /* cacheFlush */,
                        120000L /* ttlMillis */,
                        TEST_ADDRESSES[2].address),
                // Service registration records (RFC6763)
                MdnsPointerRecord(
                        serviceType,
                        0L /* receiptTimeMillis */,
                        // Not a unique name owned by the announcer, so cacheFlush=false
                        false /* cacheFlush */,
                        4500000L /* ttlMillis */,
                        serviceName),
                MdnsPointerRecord(
                        serviceSubtype,
                        0L /* receiptTimeMillis */,
                        // Not a unique name owned by the announcer, so cacheFlush=false
                        false /* cacheFlush */,
                        4500000L /* ttlMillis */,
                        serviceName),
                MdnsPointerRecord(
                        serviceSubtype2,
                        0L /* receiptTimeMillis */,
                        // Not a unique name owned by the announcer, so cacheFlush=false
                        false /* cacheFlush */,
                        4500000L /* ttlMillis */,
                        serviceName),
                MdnsServiceRecord(
                        serviceName,
                        0L /* receiptTimeMillis */,
                        true /* cacheFlush */,
                        120000L /* ttlMillis */,
                        0 /* servicePriority */,
                        0 /* serviceWeight */,
                        TEST_PORT /* servicePort */,
                        TEST_HOSTNAME),
                MdnsTextRecord(
                        serviceName,
                        0L /* receiptTimeMillis */,
                        true /* cacheFlush */,
                        4500000L /* ttlMillis */,
                        emptyList() /* entries */),
                // Service type enumeration record (RFC6763 9.)
                MdnsPointerRecord(
                        arrayOf("_services", "_dns-sd", "_udp", "local"),
                        0L /* receiptTimeMillis */,
                        false /* cacheFlush */,
                        4500000L /* ttlMillis */,
                        serviceType))

        assertContentEquals(listOf(
                MdnsNsecRecord(v4AddrRev,
                        0L /* receiptTimeMillis */,
                        true /* cacheFlush */,
                        120000L /* ttlMillis */,
                        v4AddrRev,
                        intArrayOf(MdnsRecord.TYPE_PTR)),
                MdnsNsecRecord(TEST_HOSTNAME,
                        0L /* receiptTimeMillis */,
                        true /* cacheFlush */,
                        120000L /* ttlMillis */,
                        TEST_HOSTNAME,
                        intArrayOf(MdnsRecord.TYPE_A, MdnsRecord.TYPE_AAAA)),
                MdnsNsecRecord(v6Addr1Rev,
                        0L /* receiptTimeMillis */,
                        true /* cacheFlush */,
                        120000L /* ttlMillis */,
                        v6Addr1Rev,
                        intArrayOf(MdnsRecord.TYPE_PTR)),
                MdnsNsecRecord(v6Addr2Rev,
                        0L /* receiptTimeMillis */,
                        true /* cacheFlush */,
                        120000L /* ttlMillis */,
                        v6Addr2Rev,
                        intArrayOf(MdnsRecord.TYPE_PTR)),
                MdnsNsecRecord(serviceName,
                        0L /* receiptTimeMillis */,
                        true /* cacheFlush */,
                        4500000L /* ttlMillis */,
                        serviceName,
                        intArrayOf(MdnsRecord.TYPE_TXT, MdnsRecord.TYPE_SRV))
        ), packet.additionalRecords)
    }

    @Test
    fun testGetOffloadPacket() {
        val repository = MdnsRecordRepository(thread.looper, deps, TEST_HOSTNAME, flags)
        repository.initWithService(TEST_SERVICE_ID_1, TEST_SERVICE_1)
        val serviceName = arrayOf("MyTestService", "_testservice", "_tcp", "local")
        val serviceType = arrayOf("_testservice", "_tcp", "local")
        val offloadPacket = repository.getOffloadPacket(TEST_SERVICE_ID_1)
        assertEquals(0, offloadPacket.transactionId)
        assertEquals(0x8400, offloadPacket.flags)
        assertEquals(0, offloadPacket.questions.size)
        assertEquals(0, offloadPacket.additionalRecords.size)
        assertEquals(0, offloadPacket.authorityRecords.size)
        assertContentEquals(listOf(
            MdnsPointerRecord(
                serviceType,
                0L /* receiptTimeMillis */,
                // Not a unique name owned by the announcer, so cacheFlush=false
                false /* cacheFlush */,
                4500000L /* ttlMillis */,
                serviceName),
            MdnsServiceRecord(
                serviceName,
                0L /* receiptTimeMillis */,
                true /* cacheFlush */,
                120000L /* ttlMillis */,
                0 /* servicePriority */,
                0 /* serviceWeight */,
                TEST_PORT /* servicePort */,
                TEST_HOSTNAME),
            MdnsTextRecord(
                serviceName,
                0L /* receiptTimeMillis */,
                true /* cacheFlush */,
                4500000L /* ttlMillis */,
                emptyList() /* entries */),
            MdnsInetAddressRecord(TEST_HOSTNAME,
                0L /* receiptTimeMillis */,
                true /* cacheFlush */,
                120000L /* ttlMillis */,
                TEST_ADDRESSES[0].address),
            MdnsInetAddressRecord(TEST_HOSTNAME,
                0L /* receiptTimeMillis */,
                true /* cacheFlush */,
                120000L /* ttlMillis */,
                TEST_ADDRESSES[1].address),
            MdnsInetAddressRecord(TEST_HOSTNAME,
                0L /* receiptTimeMillis */,
                true /* cacheFlush */,
                120000L /* ttlMillis */,
                TEST_ADDRESSES[2].address),
        ), offloadPacket.answers)
    }

    @Test
    fun testGetReverseDnsAddress() {
        val expectedV6 = "1.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.8.B.D.0.1.0.0.2.ip6.arpa"
                .split(".").toTypedArray()
        assertContentEquals(expectedV6, getReverseDnsAddress(parseNumericAddress("2001:db8::1")))
        val expectedV4 = "123.2.0.192.in-addr.arpa".split(".").toTypedArray()
        assertContentEquals(expectedV4, getReverseDnsAddress(parseNumericAddress("192.0.2.123")))
    }

    @Test
    fun testGetReplyCaseInsensitive() {
        val repository = MdnsRecordRepository(thread.looper, deps, TEST_HOSTNAME, flags)
        repository.initWithService(TEST_SERVICE_ID_1, TEST_SERVICE_1)
        val questionsCaseInSensitive = listOf(
                MdnsPointerRecord(arrayOf("_TESTSERVICE", "_TCP", "local"), false /* isUnicast */))
        val queryCaseInsensitive = MdnsPacket(0 /* flags */, questionsCaseInSensitive,
            listOf() /* answers */, listOf() /* authorityRecords */,
            listOf() /* additionalRecords */)
        val src = InetSocketAddress(parseNumericAddress("192.0.2.123"), 5353)
        val replyCaseInsensitive = repository.getReply(queryCaseInsensitive, src)
        assertNotNull(replyCaseInsensitive)
        assertEquals(1, replyCaseInsensitive.answers.size)
        assertEquals(7, replyCaseInsensitive.additionalAnswers.size)
    }

    @Test
    fun testGetReply() {
        doGetReplyTest(queryWithSubtype = false)
    }

    @Test
    fun testGetReply_WithSubtype() {
        doGetReplyTest(queryWithSubtype = true)
    }

    private fun doGetReplyTest(queryWithSubtype: Boolean) {
        val repository = MdnsRecordRepository(thread.looper, deps, TEST_HOSTNAME, flags)
        repository.initWithService(TEST_SERVICE_ID_1, TEST_SERVICE_1,
                setOf(TEST_SUBTYPE, TEST_SUBTYPE2))
        val queriedName = if (!queryWithSubtype) arrayOf("_testservice", "_tcp", "local")
                else arrayOf(TEST_SUBTYPE, "_sub", "_testservice", "_tcp", "local")

        val questions = listOf(MdnsPointerRecord(queriedName, false /* isUnicast */))
        val query = MdnsPacket(0 /* flags */, questions, listOf() /* answers */,
                listOf() /* authorityRecords */, listOf() /* additionalRecords */)
        val src = InetSocketAddress(parseNumericAddress("192.0.2.123"), 5353)
        val reply = repository.getReply(query, src)

        assertNotNull(reply)
        // Source address is IPv4
        assertEquals(MdnsConstants.getMdnsIPv4Address(), reply.destination.address)
        assertEquals(MdnsConstants.MDNS_PORT, reply.destination.port)

        val serviceName = arrayOf("MyTestService", "_testservice", "_tcp", "local")

        assertEquals(listOf(
                MdnsPointerRecord(
                        queriedName,
                        0L /* receiptTimeMillis */,
                        false /* cacheFlush */,
                        LONG_TTL,
                        serviceName),
        ), reply.answers)

        assertEquals(listOf(
            MdnsTextRecord(
                    serviceName,
                    0L /* receiptTimeMillis */,
                    true /* cacheFlush */,
                    LONG_TTL,
                    listOf() /* entries */),
            MdnsServiceRecord(
                    serviceName,
                    0L /* receiptTimeMillis */,
                    true /* cacheFlush */,
                    SHORT_TTL,
                    0 /* servicePriority */,
                    0 /* serviceWeight */,
                    TEST_PORT,
                    TEST_HOSTNAME),
            MdnsInetAddressRecord(
                    TEST_HOSTNAME,
                    0L /* receiptTimeMillis */,
                    true /* cacheFlush */,
                    SHORT_TTL,
                    TEST_ADDRESSES[0].address),
            MdnsInetAddressRecord(
                    TEST_HOSTNAME,
                    0L /* receiptTimeMillis */,
                    true /* cacheFlush */,
                    SHORT_TTL,
                    TEST_ADDRESSES[1].address),
            MdnsInetAddressRecord(
                    TEST_HOSTNAME,
                    0L /* receiptTimeMillis */,
                    true /* cacheFlush */,
                    SHORT_TTL,
                    TEST_ADDRESSES[2].address),
            MdnsNsecRecord(
                    serviceName,
                    0L /* receiptTimeMillis */,
                    true /* cacheFlush */,
                    LONG_TTL,
                    serviceName /* nextDomain */,
                    intArrayOf(MdnsRecord.TYPE_TXT, MdnsRecord.TYPE_SRV)),
            MdnsNsecRecord(
                    TEST_HOSTNAME,
                    0L /* receiptTimeMillis */,
                    true /* cacheFlush */,
                    SHORT_TTL,
                    TEST_HOSTNAME /* nextDomain */,
                    intArrayOf(MdnsRecord.TYPE_A, MdnsRecord.TYPE_AAAA)),
        ), reply.additionalAnswers)
    }

    @Test
    fun testGetConflictingServices() {
        val repository = MdnsRecordRepository(thread.looper, deps, TEST_HOSTNAME, flags)
        repository.addService(TEST_SERVICE_ID_1, TEST_SERVICE_1)
        repository.addService(TEST_SERVICE_ID_2, TEST_SERVICE_2)

        val packet = MdnsPacket(
                0 /* flags */,
                emptyList() /* questions */,
                listOf(
                    MdnsServiceRecord(
                            arrayOf("MyTestService", "_testservice", "_tcp", "local"),
                            0L /* receiptTimeMillis */, true /* cacheFlush */, 0L /* ttlMillis */,
                            0 /* servicePriority */, 0 /* serviceWeight */,
                            TEST_SERVICE_1.port + 1,
                            TEST_HOSTNAME),
                    MdnsTextRecord(
                            arrayOf("MyOtherTestService", "_testservice", "_tcp", "local"),
                            0L /* receiptTimeMillis */, true /* cacheFlush */, 0L /* ttlMillis */,
                            listOf(TextEntry.fromString("somedifferent=entry"))),
                ) /* answers */,
                emptyList() /* authorityRecords */,
                emptyList() /* additionalRecords */)

        assertEquals(setOf(TEST_SERVICE_ID_1, TEST_SERVICE_ID_2),
                repository.getConflictingServices(packet))
    }

    @Test
    fun testGetConflictingServicesCaseInsensitive() {
        val repository = MdnsRecordRepository(thread.looper, deps, TEST_HOSTNAME, flags)
        repository.addService(TEST_SERVICE_ID_1, TEST_SERVICE_1)
        repository.addService(TEST_SERVICE_ID_2, TEST_SERVICE_2)

        val packet = MdnsPacket(
            0 /* flags */,
            emptyList() /* questions */,
            listOf(
                MdnsServiceRecord(
                    arrayOf("MYTESTSERVICE", "_TESTSERVICE", "_tcp", "local"),
                    0L /* receiptTimeMillis */, true /* cacheFlush */, 0L /* ttlMillis */,
                    0 /* servicePriority */, 0 /* serviceWeight */,
                    TEST_SERVICE_1.port + 1,
                    TEST_HOSTNAME),
                MdnsTextRecord(
                    arrayOf("MYOTHERTESTSERVICE", "_TESTSERVICE", "_tcp", "local"),
                    0L /* receiptTimeMillis */, true /* cacheFlush */, 0L /* ttlMillis */,
                    listOf(TextEntry.fromString("somedifferent=entry"))),
            ) /* answers */,
            emptyList() /* authorityRecords */,
            emptyList() /* additionalRecords */)

        assertEquals(setOf(TEST_SERVICE_ID_1, TEST_SERVICE_ID_2),
            repository.getConflictingServices(packet))
    }

    @Test
    fun testGetConflictingServices_IdenticalService() {
        val repository = MdnsRecordRepository(thread.looper, deps, TEST_HOSTNAME, flags)
        repository.addService(TEST_SERVICE_ID_1, TEST_SERVICE_1)
        repository.addService(TEST_SERVICE_ID_2, TEST_SERVICE_2)

        val otherTtlMillis = 1234L
        val packet = MdnsPacket(
                0 /* flags */,
                emptyList() /* questions */,
                listOf(
                        MdnsServiceRecord(
                                arrayOf("MyTestService", "_testservice", "_tcp", "local"),
                                0L /* receiptTimeMillis */, true /* cacheFlush */,
                                otherTtlMillis, 0 /* servicePriority */, 0 /* serviceWeight */,
                                TEST_SERVICE_1.port,
                                arrayOf("ANDROID_000102030405060708090A0B0C0D0E0F", "local")),
                        MdnsTextRecord(
                                arrayOf("MyOtherTestService", "_testservice", "_tcp", "local"),
                                0L /* receiptTimeMillis */, true /* cacheFlush */,
                                otherTtlMillis, emptyList()),
                ) /* answers */,
                emptyList() /* authorityRecords */,
                emptyList() /* additionalRecords */)

        // Above records are identical to the actual registrations: no conflict
        assertEquals(emptySet(), repository.getConflictingServices(packet))
    }

    @Test
    fun testGetConflictingServicesCaseInsensitive_IdenticalService() {
        val repository = MdnsRecordRepository(thread.looper, deps, TEST_HOSTNAME, flags)
        repository.addService(TEST_SERVICE_ID_1, TEST_SERVICE_1)
        repository.addService(TEST_SERVICE_ID_2, TEST_SERVICE_2)

        val otherTtlMillis = 1234L
        val packet = MdnsPacket(
                0 /* flags */,
                emptyList() /* questions */,
                listOf(
                        MdnsServiceRecord(
                                arrayOf("MYTESTSERVICE", "_TESTSERVICE", "_tcp", "local"),
                                0L /* receiptTimeMillis */, true /* cacheFlush */,
                                otherTtlMillis, 0 /* servicePriority */, 0 /* serviceWeight */,
                                TEST_SERVICE_1.port,
                                TEST_HOSTNAME),
                        MdnsTextRecord(
                                arrayOf("MyOtherTestService", "_TESTSERVICE", "_tcp", "local"),
                                0L /* receiptTimeMillis */, true /* cacheFlush */,
                                otherTtlMillis, emptyList()),
                ) /* answers */,
                emptyList() /* authorityRecords */,
                emptyList() /* additionalRecords */)

        // Above records are identical to the actual registrations: no conflict
        assertEquals(emptySet(), repository.getConflictingServices(packet))
    }

    @Test
    fun testGetServiceRepliedRequestsCount() {
        val repository = MdnsRecordRepository(thread.looper, deps, TEST_HOSTNAME, flags)
        repository.initWithService(TEST_SERVICE_ID_1, TEST_SERVICE_1)
        // Verify that there is no packet replied.
        assertEquals(MdnsConstants.NO_PACKET,
                repository.getServiceRepliedRequestsCount(TEST_SERVICE_ID_1))

        val questions = listOf(
                MdnsPointerRecord(arrayOf("_testservice", "_tcp", "local"), false /* isUnicast */))
        val query = MdnsPacket(0 /* flags */, questions, listOf() /* answers */,
                listOf() /* authorityRecords */, listOf() /* additionalRecords */)
        val src = InetSocketAddress(parseNumericAddress("192.0.2.123"), 5353)

        // Reply to the question and verify there is one packet replied.
        val reply = repository.getReply(query, src)
        assertNotNull(reply)
        assertEquals(1, repository.getServiceRepliedRequestsCount(TEST_SERVICE_ID_1))

        // No package replied for unknown service.
        assertEquals(MdnsConstants.NO_PACKET,
                repository.getServiceRepliedRequestsCount(TEST_SERVICE_ID_2))
    }

    @Test
    fun testIncludeInetAddressRecordsInProbing() {
        val repository = MdnsRecordRepository(thread.looper, deps, TEST_HOSTNAME,
                MdnsFeatureFlags.newBuilder().setIncludeInetAddressRecordsInProbing(true).build())
        repository.updateAddresses(TEST_ADDRESSES)
        assertEquals(0, repository.servicesCount)
        assertEquals(-1, repository.addService(TEST_SERVICE_ID_1, TEST_SERVICE_1))
        assertEquals(1, repository.servicesCount)

        val probingInfo = repository.setServiceProbing(TEST_SERVICE_ID_1)
        assertNotNull(probingInfo)
        assertTrue(repository.isProbing(TEST_SERVICE_ID_1))

        assertEquals(TEST_SERVICE_ID_1, probingInfo.serviceId)
        val packet = probingInfo.getPacket(0)

        assertEquals(MdnsConstants.FLAGS_QUERY, packet.flags)
        assertEquals(0, packet.answers.size)
        assertEquals(0, packet.additionalRecords.size)

        assertEquals(2, packet.questions.size)
        val expectedName = arrayOf("MyTestService", "_testservice", "_tcp", "local")
        assertContentEquals(listOf(
            MdnsAnyRecord(expectedName, false /* unicast */),
            MdnsAnyRecord(TEST_HOSTNAME, false /* unicast */),
        ), packet.questions)

        assertEquals(4, packet.authorityRecords.size)
        assertContentEquals(listOf(
            MdnsServiceRecord(
                expectedName,
                0L /* receiptTimeMillis */,
                false /* cacheFlush */,
                SHORT_TTL /* ttlMillis */,
                0 /* servicePriority */,
                0 /* serviceWeight */,
                TEST_PORT,
                TEST_HOSTNAME),
            MdnsInetAddressRecord(
                TEST_HOSTNAME,
                0L /* receiptTimeMillis */,
                false /* cacheFlush */,
                SHORT_TTL /* ttlMillis */,
                TEST_ADDRESSES[0].address),
            MdnsInetAddressRecord(
                TEST_HOSTNAME,
                0L /* receiptTimeMillis */,
                false /* cacheFlush */,
                SHORT_TTL /* ttlMillis */,
                TEST_ADDRESSES[1].address),
            MdnsInetAddressRecord(
                TEST_HOSTNAME,
                0L /* receiptTimeMillis */,
                false /* cacheFlush */,
                SHORT_TTL /* ttlMillis */,
                TEST_ADDRESSES[2].address)
        ), packet.authorityRecords)

        assertContentEquals(intArrayOf(TEST_SERVICE_ID_1), repository.clearServices())
    }

    private fun doGetReplyWithAnswersTest(
            questions: List<MdnsRecord>,
            knownAnswers: List<MdnsRecord>,
            replyAnswers: List<MdnsRecord>,
            additionalAnswers: List<MdnsRecord>,
            expectReply: Boolean
    ) {
        val repository = MdnsRecordRepository(thread.looper, deps, TEST_HOSTNAME,
                MdnsFeatureFlags.newBuilder().setIsKnownAnswerSuppressionEnabled(true).build())
        repository.initWithService(TEST_SERVICE_ID_1, TEST_SERVICE_1)
        val query = MdnsPacket(0 /* flags */, questions, knownAnswers,
                listOf() /* authorityRecords */, listOf() /* additionalRecords */)
        val src = InetSocketAddress(parseNumericAddress("192.0.2.123"), 5353)
        val reply = repository.getReply(query, src)

        if (!expectReply) {
            assertNull(reply)
            return
        }

        assertNotNull(reply)
        // Source address is IPv4
        assertEquals(MdnsConstants.getMdnsIPv4Address(), reply.destination.address)
        assertEquals(MdnsConstants.MDNS_PORT, reply.destination.port)
        assertEquals(replyAnswers, reply.answers)
        assertEquals(additionalAnswers, reply.additionalAnswers)
    }

    @Test
    fun testGetReply_HasAnswers() {
        val queriedName = arrayOf("_testservice", "_tcp", "local")
        val questions = listOf(MdnsPointerRecord(queriedName, false /* isUnicast */))
        val knownAnswers = listOf(MdnsPointerRecord(
                arrayOf("_testservice", "_tcp", "local"),
                0L /* receiptTimeMillis */,
                false /* cacheFlush */,
                LONG_TTL,
                arrayOf("MyTestService", "_testservice", "_tcp", "local")))
        doGetReplyWithAnswersTest(questions, knownAnswers, listOf() /* replyAnswers */,
                listOf() /* additionalAnswers */, false /* expectReply */)
    }

    @Test
    fun testGetReply_HasAnswers_TtlLessThanHalf() {
        val queriedName = arrayOf("_testservice", "_tcp", "local")
        val serviceName = arrayOf("MyTestService", "_testservice", "_tcp", "local")
        val questions = listOf(MdnsPointerRecord(queriedName, false /* isUnicast */))
        val knownAnswers = listOf(MdnsPointerRecord(
                arrayOf("_testservice", "_tcp", "local"),
                0L /* receiptTimeMillis */,
                false /* cacheFlush */,
                (LONG_TTL / 2 - 1000L),
                arrayOf("MyTestService", "_testservice", "_tcp", "local")))
        val replyAnswers = listOf(MdnsPointerRecord(
                queriedName,
                0L /* receiptTimeMillis */,
                false /* cacheFlush */,
                LONG_TTL,
                serviceName))
        val additionalAnswers = listOf(
                MdnsTextRecord(
                        serviceName,
                        0L /* receiptTimeMillis */,
                        true /* cacheFlush */,
                        LONG_TTL,
                        listOf() /* entries */),
                MdnsServiceRecord(
                        serviceName,
                        0L /* receiptTimeMillis */,
                        true /* cacheFlush */,
                        SHORT_TTL,
                        0 /* servicePriority */,
                        0 /* serviceWeight */,
                        TEST_PORT,
                        TEST_HOSTNAME),
                MdnsInetAddressRecord(
                        TEST_HOSTNAME,
                        0L /* receiptTimeMillis */,
                        true /* cacheFlush */,
                        SHORT_TTL,
                        TEST_ADDRESSES[0].address),
                MdnsInetAddressRecord(
                        TEST_HOSTNAME,
                        0L /* receiptTimeMillis */,
                        true /* cacheFlush */,
                        SHORT_TTL,
                        TEST_ADDRESSES[1].address),
                MdnsInetAddressRecord(
                        TEST_HOSTNAME,
                        0L /* receiptTimeMillis */,
                        true /* cacheFlush */,
                        SHORT_TTL,
                        TEST_ADDRESSES[2].address),
                MdnsNsecRecord(
                        serviceName,
                        0L /* receiptTimeMillis */,
                        true /* cacheFlush */,
                        LONG_TTL,
                        serviceName /* nextDomain */,
                        intArrayOf(MdnsRecord.TYPE_TXT, MdnsRecord.TYPE_SRV)),
                MdnsNsecRecord(
                        TEST_HOSTNAME,
                        0L /* receiptTimeMillis */,
                        true /* cacheFlush */,
                        SHORT_TTL,
                        TEST_HOSTNAME /* nextDomain */,
                        intArrayOf(MdnsRecord.TYPE_A, MdnsRecord.TYPE_AAAA)))
        doGetReplyWithAnswersTest(questions, knownAnswers, replyAnswers, additionalAnswers,
                true /* expectReply */)
    }

    @Test
    fun testGetReply_HasAnotherAnswer() {
        val queriedName = arrayOf("_testservice", "_tcp", "local")
        val serviceName = arrayOf("MyTestService", "_testservice", "_tcp", "local")
        val questions = listOf(MdnsPointerRecord(queriedName, false /* isUnicast */))
        val knownAnswers = listOf(MdnsPointerRecord(
                queriedName,
                0L /* receiptTimeMillis */,
                false /* cacheFlush */,
                LONG_TTL,
                arrayOf("MyOtherTestService", "_testservice", "_tcp", "local")))
        val replyAnswers = listOf(MdnsPointerRecord(
                queriedName,
                0L /* receiptTimeMillis */,
                false /* cacheFlush */,
                LONG_TTL,
                serviceName))
        val additionalAnswers = listOf(
                MdnsTextRecord(
                        serviceName,
                        0L /* receiptTimeMillis */,
                        true /* cacheFlush */,
                        LONG_TTL,
                        listOf() /* entries */),
                MdnsServiceRecord(
                        serviceName,
                        0L /* receiptTimeMillis */,
                        true /* cacheFlush */,
                        SHORT_TTL,
                        0 /* servicePriority */,
                        0 /* serviceWeight */,
                        TEST_PORT,
                        TEST_HOSTNAME),
                MdnsInetAddressRecord(
                        TEST_HOSTNAME,
                        0L /* receiptTimeMillis */,
                        true /* cacheFlush */,
                        SHORT_TTL,
                        TEST_ADDRESSES[0].address),
                MdnsInetAddressRecord(
                        TEST_HOSTNAME,
                        0L /* receiptTimeMillis */,
                        true /* cacheFlush */,
                        SHORT_TTL,
                        TEST_ADDRESSES[1].address),
                MdnsInetAddressRecord(
                        TEST_HOSTNAME,
                        0L /* receiptTimeMillis */,
                        true /* cacheFlush */,
                        SHORT_TTL,
                        TEST_ADDRESSES[2].address),
                MdnsNsecRecord(
                        serviceName,
                        0L /* receiptTimeMillis */,
                        true /* cacheFlush */,
                        LONG_TTL,
                        serviceName /* nextDomain */,
                        intArrayOf(MdnsRecord.TYPE_TXT, MdnsRecord.TYPE_SRV)),
                MdnsNsecRecord(
                        TEST_HOSTNAME,
                        0L /* receiptTimeMillis */,
                        true /* cacheFlush */,
                        SHORT_TTL,
                        TEST_HOSTNAME /* nextDomain */,
                        intArrayOf(MdnsRecord.TYPE_A, MdnsRecord.TYPE_AAAA)))
        doGetReplyWithAnswersTest(questions, knownAnswers, replyAnswers, additionalAnswers,
                true /* expectReply */)
    }

    @Test
    fun testGetReply_HasAnswers_MultiQuestions() {
        val queriedName = arrayOf("_testservice", "_tcp", "local")
        val serviceName = arrayOf("MyTestService", "_testservice", "_tcp", "local")
        val questions = listOf(
                MdnsPointerRecord(queriedName, false /* isUnicast */),
                MdnsServiceRecord(serviceName, false /* isUnicast */))
        val knownAnswers = listOf(MdnsPointerRecord(
                queriedName,
                0L /* receiptTimeMillis */,
                false /* cacheFlush */,
                LONG_TTL - 1000L,
                serviceName))
        val replyAnswers = listOf(MdnsServiceRecord(
                serviceName,
                0L /* receiptTimeMillis */,
                false /* cacheFlush */,
                SHORT_TTL /* ttlMillis */,
                0 /* servicePriority */,
                0 /* serviceWeight */,
                TEST_PORT,
                TEST_HOSTNAME))
        val additionalAnswers = listOf(
                MdnsInetAddressRecord(
                        TEST_HOSTNAME,
                        0L /* receiptTimeMillis */,
                        true /* cacheFlush */,
                        SHORT_TTL,
                        TEST_ADDRESSES[0].address),
                MdnsInetAddressRecord(
                        TEST_HOSTNAME,
                        0L /* receiptTimeMillis */,
                        true /* cacheFlush */,
                        SHORT_TTL,
                        TEST_ADDRESSES[1].address),
                MdnsInetAddressRecord(
                        TEST_HOSTNAME,
                        0L /* receiptTimeMillis */,
                        true /* cacheFlush */,
                        SHORT_TTL,
                        TEST_ADDRESSES[2].address),
                MdnsNsecRecord(
                        serviceName,
                        0L /* receiptTimeMillis */,
                        true /* cacheFlush */,
                        LONG_TTL,
                        serviceName /* nextDomain */,
                        intArrayOf(MdnsRecord.TYPE_SRV)),
                MdnsNsecRecord(
                        TEST_HOSTNAME,
                        0L /* receiptTimeMillis */,
                        true /* cacheFlush */,
                        SHORT_TTL,
                        TEST_HOSTNAME /* nextDomain */,
                        intArrayOf(MdnsRecord.TYPE_A, MdnsRecord.TYPE_AAAA)))
        doGetReplyWithAnswersTest(questions, knownAnswers, replyAnswers, additionalAnswers,
                true /* expectReply */)
    }

    @Test
    fun testGetReply_HasAnswers_MultiQuestions_NoReply() {
        val queriedName = arrayOf("_testservice", "_tcp", "local")
        val serviceName = arrayOf("MyTestService", "_testservice", "_tcp", "local")
        val questions = listOf(
                MdnsPointerRecord(queriedName, false /* isUnicast */),
                MdnsServiceRecord(serviceName, false /* isUnicast */))
        val knownAnswers = listOf(
                MdnsPointerRecord(
                        queriedName,
                        0L /* receiptTimeMillis */,
                        false /* cacheFlush */,
                        LONG_TTL - 1000L,
                        serviceName),
                MdnsServiceRecord(
                        serviceName,
                        0L /* receiptTimeMillis */,
                        false /* cacheFlush */,
                        SHORT_TTL - 15_000L,
                        0 /* servicePriority */,
                        0 /* serviceWeight */,
                        TEST_PORT,
                        TEST_HOSTNAME))
        doGetReplyWithAnswersTest(questions, knownAnswers, listOf() /* replyAnswers */,
                listOf() /* additionalAnswers */, false /* expectReply */)
    }
}

private fun MdnsRecordRepository.initWithService(
    serviceId: Int,
    serviceInfo: NsdServiceInfo,
    subtypes: Set<String> = setOf(),
): AnnouncementInfo {
    updateAddresses(TEST_ADDRESSES)
    serviceInfo.setSubtypes(subtypes)
    addService(serviceId, serviceInfo)
    val probingInfo = setServiceProbing(serviceId)
    assertNotNull(probingInfo)
    return onProbingSucceeded(probingInfo)
}
