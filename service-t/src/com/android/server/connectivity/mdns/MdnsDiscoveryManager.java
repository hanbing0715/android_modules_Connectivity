/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.Manifest.permission;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.net.Network;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.connectivity.mdns.util.MdnsLogger;

import java.io.IOException;
import java.util.Map;

/**
 * This class keeps tracking the set of registered {@link MdnsServiceBrowserListener} instances, and
 * notify them when a mDNS service instance is found, updated, or removed?
 */
public class MdnsDiscoveryManager implements MdnsSocketClientBase.Callback {
    private static final String TAG = MdnsDiscoveryManager.class.getSimpleName();
    public static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);
    private static final MdnsLogger LOGGER = new MdnsLogger("MdnsDiscoveryManager");

    private final ExecutorProvider executorProvider;
    private final MdnsSocketClientBase socketClient;

    private final Map<String, MdnsServiceTypeClient> serviceTypeClients = new ArrayMap<>();

    public MdnsDiscoveryManager(@NonNull ExecutorProvider executorProvider,
            @NonNull MdnsSocketClientBase socketClient) {
        this.executorProvider = executorProvider;
        this.socketClient = socketClient;
    }

    /**
     * Starts (or continue) to discovery mDNS services with given {@code serviceType}, and registers
     * {@code listener} for receiving mDNS service discovery responses.
     *
     * @param serviceType   The type of the service to discover.
     * @param listener      The {@link MdnsServiceBrowserListener} listener.
     * @param searchOptions The {@link MdnsSearchOptions} to be used for discovering {@code
     *                      serviceType}.
     */
    @RequiresPermission(permission.CHANGE_WIFI_MULTICAST_STATE)
    public synchronized void registerListener(
            @NonNull String serviceType,
            @NonNull MdnsServiceBrowserListener listener,
            @NonNull MdnsSearchOptions searchOptions) {
        LOGGER.log(
                "Registering listener for subtypes: %s",
                TextUtils.join(",", searchOptions.getSubtypes()));
        if (serviceTypeClients.isEmpty()) {
            // First listener. Starts the socket client.
            try {
                socketClient.startDiscovery();
            } catch (IOException e) {
                LOGGER.e("Failed to start discover.", e);
                return;
            }
        }
        // Request the network for discovery.
        socketClient.notifyNetworkRequested(listener, searchOptions.getNetwork(), network -> {
            // All listeners of the same service types shares the same MdnsServiceTypeClient.
            MdnsServiceTypeClient serviceTypeClient = serviceTypeClients.get(serviceType);
            if (serviceTypeClient == null) {
                serviceTypeClient = createServiceTypeClient(serviceType);
                serviceTypeClients.put(serviceType, serviceTypeClient);
            }
            serviceTypeClient.startSendAndReceive(listener, searchOptions);
        });
    }

    /**
     * Unregister {@code listener} for receiving mDNS service discovery responses. IF no listener is
     * registered for the given service type, stops discovery for the service type.
     *
     * @param serviceType The type of the service to discover.
     * @param listener    The {@link MdnsServiceBrowserListener} listener.
     */
    @RequiresPermission(permission.CHANGE_WIFI_MULTICAST_STATE)
    public synchronized void unregisterListener(
            @NonNull String serviceType, @NonNull MdnsServiceBrowserListener listener) {
        LOGGER.log("Unregistering listener for service type: %s", serviceType);
        if (DBG) Log.d(TAG, "Unregistering listener for serviceType:" + serviceType);
        MdnsServiceTypeClient serviceTypeClient = serviceTypeClients.get(serviceType);
        if (serviceTypeClient == null) {
            return;
        }
        if (serviceTypeClient.stopSendAndReceive(listener)) {
            // No listener is registered for the service type anymore, remove it from the list of
            // the service type clients.
            serviceTypeClients.remove(serviceType);
            if (serviceTypeClients.isEmpty()) {
                // No discovery request. Stops the socket client.
                socketClient.stopDiscovery();
            }
        }
        // Unrequested the network.
        socketClient.notifyNetworkUnrequested(listener);
    }

    @Override
    public synchronized void onResponseReceived(@NonNull MdnsPacket packet,
            int interfaceIndex, Network network) {
        for (MdnsServiceTypeClient serviceTypeClient : serviceTypeClients.values()) {
            serviceTypeClient.processResponse(packet, interfaceIndex, network);
        }
    }

    @Override
    public synchronized void onFailedToParseMdnsResponse(int receivedPacketNumber, int errorCode) {
        for (MdnsServiceTypeClient serviceTypeClient : serviceTypeClients.values()) {
            serviceTypeClient.onFailedToParseMdnsResponse(receivedPacketNumber, errorCode);
        }
    }

    @VisibleForTesting
    MdnsServiceTypeClient createServiceTypeClient(@NonNull String serviceType) {
        return new MdnsServiceTypeClient(
                serviceType, socketClient,
                executorProvider.newServiceTypeClientSchedulerExecutor());
    }
}