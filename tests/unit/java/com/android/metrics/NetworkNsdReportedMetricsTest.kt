/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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

package com.android.metrics

import android.os.Build
import android.stats.connectivity.MdnsQueryResult
import android.stats.connectivity.NsdEventType
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify

@RunWith(DevSdkIgnoreRunner::class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.TIRAMISU)
class NetworkNsdReportedMetricsTest {
    private val deps = mock(NetworkNsdReportedMetrics.Dependencies::class.java)

    @Test
    fun testReportServiceRegistrationSucceeded() {
        val clientId = 99
        val transactionId = 100
        val durationMs = 10L
        val metrics = NetworkNsdReportedMetrics(true /* isLegacy */, clientId, deps)
        metrics.reportServiceRegistrationSucceeded(transactionId, durationMs)

        val eventCaptor = ArgumentCaptor.forClass(NetworkNsdReported::class.java)
        verify(deps).statsWrite(eventCaptor.capture())
        eventCaptor.value.let {
            assertTrue(it.isLegacy)
            assertEquals(clientId, it.clientId)
            assertEquals(transactionId, it.transactionId)
            assertEquals(NsdEventType.NET_REGISTER, it.type)
            assertEquals(MdnsQueryResult.MQR_SERVICE_REGISTERED, it.queryResult)
            assertEquals(durationMs, it.eventDurationMillisec)
        }
    }

    @Test
    fun testReportServiceRegistrationFailed() {
        val clientId = 99
        val transactionId = 100
        val durationMs = 10L
        val metrics = NetworkNsdReportedMetrics(false /* isLegacy */, clientId, deps)
        metrics.reportServiceRegistrationFailed(transactionId, durationMs)

        val eventCaptor = ArgumentCaptor.forClass(NetworkNsdReported::class.java)
        verify(deps).statsWrite(eventCaptor.capture())
        eventCaptor.value.let {
            assertFalse(it.isLegacy)
            assertEquals(clientId, it.clientId)
            assertEquals(transactionId, it.transactionId)
            assertEquals(NsdEventType.NET_REGISTER, it.type)
            assertEquals(MdnsQueryResult.MQR_SERVICE_REGISTRATION_FAILED, it.queryResult)
            assertEquals(durationMs, it.eventDurationMillisec)
        }
    }

    @Test
    fun testReportServiceUnregistration() {
        val clientId = 99
        val transactionId = 100
        val durationMs = 10L
        val metrics = NetworkNsdReportedMetrics(true /* isLegacy */, clientId, deps)
        metrics.reportServiceUnregistration(transactionId, durationMs)

        val eventCaptor = ArgumentCaptor.forClass(NetworkNsdReported::class.java)
        verify(deps).statsWrite(eventCaptor.capture())
        eventCaptor.value.let {
            assertTrue(it.isLegacy)
            assertEquals(clientId, it.clientId)
            assertEquals(transactionId, it.transactionId)
            assertEquals(NsdEventType.NET_REGISTER, it.type)
            assertEquals(MdnsQueryResult.MQR_SERVICE_UNREGISTERED, it.queryResult)
            assertEquals(durationMs, it.eventDurationMillisec)
        }
    }
}
