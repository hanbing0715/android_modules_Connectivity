/*
 * Copyright (C) 2012 The Android Open Source Project
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
package android.net.cts

import android.Manifest.permission.MANAGE_TEST_NETWORKS
import android.Manifest.permission.NETWORK_SETTINGS
import android.app.compat.CompatChanges
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.InetAddresses.parseNumericAddress
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.MacAddress
import android.net.Network
import android.net.NetworkAgentConfig
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED
import android.net.NetworkCapabilities.NET_CAPABILITY_TRUSTED
import android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED
import android.net.NetworkCapabilities.TRANSPORT_TEST
import android.net.NetworkRequest
import android.net.TestNetworkInterface
import android.net.TestNetworkManager
import android.net.TestNetworkSpecifier
import android.net.connectivity.ConnectivityCompatChanges
import android.net.cts.NsdManagerTest.NsdDiscoveryRecord.DiscoveryEvent.DiscoveryStarted
import android.net.cts.NsdManagerTest.NsdDiscoveryRecord.DiscoveryEvent.DiscoveryStopped
import android.net.cts.NsdManagerTest.NsdDiscoveryRecord.DiscoveryEvent.ServiceFound
import android.net.cts.NsdManagerTest.NsdDiscoveryRecord.DiscoveryEvent.ServiceLost
import android.net.cts.NsdManagerTest.NsdDiscoveryRecord.DiscoveryEvent.StartDiscoveryFailed
import android.net.cts.NsdManagerTest.NsdDiscoveryRecord.DiscoveryEvent.StopDiscoveryFailed
import android.net.cts.NsdManagerTest.NsdRegistrationRecord.RegistrationEvent.RegistrationFailed
import android.net.cts.NsdManagerTest.NsdRegistrationRecord.RegistrationEvent.ServiceRegistered
import android.net.cts.NsdManagerTest.NsdRegistrationRecord.RegistrationEvent.ServiceUnregistered
import android.net.cts.NsdManagerTest.NsdRegistrationRecord.RegistrationEvent.UnregistrationFailed
import android.net.cts.NsdManagerTest.NsdResolveRecord.ResolveEvent.ResolutionStopped
import android.net.cts.NsdManagerTest.NsdResolveRecord.ResolveEvent.ResolveFailed
import android.net.cts.NsdManagerTest.NsdResolveRecord.ResolveEvent.ServiceResolved
import android.net.cts.NsdManagerTest.NsdResolveRecord.ResolveEvent.StopResolutionFailed
import android.net.cts.NsdManagerTest.NsdServiceInfoCallbackRecord.ServiceInfoCallbackEvent.RegisterCallbackFailed
import android.net.cts.NsdManagerTest.NsdServiceInfoCallbackRecord.ServiceInfoCallbackEvent.ServiceUpdated
import android.net.cts.NsdManagerTest.NsdServiceInfoCallbackRecord.ServiceInfoCallbackEvent.ServiceUpdatedLost
import android.net.cts.NsdManagerTest.NsdServiceInfoCallbackRecord.ServiceInfoCallbackEvent.UnregisterCallbackSucceeded
import android.net.cts.util.CtsNetUtils
import android.net.nsd.NsdManager
import android.net.nsd.NsdManager.DiscoveryListener
import android.net.nsd.NsdManager.RegistrationListener
import android.net.nsd.NsdManager.ResolveListener
import android.net.nsd.NsdServiceInfo
import android.net.nsd.OffloadEngine
import android.net.nsd.OffloadServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process.myTid
import android.platform.test.annotations.AppModeFull
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants.AF_INET6
import android.system.OsConstants.EADDRNOTAVAIL
import android.system.OsConstants.ENETUNREACH
import android.system.OsConstants.ETH_P_IPV6
import android.system.OsConstants.IPPROTO_IPV6
import android.system.OsConstants.IPPROTO_UDP
import android.system.OsConstants.SOCK_DGRAM
import android.util.Log
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry
import com.android.compatibility.common.util.PollingCheck
import com.android.compatibility.common.util.PropertyUtil
import com.android.modules.utils.build.SdkLevel.isAtLeastU
import com.android.net.module.util.ArrayTrackRecord
import com.android.net.module.util.DnsPacket
import com.android.net.module.util.HexDump
import com.android.net.module.util.NetworkStackConstants.ETHER_HEADER_LEN
import com.android.net.module.util.NetworkStackConstants.IPV6_HEADER_LEN
import com.android.net.module.util.NetworkStackConstants.UDP_HEADER_LEN
import com.android.net.module.util.PacketBuilder
import com.android.net.module.util.TrackRecord
import com.android.testutils.ConnectivityModuleTest
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.IPv6UdpFilter
import com.android.testutils.RecorderCallback.CallbackEntry.CapabilitiesChanged
import com.android.testutils.RecorderCallback.CallbackEntry.LinkPropertiesChanged
import com.android.testutils.TapPacketReader
import com.android.testutils.TestableNetworkAgent
import com.android.testutils.TestableNetworkAgent.CallbackEntry.OnNetworkCreated
import com.android.testutils.TestableNetworkCallback
import com.android.testutils.filters.CtsNetTestCasesMaxTargetSdk30
import com.android.testutils.filters.CtsNetTestCasesMaxTargetSdk33
import com.android.testutils.runAsShell
import com.android.testutils.tryTest
import com.android.testutils.waitForIdle
import java.io.File
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Random
import java.util.concurrent.Executor
import kotlin.math.min
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TAG = "NsdManagerTest"
private const val TIMEOUT_MS = 2000L
private const val NO_CALLBACK_TIMEOUT_MS = 200L
// Registration may take a long time if there are devices with the same hostname on the network,
// as the device needs to try another name and probe again. This is especially true since when using
// mdnsresponder the usual hostname is "Android", and on conflict "Android-2", "Android-3", ... are
// tried sequentially
private const val REGISTRATION_TIMEOUT_MS = 10_000L
private const val DBG = false
private const val TEST_PORT = 12345
private const val MDNS_PORT = 5353.toShort()
private val multicastIpv6Addr = parseNumericAddress("ff02::fb") as Inet6Address

@AppModeFull(reason = "Socket cannot bind in instant app mode")
@RunWith(DevSdkIgnoreRunner::class)
@SmallTest
@ConnectivityModuleTest
@IgnoreUpTo(Build.VERSION_CODES.S_V2)
class NsdManagerTest {
    // Rule used to filter CtsNetTestCasesMaxTargetSdkXX
    @get:Rule
    val ignoreRule = DevSdkIgnoreRule()

    private val context by lazy { InstrumentationRegistry.getInstrumentation().context }
    private val nsdManager by lazy { context.getSystemService(NsdManager::class.java) }

    private val cm by lazy { context.getSystemService(ConnectivityManager::class.java) }
    private val serviceName = "NsdTest%09d".format(Random().nextInt(1_000_000_000))
    private val serviceType = "_nmt%09d._tcp".format(Random().nextInt(1_000_000_000))
    private val handlerThread = HandlerThread(NsdManagerTest::class.java.simpleName)
    private val ctsNetUtils by lazy{ CtsNetUtils(context) }

    private lateinit var testNetwork1: TestTapNetwork
    private lateinit var testNetwork2: TestTapNetwork

    private class TestTapNetwork(
        val iface: TestNetworkInterface,
        val requestCb: NetworkCallback,
        val agent: TestableNetworkAgent,
        val network: Network
    ) {
        fun close(cm: ConnectivityManager) {
            cm.unregisterNetworkCallback(requestCb)
            agent.unregister()
            iface.fileDescriptor.close()
            agent.waitForIdle(TIMEOUT_MS)
        }
    }

    private interface NsdEvent
    private open class NsdRecord<T : NsdEvent> private constructor(
        private val history: ArrayTrackRecord<T>,
        private val expectedThreadId: Int? = null
    ) : TrackRecord<T> by history {
        constructor(expectedThreadId: Int? = null) : this(ArrayTrackRecord(), expectedThreadId)

        val nextEvents = history.newReadHead()

        override fun add(e: T): Boolean {
            if (expectedThreadId != null) {
                assertEquals(expectedThreadId, myTid(), "Callback is running on the wrong thread")
            }
            return history.add(e)
        }

        inline fun <reified V : NsdEvent> expectCallbackEventually(
            timeoutMs: Long = TIMEOUT_MS,
            crossinline predicate: (V) -> Boolean = { true }
        ): V = nextEvents.poll(timeoutMs) { e -> e is V && predicate(e) } as V?
                ?: fail("Callback for ${V::class.java.simpleName} not seen after $timeoutMs ms")

        inline fun <reified V : NsdEvent> expectCallback(timeoutMs: Long = TIMEOUT_MS): V {
            val nextEvent = nextEvents.poll(timeoutMs)
            assertNotNull(nextEvent, "No callback received after $timeoutMs ms, " +
                    "expected ${V::class.java.simpleName}")
            assertTrue(nextEvent is V, "Expected ${V::class.java.simpleName} but got " +
                    nextEvent.javaClass.simpleName)
            return nextEvent
        }

        inline fun assertNoCallback(timeoutMs: Long = NO_CALLBACK_TIMEOUT_MS) {
            val cb = nextEvents.poll(timeoutMs)
            assertNull(cb, "Expected no callback but got $cb")
        }
    }

    private class NsdRegistrationRecord(expectedThreadId: Int? = null) : RegistrationListener,
            NsdRecord<NsdRegistrationRecord.RegistrationEvent>(expectedThreadId) {
        sealed class RegistrationEvent : NsdEvent {
            abstract val serviceInfo: NsdServiceInfo

            data class RegistrationFailed(
                override val serviceInfo: NsdServiceInfo,
                val errorCode: Int
            ) : RegistrationEvent()

            data class UnregistrationFailed(
                override val serviceInfo: NsdServiceInfo,
                val errorCode: Int
            ) : RegistrationEvent()

            data class ServiceRegistered(override val serviceInfo: NsdServiceInfo) :
                    RegistrationEvent()
            data class ServiceUnregistered(override val serviceInfo: NsdServiceInfo) :
                    RegistrationEvent()
        }

        override fun onRegistrationFailed(si: NsdServiceInfo, err: Int) {
            add(RegistrationFailed(si, err))
        }

        override fun onUnregistrationFailed(si: NsdServiceInfo, err: Int) {
            add(UnregistrationFailed(si, err))
        }

        override fun onServiceRegistered(si: NsdServiceInfo) {
            add(ServiceRegistered(si))
        }

        override fun onServiceUnregistered(si: NsdServiceInfo) {
            add(ServiceUnregistered(si))
        }
    }

    private class NsdDiscoveryRecord(expectedThreadId: Int? = null) :
            DiscoveryListener, NsdRecord<NsdDiscoveryRecord.DiscoveryEvent>(expectedThreadId) {
        sealed class DiscoveryEvent : NsdEvent {
            data class StartDiscoveryFailed(val serviceType: String, val errorCode: Int) :
                    DiscoveryEvent()

            data class StopDiscoveryFailed(val serviceType: String, val errorCode: Int) :
                    DiscoveryEvent()

            data class DiscoveryStarted(val serviceType: String) : DiscoveryEvent()
            data class DiscoveryStopped(val serviceType: String) : DiscoveryEvent()
            data class ServiceFound(val serviceInfo: NsdServiceInfo) : DiscoveryEvent()
            data class ServiceLost(val serviceInfo: NsdServiceInfo) : DiscoveryEvent()
        }

        override fun onStartDiscoveryFailed(serviceType: String, err: Int) {
            add(StartDiscoveryFailed(serviceType, err))
        }

        override fun onStopDiscoveryFailed(serviceType: String, err: Int) {
            add(StopDiscoveryFailed(serviceType, err))
        }

        override fun onDiscoveryStarted(serviceType: String) {
            add(DiscoveryStarted(serviceType))
        }

        override fun onDiscoveryStopped(serviceType: String) {
            add(DiscoveryStopped(serviceType))
        }

        override fun onServiceFound(si: NsdServiceInfo) {
            add(ServiceFound(si))
        }

        override fun onServiceLost(si: NsdServiceInfo) {
            add(ServiceLost(si))
        }

        fun waitForServiceDiscovered(
            serviceName: String,
            serviceType: String,
            expectedNetwork: Network? = null
        ): NsdServiceInfo {
            val serviceFound = expectCallbackEventually<ServiceFound> {
                it.serviceInfo.serviceName == serviceName &&
                        (expectedNetwork == null ||
                                expectedNetwork == it.serviceInfo.network)
            }.serviceInfo
            // Discovered service types have a dot at the end
            assertEquals("$serviceType.", serviceFound.serviceType)
            return serviceFound
        }
    }

    private class NsdResolveRecord : ResolveListener,
            NsdRecord<NsdResolveRecord.ResolveEvent>() {
        sealed class ResolveEvent : NsdEvent {
            data class ResolveFailed(val serviceInfo: NsdServiceInfo, val errorCode: Int) :
                    ResolveEvent()

            data class ServiceResolved(val serviceInfo: NsdServiceInfo) : ResolveEvent()
            data class ResolutionStopped(val serviceInfo: NsdServiceInfo) : ResolveEvent()
            data class StopResolutionFailed(val serviceInfo: NsdServiceInfo, val errorCode: Int) :
                    ResolveEvent()
        }

        override fun onResolveFailed(si: NsdServiceInfo, err: Int) {
            add(ResolveFailed(si, err))
        }

        override fun onServiceResolved(si: NsdServiceInfo) {
            add(ServiceResolved(si))
        }

        override fun onResolutionStopped(si: NsdServiceInfo) {
            add(ResolutionStopped(si))
        }

        override fun onStopResolutionFailed(si: NsdServiceInfo, err: Int) {
            super.onStopResolutionFailed(si, err)
            add(StopResolutionFailed(si, err))
        }
    }

    private class NsdServiceInfoCallbackRecord : NsdManager.ServiceInfoCallback,
            NsdRecord<NsdServiceInfoCallbackRecord.ServiceInfoCallbackEvent>() {
        sealed class ServiceInfoCallbackEvent : NsdEvent {
            data class RegisterCallbackFailed(val errorCode: Int) : ServiceInfoCallbackEvent()
            data class ServiceUpdated(val serviceInfo: NsdServiceInfo) : ServiceInfoCallbackEvent()
            object ServiceUpdatedLost : ServiceInfoCallbackEvent()
            object UnregisterCallbackSucceeded : ServiceInfoCallbackEvent()
        }

        override fun onServiceInfoCallbackRegistrationFailed(err: Int) {
            add(RegisterCallbackFailed(err))
        }

        override fun onServiceUpdated(si: NsdServiceInfo) {
            add(ServiceUpdated(si))
        }

        override fun onServiceLost() {
            add(ServiceUpdatedLost)
        }

        override fun onServiceInfoCallbackUnregistered() {
            add(UnregisterCallbackSucceeded)
        }
    }

    private class TestNsdOffloadEngine : OffloadEngine,
        NsdRecord<TestNsdOffloadEngine.OffloadEvent>() {
        sealed class OffloadEvent : NsdEvent {
            data class AddOrUpdateEvent(val info: OffloadServiceInfo) : OffloadEvent()
            data class RemoveEvent(val info: OffloadServiceInfo) : OffloadEvent()
        }

        override fun onOffloadServiceUpdated(info: OffloadServiceInfo) {
            add(OffloadEvent.AddOrUpdateEvent(info))
        }

        override fun onOffloadServiceRemoved(info: OffloadServiceInfo) {
            add(OffloadEvent.RemoveEvent(info))
        }
    }

    @Before
    fun setUp() {
        handlerThread.start()

        runAsShell(MANAGE_TEST_NETWORKS) {
            testNetwork1 = createTestNetwork()
            testNetwork2 = createTestNetwork()
        }
    }

    private fun createTestNetwork(): TestTapNetwork {
        val tnm = context.getSystemService(TestNetworkManager::class.java)
        val iface = tnm.createTapInterface()
        val cb = TestableNetworkCallback()
        val testNetworkSpecifier = TestNetworkSpecifier(iface.interfaceName)
        cm.requestNetwork(NetworkRequest.Builder()
                .removeCapability(NET_CAPABILITY_TRUSTED)
                .addTransportType(TRANSPORT_TEST)
                .setNetworkSpecifier(testNetworkSpecifier)
                .build(), cb)
        val agent = registerTestNetworkAgent(iface.interfaceName)
        val network = agent.network ?: fail("Registered agent should have a network")

        cb.eventuallyExpect<LinkPropertiesChanged>(TIMEOUT_MS) {
            it.lp.linkAddresses.isNotEmpty()
        }

        // The network has no INTERNET capability, so will be marked validated immediately
        // It does not matter if validated capabilities come before/after the link addresses change
        cb.eventuallyExpect<CapabilitiesChanged>(TIMEOUT_MS, from = 0) {
            it.caps.hasCapability(NET_CAPABILITY_VALIDATED)
        }
        return TestTapNetwork(iface, cb, agent, network)
    }

    private fun registerTestNetworkAgent(ifaceName: String): TestableNetworkAgent {
        val lp = LinkProperties().apply {
            interfaceName = ifaceName
        }
        val agent = TestableNetworkAgent(context, handlerThread.looper,
                NetworkCapabilities().apply {
                    removeCapability(NET_CAPABILITY_TRUSTED)
                    addTransportType(TRANSPORT_TEST)
                    setNetworkSpecifier(TestNetworkSpecifier(ifaceName))
                }, lp, NetworkAgentConfig.Builder().build())
        val network = agent.register()
        agent.markConnected()
        agent.expectCallback<OnNetworkCreated>()

        // Wait until the link-local address can be used. Address flags are not available without
        // elevated permissions, so check that bindSocket works.
        PollingCheck.check("No usable v6 address on interface after $TIMEOUT_MS ms", TIMEOUT_MS) {
            // To avoid race condition between socket connection succeeding and interface returning
            // a non-empty address list. Verify that interface returns a non-empty list, before
            // trying the socket connection.
            if (NetworkInterface.getByName(ifaceName).interfaceAddresses.isEmpty()) {
                return@check false
            }

            val sock = Os.socket(AF_INET6, SOCK_DGRAM, IPPROTO_UDP)
            tryTest {
                network.bindSocket(sock)
                Os.connect(sock, parseNumericAddress("ff02::fb%$ifaceName"), 12345)
                true
            }.catch<ErrnoException> {
                if (it.errno != ENETUNREACH && it.errno != EADDRNOTAVAIL) {
                    throw it
                }
                false
            } cleanup {
                Os.close(sock)
            }
        }

        lp.setLinkAddresses(NetworkInterface.getByName(ifaceName).interfaceAddresses.map {
            LinkAddress(it.address, it.networkPrefixLength.toInt())
        })
        agent.sendLinkProperties(lp)
        return agent
    }

    private fun makeTestServiceInfo(network: Network? = null) = NsdServiceInfo().also {
        it.serviceType = serviceType
        it.serviceName = serviceName
        it.network = network
        it.port = TEST_PORT
    }

    @After
    fun tearDown() {
        runAsShell(MANAGE_TEST_NETWORKS) {
            // Avoid throwing here if initializing failed in setUp
            if (this::testNetwork1.isInitialized) testNetwork1.close(cm)
            if (this::testNetwork2.isInitialized) testNetwork2.close(cm)
        }
        handlerThread.waitForIdle(TIMEOUT_MS)
        handlerThread.quitSafely()
        handlerThread.join()
    }

    @Test
    fun testNsdManager() {
        val si = NsdServiceInfo()
        si.serviceType = serviceType
        si.serviceName = serviceName
        // Test binary data with various bytes
        val testByteArray = byteArrayOf(-128, 127, 2, 1, 0, 1, 2)
        // Test string data with 256 characters (25 blocks of 10 characters + 6)
        val string256 = "1_________2_________3_________4_________5_________6_________" +
                "7_________8_________9_________10________11________12________13________" +
                "14________15________16________17________18________19________20________" +
                "21________22________23________24________25________123456"

        // Illegal attributes
        listOf(
                Triple(null, null, "null key"),
                Triple("", null, "empty key"),
                Triple(string256, null, "key with 256 characters"),
                Triple("key", string256.substring(3),
                        "key+value combination with more than 255 characters"),
                Triple("key", string256.substring(4), "key+value combination with 255 characters"),
                Triple("\u0019", null, "key with invalid character"),
                Triple("=", null, "key with invalid character"),
                Triple("\u007f", null, "key with invalid character")
        ).forEach {
            assertFailsWith<IllegalArgumentException>(
                    "Setting invalid ${it.third} unexpectedly succeeded") {
                si.setAttribute(it.first, it.second)
            }
        }

        // Allowed attributes
        si.setAttribute("booleanAttr", null as String?)
        si.setAttribute("keyValueAttr", "value")
        si.setAttribute("keyEqualsAttr", "=")
        si.setAttribute(" whiteSpaceKeyValueAttr ", " value ")
        si.setAttribute("binaryDataAttr", testByteArray)
        si.setAttribute("nullBinaryDataAttr", null as ByteArray?)
        si.setAttribute("emptyBinaryDataAttr", byteArrayOf())
        si.setAttribute("longkey", string256.substring(9))
        val socket = ServerSocket(0)
        val localPort = socket.localPort
        si.port = localPort
        if (DBG) Log.d(TAG, "Port = $localPort")

        val registrationRecord = NsdRegistrationRecord()
        // Test registering without an Executor
        nsdManager.registerService(si, NsdManager.PROTOCOL_DNS_SD, registrationRecord)
        val registeredInfo = registrationRecord.expectCallback<ServiceRegistered>(
                REGISTRATION_TIMEOUT_MS).serviceInfo

        // Only service name is included in ServiceRegistered callbacks
        assertNull(registeredInfo.serviceType)
        assertEquals(si.serviceName, registeredInfo.serviceName)

        val discoveryRecord = NsdDiscoveryRecord()
        // Test discovering without an Executor
        nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryRecord)

        // Expect discovery started
        discoveryRecord.expectCallback<DiscoveryStarted>()

        // Expect a service record to be discovered
        val foundInfo = discoveryRecord.waitForServiceDiscovered(
                registeredInfo.serviceName, serviceType)

        // Test resolving without an Executor
        val resolveRecord = NsdResolveRecord()
        nsdManager.resolveService(foundInfo, resolveRecord)
        val resolvedService = resolveRecord.expectCallback<ServiceResolved>().serviceInfo
        assertEquals(".$serviceType", resolvedService.serviceType)
        assertEquals(registeredInfo.serviceName, resolvedService.serviceName)

        // Check Txt attributes
        assertEquals(8, resolvedService.attributes.size)
        assertTrue(resolvedService.attributes.containsKey("booleanAttr"))
        assertNull(resolvedService.attributes["booleanAttr"])
        assertEquals("value", resolvedService.attributes["keyValueAttr"].utf8ToString())
        assertEquals("=", resolvedService.attributes["keyEqualsAttr"].utf8ToString())
        assertEquals(" value ",
                resolvedService.attributes[" whiteSpaceKeyValueAttr "].utf8ToString())
        assertEquals(string256.substring(9), resolvedService.attributes["longkey"].utf8ToString())
        assertArrayEquals(testByteArray, resolvedService.attributes["binaryDataAttr"])
        assertTrue(resolvedService.attributes.containsKey("nullBinaryDataAttr"))
        assertNull(resolvedService.attributes["nullBinaryDataAttr"])
        assertTrue(resolvedService.attributes.containsKey("emptyBinaryDataAttr"))
        if (isAtLeastU() || CompatChanges.isChangeEnabled(
                ConnectivityCompatChanges.ENABLE_PLATFORM_MDNS_BACKEND
            )) {
            assertArrayEquals(byteArrayOf(), resolvedService.attributes["emptyBinaryDataAttr"])
        } else {
            assertNull(resolvedService.attributes["emptyBinaryDataAttr"])
        }
        assertEquals(localPort, resolvedService.port)

        // Unregister the service
        nsdManager.unregisterService(registrationRecord)
        registrationRecord.expectCallback<ServiceUnregistered>()

        // Expect a callback for service lost
        val lostCb = discoveryRecord.expectCallbackEventually<ServiceLost> {
            it.serviceInfo.serviceName == serviceName
        }
        // Lost service types have a dot at the end
        assertEquals("$serviceType.", lostCb.serviceInfo.serviceType)

        // Register service again to see if NsdManager can discover it
        val si2 = NsdServiceInfo()
        si2.serviceType = serviceType
        si2.serviceName = serviceName
        si2.port = localPort
        val registrationRecord2 = NsdRegistrationRecord()
        nsdManager.registerService(si2, NsdManager.PROTOCOL_DNS_SD, registrationRecord2)
        val registeredInfo2 = registrationRecord2.expectCallback<ServiceRegistered>(
                REGISTRATION_TIMEOUT_MS).serviceInfo

        // Expect a service record to be discovered (and filter the ones
        // that are unrelated to this test)
        val foundInfo2 = discoveryRecord.waitForServiceDiscovered(
                registeredInfo2.serviceName, serviceType)

        // Resolve the service
        val resolveRecord2 = NsdResolveRecord()
        nsdManager.resolveService(foundInfo2, resolveRecord2)
        val resolvedService2 = resolveRecord2.expectCallback<ServiceResolved>().serviceInfo

        // Check that the resolved service doesn't have any TXT records
        assertEquals(0, resolvedService2.attributes.size)

        nsdManager.stopServiceDiscovery(discoveryRecord)

        discoveryRecord.expectCallbackEventually<DiscoveryStopped>()

        nsdManager.unregisterService(registrationRecord2)
        registrationRecord2.expectCallback<ServiceUnregistered>()
    }

    @Test
    fun testNsdManager_DiscoverOnNetwork() {
        val si = NsdServiceInfo()
        si.serviceType = serviceType
        si.serviceName = this.serviceName
        si.port = 12345 // Test won't try to connect so port does not matter

        val registrationRecord = NsdRegistrationRecord()
        val registeredInfo = registerService(registrationRecord, si)

        tryTest {
            val discoveryRecord = NsdDiscoveryRecord()
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD,
                    testNetwork1.network, Executor { it.run() }, discoveryRecord)

            val foundInfo = discoveryRecord.waitForServiceDiscovered(
                    serviceName, serviceType, testNetwork1.network)
            assertEquals(testNetwork1.network, foundInfo.network)

            // Rewind to ensure the service is not found on the other interface
            discoveryRecord.nextEvents.rewind(0)
            assertNull(discoveryRecord.nextEvents.poll(timeoutMs = 100L) {
                it is ServiceFound &&
                        it.serviceInfo.serviceName == registeredInfo.serviceName &&
                        it.serviceInfo.network != testNetwork1.network
            }, "The service should not be found on this network")
        } cleanup {
            nsdManager.unregisterService(registrationRecord)
        }
    }

    @Test
    fun testNsdManager_DiscoverWithNetworkRequest() {
        val si = NsdServiceInfo()
        si.serviceType = serviceType
        si.serviceName = this.serviceName
        si.port = 12345 // Test won't try to connect so port does not matter

        val handler = Handler(handlerThread.looper)
        val executor = Executor { handler.post(it) }

        val registrationRecord = NsdRegistrationRecord(expectedThreadId = handlerThread.threadId)
        val registeredInfo1 = registerService(registrationRecord, si, executor)
        val discoveryRecord = NsdDiscoveryRecord(expectedThreadId = handlerThread.threadId)

        tryTest {
            val specifier = TestNetworkSpecifier(testNetwork1.iface.interfaceName)
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD,
                    NetworkRequest.Builder()
                            .removeCapability(NET_CAPABILITY_TRUSTED)
                            .addTransportType(TRANSPORT_TEST)
                            .setNetworkSpecifier(specifier)
                            .build(),
                    executor, discoveryRecord)

            val discoveryStarted = discoveryRecord.expectCallback<DiscoveryStarted>()
            assertEquals(serviceType, discoveryStarted.serviceType)

            val serviceDiscovered = discoveryRecord.expectCallback<ServiceFound>()
            assertEquals(registeredInfo1.serviceName, serviceDiscovered.serviceInfo.serviceName)
            // Discovered service types have a dot at the end
            assertEquals("$serviceType.", serviceDiscovered.serviceInfo.serviceType)
            assertEquals(testNetwork1.network, serviceDiscovered.serviceInfo.network)

            // Unregister, then register the service back: it should be lost and found again
            nsdManager.unregisterService(registrationRecord)
            val serviceLost1 = discoveryRecord.expectCallback<ServiceLost>()
            assertEquals(registeredInfo1.serviceName, serviceLost1.serviceInfo.serviceName)
            assertEquals(testNetwork1.network, serviceLost1.serviceInfo.network)

            registrationRecord.expectCallback<ServiceUnregistered>()
            val registeredInfo2 = registerService(registrationRecord, si, executor)
            val serviceDiscovered2 = discoveryRecord.expectCallback<ServiceFound>()
            assertEquals(registeredInfo2.serviceName, serviceDiscovered2.serviceInfo.serviceName)
            assertEquals("$serviceType.", serviceDiscovered2.serviceInfo.serviceType)
            assertEquals(testNetwork1.network, serviceDiscovered2.serviceInfo.network)

            // Teardown, then bring back up a network on the test interface: the service should
            // go away, then come back
            testNetwork1.agent.unregister()
            val serviceLost = discoveryRecord.expectCallback<ServiceLost>()
            assertEquals(registeredInfo2.serviceName, serviceLost.serviceInfo.serviceName)
            assertEquals(testNetwork1.network, serviceLost.serviceInfo.network)

            val newAgent = runAsShell(MANAGE_TEST_NETWORKS) {
                registerTestNetworkAgent(testNetwork1.iface.interfaceName)
            }
            val newNetwork = newAgent.network ?: fail("Registered agent should have a network")
            val serviceDiscovered3 = discoveryRecord.expectCallback<ServiceFound>()
            assertEquals(registeredInfo2.serviceName, serviceDiscovered3.serviceInfo.serviceName)
            assertEquals("$serviceType.", serviceDiscovered3.serviceInfo.serviceType)
            assertEquals(newNetwork, serviceDiscovered3.serviceInfo.network)
        } cleanupStep {
            nsdManager.stopServiceDiscovery(discoveryRecord)
            discoveryRecord.expectCallback<DiscoveryStopped>()
        } cleanup {
            nsdManager.unregisterService(registrationRecord)
        }
    }

    @Test
    fun testNsdManager_DiscoverWithNetworkRequest_NoMatchingNetwork() {
        val si = NsdServiceInfo()
        si.serviceType = serviceType
        si.serviceName = this.serviceName
        si.port = 12345 // Test won't try to connect so port does not matter

        val handler = Handler(handlerThread.looper)
        val executor = Executor { handler.post(it) }

        val discoveryRecord = NsdDiscoveryRecord(expectedThreadId = handlerThread.threadId)
        val specifier = TestNetworkSpecifier(testNetwork1.iface.interfaceName)

        tryTest {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD,
                    NetworkRequest.Builder()
                            .removeCapability(NET_CAPABILITY_TRUSTED)
                            .addTransportType(TRANSPORT_TEST)
                            // Specified network does not have this capability
                            .addCapability(NET_CAPABILITY_TEMPORARILY_NOT_METERED)
                            .setNetworkSpecifier(specifier)
                            .build(),
                    executor, discoveryRecord)
            discoveryRecord.expectCallback<DiscoveryStarted>()
        } cleanup {
            nsdManager.stopServiceDiscovery(discoveryRecord)
            discoveryRecord.expectCallback<DiscoveryStopped>()
        }
    }

    private fun checkAddressScopeId(iface: TestNetworkInterface, address: List<InetAddress>) {
        val targetSdkVersion = context.packageManager
            .getTargetSdkVersion(context.applicationInfo.packageName)
        if (targetSdkVersion <= Build.VERSION_CODES.TIRAMISU) {
            return
        }
        val ifaceIdx = NetworkInterface.getByName(iface.interfaceName).index
        address.forEach {
            if (it is Inet6Address && it.isLinkLocalAddress) {
                assertEquals(ifaceIdx, it.scopeId)
            }
        }
    }

    @Test
    fun testNsdManager_ResolveOnNetwork() {
        val si = NsdServiceInfo()
        si.serviceType = serviceType
        si.serviceName = this.serviceName
        si.port = 12345 // Test won't try to connect so port does not matter

        val registrationRecord = NsdRegistrationRecord()
        val registeredInfo = registerService(registrationRecord, si)
        tryTest {
            val resolveRecord = NsdResolveRecord()

            val discoveryRecord = NsdDiscoveryRecord()
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryRecord)

            val foundInfo1 = discoveryRecord.waitForServiceDiscovered(
                    serviceName, serviceType, testNetwork1.network)
            assertEquals(testNetwork1.network, foundInfo1.network)
            // Rewind as the service could be found on each interface in any order
            discoveryRecord.nextEvents.rewind(0)
            val foundInfo2 = discoveryRecord.waitForServiceDiscovered(
                    serviceName, serviceType, testNetwork2.network)
            assertEquals(testNetwork2.network, foundInfo2.network)

            nsdManager.resolveService(foundInfo1, Executor { it.run() }, resolveRecord)
            val cb = resolveRecord.expectCallback<ServiceResolved>()
            cb.serviceInfo.let {
                // Resolved service type has leading dot
                assertEquals(".$serviceType", it.serviceType)
                assertEquals(registeredInfo.serviceName, it.serviceName)
                assertEquals(si.port, it.port)
                assertEquals(testNetwork1.network, it.network)
                checkAddressScopeId(testNetwork1.iface, it.hostAddresses)
            }
            // TODO: check that MDNS packets are sent only on testNetwork1.
        } cleanupStep {
            nsdManager.unregisterService(registrationRecord)
        } cleanup {
            registrationRecord.expectCallback<ServiceUnregistered>()
        }
    }

    @Test
    fun testNsdManager_RegisterOnNetwork() {
        val si = NsdServiceInfo()
        si.serviceType = serviceType
        si.serviceName = this.serviceName
        si.network = testNetwork1.network
        si.port = 12345 // Test won't try to connect so port does not matter

        // Register service on testNetwork1
        val registrationRecord = NsdRegistrationRecord()
        registerService(registrationRecord, si)
        val discoveryRecord = NsdDiscoveryRecord()
        val discoveryRecord2 = NsdDiscoveryRecord()
        val discoveryRecord3 = NsdDiscoveryRecord()

        tryTest {
            // Discover service on testNetwork1.
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD,
                testNetwork1.network, Executor { it.run() }, discoveryRecord)
            // Expect that service is found on testNetwork1
            val foundInfo = discoveryRecord.waitForServiceDiscovered(
                serviceName, serviceType, testNetwork1.network)
            assertEquals(testNetwork1.network, foundInfo.network)

            // Discover service on testNetwork2.
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD,
                testNetwork2.network, Executor { it.run() }, discoveryRecord2)
            // Expect that discovery is started then no other callbacks.
            discoveryRecord2.expectCallback<DiscoveryStarted>()
            discoveryRecord2.assertNoCallback()

            // Discover service on all networks (not specify any network).
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD,
                null as Network? /* network */, Executor { it.run() }, discoveryRecord3)
            // Expect that service is found on testNetwork1
            val foundInfo3 = discoveryRecord3.waitForServiceDiscovered(
                    serviceName, serviceType, testNetwork1.network)
            assertEquals(testNetwork1.network, foundInfo3.network)
        } cleanupStep {
            nsdManager.stopServiceDiscovery(discoveryRecord2)
            discoveryRecord2.expectCallback<DiscoveryStopped>()
        } cleanup {
            nsdManager.unregisterService(registrationRecord)
        }
    }

    @Test
    fun testNsdManager_RegisterServiceNameWithNonStandardCharacters() {
        val serviceNames = "^Nsd.Test|Non-#AsCiI\\Characters&\\ufffe テスト 測試"
        val si = NsdServiceInfo().apply {
            serviceType = this@NsdManagerTest.serviceType
            serviceName = serviceNames
            port = 12345 // Test won't try to connect so port does not matter
        }

        // Register the service name which contains non-standard characters.
        val registrationRecord = NsdRegistrationRecord()
        nsdManager.registerService(si, NsdManager.PROTOCOL_DNS_SD, registrationRecord)
        registrationRecord.expectCallback<ServiceRegistered>(REGISTRATION_TIMEOUT_MS)

        tryTest {
            // Discover that service name.
            val discoveryRecord = NsdDiscoveryRecord()
            nsdManager.discoverServices(
                serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryRecord
            )
            val foundInfo = discoveryRecord.waitForServiceDiscovered(serviceNames, serviceType)

            // Expect that resolving the service name works properly even service name contains
            // non-standard characters.
            val resolveRecord = NsdResolveRecord()
            nsdManager.resolveService(foundInfo, resolveRecord)
            val resolvedCb = resolveRecord.expectCallback<ServiceResolved>()
            assertEquals(foundInfo.serviceName, resolvedCb.serviceInfo.serviceName)
        } cleanupStep {
            nsdManager.unregisterService(registrationRecord)
        } cleanup {
            registrationRecord.expectCallback<ServiceUnregistered>()
        }
    }

    fun checkOffloadServiceInfo(serviceInfo: OffloadServiceInfo) {
        assertEquals(serviceName, serviceInfo.key.serviceName)
        assertEquals(serviceType, serviceInfo.key.serviceType)
        assertEquals(listOf<String>("_subtype"), serviceInfo.subtypes)
        assertTrue(serviceInfo.hostname.startsWith("Android_"))
        assertTrue(serviceInfo.hostname.endsWith("local"))
        assertEquals(0, serviceInfo.priority)
        assertEquals(OffloadEngine.OFFLOAD_TYPE_REPLY.toLong(), serviceInfo.offloadType)
    }

    @Test
    fun testNsdManager_registerOffloadEngine() {
        val targetSdkVersion = context.packageManager
            .getTargetSdkVersion(context.applicationInfo.packageName)
        // The offload callbacks are only supported with the new backend,
        // enabled with target SDK U+.
        assumeTrue(isAtLeastU() || targetSdkVersion > Build.VERSION_CODES.TIRAMISU)
        val offloadEngine = TestNsdOffloadEngine()
        runAsShell(NETWORK_SETTINGS) {
            nsdManager.registerOffloadEngine(testNetwork1.iface.interfaceName,
                OffloadEngine.OFFLOAD_TYPE_REPLY.toLong(),
                OffloadEngine.OFFLOAD_CAPABILITY_BYPASS_MULTICAST_LOCK.toLong(),
                { it.run() }, offloadEngine)
        }

        val si = NsdServiceInfo()
        si.serviceType = "$serviceType,_subtype"
        si.serviceName = serviceName
        si.network = testNetwork1.network
        si.port = 12345
        val record = NsdRegistrationRecord()
        nsdManager.registerService(si, NsdManager.PROTOCOL_DNS_SD, record)
        val addOrUpdateEvent = offloadEngine
            .expectCallbackEventually<TestNsdOffloadEngine.OffloadEvent.AddOrUpdateEvent> {
                it.info.key.serviceName == serviceName
            }
        checkOffloadServiceInfo(addOrUpdateEvent.info)

        nsdManager.unregisterService(record)
        val unregisterEvent = offloadEngine
            .expectCallbackEventually<TestNsdOffloadEngine.OffloadEvent.RemoveEvent> {
                it.info.key.serviceName == serviceName
            }
        checkOffloadServiceInfo(unregisterEvent.info)

        runAsShell(NETWORK_SETTINGS) {
            nsdManager.unregisterOffloadEngine(offloadEngine)
        }
    }

    private fun checkConnectSocketToMdnsd(shouldFail: Boolean) {
        val discoveryRecord = NsdDiscoveryRecord()
        val localSocket = LocalSocket()
        tryTest {
            // Discover any service from NsdManager to enforce NsdService to start the mdnsd.
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryRecord)
            discoveryRecord.expectCallback<DiscoveryStarted>()

            // Checks the /dev/socket/mdnsd is created.
            val socket = File("/dev/socket/mdnsd")
            val doesSocketExist = PollingCheck.waitFor(
                TIMEOUT_MS,
                {
                    socket.exists()
                },
                { doesSocketExist ->
                    doesSocketExist
                },
            )

            // If the socket is not created, then no need to check the access.
            if (doesSocketExist) {
                // Create a LocalSocket and try to connect to mdnsd.
                assertFalse("LocalSocket is connected.", localSocket.isConnected)
                val address = LocalSocketAddress("mdnsd", LocalSocketAddress.Namespace.RESERVED)
                if (shouldFail) {
                    assertFailsWith<IOException>("Expect fail but socket connected") {
                        localSocket.connect(address)
                    }
                } else {
                    localSocket.connect(address)
                    assertTrue("LocalSocket is not connected.", localSocket.isConnected)
                }
            }
        } cleanup {
            localSocket.close()
            nsdManager.stopServiceDiscovery(discoveryRecord)
            discoveryRecord.expectCallback<DiscoveryStopped>()
        }
    }

    /**
     * Starting from Android U, the access to the /dev/socket/mdnsd is blocked by the
     * sepolicy(b/265364111).
     */
    @DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.TIRAMISU)
    @Test
    fun testCannotConnectSocketToMdnsd() {
        val targetSdkVersion = context.packageManager
                .getTargetSdkVersion(context.applicationInfo.packageName)
        assumeTrue(targetSdkVersion > Build.VERSION_CODES.TIRAMISU)
        val firstApiLevel = min(PropertyUtil.getFirstApiLevel(), PropertyUtil.getVendorApiLevel())
        // The sepolicy is implemented in the vendor image, so the access may not be blocked if
        // the vendor image is not update to date.
        assumeTrue(firstApiLevel > Build.VERSION_CODES.TIRAMISU)
        checkConnectSocketToMdnsd(shouldFail = true)
    }

    @Test @CtsNetTestCasesMaxTargetSdk33("mdnsd socket is accessible up to target SDK 33")
    fun testCanConnectSocketToMdnsd() {
        checkConnectSocketToMdnsd(shouldFail = false)
    }

    @Test @CtsNetTestCasesMaxTargetSdk30("Socket is started with the service up to target SDK 30")
    fun testManagerCreatesLegacySocket() {
        nsdManager // Ensure the lazy-init member is initialized, so NsdManager is created
        val socket = File("/dev/socket/mdnsd")
        val timeout = System.currentTimeMillis() + TIMEOUT_MS
        while (!socket.exists() && System.currentTimeMillis() < timeout) {
            Thread.sleep(10)
        }
        assertTrue("$socket was not found after $TIMEOUT_MS ms", socket.exists())
    }

    // The compat change is part of a connectivity module update that applies to T+
    @ConnectivityModuleTest @DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.S_V2)
    @Test @CtsNetTestCasesMaxTargetSdk30("Socket is started with the service up to target SDK 30")
    fun testManagerCreatesLegacySocket_CompatChange() {
        // The socket may have been already created by some other app, or some other test, in which
        // case this test cannot verify creation. At least verify that the compat change is
        // disabled in a process with max SDK 30; unit tests already verify that start is requested
        // when the compat change is disabled.
        // Note that before T the compat constant had a different int value.
        assertFalse(CompatChanges.isChangeEnabled(
                ConnectivityCompatChanges.RUN_NATIVE_NSD_ONLY_IF_LEGACY_APPS_T_AND_LATER))
    }

    @Test
    fun testStopServiceResolution() {
        val si = NsdServiceInfo()
        si.serviceType = this@NsdManagerTest.serviceType
        si.serviceName = this@NsdManagerTest.serviceName
        si.port = 12345 // Test won't try to connect so port does not matter

        val resolveRecord = NsdResolveRecord()
        // Try to resolve an unknown service then stop it immediately.
        // Expected ResolutionStopped callback.
        nsdManager.resolveService(si, { it.run() }, resolveRecord)
        nsdManager.stopServiceResolution(resolveRecord)
        val stoppedCb = resolveRecord.expectCallback<ResolutionStopped>()
        assertEquals(si.serviceName, stoppedCb.serviceInfo.serviceName)
        assertEquals(si.serviceType, stoppedCb.serviceInfo.serviceType)
    }

    @Test
    fun testRegisterServiceInfoCallback() {
        val lp = cm.getLinkProperties(testNetwork1.network)
        assertNotNull(lp)
        val addresses = lp.addresses
        assertFalse(addresses.isEmpty())

        val si = NsdServiceInfo().apply {
            serviceType = this@NsdManagerTest.serviceType
            serviceName = this@NsdManagerTest.serviceName
            network = testNetwork1.network
            port = 12345 // Test won't try to connect so port does not matter
        }

        // Register service on the network
        val registrationRecord = NsdRegistrationRecord()
        registerService(registrationRecord, si)

        val discoveryRecord = NsdDiscoveryRecord()
        val cbRecord = NsdServiceInfoCallbackRecord()
        tryTest {
            // Discover service on the network.
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD,
                    testNetwork1.network, Executor { it.run() }, discoveryRecord)
            val foundInfo = discoveryRecord.waitForServiceDiscovered(
                    serviceName, serviceType, testNetwork1.network)

            // Register service callback and check the addresses are the same as network addresses
            nsdManager.registerServiceInfoCallback(foundInfo, { it.run() }, cbRecord)
            val serviceInfoCb = cbRecord.expectCallback<ServiceUpdated>()
            assertEquals(foundInfo.serviceName, serviceInfoCb.serviceInfo.serviceName)
            val hostAddresses = serviceInfoCb.serviceInfo.hostAddresses
            assertEquals(addresses.size, hostAddresses.size)
            for (hostAddress in hostAddresses) {
                assertTrue(addresses.contains(hostAddress))
            }
            checkAddressScopeId(testNetwork1.iface, serviceInfoCb.serviceInfo.hostAddresses)
        } cleanupStep {
            nsdManager.unregisterService(registrationRecord)
            registrationRecord.expectCallback<ServiceUnregistered>()
            discoveryRecord.expectCallback<ServiceLost>()
            cbRecord.expectCallback<ServiceUpdatedLost>()
        } cleanupStep {
            // Cancel subscription and check stop callback received.
            nsdManager.unregisterServiceInfoCallback(cbRecord)
            cbRecord.expectCallback<UnregisterCallbackSucceeded>()
        } cleanup {
            nsdManager.stopServiceDiscovery(discoveryRecord)
            discoveryRecord.expectCallback<DiscoveryStopped>()
        }
    }

    @Test
    fun testStopServiceResolutionFailedCallback() {
        // It's not possible to make ResolutionListener#onStopResolutionFailed callback sending
        // because it is only sent in very edge-case scenarios when the legacy implementation is
        // used, and the legacy implementation is never used in the current AOSP builds. Considering
        // that this callback isn't expected to be sent at all at the moment, and this is just an
        // interface with no implementation. To verify this callback, just call
        // onStopResolutionFailed on the record directly then verify it is received.
        val resolveRecord = NsdResolveRecord()
        resolveRecord.onStopResolutionFailed(
                NsdServiceInfo(), NsdManager.FAILURE_OPERATION_NOT_RUNNING)
        val failedCb = resolveRecord.expectCallback<StopResolutionFailed>()
        assertEquals(NsdManager.FAILURE_OPERATION_NOT_RUNNING, failedCb.errorCode)
    }

    @Test
    fun testSubtypeAdvertisingAndDiscovery() {
        val si = makeTestServiceInfo(network = testNetwork1.network)
        // Test "_type._tcp.local,_subtype" syntax with the registration
        si.serviceType = si.serviceType + ",_subtype"

        val registrationRecord = NsdRegistrationRecord()

        val baseTypeDiscoveryRecord = NsdDiscoveryRecord()
        val subtypeDiscoveryRecord = NsdDiscoveryRecord()
        val otherSubtypeDiscoveryRecord = NsdDiscoveryRecord()
        tryTest {
            registerService(registrationRecord, si)

            // Test "_subtype._type._tcp.local" syntax with discovery. Note this is not
            // "_subtype._sub._type._tcp.local".
            nsdManager.discoverServices(serviceType,
                    NsdManager.PROTOCOL_DNS_SD,
                    testNetwork1.network, Executor { it.run() }, baseTypeDiscoveryRecord)
            nsdManager.discoverServices("_othersubtype.$serviceType",
                    NsdManager.PROTOCOL_DNS_SD,
                    testNetwork1.network, Executor { it.run() }, otherSubtypeDiscoveryRecord)
            nsdManager.discoverServices("_subtype.$serviceType",
                    NsdManager.PROTOCOL_DNS_SD,
                    testNetwork1.network, Executor { it.run() }, subtypeDiscoveryRecord)

            subtypeDiscoveryRecord.waitForServiceDiscovered(
                    serviceName, serviceType, testNetwork1.network)
            baseTypeDiscoveryRecord.waitForServiceDiscovered(
                    serviceName, serviceType, testNetwork1.network)
            otherSubtypeDiscoveryRecord.expectCallback<DiscoveryStarted>()
            // The subtype callback was registered later but called, no need for an extra delay
            otherSubtypeDiscoveryRecord.assertNoCallback(timeoutMs = 0)
        } cleanupStep {
            nsdManager.stopServiceDiscovery(baseTypeDiscoveryRecord)
            nsdManager.stopServiceDiscovery(subtypeDiscoveryRecord)
            nsdManager.stopServiceDiscovery(otherSubtypeDiscoveryRecord)

            baseTypeDiscoveryRecord.expectCallback<DiscoveryStopped>()
            subtypeDiscoveryRecord.expectCallback<DiscoveryStopped>()
            otherSubtypeDiscoveryRecord.expectCallback<DiscoveryStopped>()
        } cleanup {
            nsdManager.unregisterService(registrationRecord)
        }
    }

    @Test
    fun testRegisterWithConflictDuringProbing() {
        // This test requires shims supporting T+ APIs (NsdServiceInfo.network)
        assumeTrue(TestUtils.shouldTestTApis())

        val si = NsdServiceInfo()
        si.serviceType = serviceType
        si.serviceName = serviceName
        si.network = testNetwork1.network
        si.port = 12345 // Test won't try to connect so port does not matter

        val packetReader = TapPacketReader(Handler(handlerThread.looper),
                testNetwork1.iface.fileDescriptor.fileDescriptor, 1500 /* maxPacketSize */)
        packetReader.startAsyncForTest()
        handlerThread.waitForIdle(TIMEOUT_MS)

        // Register service on testNetwork1
        val registrationRecord = NsdRegistrationRecord()
        nsdManager.registerService(si, NsdManager.PROTOCOL_DNS_SD, { it.run() },
                registrationRecord)

        tryTest {
            assertNotNull(packetReader.pollForProbe(serviceName, serviceType),
                    "Did not find a probe for the service")
            packetReader.sendResponse(buildConflictingAnnouncement())

            // Registration must use an updated name to avoid the conflict
            val cb = registrationRecord.expectCallback<ServiceRegistered>(REGISTRATION_TIMEOUT_MS)
            cb.serviceInfo.serviceName.let {
                assertTrue("Unexpected registered name: $it",
                        it.startsWith(serviceName) && it != serviceName)
            }
        } cleanupStep {
            nsdManager.unregisterService(registrationRecord)
            registrationRecord.expectCallback<ServiceUnregistered>()
        } cleanup {
            packetReader.handler.post { packetReader.stop() }
            handlerThread.waitForIdle(TIMEOUT_MS)
        }
    }

    @Test
    fun testRegisterWithConflictAfterProbing() {
        // This test requires shims supporting T+ APIs (NsdServiceInfo.network)
        assumeTrue(TestUtils.shouldTestTApis())

        val si = NsdServiceInfo()
        si.serviceType = serviceType
        si.serviceName = serviceName
        si.network = testNetwork1.network
        si.port = 12345 // Test won't try to connect so port does not matter

        // Register service on testNetwork1
        val registrationRecord = NsdRegistrationRecord()
        val discoveryRecord = NsdDiscoveryRecord()
        val registeredService = registerService(registrationRecord, si)
        val packetReader = TapPacketReader(Handler(handlerThread.looper),
                testNetwork1.iface.fileDescriptor.fileDescriptor, 1500 /* maxPacketSize */)
        packetReader.startAsyncForTest()
        handlerThread.waitForIdle(TIMEOUT_MS)

        tryTest {
            assertNotNull(packetReader.pollForAdvertisement(serviceName, serviceType),
                    "No announcements sent after initial probing")

            assertEquals(si.serviceName, registeredService.serviceName)

            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD,
                testNetwork1.network, { it.run() }, discoveryRecord)
            discoveryRecord.waitForServiceDiscovered(si.serviceName, serviceType)

            // Send a conflicting announcement
            val conflictingAnnouncement = buildConflictingAnnouncement()
            packetReader.sendResponse(conflictingAnnouncement)

            // Expect to see probes (RFC6762 9., service is reset to probing state)
            assertNotNull(packetReader.pollForProbe(serviceName, serviceType),
                    "Probe not received within timeout after conflict")

            // Send the conflicting packet again to reply to the probe
            packetReader.sendResponse(conflictingAnnouncement)

            // Note the legacy mdnsresponder would send an exit announcement here (a 0-lifetime
            // advertisement just for the PTR record), but not the new advertiser. This probably
            // follows RFC 6762 8.4, saying that when a record rdata changed, "In the case of shared
            // records, a host MUST send a "goodbye" announcement with RR TTL zero [...] for the old
            // rdata, to cause it to be deleted from peer caches, before announcing the new rdata".
            //
            // This should be implemented by the new advertiser, but in the case of conflicts it is
            // not very valuable since an identical PTR record would be used by the conflicting
            // service (except for subtypes). In that case the exit announcement may be
            // counter-productive as it conflicts with announcements done by the conflicting
            // service.

            // Note that before sending the following ServiceRegistered callback for the renamed
            // service, the legacy mdnsresponder-based implementation would first send a
            // Service*Registered* callback for the original service name being *unregistered*; it
            // should have been a ServiceUnregistered callback instead (bug in NsdService
            // interpretation of the callback).
            val newRegistration = registrationRecord.expectCallbackEventually<ServiceRegistered>(
                    REGISTRATION_TIMEOUT_MS) {
                it.serviceInfo.serviceName.startsWith(serviceName) &&
                        it.serviceInfo.serviceName != serviceName
            }

            discoveryRecord.expectCallbackEventually<ServiceFound> {
                it.serviceInfo.serviceName == newRegistration.serviceInfo.serviceName
            }
        } cleanupStep {
            nsdManager.stopServiceDiscovery(discoveryRecord)
            discoveryRecord.expectCallback<DiscoveryStopped>()
        } cleanupStep {
            nsdManager.unregisterService(registrationRecord)
            registrationRecord.expectCallback<ServiceUnregistered>()
        } cleanup {
            packetReader.handler.post { packetReader.stop() }
            handlerThread.waitForIdle(TIMEOUT_MS)
        }
    }

    private fun buildConflictingAnnouncement(): ByteBuffer {
        /*
        Generated with:
        scapy.raw(scapy.DNS(rd=0, qr=1, aa=1, qd = None, an =
                scapy.DNSRRSRV(rrname='NsdTest123456789._nmt123456789._tcp.local',
                    rclass=0x8001, port=31234, target='conflict.local', ttl=120)
        )).hex()
         */
        val mdnsPayload = HexDump.hexStringToByteArray("000084000000000100000000104e736454657" +
                "3743132333435363738390d5f6e6d74313233343536373839045f746370056c6f63616c00002" +
                "18001000000780016000000007a0208636f6e666c696374056c6f63616c00")
        val packetBuffer = ByteBuffer.wrap(mdnsPayload)
        // Replace service name and types in the packet with the random ones used in the test.
        // Test service name and types have consistent length and are always ASCII
        val testPacketName = "NsdTest123456789".encodeToByteArray()
        val testPacketTypePrefix = "_nmt123456789".encodeToByteArray()
        val encodedServiceName = serviceName.encodeToByteArray()
        val encodedTypePrefix = serviceType.split('.')[0].encodeToByteArray()
        assertEquals(testPacketName.size, encodedServiceName.size)
        assertEquals(testPacketTypePrefix.size, encodedTypePrefix.size)
        packetBuffer.position(mdnsPayload.indexOf(testPacketName))
        packetBuffer.put(encodedServiceName)
        packetBuffer.position(mdnsPayload.indexOf(testPacketTypePrefix))
        packetBuffer.put(encodedTypePrefix)

        return buildMdnsPacket(mdnsPayload)
    }

    private fun buildMdnsPacket(mdnsPayload: ByteArray): ByteBuffer {
        val packetBuffer = PacketBuilder.allocate(true /* hasEther */, IPPROTO_IPV6,
                IPPROTO_UDP, mdnsPayload.size)
        val packetBuilder = PacketBuilder(packetBuffer)
        // Multicast ethernet address for IPv6 to ff02::fb
        val multicastEthAddr = MacAddress.fromBytes(
                byteArrayOf(0x33, 0x33, 0, 0, 0, 0xfb.toByte()))
        packetBuilder.writeL2Header(
                MacAddress.fromBytes(byteArrayOf(1, 2, 3, 4, 5, 6)) /* srcMac */,
                multicastEthAddr,
                ETH_P_IPV6.toShort())
        packetBuilder.writeIpv6Header(
                0x60000000, // version=6, traffic class=0x0, flowlabel=0x0
                IPPROTO_UDP.toByte(),
                64 /* hop limit */,
                parseNumericAddress("2001:db8::123") as Inet6Address /* srcIp */,
                multicastIpv6Addr /* dstIp */)
        packetBuilder.writeUdpHeader(MDNS_PORT /* srcPort */, MDNS_PORT /* dstPort */)
        packetBuffer.put(mdnsPayload)
        return packetBuilder.finalizePacket()
    }

    /**
     * Register a service and return its registration record.
     */
    private fun registerService(
        record: NsdRegistrationRecord,
        si: NsdServiceInfo,
        executor: Executor = Executor { it.run() }
    ): NsdServiceInfo {
        nsdManager.registerService(si, NsdManager.PROTOCOL_DNS_SD, executor, record)
        // We may not always get the name that we tried to register;
        // This events tells us the name that was registered.
        val cb = record.expectCallback<ServiceRegistered>(REGISTRATION_TIMEOUT_MS)
        return cb.serviceInfo
    }

    private fun resolveService(discoveredInfo: NsdServiceInfo): NsdServiceInfo {
        val record = NsdResolveRecord()
        nsdManager.resolveService(discoveredInfo, Executor { it.run() }, record)
        val resolvedCb = record.expectCallback<ServiceResolved>()
        assertEquals(discoveredInfo.serviceName, resolvedCb.serviceInfo.serviceName)

        return resolvedCb.serviceInfo
    }
}

private fun TapPacketReader.pollForMdnsPacket(
    timeoutMs: Long = REGISTRATION_TIMEOUT_MS,
    predicate: (TestDnsPacket) -> Boolean
): ByteArray? {
    val mdnsProbeFilter = IPv6UdpFilter(srcPort = MDNS_PORT, dstPort = MDNS_PORT).and {
        val mdnsPayload = it.copyOfRange(
                ETHER_HEADER_LEN + IPV6_HEADER_LEN + UDP_HEADER_LEN, it.size)
        try {
            predicate(TestDnsPacket(mdnsPayload))
        } catch (e: DnsPacket.ParseException) {
            false
        }
    }
    return poll(timeoutMs, mdnsProbeFilter)
}

private fun TapPacketReader.pollForProbe(
    serviceName: String,
    serviceType: String,
    timeoutMs: Long = REGISTRATION_TIMEOUT_MS
): ByteArray? = pollForMdnsPacket(timeoutMs) { it.isProbeFor("$serviceName.$serviceType.local") }

private fun TapPacketReader.pollForAdvertisement(
    serviceName: String,
    serviceType: String,
    timeoutMs: Long = REGISTRATION_TIMEOUT_MS
): ByteArray? = pollForMdnsPacket(timeoutMs) { it.isReplyFor("$serviceName.$serviceType.local") }

private class TestDnsPacket(data: ByteArray) : DnsPacket(data) {
    fun isProbeFor(name: String): Boolean = mRecords[QDSECTION].any {
        it.dName == name && it.nsType == 0xff /* ANY */
    }

    fun isReplyFor(name: String): Boolean = mRecords[ANSECTION].any {
        it.dName == name && it.nsType == 0x21 /* SRV */
    }
}

private fun ByteArray?.utf8ToString(): String {
    if (this == null) return ""
    return String(this, StandardCharsets.UTF_8)
}

private fun ByteArray.indexOf(sub: ByteArray): Int {
    var subIndex = 0
    forEachIndexed { i, b ->
        when (b) {
            // Still matching: continue comparing with next byte
            sub[subIndex] -> {
                subIndex++
                if (subIndex == sub.size) {
                    return i - sub.size + 1
                }
            }
            // Not matching next byte but matches first byte: continue comparing with 2nd byte
            sub[0] -> subIndex = 1
            // No matches: continue comparing from first byte
            else -> subIndex = 0
        }
    }
    return -1
}
