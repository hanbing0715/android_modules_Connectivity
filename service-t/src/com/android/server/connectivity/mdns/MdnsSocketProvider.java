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

package com.android.server.connectivity.mdns;

import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;
import static android.net.NetworkCapabilities.TRANSPORT_VPN;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.TetheringManager;
import android.net.TetheringManager.TetheringEventCallback;
import android.os.Handler;
import android.os.Looper;
import android.util.ArrayMap;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.net.module.util.CollectionUtils;
import com.android.net.module.util.LinkPropertiesUtils.CompareResult;
import com.android.net.module.util.SharedLog;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@link MdnsSocketProvider} manages the multiple sockets for mDns.
 *
 * <p>This class is not thread safe, it is intended to be used only from the looper thread.
 * However, the constructor is an exception, as it is called on another thread;
 * therefore for thread safety all members of this class MUST either be final or initialized
 * to their default value (0, false or null).
 *
 */
public class MdnsSocketProvider {
    private static final String TAG = MdnsSocketProvider.class.getSimpleName();
    private static final boolean DBG = MdnsDiscoveryManager.DBG;
    // This buffer size matches what MdnsSocketClient uses currently.
    // But 1440 should generally be enough because of standard Ethernet.
    // Note: mdnsresponder mDNSEmbeddedAPI.h uses 8940 for Ethernet jumbo frames.
    private static final int READ_BUFFER_SIZE = 2048;
    private static final SharedLog LOGGER = new SharedLog(TAG);
    private static final int IFACE_IDX_NOT_EXIST = -1;
    @NonNull private final Context mContext;
    @NonNull private final Looper mLooper;
    @NonNull private final Handler mHandler;
    @NonNull private final Dependencies mDependencies;
    @NonNull private final NetworkCallback mNetworkCallback;
    @NonNull private final TetheringEventCallback mTetheringEventCallback;
    @NonNull private final AbstractSocketNetlink mSocketNetlinkMonitor;
    private final ArrayMap<Network, SocketInfo> mNetworkSockets = new ArrayMap<>();
    private final ArrayMap<String, SocketInfo> mTetherInterfaceSockets = new ArrayMap<>();
    private final ArrayMap<Network, LinkProperties> mActiveNetworksLinkProperties =
            new ArrayMap<>();
    private final ArrayMap<Network, int[]> mActiveNetworksTransports = new ArrayMap<>();
    private final ArrayMap<SocketCallback, Network> mCallbacksToRequestedNetworks =
            new ArrayMap<>();
    private final List<String> mLocalOnlyInterfaces = new ArrayList<>();
    private final List<String> mTetheredInterfaces = new ArrayList<>();
    // mIfaceIdxToLinkProperties should not be cleared in maybeStopMonitoringSockets() because
    // the netlink monitor is never stop and the old states must be kept.
    private final SparseArray<LinkProperties> mIfaceIdxToLinkProperties = new SparseArray<>();
    private final byte[] mPacketReadBuffer = new byte[READ_BUFFER_SIZE];
    private boolean mMonitoringSockets = false;
    private boolean mRequestStop = false;

    public MdnsSocketProvider(@NonNull Context context, @NonNull Looper looper) {
        this(context, looper, new Dependencies());
    }

    MdnsSocketProvider(@NonNull Context context, @NonNull Looper looper,
            @NonNull Dependencies deps) {
        mContext = context;
        mLooper = looper;
        mHandler = new Handler(looper);
        mDependencies = deps;
        mNetworkCallback = new NetworkCallback() {
            @Override
            public void onLost(Network network) {
                mActiveNetworksLinkProperties.remove(network);
                mActiveNetworksTransports.remove(network);
                removeNetworkSocket(network);
            }

            @Override
            public void onCapabilitiesChanged(@NonNull Network network,
                    @NonNull NetworkCapabilities networkCapabilities) {
                mActiveNetworksTransports.put(network, networkCapabilities.getTransportTypes());
            }

            @Override
            public void onLinkPropertiesChanged(Network network, LinkProperties lp) {
                handleLinkPropertiesChanged(network, lp);
            }
        };
        mTetheringEventCallback = new TetheringEventCallback() {
            @Override
            public void onLocalOnlyInterfacesChanged(@NonNull List<String> interfaces) {
                handleTetherInterfacesChanged(mLocalOnlyInterfaces, interfaces);
            }

            @Override
            public void onTetheredInterfacesChanged(@NonNull List<String> interfaces) {
                handleTetherInterfacesChanged(mTetheredInterfaces, interfaces);
            }
        };

        mSocketNetlinkMonitor = mDependencies.createSocketNetlinkMonitor(mHandler, LOGGER,
                new NetLinkMessageProcessor());
    }

    /**
     * Dependencies of MdnsSocketProvider, for injection in tests.
     */
    @VisibleForTesting
    public static class Dependencies {
        /*** Get network interface by given interface name */
        public NetworkInterfaceWrapper getNetworkInterfaceByName(@NonNull String interfaceName)
                throws SocketException {
            final NetworkInterface ni = NetworkInterface.getByName(interfaceName);
            return ni == null ? null : new NetworkInterfaceWrapper(ni);
        }

        /*** Create a MdnsInterfaceSocket */
        public MdnsInterfaceSocket createMdnsInterfaceSocket(
                @NonNull NetworkInterface networkInterface, int port, @NonNull Looper looper,
                @NonNull byte[] packetReadBuffer) throws IOException {
            return new MdnsInterfaceSocket(networkInterface, port, looper, packetReadBuffer);
        }

        /*** Get network interface by given interface name */
        public int getNetworkInterfaceIndexByName(@NonNull final String ifaceName) {
            final NetworkInterface iface;
            try {
                iface = NetworkInterface.getByName(ifaceName);
            } catch (SocketException e) {
                Log.e(TAG, "Error querying interface", e);
                return IFACE_IDX_NOT_EXIST;
            }
            if (iface == null) {
                Log.e(TAG, "Interface not found: " + ifaceName);
                return IFACE_IDX_NOT_EXIST;
            }
            return iface.getIndex();
        }
        /*** Creates a SocketNetlinkMonitor */
        public AbstractSocketNetlink createSocketNetlinkMonitor(@NonNull final Handler handler,
                @NonNull final SharedLog log,
                @NonNull final NetLinkMonitorCallBack cb) {
            return SocketNetLinkMonitorFactory.createNetLinkMonitor(handler, log, cb);
        }
    }
    /**
     * The callback interface for the netlink monitor messages.
     */
    public interface NetLinkMonitorCallBack {
        /**
         * Handles the interface address add or update.
         */
        void addOrUpdateInterfaceAddress(int ifaceIdx, @NonNull LinkAddress newAddress);


        /**
         * Handles the interface address delete.
         */
        void deleteInterfaceAddress(int ifaceIdx, @NonNull LinkAddress deleteAddress);
    }
    private class NetLinkMessageProcessor implements NetLinkMonitorCallBack {

        @Override
        public void addOrUpdateInterfaceAddress(int ifaceIdx,
                @NonNull final LinkAddress newAddress) {

            LinkProperties linkProperties;
            linkProperties = mIfaceIdxToLinkProperties.get(ifaceIdx);
            if (linkProperties == null) {
                linkProperties = new LinkProperties();
                mIfaceIdxToLinkProperties.put(ifaceIdx, linkProperties);
            }
            boolean updated = linkProperties.addLinkAddress(newAddress);

            if (!updated) {
                return;
            }
            maybeUpdateTetheringSocketAddress(ifaceIdx, linkProperties.getLinkAddresses());
        }

        @Override
        public void deleteInterfaceAddress(int ifaceIdx, @NonNull LinkAddress deleteAddress) {
            LinkProperties linkProperties;
            boolean updated = false;
            linkProperties = mIfaceIdxToLinkProperties.get(ifaceIdx);
            if (linkProperties != null) {
                updated = linkProperties.removeLinkAddress(deleteAddress);
                if (linkProperties.getLinkAddresses().isEmpty()) {
                    mIfaceIdxToLinkProperties.remove(ifaceIdx);
                }
            }

            if (linkProperties == null || !updated) {
                return;
            }
            maybeUpdateTetheringSocketAddress(ifaceIdx, linkProperties.getLinkAddresses());

        }
    }
    /*** Data class for storing socket related info  */
    private static class SocketInfo {
        final MdnsInterfaceSocket mSocket;
        final List<LinkAddress> mAddresses;

        SocketInfo(MdnsInterfaceSocket socket, List<LinkAddress> addresses) {
            mSocket = socket;
            mAddresses = new ArrayList<>(addresses);
        }
    }

    /*** Ensure that current running thread is same as given handler thread */
    public static void ensureRunningOnHandlerThread(Handler handler) {
        if (handler.getLooper().getThread() != Thread.currentThread()) {
            throw new IllegalStateException(
                    "Not running on Handler thread: " + Thread.currentThread().getName());
        }
    }

    /*** Start monitoring sockets by listening callbacks for sockets creation or removal */
    public void startMonitoringSockets() {
        ensureRunningOnHandlerThread(mHandler);
        mRequestStop = false; // Reset stop request flag.
        if (mMonitoringSockets) {
            Log.d(TAG, "Already monitoring sockets.");
            return;
        }
        LOGGER.i("Start monitoring sockets.");
        mContext.getSystemService(ConnectivityManager.class).registerNetworkCallback(
                new NetworkRequest.Builder().clearCapabilities().build(),
                mNetworkCallback, mHandler);

        final TetheringManager tetheringManager = mContext.getSystemService(TetheringManager.class);
        tetheringManager.registerTetheringEventCallback(mHandler::post, mTetheringEventCallback);

        if (mSocketNetlinkMonitor.isSupported()) {
            mHandler.post(mSocketNetlinkMonitor::startMonitoring);
        }
        mMonitoringSockets = true;
    }
    /**
     * Start netlink monitor.
     */
    public void startNetLinkMonitor() {
        ensureRunningOnHandlerThread(mHandler);
        if (mSocketNetlinkMonitor.isSupported()) {
            mSocketNetlinkMonitor.startMonitoring();
        }
    }

    private void maybeStopMonitoringSockets() {
        if (!mMonitoringSockets) return; // Already unregistered.
        if (!mRequestStop) return; // No stop request.

        // Only unregister the network callback if there is no socket request.
        if (mCallbacksToRequestedNetworks.isEmpty()) {
            LOGGER.i("Stop monitoring sockets.");
            mContext.getSystemService(ConnectivityManager.class)
                    .unregisterNetworkCallback(mNetworkCallback);

            final TetheringManager tetheringManager = mContext.getSystemService(
                    TetheringManager.class);
            tetheringManager.unregisterTetheringEventCallback(mTetheringEventCallback);
            // Clear all saved status.
            mActiveNetworksLinkProperties.clear();
            mNetworkSockets.clear();
            mTetherInterfaceSockets.clear();
            mLocalOnlyInterfaces.clear();
            mTetheredInterfaces.clear();
            mMonitoringSockets = false;
        }
        // The netlink monitor is not stopped here because the MdnsSocketProvider need to listen
        // to all the netlink updates when the system is up and running.
    }

    /*** Request to stop monitoring sockets and unregister callbacks */
    public void requestStopWhenInactive() {
        ensureRunningOnHandlerThread(mHandler);
        if (!mMonitoringSockets) {
            Log.d(TAG, "Monitoring sockets hasn't been started.");
            return;
        }
        mRequestStop = true;
        maybeStopMonitoringSockets();
    }

    /*** Check whether the target network is matched current network */
    public static boolean isNetworkMatched(@Nullable Network targetNetwork,
            @Nullable Network currentNetwork) {
        return targetNetwork == null || targetNetwork.equals(currentNetwork);
    }

    private boolean matchRequestedNetwork(Network network) {
        return hasAllNetworksRequest()
                || mCallbacksToRequestedNetworks.containsValue(network);
    }

    private boolean hasAllNetworksRequest() {
        return mCallbacksToRequestedNetworks.containsValue(null);
    }

    private void handleLinkPropertiesChanged(Network network, LinkProperties lp) {
        mActiveNetworksLinkProperties.put(network, lp);
        if (!matchRequestedNetwork(network)) {
            if (DBG) {
                Log.d(TAG, "Ignore LinkProperties change. There is no request for the"
                        + " Network:" + network);
            }
            return;
        }

        final NetworkAsKey networkKey = new NetworkAsKey(network);
        final SocketInfo socketInfo = mNetworkSockets.get(network);
        if (socketInfo == null) {
            createSocket(networkKey, lp);
        } else {
            updateSocketInfoAddress(network, socketInfo, lp.getLinkAddresses());
        }
    }
    private void maybeUpdateTetheringSocketAddress(int ifaceIndex,
            @NonNull final List<LinkAddress> updatedAddresses) {
        for (int i = 0; i < mTetherInterfaceSockets.size(); ++i) {
            String tetheringInterfaceName = mTetherInterfaceSockets.keyAt(i);
            if (mDependencies.getNetworkInterfaceIndexByName(tetheringInterfaceName)
                    == ifaceIndex) {
                updateSocketInfoAddress(null /* network */,
                        mTetherInterfaceSockets.valueAt(i), updatedAddresses);
                return;
            }
        }
    }

    private void updateSocketInfoAddress(@Nullable final Network network,
            @NonNull final SocketInfo socketInfo,
            @NonNull final List<LinkAddress> addresses) {
        // Update the addresses of this socket.
        socketInfo.mAddresses.clear();
        socketInfo.mAddresses.addAll(addresses);
        // Try to join the group again.
        socketInfo.mSocket.joinGroup(addresses);

        notifyAddressesChanged(network, socketInfo.mSocket, addresses);
    }
    private LinkProperties createLPForTetheredInterface(@NonNull final String interfaceName,
            int ifaceIndex) {
        final LinkProperties linkProperties =
                new LinkProperties(mIfaceIdxToLinkProperties.get(ifaceIndex));
        linkProperties.setInterfaceName(interfaceName);
        return linkProperties;
    }

    private void handleTetherInterfacesChanged(List<String> current, List<String> updated) {
        if (!hasAllNetworksRequest()) {
            // Currently, the network for tethering can not be requested, so the sockets for
            // tethering are only created if there is a request for all networks (interfaces).
            // Therefore, this change can skip if there is no such request.
            if (DBG) {
                Log.d(TAG, "Ignore tether interfaces change. There is no request for all"
                        + " networks.");
            }
            return;
        }

        final CompareResult<String> interfaceDiff = new CompareResult<>(
                current, updated);
        for (String name : interfaceDiff.added) {
            int ifaceIndex = mDependencies.getNetworkInterfaceIndexByName(name);
            createSocket(LOCAL_NET, createLPForTetheredInterface(name, ifaceIndex));
        }
        for (String name : interfaceDiff.removed) {
            removeTetherInterfaceSocket(name);
        }
        current.clear();
        current.addAll(updated);
    }

    private void createSocket(NetworkKey networkKey, LinkProperties lp) {
        final String interfaceName = lp.getInterfaceName();
        if (interfaceName == null) {
            Log.e(TAG, "Can not create socket with null interface name.");
            return;
        }

        try {
            final NetworkInterfaceWrapper networkInterface =
                    mDependencies.getNetworkInterfaceByName(interfaceName);
            // There are no transports for tethered interfaces. Other interfaces should always
            // have transports since LinkProperties updates are always sent after
            // NetworkCapabilities updates.
            final int[] transports;
            if (networkKey == LOCAL_NET) {
                transports = new int[0];
            } else {
                transports = mActiveNetworksTransports.getOrDefault(
                        ((NetworkAsKey) networkKey).mNetwork, new int[0]);
            }
            if (networkInterface == null || !isMdnsCapableInterface(networkInterface, transports)) {
                return;
            }

            LOGGER.log("Create socket on net:" + networkKey + ", ifName:" + interfaceName);
            final MdnsInterfaceSocket socket = mDependencies.createMdnsInterfaceSocket(
                    networkInterface.getNetworkInterface(), MdnsConstants.MDNS_PORT, mLooper,
                    mPacketReadBuffer);
            final List<LinkAddress> addresses = lp.getLinkAddresses();
            if (networkKey == LOCAL_NET) {
                mTetherInterfaceSockets.put(interfaceName, new SocketInfo(socket, addresses));
            } else {
                mNetworkSockets.put(((NetworkAsKey) networkKey).mNetwork,
                        new SocketInfo(socket, addresses));
            }
            // Try to join IPv4/IPv6 group.
            socket.joinGroup(addresses);

            // Notify the listeners which need this socket.
            if (networkKey == LOCAL_NET) {
                notifySocketCreated(null /* network */, socket, addresses);
            } else {
                notifySocketCreated(((NetworkAsKey) networkKey).mNetwork, socket, addresses);
            }
        } catch (IOException e) {
            LOGGER.e("Create socket failed ifName:" + interfaceName, e);
        }
    }

    private boolean isMdnsCapableInterface(
            @NonNull NetworkInterfaceWrapper iface, @NonNull int[] transports) {
        try {
            // Never try mDNS on cellular, or on interfaces with incompatible flags
            if (CollectionUtils.contains(transports, TRANSPORT_CELLULAR)
                    || iface.isLoopback()
                    || iface.isPointToPoint()
                    || iface.isVirtual()
                    || !iface.isUp()) {
                return false;
            }

            // Otherwise, always try mDNS on non-VPN Wifi.
            if (!CollectionUtils.contains(transports, TRANSPORT_VPN)
                    && CollectionUtils.contains(transports, TRANSPORT_WIFI)) {
                return true;
            }

            // For other transports, or no transports (tethering downstreams), do mDNS based on the
            // interface flags. This is not always reliable (for example some Wifi interfaces may
            // not have the MULTICAST flag even though they can do mDNS, and some cellular
            // interfaces may have the BROADCAST or MULTICAST flags), so checks are done based on
            // transports above in priority.
            return iface.supportsMulticast();
        } catch (SocketException e) {
            LOGGER.e("Error checking interface flags", e);
            return false;
        }
    }

    private void removeNetworkSocket(Network network) {
        final SocketInfo socketInfo = mNetworkSockets.remove(network);
        if (socketInfo == null) return;

        socketInfo.mSocket.destroy();
        notifyInterfaceDestroyed(network, socketInfo.mSocket);
        LOGGER.log("Remove socket on net:" + network);
    }

    private void removeTetherInterfaceSocket(String interfaceName) {
        final SocketInfo socketInfo = mTetherInterfaceSockets.remove(interfaceName);
        if (socketInfo == null) return;
        socketInfo.mSocket.destroy();
        notifyInterfaceDestroyed(null /* network */, socketInfo.mSocket);
        LOGGER.log("Remove socket on ifName:" + interfaceName);
    }

    private void notifySocketCreated(Network network, MdnsInterfaceSocket socket,
            List<LinkAddress> addresses) {
        for (int i = 0; i < mCallbacksToRequestedNetworks.size(); i++) {
            final Network requestedNetwork = mCallbacksToRequestedNetworks.valueAt(i);
            if (isNetworkMatched(requestedNetwork, network)) {
                mCallbacksToRequestedNetworks.keyAt(i).onSocketCreated(network, socket, addresses);
            }
        }
    }

    private void notifyInterfaceDestroyed(Network network, MdnsInterfaceSocket socket) {
        for (int i = 0; i < mCallbacksToRequestedNetworks.size(); i++) {
            final Network requestedNetwork = mCallbacksToRequestedNetworks.valueAt(i);
            if (isNetworkMatched(requestedNetwork, network)) {
                mCallbacksToRequestedNetworks.keyAt(i).onInterfaceDestroyed(network, socket);
            }
        }
    }

    private void notifyAddressesChanged(Network network, MdnsInterfaceSocket socket,
            List<LinkAddress> addresses) {
        for (int i = 0; i < mCallbacksToRequestedNetworks.size(); i++) {
            final Network requestedNetwork = mCallbacksToRequestedNetworks.valueAt(i);
            if (isNetworkMatched(requestedNetwork, network)) {
                mCallbacksToRequestedNetworks.keyAt(i)
                        .onAddressesChanged(network, socket, addresses);
            }
        }
    }

    private void retrieveAndNotifySocketFromNetwork(Network network, SocketCallback cb) {
        final SocketInfo socketInfo = mNetworkSockets.get(network);
        if (socketInfo == null) {
            final LinkProperties lp = mActiveNetworksLinkProperties.get(network);
            if (lp == null) {
                // The requested network is not existed. Maybe wait for LinkProperties change later.
                if (DBG) Log.d(TAG, "There is no LinkProperties for this network:" + network);
                return;
            }
            createSocket(new NetworkAsKey(network), lp);
        } else {
            // Notify the socket for requested network.
            cb.onSocketCreated(network, socketInfo.mSocket, socketInfo.mAddresses);
        }
    }

    private void retrieveAndNotifySocketFromInterface(String interfaceName, SocketCallback cb) {
        final SocketInfo socketInfo = mTetherInterfaceSockets.get(interfaceName);
        if (socketInfo == null) {
            int ifaceIndex = mDependencies.getNetworkInterfaceIndexByName(interfaceName);
            createSocket(
                    LOCAL_NET,
                    createLPForTetheredInterface(interfaceName, ifaceIndex));
        } else {
            // Notify the socket for requested network.
            cb.onSocketCreated(
                    null /* network */, socketInfo.mSocket, socketInfo.mAddresses);
        }
    }

    /**
     * Request a socket for given network.
     *
     * @param network the required network for a socket. Null means create sockets on all possible
     *                networks (interfaces).
     * @param cb the callback to listen the socket creation.
     */
    public void requestSocket(@Nullable Network network, @NonNull SocketCallback cb) {
        ensureRunningOnHandlerThread(mHandler);
        mCallbacksToRequestedNetworks.put(cb, network);
        if (network == null) {
            // Does not specify a required network, create sockets for all possible
            // networks (interfaces).
            for (int i = 0; i < mActiveNetworksLinkProperties.size(); i++) {
                retrieveAndNotifySocketFromNetwork(mActiveNetworksLinkProperties.keyAt(i), cb);
            }

            for (String localInterface : mLocalOnlyInterfaces) {
                retrieveAndNotifySocketFromInterface(localInterface, cb);
            }

            for (String tetheredInterface : mTetheredInterfaces) {
                retrieveAndNotifySocketFromInterface(tetheredInterface, cb);
            }
        } else {
            retrieveAndNotifySocketFromNetwork(network, cb);
        }
    }

    /*** Unrequest the socket */
    public void unrequestSocket(@NonNull SocketCallback cb) {
        ensureRunningOnHandlerThread(mHandler);
        mCallbacksToRequestedNetworks.remove(cb);
        if (hasAllNetworksRequest()) {
            // Still has a request for all networks (interfaces).
            return;
        }

        // Check if remaining requests are matched any of sockets.
        for (int i = mNetworkSockets.size() - 1; i >= 0; i--) {
            final Network network = mNetworkSockets.keyAt(i);
            if (matchRequestedNetwork(network)) continue;
            final SocketInfo info = mNetworkSockets.removeAt(i);
            info.mSocket.destroy();
            // Still notify to unrequester for socket destroy.
            cb.onInterfaceDestroyed(network, info.mSocket);
            LOGGER.log("Remove socket on net:" + network + " after unrequestSocket");
        }

        // Remove all sockets for tethering interface because these sockets do not have associated
        // networks, and they should invoke by a request for all networks (interfaces). If there is
        // no such request, the sockets for tethering interface should be removed.
        for (int i = mTetherInterfaceSockets.size() - 1; i >= 0; i--) {
            final SocketInfo info = mTetherInterfaceSockets.valueAt(i);
            info.mSocket.destroy();
            // Still notify to unrequester for socket destroy.
            cb.onInterfaceDestroyed(null /* network */, info.mSocket);
            LOGGER.log("Remove socket on ifName:" + mTetherInterfaceSockets.keyAt(i)
                    + " after unrequestSocket");
        }
        mTetherInterfaceSockets.clear();

        // Try to unregister network callback.
        maybeStopMonitoringSockets();
    }

    /** Dump info to dumpsys */
    public void dump(PrintWriter pw) {
        LOGGER.reverseDump(pw);
    }

    /*** Callbacks for listening socket changes */
    public interface SocketCallback {
        /*** Notify the socket is created */
        default void onSocketCreated(@Nullable Network network, @NonNull MdnsInterfaceSocket socket,
                @NonNull List<LinkAddress> addresses) {}
        /*** Notify the interface is destroyed */
        default void onInterfaceDestroyed(@Nullable Network network,
                @NonNull MdnsInterfaceSocket socket) {}
        /*** Notify the addresses is changed on the network */
        default void onAddressesChanged(@Nullable Network network,
                @NonNull MdnsInterfaceSocket socket, @NonNull List<LinkAddress> addresses) {}
    }

    private interface NetworkKey {
    }

    private static final NetworkKey LOCAL_NET = new NetworkKey() {
        @Override
        public String toString() {
            return "NetworkKey:LOCAL_NET";
        }
    };

    private static class NetworkAsKey implements NetworkKey {
        private final Network mNetwork;

        NetworkAsKey(Network network) {
            this.mNetwork = network;
        }

        @Override
        public int hashCode() {
            return mNetwork.hashCode();
        }

        @Override
        public boolean equals(@Nullable Object other) {
            if (!(other instanceof NetworkAsKey)) {
                return false;
            }
            return mNetwork.equals(((NetworkAsKey) other).mNetwork);
        }

        @Override
        public String toString() {
            return "NetworkAsKey{ network=" + mNetwork + " }";
        }
    }
}
