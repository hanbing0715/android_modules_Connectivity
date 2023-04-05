/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.cts.net;

import android.platform.test.annotations.RequiresDevice;

public class HostsideVpnTests extends HostsideNetworkTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        uninstallPackage(TEST_APP2_PKG, false);
        installPackage(TEST_APP2_APK);
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();

        uninstallPackage(TEST_APP2_PKG, true);
    }

    public void testChangeUnderlyingNetworks() throws Exception {
        runDeviceTests(TEST_PKG, TEST_PKG + ".VpnTest", "testChangeUnderlyingNetworks");
    }

    public void testDefault() throws Exception {
        runDeviceTests(TEST_PKG, TEST_PKG + ".VpnTest", "testDefault");
    }

    public void testAppAllowed() throws Exception {
        runDeviceTests(TEST_PKG, TEST_PKG + ".VpnTest", "testAppAllowed");
    }

    public void testAppDisallowed() throws Exception {
        runDeviceTests(TEST_PKG, TEST_PKG + ".VpnTest", "testAppDisallowed");
    }

    public void testSocketClosed() throws Exception {
        runDeviceTests(TEST_PKG, TEST_PKG + ".VpnTest", "testSocketClosed");
    }

    public void testGetConnectionOwnerUidSecurity() throws Exception {
        runDeviceTests(TEST_PKG, TEST_PKG + ".VpnTest", "testGetConnectionOwnerUidSecurity");
    }

    public void testSetProxy() throws Exception {
        runDeviceTests(TEST_PKG, TEST_PKG + ".VpnTest", "testSetProxy");
    }

    public void testSetProxyDisallowedApps() throws Exception {
        runDeviceTests(TEST_PKG, TEST_PKG + ".VpnTest", "testSetProxyDisallowedApps");
    }

    public void testNoProxy() throws Exception {
        runDeviceTests(TEST_PKG, TEST_PKG + ".VpnTest", "testNoProxy");
    }

    public void testBindToNetworkWithProxy() throws Exception {
        runDeviceTests(TEST_PKG, TEST_PKG + ".VpnTest", "testBindToNetworkWithProxy");
    }

    public void testVpnMeterednessWithNoUnderlyingNetwork() throws Exception {
        runDeviceTests(
                TEST_PKG, TEST_PKG + ".VpnTest", "testVpnMeterednessWithNoUnderlyingNetwork");
    }

    public void testVpnMeterednessWithNullUnderlyingNetwork() throws Exception {
        runDeviceTests(
                TEST_PKG, TEST_PKG + ".VpnTest", "testVpnMeterednessWithNullUnderlyingNetwork");
    }

    public void testVpnMeterednessWithNonNullUnderlyingNetwork() throws Exception {
        runDeviceTests(
                TEST_PKG, TEST_PKG + ".VpnTest", "testVpnMeterednessWithNonNullUnderlyingNetwork");
    }

    public void testAlwaysMeteredVpnWithNullUnderlyingNetwork() throws Exception {
        runDeviceTests(
                TEST_PKG, TEST_PKG + ".VpnTest", "testAlwaysMeteredVpnWithNullUnderlyingNetwork");
    }

    @RequiresDevice // Keepalive is not supported on virtual hardware
    public void testAutomaticOnOffKeepaliveModeClose() throws Exception {
        runDeviceTests(
                TEST_PKG, TEST_PKG + ".VpnTest", "testAutomaticOnOffKeepaliveModeClose");
    }

    @RequiresDevice // Keepalive is not supported on virtual hardware
    public void testAutomaticOnOffKeepaliveModeNoClose() throws Exception {
        runDeviceTests(
                TEST_PKG, TEST_PKG + ".VpnTest", "testAutomaticOnOffKeepaliveModeNoClose");
    }

    public void testAlwaysMeteredVpnWithNonNullUnderlyingNetwork() throws Exception {
        runDeviceTests(
                TEST_PKG,
                TEST_PKG + ".VpnTest",
                "testAlwaysMeteredVpnWithNonNullUnderlyingNetwork");
    }

    public void testB141603906() throws Exception {
        runDeviceTests(TEST_PKG, TEST_PKG + ".VpnTest", "testB141603906");
    }

    public void testDownloadWithDownloadManagerDisallowed() throws Exception {
        runDeviceTests(TEST_PKG, TEST_PKG + ".VpnTest",
                "testDownloadWithDownloadManagerDisallowed");
    }

    public void testExcludedRoutes() throws Exception {
        runDeviceTests(TEST_PKG, TEST_PKG + ".VpnTest", "testExcludedRoutes");
    }

    public void testIncludedRoutes() throws Exception {
        runDeviceTests(TEST_PKG, TEST_PKG + ".VpnTest", "testIncludedRoutes");
    }

    public void testInterleavedRoutes() throws Exception {
        runDeviceTests(TEST_PKG, TEST_PKG + ".VpnTest", "testInterleavedRoutes");
    }

    public void testBlockIncomingPackets() throws Exception {
        runDeviceTests(TEST_PKG, TEST_PKG + ".VpnTest", "testBlockIncomingPackets");
    }

    public void testSetVpnDefaultForUids() throws Exception {
        runDeviceTests(TEST_PKG, TEST_PKG + ".VpnTest", "testSetVpnDefaultForUids");
    }
}
