package org.sugr.gearshift.unit.service;

import android.app.Activity;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.util.Base64;

import org.apache.http.protocol.HTTP;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.robolectric.tester.android.content.TestSharedPreferences;
import org.sugr.gearshift.core.TransmissionProfile;
import org.sugr.gearshift.datasource.DataSource;
import org.sugr.gearshift.service.ConnectionProvider;
import org.sugr.gearshift.service.TransmissionSessionManager;
import org.sugr.gearshift.unit.util.RobolectricGradleTestRunner;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.Charset;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocketFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Config(emulateSdk = 17)
@RunWith(RobolectricGradleTestRunner.class)
public class TransmissionSessionManagerTest {
    private ConnectivityManager connMananager;
    private TestSharedPreferences defaultPrefs;
    private TransmissionProfile profile;
    private DataSource dataSource;
    private ConnectionProvider connProvider;
    private HttpsURLConnectionTest connection;
    private TransmissionSessionManager manager;

    @Before public void setUp() throws Exception {
        defaultPrefs = new TestSharedPreferences(new HashMap<String, Map<String, Object>>(),
            "default", Activity.MODE_PRIVATE);

        connMananager = mock(ConnectivityManager.class);
        when(connMananager.getActiveNetworkInfo()).thenReturn(mock(NetworkInfo.class));

        profile = mock(TransmissionProfile.class);
        when(profile.getTimeout()).thenReturn(1);
        when(profile.getId()).thenReturn("existingId");

        dataSource = mock(DataSource.class);

        connProvider = mock(ConnectionProvider.class);
        connection = new HttpsURLConnectionTest(new URL("http://example.com"));
        connection.outputStream = new ByteArrayOutputStream();
        when(connProvider.open(profile)).thenReturn(connection);

        manager = new TransmissionSessionManager(connMananager, defaultPrefs, profile,
            dataSource, connProvider);
    }

    @Test public void connectivity() {
        assertNotNull(manager);

        when(connMananager.getActiveNetworkInfo()).thenReturn(null);
        assertFalse(manager.hasConnectivity());

        when(connMananager.getActiveNetworkInfo()).thenReturn(mock(NetworkInfo.class));
        assertTrue(manager.hasConnectivity());
    }

    @Test public void network() throws Exception {
        assertNotNull(manager);

        setupConnection(HttpURLConnection.HTTP_NOT_FOUND, new HashMap<String, String>(), "", null, "404 not found");

        try {
            manager.updateSession();
        } catch (TransmissionSessionManager.ManagerException e) {
            assertEquals(1000, connection.readTimeout);
            assertEquals(1000, connection.connectTimeout);
            assertFalse(connection.isConnected);
            assertEquals(1, connection.connectCount);
            assertEquals("application/json", connection.requestProperties.get("Content-Type"));
            assertEquals("gzip, deflate", connection.requestProperties.get("Accept-Encoding"));
            assertFalse(connection.useCaches);
            assertFalse(connection.allowUserInteraction);
            assertEquals("POST", connection.requestMethod);
            assertTrue(connection.doInput);
            assertTrue(connection.doOutput);
            assertFalse(connection.requestProperties.containsKey("X-Transmission-Session-Id"));
            assertFalse(connection.requestProperties.containsKey("Authorization"));

            assertEquals("{\"method\":\"session-get\"}", connection.outputStream.toString());

            assertEquals(HttpURLConnection.HTTP_NOT_FOUND, e.getCode());
            assertEquals("404 not found", e.getMessage());
        }

        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "text/html");
        setupConnection(HttpURLConnection.HTTP_OK, headers, "<h1>Hello World</h1>", null, "");

        try {
            manager.updateSession();
        } catch (TransmissionSessionManager.ManagerException e) {
            assertEquals(HttpURLConnection.HTTP_OK, e.getCode());
            assertEquals("no-json", e.getMessage());
        }

        assertEquals(201, HttpURLConnection.HTTP_CREATED);
        setupConnection(HttpURLConnection.HTTP_CREATED, headers, "<h1>Hello World</h1>", null, "");

        try {
            manager.updateSession();
        } catch (TransmissionSessionManager.ManagerException e) {
            assertEquals(HttpURLConnection.HTTP_CREATED, e.getCode());
            assertEquals("no-json", e.getMessage());
        }

        headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        setupConnection(HttpURLConnection.HTTP_OK, headers, "{\"result\": \"success\", \"arguments\": {}}", null, "");

        manager.updateSession();

        headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        setupConnection(HttpURLConnection.HTTP_OK, headers, "{\"arguments\": \"{}\", \"result\": \"success\"}", null, "");

        manager.updateSession();

        final List<Boolean> tries = new ArrayList<>();
        connection.setConnectTest(new Runnable() {
            @Override
            public void run() {
                Map<String, String> headers = new HashMap<>();
                if ("sessid".equals(connection.requestProperties.get("X-Transmission-Session-Id"))) {
                    headers.put("Content-Type", "application/json");
                    setupConnection(HttpURLConnection.HTTP_OK, headers, "{\"arguments\": \"{}\", \"result\": \"success\"}", null, "");
                    tries.add(true);
                } else {
                    headers.put("X-Transmission-Session-Id", "sessid");
                    headers.put("Content-Type", "text/html");
                    setupConnection(HttpURLConnection.HTTP_CONFLICT, headers, "blabla", null, "");
                    tries.add(false);
                }
            }
        });
        manager.updateSession();

        assertEquals(2, tries.size());
        assertFalse(tries.get(0));
        assertTrue(tries.get(1));

        headers = new HashMap<>();
        connection.setConnectTest(null);
        setupConnection(HttpURLConnection.HTTP_OK, headers, "error", null, "");

        try {
            manager.updateSession();
        } catch (TransmissionSessionManager.ManagerException e) {
            assertEquals(-4, e.getCode());
        }

        when(connMananager.getActiveNetworkInfo()).thenReturn(null);
        headers.put("Content-Type", "application/json");
        setupConnection(HttpURLConnection.HTTP_OK, headers, "{\"arguments\": \"{}\", \"result\": \"success\"}", null, "");

        try {
            manager.updateSession();
        } catch (TransmissionSessionManager.ManagerException e) {
            assertEquals("connectivity", e.getMessage());
            assertEquals(-1, e.getCode());
        }
        when(connMananager.getActiveNetworkInfo()).thenReturn(mock(NetworkInfo.class));
    }

    @Test public void delayedNetwork() throws Exception {
        connection = new DelayedHttpsURLConnectionTest(new URL("http://example.com"));
        connection.outputStream = new ByteArrayOutputStream();
        ((DelayedHttpsURLConnectionTest) connection).setDelay(2);
        when(connProvider.open(profile)).thenReturn(connection);

        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        setupConnection(HttpURLConnection.HTTP_OK, headers, "{\"arguments\": \"{}\", \"result\": \"success\"}", null, "");

        boolean timeout = false;
        try {
            manager.updateSession();
        } catch (TransmissionSessionManager.ManagerException e) {
            assertEquals("timeout", e.getMessage());
            assertEquals(-1, e.getCode());
            timeout = true;
        }
        assertTrue(timeout);
    }

    @Test public void sslNetwork() throws Exception {
        when(profile.isUseSSL()).thenReturn(true);

        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        setupConnection(HttpURLConnection.HTTP_OK, headers, "{\"arguments\": \"{}\", \"result\": \"success\"}", null, "");

        manager.updateSession();

        assertTrue(connection.socketFactory instanceof SSLSocketFactory);
        assertTrue(connection.hostnameVerifier instanceof HostnameVerifier);

        assertTrue(connection.hostnameVerifier.verify(null, null));

        when(profile.getUsername()).thenReturn("foo");
        when(profile.getPassword()).thenReturn("bar");

        setupConnection(HttpURLConnection.HTTP_OK, headers, "{\"arguments\": \"{}\", \"result\": \"success\"}", null, "");

        manager.updateSession();

        assertTrue(connection.requestProperties.containsKey("Authorization"));
        assertEquals("Basic " + Base64.encodeToString(("foo:bar").getBytes(), Base64.DEFAULT),
            connection.requestProperties.get("Authorization"));
    }

    private void setupConnection(int responseCode, Map<String, String> headerFields,
                                 String response, String contentEncoding, String responseMessage) {
        connection.responseCode = responseCode;
        connection.contentEncoding = contentEncoding;
        connection.responseMessage = responseMessage;

        InputStream is = new ByteArrayInputStream(Charset.forName("UTF-16").encode(response).array());
        connection.inputStream = is;

        if (headerFields != null) {
            connection.headerFields.putAll(headerFields);
        }
    }

    private static class HttpsURLConnectionTest extends HttpsURLConnection {
        public int connectCount = 0;
        public boolean isConnected;
        public int readTimeout;
        public int connectTimeout;
        public Map<String, String> requestProperties = new HashMap<>();
        public boolean useCaches;
        public boolean allowUserInteraction;
        public String requestMethod = "GET";
        public boolean doInput;
        public boolean doOutput;

        public SSLSocketFactory socketFactory;
        public HostnameVerifier hostnameVerifier;

        public OutputStream outputStream;
        public InputStream inputStream;
        public int responseCode;
        public Map<String, String> headerFields = new HashMap<>();
        public String contentEncoding;
        public String responseMessage;

        public Runnable connectRunnable;

        public HttpsURLConnectionTest(URL url) {
            super(url);
        }

        public void setConnectTest(Runnable connectTest) {
            this.connectRunnable = connectTest;
        }

        @Override public String getCipherSuite() {
            return null;
        }

        @Override public Certificate[] getLocalCertificates() {
            return new Certificate[0];
        }

        @Override public Certificate[] getServerCertificates() throws SSLPeerUnverifiedException {
            return new Certificate[0];
        }

        @Override public void disconnect() { isConnected = false; }

        @Override public boolean usingProxy() { return false; }

        @Override public void connect() throws IOException {
            if (connectRunnable != null) {
                connectRunnable.run();
            }
            isConnected = true;
            connectCount++;
        }

        @Override public void setReadTimeout(int timeout) { readTimeout = timeout; }

        @Override public void setConnectTimeout(int connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        @Override public void setRequestProperty(String key, String value) {
            requestProperties.put(key, value);
        }

        @Override public void setUseCaches(boolean useCaches) {
            this.useCaches = useCaches;
        }

        @Override public void setAllowUserInteraction(boolean allowUserInteraction) {
            this.allowUserInteraction = allowUserInteraction;
        }

        @Override public void setRequestMethod(String requestMethod) {
            this.requestMethod = requestMethod;
        }

        @Override public void setDoInput(boolean doInput) {
            this.doInput = doInput;
        }

        @Override public void setDoOutput(boolean doOutput) {
            this.doOutput = doOutput;
        }

        @Override public void setSSLSocketFactory(SSLSocketFactory sf) {
            socketFactory = sf;
        }

        @Override public void setHostnameVerifier(HostnameVerifier hv) {
            hostnameVerifier = hv;
        }

        @Override public OutputStream getOutputStream() {
            return outputStream;
        }

        @Override public int getResponseCode() {
            return responseCode;
        }

        @Override public InputStream getInputStream() {
            return inputStream;
        }

        @Override public String getHeaderField(String key) {
            return headerFields.get(key);
        }

        @Override public String getContentEncoding() {
            return contentEncoding;
        }

        @Override public String getResponseMessage() {
            return responseMessage;
        }
    }

    private static class DelayedHttpsURLConnectionTest extends HttpsURLConnectionTest {
        private int delay;

        public DelayedHttpsURLConnectionTest(URL url) {
            super(url);
        }

        public void setDelay(int delay) {
            this.delay = delay;
        }

        @Override public void connect() throws IOException {
            if (delay * 1000 > connectTimeout) {
                throw new SocketTimeoutException();
            }
            super.connect();
        }
    }
}
