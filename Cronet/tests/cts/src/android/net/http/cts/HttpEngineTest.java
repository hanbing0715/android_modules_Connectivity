/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.net.http.cts;

import static android.net.http.cts.util.TestUtilsKt.assertOKStatusCode;
import static android.net.http.cts.util.TestUtilsKt.assumeOKStatusCode;
import static android.net.http.cts.util.TestUtilsKt.skipIfNoInternetConnection;

import static com.google.common.truth.Truth.assertThat;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.Network;
import android.net.http.ConnectionMigrationOptions;
import android.net.http.DnsOptions;
import android.net.http.HttpEngine;
import android.net.http.UrlRequest;
import android.net.http.UrlResponseInfo;
import android.net.http.cts.util.HttpCtsTestServer;
import android.net.http.cts.util.TestUrlRequestCallback;
import android.net.http.cts.util.TestUrlRequestCallback.ResponseStep;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;

@RunWith(AndroidJUnit4.class)
public class HttpEngineTest {
    private static final String HOST = "source.android.com";
    private static final String URL = "https://" + HOST;

    private HttpEngine.Builder mEngineBuilder;
    private TestUrlRequestCallback mCallback;
    private HttpCtsTestServer mTestServer;
    private UrlRequest mRequest;
    private HttpEngine mEngine;
    private Context mContext;

    @Before
    public void setUp() throws Exception {
        mContext = ApplicationProvider.getApplicationContext();
        skipIfNoInternetConnection(mContext);
        mEngineBuilder = new HttpEngine.Builder(mContext);
        mCallback = new TestUrlRequestCallback();
        mTestServer = new HttpCtsTestServer(mContext);
    }

    @After
    public void tearDown() throws Exception {
        if (mRequest != null) {
            mRequest.cancel();
            mCallback.blockForDone();
        }
        if (mEngine != null) {
            mEngine.shutdown();
        }
        if (mTestServer != null) {
            mTestServer.shutdown();
        }
    }

    private boolean isQuic(String negotiatedProtocol) {
        return negotiatedProtocol.startsWith("http/2+quic") || negotiatedProtocol.startsWith("h3");
    }

    @Test
    public void testHttpEngine_Default() throws Exception {
        mEngine = mEngineBuilder.build();
        UrlRequest.Builder builder =
                mEngine.newUrlRequestBuilder(URL, mCallback.getExecutor(), mCallback);
        mRequest = builder.build();
        mRequest.start();

        // This tests uses a non-hermetic server. Instead of asserting, assume the next callback.
        // This way, if the request were to fail, the test would just be skipped instead of failing.
        mCallback.assumeCallback(ResponseStep.ON_SUCCEEDED);
        UrlResponseInfo info = mCallback.mResponseInfo;
        assertOKStatusCode(info);
        assertEquals("h2", info.getNegotiatedProtocol());
    }

    @Test
    public void testHttpEngine_EnableHttpCache() {
        // We need a server which sets cache-control != no-cache.
        String url = "https://www.example.com";
        mEngine =
                mEngineBuilder
                        .setStoragePath(mContext.getApplicationInfo().dataDir)
                        .setEnableHttpCache(HttpEngine.Builder.HTTP_CACHE_DISK,
                                            /* maxSize */ 100 * 1024)
                        .build();

        UrlRequest.Builder builder =
                mEngine.newUrlRequestBuilder(url, mCallback.getExecutor(), mCallback);
        mRequest = builder.build();
        mRequest.start();
        // This tests uses a non-hermetic server. Instead of asserting, assume the next callback.
        // This way, if the request were to fail, the test would just be skipped instead of failing.
        mCallback.assumeCallback(ResponseStep.ON_SUCCEEDED);
        UrlResponseInfo info = mCallback.mResponseInfo;
        assumeOKStatusCode(info);
        assertFalse(info.wasCached());

        mCallback = new TestUrlRequestCallback();
        builder = mEngine.newUrlRequestBuilder(url, mCallback.getExecutor(), mCallback);
        mRequest = builder.build();
        mRequest.start();
        mCallback.assumeCallback(ResponseStep.ON_SUCCEEDED);
        info = mCallback.mResponseInfo;
        assertOKStatusCode(info);
        assertTrue(info.wasCached());
    }

    @Test
    public void testHttpEngine_DisableHttp2() throws Exception {
        mEngine = mEngineBuilder.setEnableHttp2(false).build();
        UrlRequest.Builder builder =
                mEngine.newUrlRequestBuilder(URL, mCallback.getExecutor(), mCallback);
        mRequest = builder.build();
        mRequest.start();

        // This tests uses a non-hermetic server. Instead of asserting, assume the next callback.
        // This way, if the request were to fail, the test would just be skipped instead of failing.
        mCallback.assumeCallback(ResponseStep.ON_SUCCEEDED);
        UrlResponseInfo info = mCallback.mResponseInfo;
        assertOKStatusCode(info);
        assertEquals("http/1.1", info.getNegotiatedProtocol());
    }

    @Test
    public void testHttpEngine_EnablePublicKeyPinningBypassForLocalTrustAnchors() {
        // For known hosts, requests should succeed whether we're bypassing the local trust anchor
        // or not.
        mEngine = mEngineBuilder.setEnablePublicKeyPinningBypassForLocalTrustAnchors(false).build();
        UrlRequest.Builder builder =
                mEngine.newUrlRequestBuilder(URL, mCallback.getExecutor(), mCallback);
        mRequest = builder.build();
        mRequest.start();
        mCallback.expectCallback(ResponseStep.ON_SUCCEEDED);

        mEngine.shutdown();
        mEngine = mEngineBuilder.setEnablePublicKeyPinningBypassForLocalTrustAnchors(true).build();
        mCallback = new TestUrlRequestCallback();
        builder = mEngine.newUrlRequestBuilder(URL, mCallback.getExecutor(), mCallback);
        mRequest = builder.build();
        mRequest.start();
        mCallback.expectCallback(ResponseStep.ON_SUCCEEDED);

        // TODO(b/270918920): We should also test with a certificate not present in the device's
        // trusted store.
        // This requires either:
        // * Mocking the underlying CertificateVerifier.
        // * Or, having the server return a root certificate not present in the device's trusted
        //   store.
        // The former doesn't make sense for a CTS test as it would depend on the underlying
        // implementation. The latter is something we should support once we write a proper test
        // server.
    }

    @Test
    public void testHttpEngine_EnableQuic() throws Exception {
        mEngine = mEngineBuilder.setEnableQuic(true).addQuicHint(HOST, 443, 443).build();
        // The hint doesn't guarantee that QUIC will win the race, just that it will race TCP.
        // We send multiple requests to reduce the flakiness of the test.
        boolean quicWasUsed = false;
        for (int i = 0; i < 5; i++) {
            mCallback = new TestUrlRequestCallback();
            UrlRequest.Builder builder =
                    mEngine.newUrlRequestBuilder(URL, mCallback.getExecutor(), mCallback);
            mRequest = builder.build();
            mRequest.start();

            // This tests uses a non-hermetic server. Instead of asserting, assume the next
            // callback. This way, if the request were to fail, the test would just be skipped
            // instead of failing.
            mCallback.assumeCallback(ResponseStep.ON_SUCCEEDED);
            UrlResponseInfo info = mCallback.mResponseInfo;
            assumeOKStatusCode(info);
            quicWasUsed = isQuic(info.getNegotiatedProtocol());
            if (quicWasUsed) {
                break;
            }
        }
        assertTrue(quicWasUsed);
    }

    @Test
    public void testHttpEngine_GetDefaultUserAgent() throws Exception {
        assertThat(mEngineBuilder.getDefaultUserAgent(), containsString("AndroidHttpClient"));
    }

    @Test
    public void testHttpEngine_requestUsesDefaultUserAgent() throws Exception {
        mEngine = mEngineBuilder.build();
        HttpCtsTestServer server =
                new HttpCtsTestServer(ApplicationProvider.getApplicationContext());

        String url = server.getUserAgentUrl();
        UrlRequest request =
                mEngine.newUrlRequestBuilder(url, mCallback.getExecutor(), mCallback).build();
        request.start();

        mCallback.expectCallback(ResponseStep.ON_SUCCEEDED);
        UrlResponseInfo info = mCallback.mResponseInfo;
        assertOKStatusCode(info);
        String receivedUserAgent = extractUserAgent(mCallback.mResponseAsString);

        assertThat(receivedUserAgent).isEqualTo(mEngineBuilder.getDefaultUserAgent());
    }

    @Test
    public void testHttpEngine_requestUsesCustomUserAgent() throws Exception {
        String userAgent = "CtsTests User Agent";
        HttpCtsTestServer server =
                new HttpCtsTestServer(ApplicationProvider.getApplicationContext());
        mEngine =
                new HttpEngine.Builder(ApplicationProvider.getApplicationContext())
                        .setUserAgent(userAgent)
                        .build();

        String url = server.getUserAgentUrl();
        UrlRequest request =
                mEngine.newUrlRequestBuilder(url, mCallback.getExecutor(), mCallback).build();
        request.start();

        mCallback.expectCallback(ResponseStep.ON_SUCCEEDED);
        UrlResponseInfo info = mCallback.mResponseInfo;
        assertOKStatusCode(info);
        String receivedUserAgent = extractUserAgent(mCallback.mResponseAsString);

        assertThat(receivedUserAgent).isEqualTo(userAgent);
    }

    private static String extractUserAgent(String userAgentResponseBody) {
        // If someone wants to be evil and have the title HTML tag a part of the user agent,
        // they'll have to fix this method :)
        return userAgentResponseBody
                .replaceFirst(".*<title>", "")
                .replaceFirst("</title>.*", "");
    }

    @Test
    public void testHttpEngine_bindToNetwork() throws Exception {
        // Create a fake Android.net.Network. Since that network doesn't exist, binding to
        // that should end up in a failed request.
        Network mockNetwork = Mockito.mock(Network.class);
        Mockito.when(mockNetwork.getNetworkHandle()).thenReturn(123L);
        String url = mTestServer.getSuccessUrl();

        mEngine = mEngineBuilder.build();
        mEngine.bindToNetwork(mockNetwork);
        UrlRequest.Builder builder =
                mEngine.newUrlRequestBuilder(url, mCallback.getExecutor(), mCallback);
        mRequest = builder.build();
        mRequest.start();

        mCallback.expectCallback(ResponseStep.ON_FAILED);
    }

    @Test
    public void testHttpEngine_unbindFromNetwork() throws Exception {
        // Create a fake Android.net.Network. Since that network doesn't exist, binding to
        // that should end up in a failed request.
        Network mockNetwork = Mockito.mock(Network.class);
        Mockito.when(mockNetwork.getNetworkHandle()).thenReturn(123L);
        String url = mTestServer.getSuccessUrl();

        mEngine = mEngineBuilder.build();
        // Bind to the fake network but then unbind. This should result in a successful
        // request.
        mEngine.bindToNetwork(mockNetwork);
        mEngine.bindToNetwork(null);
        UrlRequest.Builder builder =
                mEngine.newUrlRequestBuilder(url, mCallback.getExecutor(), mCallback);
        mRequest = builder.build();
        mRequest.start();

        mCallback.expectCallback(ResponseStep.ON_SUCCEEDED);
        UrlResponseInfo info = mCallback.mResponseInfo;
        assertOKStatusCode(info);
    }

    @Test
    public void testHttpEngine_setConnectionMigrationOptions_requestSucceeds() {
        ConnectionMigrationOptions options = new ConnectionMigrationOptions.Builder().build();
        mEngine = mEngineBuilder.setConnectionMigrationOptions(options).build();
        UrlRequest.Builder builder =
                mEngine.newUrlRequestBuilder(
                        mTestServer.getSuccessUrl(), mCallback.getExecutor(), mCallback);
        mRequest = builder.build();
        mRequest.start();

        mCallback.expectCallback(ResponseStep.ON_SUCCEEDED);
        UrlResponseInfo info = mCallback.mResponseInfo;
        assertOKStatusCode(info);
    }

    @Test
    public void testHttpEngine_setDnsOptions_requestSucceeds() {
        DnsOptions options = new DnsOptions.Builder().build();
        mEngine = mEngineBuilder.setDnsOptions(options).build();
        UrlRequest.Builder builder =
                mEngine.newUrlRequestBuilder(
                        mTestServer.getSuccessUrl(), mCallback.getExecutor(), mCallback);
        mRequest = builder.build();
        mRequest.start();

        mCallback.expectCallback(ResponseStep.ON_SUCCEEDED);
        UrlResponseInfo info = mCallback.mResponseInfo;
        assertOKStatusCode(info);
    }
}