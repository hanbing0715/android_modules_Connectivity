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

package com.android.server.connectivity;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.net.ConnectivityManager.TYPE_MOBILE;
import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;

import static com.android.testutils.HandlerUtils.visibleOnHandlerThread;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.longThat;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.ignoreStubs;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import android.app.AlarmManager;
import android.content.Context;
import android.content.res.Resources;
import android.net.INetd;
import android.net.ISocketKeepaliveCallback;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.MarkMaskParcel;
import android.net.NattKeepalivePacketData;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.SocketKeepalive;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.connectivity.resources.R;
import com.android.server.connectivity.AutomaticOnOffKeepaliveTracker.AutomaticOnOffKeepalive;
import com.android.server.connectivity.KeepaliveTracker.KeepaliveInfo;
import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRunner;
import com.android.testutils.HandlerUtils;

import libcore.util.HexEncoding;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.FileDescriptor;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

@RunWith(DevSdkIgnoreRunner.class)
@SmallTest
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.TIRAMISU)
public class AutomaticOnOffKeepaliveTrackerTest {
    private static final String TAG = AutomaticOnOffKeepaliveTrackerTest.class.getSimpleName();
    private static final int TEST_SLOT = 1;
    private static final int TEST_NETID = 0xA85;
    private static final int TEST_NETID_FWMARK = 0x0A85;
    private static final int OTHER_NETID = 0x1A85;
    private static final int NETID_MASK = 0xffff;
    private static final int TIMEOUT_MS = 30_000;
    private static final int MOCK_RESOURCE_ID = 5;
    private static final int TEST_KEEPALIVE_INTERVAL_SEC = 10;
    private static final int TEST_KEEPALIVE_INVALID_INTERVAL_SEC = 9;

    private AutomaticOnOffKeepaliveTracker mAOOKeepaliveTracker;
    private HandlerThread mHandlerThread;

    @Mock INetd mNetd;
    @Mock AutomaticOnOffKeepaliveTracker.Dependencies mDependencies;
    @Mock Context mCtx;
    @Mock AlarmManager mAlarmManager;
    @Mock NetworkAgentInfo mNai;

    TestKeepaliveTracker mKeepaliveTracker;
    AOOTestHandler mTestHandler;

    // Hexadecimal representation of a SOCK_DIAG response with tcp info.
    private static final String SOCK_DIAG_TCP_INET_HEX =
            // struct nlmsghdr.
            "14010000" +        // length = 276
            "1400" +            // type = SOCK_DIAG_BY_FAMILY
            "0301" +            // flags = NLM_F_REQUEST | NLM_F_DUMP
            "00000000" +        // seqno
            "00000000" +        // pid (0 == kernel)
            // struct inet_diag_req_v2
            "02" +              // family = AF_INET
            "06" +              // state
            "00" +              // timer
            "00" +              // retrans
            // inet_diag_sockid
            "DEA5" +            // idiag_sport = 42462
            "71B9" +            // idiag_dport = 47473
            "0a006402000000000000000000000000" + // idiag_src = 10.0.100.2
            "08080808000000000000000000000000" + // idiag_dst = 8.8.8.8
            "00000000" +            // idiag_if
            "34ED000076270000" +    // idiag_cookie = 43387759684916
            "00000000" +            // idiag_expires
            "00000000" +            // idiag_rqueue
            "00000000" +            // idiag_wqueue
            "00000000" +            // idiag_uid
            "00000000" +            // idiag_inode
            // rtattr
            "0500" +            // len = 5
            "0800" +            // type = 8
            "00000000" +        // data
            "0800" +            // len = 8
            "0F00" +            // type = 15(INET_DIAG_MARK)
            "850A0C00" +        // data, socket mark=789125
            "AC00" +            // len = 172
            "0200" +            // type = 2(INET_DIAG_INFO)
            // tcp_info
            "01" +              // state = TCP_ESTABLISHED
            "00" +              // ca_state = TCP_CA_OPEN
            "05" +              // retransmits = 5
            "00" +              // probes = 0
            "00" +              // backoff = 0
            "07" +              // option = TCPI_OPT_WSCALE|TCPI_OPT_SACK|TCPI_OPT_TIMESTAMPS
            "88" +              // wscale = 8
            "00" +              // delivery_rate_app_limited = 0
            "4A911B00" +        // rto = 1806666
            "00000000" +        // ato = 0
            "2E050000" +        // sndMss = 1326
            "18020000" +        // rcvMss = 536
            "00000000" +        // unsacked = 0
            "00000000" +        // acked = 0
            "00000000" +        // lost = 0
            "00000000" +        // retrans = 0
            "00000000" +        // fackets = 0
            "BB000000" +        // lastDataSent = 187
            "00000000" +        // lastAckSent = 0
            "BB000000" +        // lastDataRecv = 187
            "BB000000" +        // lastDataAckRecv = 187
            "DC050000" +        // pmtu = 1500
            "30560100" +        // rcvSsthresh = 87600
            "3E2C0900" +        // rttt = 601150
            "1F960400" +        // rttvar = 300575
            "78050000" +        // sndSsthresh = 1400
            "0A000000" +        // sndCwnd = 10
            "A8050000" +        // advmss = 1448
            "03000000" +        // reordering = 3
            "00000000" +        // rcvrtt = 0
            "30560100" +        // rcvspace = 87600
            "00000000" +        // totalRetrans = 0
            "53AC000000000000" +    // pacingRate = 44115
            "FFFFFFFFFFFFFFFF" +    // maxPacingRate = 18446744073709551615
            "0100000000000000" +    // bytesAcked = 1
            "0000000000000000" +    // bytesReceived = 0
            "0A000000" +        // SegsOut = 10
            "00000000" +        // SegsIn = 0
            "00000000" +        // NotSentBytes = 0
            "3E2C0900" +        // minRtt = 601150
            "00000000" +        // DataSegsIn = 0
            "00000000" +        // DataSegsOut = 0
            "0000000000000000"; // deliverRate = 0
    private static final String SOCK_DIAG_NO_TCP_INET_HEX =
            // struct nlmsghdr
            "14000000"     // length = 20
            + "0300"         // type = NLMSG_DONE
            + "0301"         // flags = NLM_F_REQUEST | NLM_F_DUMP
            + "00000000"     // seqno
            + "00000000"     // pid (0 == kernel)
            // struct inet_diag_req_v2
            + "02"           // family = AF_INET
            + "06"           // state
            + "00"           // timer
            + "00";          // retrans
    private static final byte[] SOCK_DIAG_NO_TCP_INET_BYTES =
            HexEncoding.decode(SOCK_DIAG_NO_TCP_INET_HEX.toCharArray(), false);
    private static final String TEST_RESPONSE_HEX =
            SOCK_DIAG_TCP_INET_HEX + SOCK_DIAG_NO_TCP_INET_HEX;
    private static final byte[] TEST_RESPONSE_BYTES =
            HexEncoding.decode(TEST_RESPONSE_HEX.toCharArray(), false);

    private static class TestKeepaliveInfo {
        private static List<Socket> sOpenSockets = new ArrayList<>();

        public static void closeAllSockets() throws Exception {
            for (final Socket socket : sOpenSockets) {
                socket.close();
            }
            sOpenSockets.clear();
        }

        public final Socket socket;
        public final Binder binder;
        public final FileDescriptor fd;
        public final ISocketKeepaliveCallback socketKeepaliveCallback;
        public final Network underpinnedNetwork;
        public final NattKeepalivePacketData kpd;

        TestKeepaliveInfo(NattKeepalivePacketData kpd) throws Exception {
            this.kpd = kpd;
            socket = new Socket();
            socket.bind(null);
            sOpenSockets.add(socket);
            fd = socket.getFileDescriptor$();

            binder = new Binder();
            socketKeepaliveCallback = mock(ISocketKeepaliveCallback.class);
            doReturn(binder).when(socketKeepaliveCallback).asBinder();
            underpinnedNetwork = mock(Network.class);
        }
    }

    private class TestKeepaliveTracker extends KeepaliveTracker {
        private KeepaliveInfo mKi;

        TestKeepaliveTracker(@NonNull final Context context, @NonNull final Handler handler) {
            super(context, handler);
        }

        public void setReturnedKeepaliveInfo(@NonNull final KeepaliveInfo ki) {
            mKi = ki;
        }

        @NonNull
        @Override
        public KeepaliveInfo makeNattKeepaliveInfo(@Nullable final NetworkAgentInfo nai,
                @Nullable final FileDescriptor fd, final int intervalSeconds,
                @NonNull final ISocketKeepaliveCallback cb, @NonNull final String srcAddrString,
                final int srcPort,
                @NonNull final String dstAddrString, final int dstPort) {
            if (null == mKi) {
                throw new IllegalStateException("Must call setReturnedKeepaliveInfo");
            }
            return mKi;
        }
    }

    @Before
    public void setup() throws Exception {
        MockitoAnnotations.initMocks(this);

        mNai.networkCapabilities =
                new NetworkCapabilities.Builder().addTransportType(TRANSPORT_CELLULAR).build();
        mNai.networkInfo = new NetworkInfo(TYPE_MOBILE, 0 /* subtype */, "LTE", "LTE");
        mNai.networkInfo.setDetailedState(
                NetworkInfo.DetailedState.CONNECTED, "test reason", "test extra info");
        doReturn(new Network(TEST_NETID)).when(mNai).network();
        mNai.linkProperties = new LinkProperties();

        doReturn(PERMISSION_GRANTED).when(mCtx).checkPermission(any() /* permission */,
                anyInt() /* pid */, anyInt() /* uid */);
        ConnectivityResources.setResourcesContextForTest(mCtx);
        final Resources mockResources = mock(Resources.class);
        doReturn(new String[] { "0,3", "3,3" }).when(mockResources)
                .getStringArray(R.array.config_networkSupportedKeepaliveCount);
        doReturn(mockResources).when(mCtx).getResources();
        doReturn(mNetd).when(mDependencies).getNetd();
        doReturn(mAlarmManager).when(mDependencies).getAlarmManager(any());
        doReturn(makeMarkMaskParcel(NETID_MASK, TEST_NETID_FWMARK)).when(mNetd)
                .getFwmarkForNetwork(TEST_NETID);

        doNothing().when(mDependencies).sendRequest(any(), any());

        mHandlerThread = new HandlerThread("KeepaliveTrackerTest");
        mHandlerThread.start();
        mTestHandler = new AOOTestHandler(mHandlerThread.getLooper());
        mKeepaliveTracker = new TestKeepaliveTracker(mCtx, mTestHandler);
        doReturn(mKeepaliveTracker).when(mDependencies).newKeepaliveTracker(mCtx, mTestHandler);
        doReturn(true).when(mDependencies).isFeatureEnabled(any(), anyBoolean());
        mAOOKeepaliveTracker =
                new AutomaticOnOffKeepaliveTracker(mCtx, mTestHandler, mDependencies);
    }

    @After
    public void teardown() throws Exception {
        TestKeepaliveInfo.closeAllSockets();
    }

    private final class AOOTestHandler extends Handler {
        public AutomaticOnOffKeepaliveTracker.AutomaticOnOffKeepalive mLastAutoKi = null;

        AOOTestHandler(@NonNull final Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(@NonNull final Message msg) {
            switch (msg.what) {
                case AutomaticOnOffKeepaliveTracker.CMD_REQUEST_START_KEEPALIVE:
                    Log.d(TAG, "Test handler received CMD_REQUEST_START_KEEPALIVE : " + msg);
                    mAOOKeepaliveTracker.handleStartKeepalive(msg);
                    break;
                case AutomaticOnOffKeepaliveTracker.CMD_MONITOR_AUTOMATIC_KEEPALIVE:
                    Log.d(TAG, "Test handler received CMD_MONITOR_AUTOMATIC_KEEPALIVE : " + msg);
                    mLastAutoKi = mAOOKeepaliveTracker.getKeepaliveForBinder((IBinder) msg.obj);
                    break;
            }
        }
    }

    @Test
    public void testIsAnyTcpSocketConnected_runOnNonHandlerThread() throws Exception {
        setupResponseWithSocketExisting();
        assertThrows(IllegalStateException.class,
                () -> mAOOKeepaliveTracker.isAnyTcpSocketConnected(TEST_NETID));
    }

    @Test
    public void testIsAnyTcpSocketConnected_withTargetNetId() throws Exception {
        setupResponseWithSocketExisting();
        mTestHandler.post(
                () -> assertTrue(mAOOKeepaliveTracker.isAnyTcpSocketConnected(TEST_NETID)));
    }

    @Test
    public void testIsAnyTcpSocketConnected_withIncorrectNetId() throws Exception {
        setupResponseWithSocketExisting();
        mTestHandler.post(
                () -> assertFalse(mAOOKeepaliveTracker.isAnyTcpSocketConnected(OTHER_NETID)));
    }

    @Test
    public void testIsAnyTcpSocketConnected_noSocketExists() throws Exception {
        setupResponseWithoutSocketExisting();
        mTestHandler.post(
                () -> assertFalse(mAOOKeepaliveTracker.isAnyTcpSocketConnected(TEST_NETID)));
    }

    private void triggerEventKeepalive(int slot, int reason) {
        visibleOnHandlerThread(
                mTestHandler,
                () -> mAOOKeepaliveTracker.handleEventSocketKeepalive(mNai, slot, reason));
    }

    private TestKeepaliveInfo doStartNattKeepalive(int intervalSeconds) throws Exception {
        final InetAddress srcAddress = InetAddress.getByAddress(
                new byte[] { (byte) 192, 0, 0, (byte) 129 });
        final int srcPort = 12345;
        final InetAddress dstAddress = InetAddress.getByAddress(new byte[] {8, 8, 8, 8});
        final int dstPort = 12345;

        mNai.linkProperties.addLinkAddress(new LinkAddress(srcAddress, 24));

        final NattKeepalivePacketData kpd = new NattKeepalivePacketData(srcAddress, srcPort,
                dstAddress, dstPort, new byte[] {1});

        final TestKeepaliveInfo testInfo = new TestKeepaliveInfo(kpd);

        final KeepaliveInfo ki = mKeepaliveTracker.new KeepaliveInfo(
                testInfo.socketKeepaliveCallback, mNai, kpd, intervalSeconds,
                KeepaliveInfo.TYPE_NATT, testInfo.fd);
        mKeepaliveTracker.setReturnedKeepaliveInfo(ki);

        mAOOKeepaliveTracker.startNattKeepalive(mNai, testInfo.fd, intervalSeconds,
                testInfo.socketKeepaliveCallback, srcAddress.toString(), srcPort,
                dstAddress.toString(), dstPort, true /* automaticOnOffKeepalives */,
                testInfo.underpinnedNetwork);
        HandlerUtils.waitForIdle(mTestHandler, TIMEOUT_MS);

        return testInfo;
    }

    private TestKeepaliveInfo doStartNattKeepalive() throws Exception {
        return doStartNattKeepalive(TEST_KEEPALIVE_INTERVAL_SEC);
    }

    @Test
    public void testAlarm() throws Exception {
        // Mock elapsed real time to verify the alarm timer.
        final long time = SystemClock.elapsedRealtime();
        doReturn(time).when(mDependencies).getElapsedRealtime();
        final TestKeepaliveInfo testInfo = doStartNattKeepalive();

        final ArgumentCaptor<AlarmManager.OnAlarmListener> listenerCaptor =
                ArgumentCaptor.forClass(AlarmManager.OnAlarmListener.class);
        // The alarm timer should be smaller than the keepalive delay. Verify the alarm trigger time
        // is higher than base time but smaller than the keepalive delay.
        verify(mAlarmManager).setExact(eq(AlarmManager.ELAPSED_REALTIME),
                longThat(t -> t > time + 1000L && t < time + TEST_KEEPALIVE_INTERVAL_SEC * 1000L),
                any() /* tag */, listenerCaptor.capture(), eq(mTestHandler));
        final AlarmManager.OnAlarmListener listener = listenerCaptor.getValue();

        // For realism, the listener should be posted on the handler
        mTestHandler.post(() -> listener.onAlarm());
        // Wait for the listener to be called. The listener enqueues a message to the handler.
        HandlerUtils.waitForIdle(mTestHandler, TIMEOUT_MS);
        // Wait for the message posted by the listener to be processed.
        HandlerUtils.waitForIdle(mTestHandler, TIMEOUT_MS);

        assertNotNull(mTestHandler.mLastAutoKi);
        assertEquals(testInfo.socketKeepaliveCallback, mTestHandler.mLastAutoKi.getCallback());
        assertEquals(testInfo.underpinnedNetwork, mTestHandler.mLastAutoKi.getUnderpinnedNetwork());
    }

    private void setupResponseWithSocketExisting() throws Exception {
        final ByteBuffer tcpBufferV6 = getByteBuffer(TEST_RESPONSE_BYTES);
        final ByteBuffer tcpBufferV4 = getByteBuffer(TEST_RESPONSE_BYTES);
        doReturn(tcpBufferV6, tcpBufferV4).when(mDependencies).recvSockDiagResponse(any());
    }

    private void setupResponseWithoutSocketExisting() throws Exception {
        final ByteBuffer tcpBufferV6 = getByteBuffer(SOCK_DIAG_NO_TCP_INET_BYTES);
        final ByteBuffer tcpBufferV4 = getByteBuffer(SOCK_DIAG_NO_TCP_INET_BYTES);
        doReturn(tcpBufferV6, tcpBufferV4).when(mDependencies).recvSockDiagResponse(any());
    }

    private MarkMaskParcel makeMarkMaskParcel(final int mask, final int mark) {
        final MarkMaskParcel parcel = new MarkMaskParcel();
        parcel.mask = mask;
        parcel.mark = mark;
        return parcel;
    }

    private ByteBuffer getByteBuffer(final byte[] bytes) {
        final ByteBuffer buffer = ByteBuffer.wrap(bytes);
        buffer.order(ByteOrder.nativeOrder());
        return buffer;
    }

    private AutomaticOnOffKeepalive getAutoKiForBinder(IBinder binder) {
        return visibleOnHandlerThread(
                mTestHandler, () -> mAOOKeepaliveTracker.getKeepaliveForBinder(binder));
    }

    private void checkAndProcessKeepaliveStart(final NattKeepalivePacketData kpd) throws Exception {
        verify(mNai).onStartNattSocketKeepalive(TEST_SLOT, TEST_KEEPALIVE_INTERVAL_SEC, kpd);
        verify(mNai).onAddNattKeepalivePacketFilter(TEST_SLOT, kpd);
        // Network agent started the keepalive successfully.
        triggerEventKeepalive(TEST_SLOT, SocketKeepalive.SUCCESS);
    }

    private void checkAndProcessKeepaliveStop() throws Exception {
        verify(mNai).onStopSocketKeepalive(TEST_SLOT);
        verify(mNai).onRemoveKeepalivePacketFilter(TEST_SLOT);
        // Network agent stops the keepalive successfully.
        triggerEventKeepalive(TEST_SLOT, SocketKeepalive.SUCCESS);
    }

    @Test
    public void testStartNattKeepalive_valid() throws Exception {
        final TestKeepaliveInfo testInfo = doStartNattKeepalive();

        checkAndProcessKeepaliveStart(testInfo.kpd);

        final AutomaticOnOffKeepalive autoKi = getAutoKiForBinder(testInfo.binder);
        assertNotNull(autoKi);
        assertEquals(testInfo.socketKeepaliveCallback, autoKi.getCallback());

        verify(testInfo.socketKeepaliveCallback).onStarted();
        verifyNoMoreInteractions(ignoreStubs(testInfo.socketKeepaliveCallback));
    }

    @Test
    public void testStartNattKeepalive_invalidInterval() throws Exception {
        final TestKeepaliveInfo testInfo =
                doStartNattKeepalive(TEST_KEEPALIVE_INVALID_INTERVAL_SEC);

        assertNull(getAutoKiForBinder(testInfo.binder));

        verify(testInfo.socketKeepaliveCallback).onError(SocketKeepalive.ERROR_INVALID_INTERVAL);
        verifyNoMoreInteractions(ignoreStubs(testInfo.socketKeepaliveCallback));
    }

    @Test
    public void testHandleEventSocketKeepalive_startingFailureHardwareError() throws Exception {
        final TestKeepaliveInfo testInfo = doStartNattKeepalive();

        verify(mNai)
                .onStartNattSocketKeepalive(TEST_SLOT, TEST_KEEPALIVE_INTERVAL_SEC, testInfo.kpd);
        verify(mNai).onAddNattKeepalivePacketFilter(TEST_SLOT, testInfo.kpd);
        // Network agent returns an error, fails to start the keepalive.
        triggerEventKeepalive(TEST_SLOT, SocketKeepalive.ERROR_HARDWARE_ERROR);

        checkAndProcessKeepaliveStop();

        assertNull(getAutoKiForBinder(testInfo.binder));

        verify(testInfo.socketKeepaliveCallback).onError(SocketKeepalive.ERROR_HARDWARE_ERROR);
        verifyNoMoreInteractions(ignoreStubs(testInfo.socketKeepaliveCallback));
    }

    @Test
    public void testHandleCheckKeepalivesStillValid_linkPropertiesChanged() throws Exception {
        // Successful start of NATT keepalive.
        final TestKeepaliveInfo testInfo = doStartNattKeepalive();
        checkAndProcessKeepaliveStart(testInfo.kpd);
        verify(testInfo.socketKeepaliveCallback).onStarted();

        // Source address is removed from link properties by clearing.
        mNai.linkProperties.clear();

        // Check for valid keepalives
        visibleOnHandlerThread(
                mTestHandler, () -> mAOOKeepaliveTracker.handleCheckKeepalivesStillValid(mNai));
        HandlerUtils.waitForIdle(mTestHandler, TIMEOUT_MS);

        checkAndProcessKeepaliveStop();

        assertNull(getAutoKiForBinder(testInfo.binder));

        verify(testInfo.socketKeepaliveCallback).onError(SocketKeepalive.ERROR_INVALID_IP_ADDRESS);
        verifyNoMoreInteractions(ignoreStubs(testInfo.socketKeepaliveCallback));
    }
}
