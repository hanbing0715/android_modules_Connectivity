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

import static com.android.testutils.HandlerUtils.visibleOnHandlerThread;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doReturn;

import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;

import androidx.test.filters.SmallTest;

import com.android.metrics.DailykeepaliveInfoReported;
import com.android.metrics.DurationForNumOfKeepalive;
import com.android.metrics.DurationPerNumOfKeepalive;
import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(DevSdkIgnoreRunner.class)
@SmallTest
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.TIRAMISU)
public class KeepaliveStatsTrackerTest {
    private HandlerThread mHandlerThread;
    private Handler mTestHandler;

    private KeepaliveStatsTracker mKeepaliveStatsTracker;

    @Mock private KeepaliveStatsTracker.Dependencies mDependencies;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mHandlerThread = new HandlerThread("KeepaliveStatsTrackerTest");
        mHandlerThread.start();
        mTestHandler = new Handler(mHandlerThread.getLooper());

        setUptimeMillis(0);
        mKeepaliveStatsTracker = new KeepaliveStatsTracker(mTestHandler, mDependencies);
    }

    private void setUptimeMillis(long time) {
        doReturn(time).when(mDependencies).getUptimeMillis();
    }

    private DailykeepaliveInfoReported buildKeepaliveMetrics(long time) {
        setUptimeMillis(time);

        return visibleOnHandlerThread(
                mTestHandler, () -> mKeepaliveStatsTracker.buildKeepaliveMetrics());
    }

    private DailykeepaliveInfoReported buildAndResetMetrics(long time) {
        setUptimeMillis(time);

        return visibleOnHandlerThread(
                mTestHandler,
                () -> {
                    final DailykeepaliveInfoReported dailyKeepaliveInfoReported =
                            mKeepaliveStatsTracker.buildKeepaliveMetrics();
                    mKeepaliveStatsTracker.resetMetrics();
                    return dailyKeepaliveInfoReported;
                });
    }

    private void onStartKeepalive(long time) {
        setUptimeMillis(time);
        visibleOnHandlerThread(mTestHandler, () -> mKeepaliveStatsTracker.onStartKeepalive());
    }

    private void onPauseKeepalive(long time) {
        setUptimeMillis(time);
        visibleOnHandlerThread(mTestHandler, () -> mKeepaliveStatsTracker.onPauseKeepalive());
    }

    private void onResumeKeepalive(long time) {
        setUptimeMillis(time);
        visibleOnHandlerThread(mTestHandler, () -> mKeepaliveStatsTracker.onResumeKeepalive());
    }

    private void onStopKeepalive(long time, boolean wasActive) {
        setUptimeMillis(time);
        visibleOnHandlerThread(
                mTestHandler, () -> mKeepaliveStatsTracker.onStopKeepalive(wasActive));
    }

    @Test
    public void testEnsureRunningOnHandlerThread() {
        // Not running on handler thread
        assertThrows(IllegalStateException.class, () -> mKeepaliveStatsTracker.onStartKeepalive());
        assertThrows(IllegalStateException.class, () -> mKeepaliveStatsTracker.onPauseKeepalive());
        assertThrows(IllegalStateException.class, () -> mKeepaliveStatsTracker.onResumeKeepalive());
        assertThrows(
                IllegalStateException.class, () -> mKeepaliveStatsTracker.onStopKeepalive(true));
        assertThrows(
                IllegalStateException.class, () -> mKeepaliveStatsTracker.buildKeepaliveMetrics());
        assertThrows(
                IllegalStateException.class, () -> mKeepaliveStatsTracker.resetMetrics());
    }

    /**
     * Asserts that a DurationPerNumOfKeepalive contains expected values
     *
     * @param expectRegisteredDurations integer array where the index is the number of concurrent
     *     keepalives and the value is the expected duration of time that the tracker is in a state
     *     with the given number of keepalives registered.
     * @param expectActiveDurations integer array where the index is the number of concurrent
     *     keepalives and the value is the expected duration of time that the tracker is in a state
     *     with the given number of keepalives active.
     * @param resultDurationsPerNumOfKeepalive the DurationPerNumOfKeepalive message to assert.
     */
    private void assertDurationMetrics(
            int[] expectRegisteredDurations,
            int[] expectActiveDurations,
            DurationPerNumOfKeepalive resultDurationsPerNumOfKeepalive) {
        final int maxNumOfKeepalive = expectRegisteredDurations.length;
        assertEquals(maxNumOfKeepalive, expectActiveDurations.length);
        assertEquals(
                maxNumOfKeepalive,
                resultDurationsPerNumOfKeepalive.getDurationForNumOfKeepaliveCount());
        for (int numOfKeepalive = 0; numOfKeepalive < maxNumOfKeepalive; numOfKeepalive++) {
            final DurationForNumOfKeepalive resultDurations =
                    resultDurationsPerNumOfKeepalive.getDurationForNumOfKeepalive(numOfKeepalive);

            assertEquals(numOfKeepalive, resultDurations.getNumOfKeepalive());
            assertEquals(
                    expectRegisteredDurations[numOfKeepalive],
                    resultDurations.getKeepaliveRegisteredDurationsMsec());
            assertEquals(
                    expectActiveDurations[numOfKeepalive],
                    resultDurations.getKeepaliveActiveDurationsMsec());
        }
    }

    private void assertDailyKeepaliveInfoReported(
            DailykeepaliveInfoReported dailyKeepaliveInfoReported,
            int[] expectRegisteredDurations,
            int[] expectActiveDurations) {
        // TODO(b/273451360) Assert these values when they are filled.
        assertFalse(dailyKeepaliveInfoReported.hasKeepaliveLifetimePerCarrier());
        assertFalse(dailyKeepaliveInfoReported.hasKeepaliveRequests());
        assertFalse(dailyKeepaliveInfoReported.hasAutomaticKeepaliveRequests());
        assertFalse(dailyKeepaliveInfoReported.hasDistinctUserCount());
        assertTrue(dailyKeepaliveInfoReported.getUidList().isEmpty());

        final DurationPerNumOfKeepalive resultDurations =
                dailyKeepaliveInfoReported.getDurationPerNumOfKeepalive();
        assertDurationMetrics(expectRegisteredDurations, expectActiveDurations, resultDurations);
    }

    @Test
    public void testNoKeepalive() {
        final int writeTime = 5000;

        final DailykeepaliveInfoReported dailyKeepaliveInfoReported =
                buildKeepaliveMetrics(writeTime);

        // Expect that the durations are all in numOfKeepalive = 0.
        final int[] expectRegisteredDurations = new int[] {writeTime};
        final int[] expectActiveDurations = new int[] {writeTime};

        assertDailyKeepaliveInfoReported(
                dailyKeepaliveInfoReported,
                expectRegisteredDurations,
                expectActiveDurations);
    }

    /*
     * Diagram of test (not to scale):
     * Key: S - Start/Stop, P - Pause, R - Resume, W - Write
     *
     * Keepalive     S                          W
     * Timeline  |------------------------------|
     */
    @Test
    public void testOneKeepalive_startOnly() {
        final int startTime = 1000;
        final int writeTime = 5000;

        onStartKeepalive(startTime);

        final DailykeepaliveInfoReported dailyKeepaliveInfoReported =
                buildKeepaliveMetrics(writeTime);

        // The keepalive is never stopped, expect the duration for numberOfKeepalive of 1 to range
        // from startTime to writeTime.
        final int[] expectRegisteredDurations = new int[] {startTime, writeTime - startTime};
        final int[] expectActiveDurations = new int[] {startTime, writeTime - startTime};
        assertDailyKeepaliveInfoReported(
                dailyKeepaliveInfoReported,
                expectRegisteredDurations,
                expectActiveDurations);
    }

    /*
     * Diagram of test (not to scale):
     * Key: S - Start/Stop, P - Pause, R - Resume, W - Write
     *
     * Keepalive     S       P                  W
     * Timeline  |------------------------------|
     */
    @Test
    public void testOneKeepalive_paused() {
        final int startTime = 1000;
        final int pauseTime = 2030;
        final int writeTime = 5000;

        onStartKeepalive(startTime);

        onPauseKeepalive(pauseTime);

        final DailykeepaliveInfoReported dailyKeepaliveInfoReported =
                buildKeepaliveMetrics(writeTime);

        // The keepalive is paused but not stopped, expect the registered duration for
        // numberOfKeepalive of 1 to still range from startTime to writeTime while the active
        // duration stops at pauseTime.
        final int[] expectRegisteredDurations = new int[] {startTime, writeTime - startTime};
        final int[] expectActiveDurations =
                new int[] {startTime + (writeTime - pauseTime), pauseTime - startTime};
        assertDailyKeepaliveInfoReported(
                dailyKeepaliveInfoReported,
                expectRegisteredDurations,
                expectActiveDurations);
    }

    /*
     * Diagram of test (not to scale):
     * Key: S - Start/Stop, P - Pause, R - Resume, W - Write
     *
     * Keepalive     S       P        R         W
     * Timeline  |------------------------------|
     */
    @Test
    public void testOneKeepalive_resumed() {
        final int startTime = 1000;
        final int pauseTime = 2030;
        final int resumeTime = 3450;
        final int writeTime = 5000;

        onStartKeepalive(startTime);

        onPauseKeepalive(pauseTime);

        onResumeKeepalive(resumeTime);

        final DailykeepaliveInfoReported dailyKeepaliveInfoReported =
                buildKeepaliveMetrics(writeTime);

        // The keepalive is paused and resumed but not stopped, expect the registered duration for
        // numberOfKeepalive of 1 to still range from startTime to writeTime while the active
        // duration stops at pauseTime but resumes at resumeTime and stops at writeTime.
        final int[] expectRegisteredDurations = new int[] {startTime, writeTime - startTime};
        final int[] expectActiveDurations =
                new int[] {
                    startTime + (resumeTime - pauseTime),
                    (pauseTime - startTime) + (writeTime - resumeTime)
                };
        assertDailyKeepaliveInfoReported(
                dailyKeepaliveInfoReported,
                expectRegisteredDurations,
                expectActiveDurations);
    }

    /*
     * Diagram of test (not to scale):
     * Key: S - Start/Stop, P - Pause, R - Resume, W - Write
     *
     * Keepalive     S       P      R     S     W
     * Timeline  |------------------------------|
     */
    @Test
    public void testOneKeepalive_stopped() {
        final int startTime = 1000;
        final int pauseTime = 2930;
        final int resumeTime = 3452;
        final int stopTime = 4157;
        final int writeTime = 5000;

        onStartKeepalive(startTime);

        onPauseKeepalive(pauseTime);

        onResumeKeepalive(resumeTime);

        onStopKeepalive(stopTime, /* wasActive= */ true);

        final DailykeepaliveInfoReported dailyKeepaliveInfoReported =
                buildKeepaliveMetrics(writeTime);

        // The keepalive is now stopped, expect the registered duration for numberOfKeepalive of 1
        // to now range from startTime to stopTime while the active duration stops at pauseTime but
        // resumes at resumeTime and stops again at stopTime.
        final int[] expectRegisteredDurations =
                new int[] {startTime + (writeTime - stopTime), stopTime - startTime};
        final int[] expectActiveDurations =
                new int[] {
                    startTime + (resumeTime - pauseTime) + (writeTime - stopTime),
                    (pauseTime - startTime) + (stopTime - resumeTime)
                };
        assertDailyKeepaliveInfoReported(
                dailyKeepaliveInfoReported,
                expectRegisteredDurations,
                expectActiveDurations);
    }

    /*
     * Diagram of test (not to scale):
     * Key: S - Start/Stop, P - Pause, R - Resume, W - Write
     *
     * Keepalive     S       P            S     W
     * Timeline  |------------------------------|
     */
    @Test
    public void testOneKeepalive_pausedStopped() {
        final int startTime = 1000;
        final int pauseTime = 2930;
        final int stopTime = 4157;
        final int writeTime = 5000;

        onStartKeepalive(startTime);

        onPauseKeepalive(pauseTime);

        onStopKeepalive(stopTime, /* wasActive= */ false);

        final DailykeepaliveInfoReported dailyKeepaliveInfoReported =
                buildKeepaliveMetrics(writeTime);

        // The keepalive is stopped while paused, expect the registered duration for
        // numberOfKeepalive of 1 to range from startTime to stopTime while the active duration
        // simply stops at pauseTime.
        final int[] expectRegisteredDurations =
                new int[] {startTime + (writeTime - stopTime), stopTime - startTime};
        final int[] expectActiveDurations =
                new int[] {startTime + (writeTime - pauseTime), (pauseTime - startTime)};
        assertDailyKeepaliveInfoReported(
                dailyKeepaliveInfoReported,
                expectRegisteredDurations,
                expectActiveDurations);
    }

    /*
     * Diagram of test (not to scale):
     * Key: S - Start/Stop, P - Pause, R - Resume, W - Write
     *
     * Keepalive     S  P R P R P R       S     W
     * Timeline  |------------------------------|
     */
    @Test
    public void testOneKeepalive_multiplePauses() {
        final int startTime = 1000;
        // Alternating timestamps of pause and resume
        final int[] pauseResumeTimes = new int[] {1200, 1400, 1700, 2000, 2400, 2800};
        final int stopTime = 4000;
        final int writeTime = 5000;

        onStartKeepalive(startTime);

        for (int i = 0; i < pauseResumeTimes.length; i++) {
            if (i % 2 == 0) {
                onPauseKeepalive(pauseResumeTimes[i]);
            } else {
                onResumeKeepalive(pauseResumeTimes[i]);
            }
        }

        onStopKeepalive(stopTime, /* wasActive= */ true);

        final DailykeepaliveInfoReported dailyKeepaliveInfoReported =
                buildKeepaliveMetrics(writeTime);

        final int[] expectRegisteredDurations =
                new int[] {startTime + (writeTime - stopTime), stopTime - startTime};
        final int[] expectActiveDurations =
                new int[] {
                    startTime + /* sum of (Resume - Pause) */ (900) + (writeTime - stopTime),
                    (pauseResumeTimes[0] - startTime)
                            + /* sum of (Pause - Resume) */ (700)
                            + (stopTime - pauseResumeTimes[5])
                };
        assertDailyKeepaliveInfoReported(
                dailyKeepaliveInfoReported,
                expectRegisteredDurations,
                expectActiveDurations);
    }

    /*
     * Diagram of test (not to scale):
     * Key: S - Start/Stop, P - Pause, R - Resume, W - Write
     *
     * Keepalive1    S1  P1     R1         S1    W
     * Keepalive2           S2     P2   R2       W
     * Timeline   |------------------------------|
     */
    @Test
    public void testTwoKeepalives() {
        // The suffix 1/2 indicates which keepalive it is referring to.
        final int startTime1 = 1000;
        final int pauseTime1 = 1500;
        final int startTime2 = 2000;
        final int resumeTime1 = 2500;
        final int pauseTime2 = 3000;
        final int resumeTime2 = 3500;
        final int stopTime1 = 4157;
        final int writeTime = 5000;

        onStartKeepalive(startTime1);

        onPauseKeepalive(pauseTime1);

        onStartKeepalive(startTime2);

        onResumeKeepalive(resumeTime1);

        onPauseKeepalive(pauseTime2);

        onResumeKeepalive(resumeTime2);

        onStopKeepalive(stopTime1, /* wasActive= */ true);

        final DailykeepaliveInfoReported dailyKeepaliveInfoReported =
                buildKeepaliveMetrics(writeTime);

        // With two keepalives, the number of concurrent keepalives can vary from 0-2 depending on
        // both keepalive states.
        final int[] expectRegisteredDurations =
                new int[] {
                    startTime1,
                    // 1 registered keepalive before keepalive2 starts and after keepalive1 stops.
                    (startTime2 - startTime1) + (writeTime - stopTime1),
                    // 2 registered keepalives between keepalive2 start and keepalive1 stop.
                    stopTime1 - startTime2
                };

        final int[] expectActiveDurations =
                new int[] {
                    // 0 active keepalives when keepalive1 is paused before keepalive2 starts.
                    startTime1 + (startTime2 - pauseTime1),
                    // 1 active keepalive before keepalive1 is paused.
                    (pauseTime1 - startTime1)
                            // before keepalive1 is resumed and after keepalive2 starts.
                            + (resumeTime1 - startTime2)
                            // during keepalive2 is paused since keepalive1 has been resumed.
                            + (resumeTime2 - pauseTime2)
                            // after keepalive1 stops since keepalive2 has been resumed.
                            + (writeTime - stopTime1),
                    // 2 active keepalives before keepalive2 is paused and before keepalive1 stops.
                    (pauseTime2 - resumeTime1) + (stopTime1 - resumeTime2)
                };
        assertDailyKeepaliveInfoReported(
                dailyKeepaliveInfoReported,
                expectRegisteredDurations,
                expectActiveDurations);
    }

    /*
     * Diagram of test (not to scale):
     * Key: S - Start/Stop, P - Pause, R - Resume, W - Write
     *
     * Keepalive     S   W(reset+W)         S    W
     * Timeline   |------------------------------|
     */
    @Test
    public void testResetMetrics() {
        final int startTime = 1000;
        final int writeTime = 5000;
        final int stopTime = 7000;
        final int writeTime2 = 10000;

        onStartKeepalive(startTime);

        final DailykeepaliveInfoReported dailyKeepaliveInfoReported =
                buildAndResetMetrics(writeTime);

        // Same expect as testOneKeepalive_startOnly
        final int[] expectRegisteredDurations = new int[] {startTime, writeTime - startTime};
        final int[] expectActiveDurations = new int[] {startTime, writeTime - startTime};
        assertDailyKeepaliveInfoReported(
                dailyKeepaliveInfoReported,
                expectRegisteredDurations,
                expectActiveDurations);

        final DailykeepaliveInfoReported dailyKeepaliveInfoReported2 =
                buildKeepaliveMetrics(writeTime);

        // Expect the stored durations to be 0 but still contain the number of keepalive = 1.
        assertDailyKeepaliveInfoReported(
                dailyKeepaliveInfoReported2,
                /* expectRegisteredDurations= */ new int[] {0, 0},
                /* expectActiveDurations= */ new int[] {0, 0});

        // Expect that the keepalive is still registered after resetting so it can be stopped.
        onStopKeepalive(stopTime, /* wasActive= */ true);

        final DailykeepaliveInfoReported dailyKeepaliveInfoReported3 =
                buildKeepaliveMetrics(writeTime2);

        final int[] expectRegisteredDurations2 =
                new int[] {writeTime2 - stopTime, stopTime - writeTime};
        final int[] expectActiveDurations2 =
                new int[] {writeTime2 - stopTime, stopTime - writeTime};
        assertDailyKeepaliveInfoReported(
                dailyKeepaliveInfoReported3,
                expectRegisteredDurations2,
                expectActiveDurations2);
    }
}
