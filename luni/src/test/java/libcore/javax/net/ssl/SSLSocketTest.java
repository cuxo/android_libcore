/*
 * Copyright (C) 2010 The Android Open Source Project
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

package libcore.javax.net.ssl;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.Thread.UncaughtExceptionHandler;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ServerSocketFactory;
import javax.net.ssl.HandshakeCompletedEvent;
import javax.net.ssl.HandshakeCompletedListener;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLProtocolException;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;
import junit.framework.TestCase;
import libcore.io.IoUtils;
import libcore.io.Streams;
import libcore.java.security.StandardNames;
import libcore.java.security.TestKeyStore;

public class SSLSocketTest extends TestCase {

    public void test_SSLSocket_defaultConfiguration() throws Exception {
        SSLDefaultConfigurationAsserts.assertSSLSocket(
                (SSLSocket) SSLSocketFactory.getDefault().createSocket());
    }

    public void test_SSLSocket_getSupportedCipherSuites_returnsCopies() throws Exception {
        SSLSocketFactory sf = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket ssl = (SSLSocket) sf.createSocket();
        assertNotSame(ssl.getSupportedCipherSuites(), ssl.getSupportedCipherSuites());
    }

    public void test_SSLSocket_getSupportedCipherSuites_connect() throws Exception {
        // note the rare usage of non-RSA keys
        TestKeyStore testKeyStore = new TestKeyStore.Builder()
                .keyAlgorithms("RSA", "DSA", "EC", "EC_RSA")
                .aliasPrefix("rsa-dsa-ec")
                .ca(true)
                .build();
        StringBuilder error = new StringBuilder();
        test_SSLSocket_getSupportedCipherSuites_connect(testKeyStore, error);
        if (error.length() > 0) {
            throw new Exception("One or more problems in "
                    + "test_SSLSocket_getSupportedCipherSuites_connect:\n" + error);
        }
    }
    private void test_SSLSocket_getSupportedCipherSuites_connect(TestKeyStore testKeyStore,
                                                                 StringBuilder error)
            throws Exception {

        String clientToServerString = "this is sent from the client to the server...";
        String serverToClientString = "... and this from the server to the client";
        byte[] clientToServer = clientToServerString.getBytes();
        byte[] serverToClient = serverToClientString.getBytes();

        KeyManager pskKeyManager = PSKKeyManagerProxy.getConscryptPSKKeyManager(
                new PSKKeyManagerProxy() {
            @Override
            protected SecretKey getKey(String identityHint, String identity, Socket socket) {
                return new SecretKeySpec("Just an arbitrary key".getBytes(), "RAW");
            }
        });
        TestSSLContext c = TestSSLContext.createWithAdditionalKeyManagers(
                testKeyStore, testKeyStore,
                new KeyManager[] {pskKeyManager}, new KeyManager[] {pskKeyManager});

        String[] cipherSuites = c.clientContext.getSocketFactory().getSupportedCipherSuites();

        for (String cipherSuite : cipherSuites) {
            boolean errorExpected = StandardNames.IS_RI && cipherSuite.endsWith("_SHA256");
            try {
                /*
                 * TLS_EMPTY_RENEGOTIATION_INFO_SCSV cannot be used on
                 * its own, but instead in conjunction with other
                 * cipher suites.
                 */
                if (cipherSuite.equals(StandardNames.CIPHER_SUITE_SECURE_RENEGOTIATION)) {
                    continue;
                }
                /*
                 * Similarly with the TLS_FALLBACK_SCSV suite, it is not
                 * a selectable suite, but is used in conjunction with
                 * other cipher suites.
                 */
                if (cipherSuite.equals(StandardNames.CIPHER_SUITE_FALLBACK)) {
                    continue;
                }
                /*
                 * Kerberos cipher suites require external setup. See "Kerberos Requirements" in
                 * https://java.sun.com/j2se/1.5.0/docs/guide/security/jsse/JSSERefGuide.html
                 * #KRBRequire
                 */
                if (cipherSuite.startsWith("TLS_KRB5_")) {
                    continue;
                }

                String[] clientCipherSuiteArray = new String[] {
                        cipherSuite,
                        StandardNames.CIPHER_SUITE_SECURE_RENEGOTIATION };
                String[] serverCipherSuiteArray = clientCipherSuiteArray;
                SSLSocket[] pair = TestSSLSocketPair.connect(c,
                                                             clientCipherSuiteArray,
                                                             serverCipherSuiteArray);

                SSLSocket server = pair[0];
                SSLSocket client = pair[1];

                // Check that the client can read the message sent by the server
                server.getOutputStream().write(serverToClient);
                byte[] clientFromServer = new byte[serverToClient.length];
                Streams.readFully(client.getInputStream(), clientFromServer);
                assertEquals(serverToClientString, new String(clientFromServer));

                // Check that the server can read the message sent by the client
                client.getOutputStream().write(clientToServer);
                byte[] serverFromClient = new byte[clientToServer.length];
                Streams.readFully(server.getInputStream(), serverFromClient);
                assertEquals(clientToServerString, new String(serverFromClient));

                // Check that the server and the client cannot read anything else
                // (reads should time out)
                server.setSoTimeout(10);
                try {
                  server.getInputStream().read();
                  fail();
                } catch (IOException expected) {}
                client.setSoTimeout(10);
                try {
                  client.getInputStream().read();
                  fail();
                } catch (IOException expected) {}

                client.close();
                server.close();
                assertFalse(errorExpected);
            } catch (Exception maybeExpected) {
                if (!errorExpected) {
                    String message = ("Problem trying to connect cipher suite " + cipherSuite);
                    System.out.println(message);
                    maybeExpected.printStackTrace();
                    error.append(message);
                    error.append('\n');
                }
            }
        }
        c.close();
    }

    public void test_SSLSocket_getEnabledCipherSuites_returnsCopies() throws Exception {
        SSLSocketFactory sf = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket ssl = (SSLSocket) sf.createSocket();
        assertNotSame(ssl.getEnabledCipherSuites(), ssl.getEnabledCipherSuites());
    }

    public void test_SSLSocket_setEnabledCipherSuites_storesCopy() throws Exception {
        SSLSocketFactory sf = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket ssl = (SSLSocket) sf.createSocket();
        String[] array = new String[] {ssl.getEnabledCipherSuites()[0]};
        String originalFirstElement = array[0];
        ssl.setEnabledCipherSuites(array);
        array[0] = "Modified after having been set";
        assertEquals(originalFirstElement, ssl.getEnabledCipherSuites()[0]);
    }

    public void test_SSLSocket_setEnabledCipherSuites() throws Exception {
        SSLSocketFactory sf = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket ssl = (SSLSocket) sf.createSocket();

        try {
            ssl.setEnabledCipherSuites(null);
            fail();
        } catch (IllegalArgumentException expected) {
        }
        try {
            ssl.setEnabledCipherSuites(new String[1]);
            fail();
        } catch (IllegalArgumentException expected) {
        }
        try {
            ssl.setEnabledCipherSuites(new String[] { "Bogus" } );
            fail();
        } catch (IllegalArgumentException expected) {
        }

        ssl.setEnabledCipherSuites(new String[0]);
        ssl.setEnabledCipherSuites(ssl.getEnabledCipherSuites());
        ssl.setEnabledCipherSuites(ssl.getSupportedCipherSuites());

        // Check that setEnabledCipherSuites affects getEnabledCipherSuites
        String[] cipherSuites = new String[] { ssl.getSupportedCipherSuites()[0] };
        ssl.setEnabledCipherSuites(cipherSuites);
        assertEquals(Arrays.asList(cipherSuites), Arrays.asList(ssl.getEnabledCipherSuites()));
    }

    public void test_SSLSocket_getSupportedProtocols_returnsCopies() throws Exception {
        SSLSocketFactory sf = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket ssl = (SSLSocket) sf.createSocket();
        assertNotSame(ssl.getSupportedProtocols(), ssl.getSupportedProtocols());
    }

    public void test_SSLSocket_getEnabledProtocols_returnsCopies() throws Exception {
        SSLSocketFactory sf = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket ssl = (SSLSocket) sf.createSocket();
        assertNotSame(ssl.getEnabledProtocols(), ssl.getEnabledProtocols());
    }

    public void test_SSLSocket_setEnabledProtocols_storesCopy() throws Exception {
        SSLSocketFactory sf = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket ssl = (SSLSocket) sf.createSocket();
        String[] array = new String[] {ssl.getEnabledProtocols()[0]};
        String originalFirstElement = array[0];
        ssl.setEnabledProtocols(array);
        array[0] = "Modified after having been set";
        assertEquals(originalFirstElement, ssl.getEnabledProtocols()[0]);
    }

    public void test_SSLSocket_setEnabledProtocols() throws Exception {
        SSLSocketFactory sf = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket ssl = (SSLSocket) sf.createSocket();

        try {
            ssl.setEnabledProtocols(null);
            fail();
        } catch (IllegalArgumentException expected) {
        }
        try {
            ssl.setEnabledProtocols(new String[1]);
            fail();
        } catch (IllegalArgumentException expected) {
        }
        try {
            ssl.setEnabledProtocols(new String[] { "Bogus" } );
            fail();
        } catch (IllegalArgumentException expected) {
        }
        ssl.setEnabledProtocols(new String[0]);
        ssl.setEnabledProtocols(ssl.getEnabledProtocols());
        ssl.setEnabledProtocols(ssl.getSupportedProtocols());

        // Check that setEnabledProtocols affects getEnabledProtocols
        for (String protocol : ssl.getSupportedProtocols()) {
            if ("SSLv2Hello".equals(protocol)) {
                try {
                    ssl.setEnabledProtocols(new String[] { protocol });
                    fail("Should fail when SSLv2Hello is set by itself");
                } catch (IllegalArgumentException expected) {}
            } else {
                String[] protocols = new String[] { protocol };
                ssl.setEnabledProtocols(protocols);
                assertEquals(Arrays.deepToString(protocols),
                        Arrays.deepToString(ssl.getEnabledProtocols()));
            }
        }
    }

    public void test_SSLSocket_getSession() throws Exception {
        SSLSocketFactory sf = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket ssl = (SSLSocket) sf.createSocket();
        SSLSession session = ssl.getSession();
        assertNotNull(session);
        assertFalse(session.isValid());
    }

    public void test_SSLSocket_startHandshake() throws Exception {
        final TestSSLContext c = TestSSLContext.create();
        SSLSocket client = (SSLSocket) c.clientContext.getSocketFactory().createSocket(c.host,
                                                                                       c.port);
        final SSLSocket server = (SSLSocket) c.serverSocket.accept();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Void> future = executor.submit(new Callable<Void>() {
            @Override public Void call() throws Exception {
                server.startHandshake();
                assertNotNull(server.getSession());
                try {
                    server.getSession().getPeerCertificates();
                    fail();
                } catch (SSLPeerUnverifiedException expected) {
                }
                Certificate[] localCertificates = server.getSession().getLocalCertificates();
                assertNotNull(localCertificates);
                TestKeyStore.assertChainLength(localCertificates);
                assertNotNull(localCertificates[0]);
                TestSSLContext.assertServerCertificateChain(c.serverTrustManager,
                                                            localCertificates);
                TestSSLContext.assertCertificateInKeyStore(localCertificates[0],
                                                           c.serverKeyStore);
                return null;
            }
        });
        executor.shutdown();
        client.startHandshake();
        assertNotNull(client.getSession());
        assertNull(client.getSession().getLocalCertificates());
        Certificate[] peerCertificates = client.getSession().getPeerCertificates();
        assertNotNull(peerCertificates);
        TestKeyStore.assertChainLength(peerCertificates);
        assertNotNull(peerCertificates[0]);
        TestSSLContext.assertServerCertificateChain(c.clientTrustManager,
                                                    peerCertificates);
        TestSSLContext.assertCertificateInKeyStore(peerCertificates[0], c.serverKeyStore);
        future.get();
        client.close();
        server.close();
        c.close();
    }

    private static final class SSLServerSessionIdCallable implements Callable<byte[]> {
        private final SSLSocket server;
        private SSLServerSessionIdCallable(SSLSocket server) {
            this.server = server;
        }
        @Override public byte[] call() throws Exception {
            server.startHandshake();
            assertNotNull(server.getSession());
            assertNotNull(server.getSession().getId());
            return server.getSession().getId();
        }
    }

    public void test_SSLSocket_confirmSessionReuse() throws Exception {
        final TestSSLContext c = TestSSLContext.create();
        final ExecutorService executor = Executors.newSingleThreadExecutor();

        final SSLSocket client1 = (SSLSocket) c.clientContext.getSocketFactory().createSocket(c.host,
                                                                                       c.port);
        final SSLSocket server1 = (SSLSocket) c.serverSocket.accept();
        final Future<byte[]> future1 = executor.submit(new SSLServerSessionIdCallable(server1));
        client1.startHandshake();
        assertNotNull(client1.getSession());
        assertNotNull(client1.getSession().getId());
        final byte[] clientSessionId1 = client1.getSession().getId();
        final byte[] serverSessionId1 = future1.get();
        assertTrue(Arrays.equals(clientSessionId1, serverSessionId1));
        client1.close();
        server1.close();

        final SSLSocket client2 = (SSLSocket) c.clientContext.getSocketFactory().createSocket(c.host,
                                                                                       c.port);
        final SSLSocket server2 = (SSLSocket) c.serverSocket.accept();
        final Future<byte[]> future2 = executor.submit(new SSLServerSessionIdCallable(server2));
        client2.startHandshake();
        assertNotNull(client2.getSession());
        assertNotNull(client2.getSession().getId());
        final byte[] clientSessionId2 = client2.getSession().getId();
        final byte[] serverSessionId2 = future2.get();
        assertTrue(Arrays.equals(clientSessionId2, serverSessionId2));
        client2.close();
        server2.close();

        assertTrue(Arrays.equals(clientSessionId1, clientSessionId2));

        executor.shutdown();
        c.close();
    }

    public void test_SSLSocket_startHandshake_noKeyStore() throws Exception {
        TestSSLContext c = TestSSLContext.create(null, null, null, null, null, null, null, null,
                                                 SSLContext.getDefault(), SSLContext.getDefault());
        SSLSocket client = (SSLSocket) c.clientContext.getSocketFactory().createSocket(c.host,
                                                                                       c.port);
        final SSLSocket server = (SSLSocket) c.serverSocket.accept();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Void> future = executor.submit(new Callable<Void>() {
            @Override public Void call() throws Exception {
                try {
                    server.startHandshake();
                    fail();
                } catch (SSLHandshakeException expected) {
                }
                return null;
            }
        });
        executor.shutdown();
        try {
            client.startHandshake();
            fail();
        } catch (SSLHandshakeException expected) {
        }
        future.get();
        server.close();
        client.close();
        c.close();
    }

    public void test_SSLSocket_startHandshake_noClientCertificate() throws Exception {
        TestSSLContext c = TestSSLContext.create();
        SSLContext serverContext = c.serverContext;
        SSLContext clientContext = c.clientContext;
        SSLSocket client = (SSLSocket)
            clientContext.getSocketFactory().createSocket(c.host, c.port);
        final SSLSocket server = (SSLSocket) c.serverSocket.accept();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Void> future = executor.submit(new Callable<Void>() {
            @Override public Void call() throws Exception {
                server.startHandshake();
                return null;
            }
        });
        executor.shutdown();
        client.startHandshake();
        future.get();
        client.close();
        server.close();
        c.close();
    }

    public void test_SSLSocket_HandshakeCompletedListener() throws Exception {
        final TestSSLContext c = TestSSLContext.create();
        final SSLSocket client = (SSLSocket)
                c.clientContext.getSocketFactory().createSocket(c.host, c.port);
        final SSLSocket server = (SSLSocket) c.serverSocket.accept();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Void> future = executor.submit(new Callable<Void>() {
            @Override public Void call() throws Exception {
                server.startHandshake();
                return null;
            }
        });
        executor.shutdown();
        final boolean[] handshakeCompletedListenerCalled = new boolean[1];
        client.addHandshakeCompletedListener(new HandshakeCompletedListener() {
            public void handshakeCompleted(HandshakeCompletedEvent event) {
                try {
                    SSLSession session = event.getSession();
                    String cipherSuite = event.getCipherSuite();
                    Certificate[] localCertificates = event.getLocalCertificates();
                    Certificate[] peerCertificates = event.getPeerCertificates();
                    javax.security.cert.X509Certificate[] peerCertificateChain
                            = event.getPeerCertificateChain();
                    Principal peerPrincipal = event.getPeerPrincipal();
                    Principal localPrincipal = event.getLocalPrincipal();
                    Socket socket = event.getSocket();

                    if (false) {
                        System.out.println("Session=" + session);
                        System.out.println("CipherSuite=" + cipherSuite);
                        System.out.println("LocalCertificates="
                                + Arrays.toString(localCertificates));
                        System.out.println("PeerCertificates="
                                + Arrays.toString(peerCertificates));
                        System.out.println("PeerCertificateChain="
                                + Arrays.toString(peerCertificateChain));
                        System.out.println("PeerPrincipal=" + peerPrincipal);
                        System.out.println("LocalPrincipal=" + localPrincipal);
                        System.out.println("Socket=" + socket);
                    }

                    assertNotNull(session);
                    byte[] id = session.getId();
                    assertNotNull(id);
                    assertEquals(32, id.length);
                    assertNotNull(c.clientContext.getClientSessionContext().getSession(id));

                    assertNotNull(cipherSuite);
                    assertTrue(Arrays.asList(
                            client.getEnabledCipherSuites()).contains(cipherSuite));
                    assertTrue(Arrays.asList(
                            c.serverSocket.getEnabledCipherSuites()).contains(cipherSuite));

                    assertNull(localCertificates);

                    assertNotNull(peerCertificates);
                    TestKeyStore.assertChainLength(peerCertificates);
                    assertNotNull(peerCertificates[0]);
                    TestSSLContext.assertServerCertificateChain(c.clientTrustManager,
                                                                peerCertificates);
                    TestSSLContext.assertCertificateInKeyStore(peerCertificates[0],
                                                               c.serverKeyStore);

                    assertNotNull(peerCertificateChain);
                    TestKeyStore.assertChainLength(peerCertificateChain);
                    assertNotNull(peerCertificateChain[0]);
                    TestSSLContext.assertCertificateInKeyStore(
                        peerCertificateChain[0].getSubjectDN(), c.serverKeyStore);

                    assertNotNull(peerPrincipal);
                    TestSSLContext.assertCertificateInKeyStore(peerPrincipal, c.serverKeyStore);

                    assertNull(localPrincipal);

                    assertNotNull(socket);
                    assertSame(client, socket);

                    assertTrue(socket instanceof SSLSocket);

                    synchronized (handshakeCompletedListenerCalled) {
                        handshakeCompletedListenerCalled[0] = true;
                        handshakeCompletedListenerCalled.notify();
                    }
                    handshakeCompletedListenerCalled[0] = true;
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });
        client.startHandshake();
        future.get();
        if (!TestSSLContext.sslServerSocketSupportsSessionTickets()) {
            assertNotNull(c.serverContext.getServerSessionContext().getSession(
                    client.getSession().getId()));
        }
        synchronized (handshakeCompletedListenerCalled) {
            while (!handshakeCompletedListenerCalled[0]) {
                handshakeCompletedListenerCalled.wait();
            }
        }
        client.close();
        server.close();
        c.close();
    }

    private static final class TestUncaughtExceptionHandler implements UncaughtExceptionHandler {
        Throwable actualException;
        @Override public void uncaughtException(Thread thread, Throwable ex) {
            assertNull(actualException);
            actualException = ex;
        }
    }

    public void test_SSLSocket_HandshakeCompletedListener_RuntimeException() throws Exception {
        final Thread self = Thread.currentThread();
        final UncaughtExceptionHandler original = self.getUncaughtExceptionHandler();

        final RuntimeException expectedException = new RuntimeException("expected");
        final TestUncaughtExceptionHandler test = new TestUncaughtExceptionHandler();
        self.setUncaughtExceptionHandler(test);

        final TestSSLContext c = TestSSLContext.create();
        final SSLSocket client = (SSLSocket)
                c.clientContext.getSocketFactory().createSocket(c.host, c.port);
        final SSLSocket server = (SSLSocket) c.serverSocket.accept();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Void> future = executor.submit(new Callable<Void>() {
            @Override public Void call() throws Exception {
                server.startHandshake();
                return null;
            }
        });
        executor.shutdown();
        client.addHandshakeCompletedListener(new HandshakeCompletedListener() {
            public void handshakeCompleted(HandshakeCompletedEvent event) {
                throw expectedException;
            }
        });
        client.startHandshake();
        future.get();
        client.close();
        server.close();
        c.close();

        assertSame(expectedException, test.actualException);
        self.setUncaughtExceptionHandler(original);
    }

    public void test_SSLSocket_getUseClientMode() throws Exception {
        TestSSLContext c = TestSSLContext.create();
        SSLSocket client = (SSLSocket) c.clientContext.getSocketFactory().createSocket(c.host,
                                                                                       c.port);
        SSLSocket server = (SSLSocket) c.serverSocket.accept();
        assertTrue(client.getUseClientMode());
        assertFalse(server.getUseClientMode());
        client.close();
        server.close();
        c.close();
    }

    public void test_SSLSocket_setUseClientMode() throws Exception {
        // client is client, server is server
        test_SSLSocket_setUseClientMode(true, false);
        // client is server, server is client
        test_SSLSocket_setUseClientMode(true, false);
        // both are client
        try {
            test_SSLSocket_setUseClientMode(true, true);
            fail();
        } catch (SSLProtocolException expected) {
            assertTrue(StandardNames.IS_RI);
        } catch (SSLHandshakeException expected) {
            assertFalse(StandardNames.IS_RI);
        }

        // both are server
        try {
            test_SSLSocket_setUseClientMode(false, false);
            fail();
        } catch (SocketTimeoutException expected) {
        }
    }

    private void test_SSLSocket_setUseClientMode(final boolean clientClientMode,
                                                 final boolean serverClientMode)
            throws Exception {
        TestSSLContext c = TestSSLContext.create();
        SSLSocket client = (SSLSocket) c.clientContext.getSocketFactory().createSocket(c.host,
                                                                                       c.port);
        final SSLSocket server = (SSLSocket) c.serverSocket.accept();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<IOException> future = executor.submit(new Callable<IOException>() {
            @Override public IOException call() throws Exception {
                try {
                    if (!serverClientMode) {
                        server.setSoTimeout(1 * 1000);
                    }
                    server.setUseClientMode(serverClientMode);
                    server.startHandshake();
                    return null;
                } catch (SSLHandshakeException e) {
                    return e;
                } catch (SocketTimeoutException e) {
                    return e;
                }
            }
        });
        executor.shutdown();
        if (!clientClientMode) {
            client.setSoTimeout(1 * 1000);
        }
        client.setUseClientMode(clientClientMode);
        client.startHandshake();
        IOException ioe = future.get();
        if (ioe != null) {
            throw ioe;
        }
        client.close();
        server.close();
        c.close();
    }

    public void test_SSLSocket_setUseClientMode_afterHandshake() throws Exception {

        // can't set after handshake
        TestSSLSocketPair pair = TestSSLSocketPair.create();
        try {
            pair.server.setUseClientMode(false);
            fail();
        } catch (IllegalArgumentException expected) {
        }
        try {
            pair.client.setUseClientMode(false);
            fail();
        } catch (IllegalArgumentException expected) {
        }
    }

    public void test_SSLSocket_untrustedServer() throws Exception {
        TestSSLContext c = TestSSLContext.create(TestKeyStore.getClientCA2(),
                                                 TestKeyStore.getServer());
        SSLSocket client = (SSLSocket) c.clientContext.getSocketFactory().createSocket(c.host,
                                                                                       c.port);
        final SSLSocket server = (SSLSocket) c.serverSocket.accept();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Void> future = executor.submit(new Callable<Void>() {
            @Override public Void call() throws Exception {
                try {
                    server.startHandshake();
                    fail();
                } catch (SSLHandshakeException expected) {
                }
                return null;
            }
        });
        executor.shutdown();
        try {
            client.startHandshake();
            fail();
        } catch (SSLHandshakeException expected) {
            assertTrue(expected.getCause() instanceof CertificateException);
        }
        future.get();
        client.close();
        server.close();
        c.close();
    }

    public void test_SSLSocket_clientAuth() throws Exception {
        TestSSLContext c = TestSSLContext.create(TestKeyStore.getClientCertificate(),
                                                 TestKeyStore.getServer());
        SSLSocket client = (SSLSocket) c.clientContext.getSocketFactory().createSocket(c.host,
                                                                                       c.port);
        final SSLSocket server = (SSLSocket) c.serverSocket.accept();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Void> future = executor.submit(new Callable<Void>() {
            @Override public Void call() throws Exception {
                assertFalse(server.getWantClientAuth());
                assertFalse(server.getNeedClientAuth());

                // confirm turning one on by itself
                server.setWantClientAuth(true);
                assertTrue(server.getWantClientAuth());
                assertFalse(server.getNeedClientAuth());

                // confirm turning setting on toggles the other
                server.setNeedClientAuth(true);
                assertFalse(server.getWantClientAuth());
                assertTrue(server.getNeedClientAuth());

                // confirm toggling back
                server.setWantClientAuth(true);
                assertTrue(server.getWantClientAuth());
                assertFalse(server.getNeedClientAuth());

                server.startHandshake();
                return null;
            }
        });
        executor.shutdown();
        client.startHandshake();
        assertNotNull(client.getSession().getLocalCertificates());
        TestKeyStore.assertChainLength(client.getSession().getLocalCertificates());
        TestSSLContext.assertClientCertificateChain(c.clientTrustManager,
                                                    client.getSession().getLocalCertificates());
        future.get();
        client.close();
        server.close();
        c.close();
    }

    public void test_SSLSocket_clientAuth_bogusAlias() throws Exception {
        TestSSLContext c = TestSSLContext.create();
        SSLContext clientContext = SSLContext.getInstance("TLS");
        X509KeyManager keyManager = new X509KeyManager() {
            @Override public String chooseClientAlias(String[] keyType,
                                                      Principal[] issuers,
                                                      Socket socket) {
                return "bogus";
            }
            @Override public String chooseServerAlias(String keyType,
                                                      Principal[] issuers,
                                                      Socket socket) {
                throw new AssertionError();
            }
            @Override public X509Certificate[] getCertificateChain(String alias) {
                // return null for "bogus" alias
                return null;
            }
            @Override public String[] getClientAliases(String keyType, Principal[] issuers) {
                throw new AssertionError();
            }
            @Override public String[] getServerAliases(String keyType, Principal[] issuers) {
                throw new AssertionError();
            }
            @Override public PrivateKey getPrivateKey(String alias) {
                // return null for "bogus" alias
                return null;
            }
        };
        clientContext.init(new KeyManager[] { keyManager },
                           new TrustManager[] { c.clientTrustManager },
                           null);
        SSLSocket client = (SSLSocket) clientContext.getSocketFactory().createSocket(c.host,
                                                                                     c.port);
        final SSLSocket server = (SSLSocket) c.serverSocket.accept();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Void> future = executor.submit(new Callable<Void>() {
            @Override public Void call() throws Exception {
                try {
                    server.setNeedClientAuth(true);
                    server.startHandshake();
                    fail();
                } catch (SSLHandshakeException expected) {
                }
                return null;
            }
        });

        executor.shutdown();
        try {
            client.startHandshake();
            fail();
        } catch (SSLHandshakeException expected) {
            // before we would get a NullPointerException from passing
            // due to the null PrivateKey return by the X509KeyManager.
        }
        future.get();
        client.close();
        server.close();
        c.close();
    }

    public void test_SSLSocket_TrustManagerRuntimeException() throws Exception {
        TestSSLContext c = TestSSLContext.create();
        SSLContext clientContext = SSLContext.getInstance("TLS");
        X509TrustManager trustManager = new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType)
                    throws CertificateException {
                throw new AssertionError();
            }
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType)
                    throws CertificateException {
                throw new RuntimeException();  // throw a RuntimeException from custom TrustManager
            }
            @Override public X509Certificate[] getAcceptedIssuers() {
                throw new AssertionError();
            }
        };
        clientContext.init(null, new TrustManager[] { trustManager }, null);
        SSLSocket client = (SSLSocket) clientContext.getSocketFactory().createSocket(c.host,
                                                                                     c.port);
        final SSLSocket server = (SSLSocket) c.serverSocket.accept();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Void> future = executor.submit(new Callable<Void>() {
            @Override public Void call() throws Exception {
                try {
                    server.startHandshake();
                    fail();
                } catch (SSLHandshakeException expected) {
                }
                return null;
            }
        });

        executor.shutdown();
        try {
            client.startHandshake();
            fail();
        } catch (SSLHandshakeException expected) {
            // before we would get a RuntimeException from checkServerTrusted.
        }
        future.get();
        client.close();
        server.close();
        c.close();
    }

    public void test_SSLSocket_getEnableSessionCreation() throws Exception {
        TestSSLContext c = TestSSLContext.create();
        SSLSocket client = (SSLSocket) c.clientContext.getSocketFactory().createSocket(c.host,
                                                                                       c.port);
        SSLSocket server = (SSLSocket) c.serverSocket.accept();
        assertTrue(client.getEnableSessionCreation());
        assertTrue(server.getEnableSessionCreation());
        client.close();
        server.close();
        c.close();
    }

    public void test_SSLSocket_setEnableSessionCreation_server() throws Exception {
        TestSSLContext c = TestSSLContext.create();
        SSLSocket client = (SSLSocket) c.clientContext.getSocketFactory().createSocket(c.host,
                                                                                       c.port);
        final SSLSocket server = (SSLSocket) c.serverSocket.accept();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Void> future = executor.submit(new Callable<Void>() {
            @Override public Void call() throws Exception {
                server.setEnableSessionCreation(false);
                try {
                    server.startHandshake();
                    fail();
                } catch (SSLException expected) {
                }
                return null;
            }
        });
        executor.shutdown();
        try {
            client.startHandshake();
            fail();
        } catch (SSLException expected) {
        }
        future.get();
        client.close();
        server.close();
        c.close();
    }

    public void test_SSLSocket_setEnableSessionCreation_client() throws Exception {
        TestSSLContext c = TestSSLContext.create();
        SSLSocket client = (SSLSocket) c.clientContext.getSocketFactory().createSocket(c.host,
                                                                                       c.port);
        final SSLSocket server = (SSLSocket) c.serverSocket.accept();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Void> future = executor.submit(new Callable<Void>() {
            @Override public Void call() throws Exception {
                try {
                    server.startHandshake();
                    fail();
                } catch (SSLException expected) {
                }
                return null;
            }
        });
        executor.shutdown();
        client.setEnableSessionCreation(false);
        try {
            client.startHandshake();
            fail();
        } catch (SSLException expected) {
        }
        future.get();
        client.close();
        server.close();
        c.close();
    }

    public void test_SSLSocket_getSSLParameters() throws Exception {
        SSLSocketFactory sf = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket ssl = (SSLSocket) sf.createSocket();

        SSLParameters p = ssl.getSSLParameters();
        assertNotNull(p);

        String[] cipherSuites = p.getCipherSuites();
        assertNotSame(cipherSuites, ssl.getEnabledCipherSuites());
        assertEquals(Arrays.asList(cipherSuites), Arrays.asList(ssl.getEnabledCipherSuites()));

        String[] protocols = p.getProtocols();
        assertNotSame(protocols, ssl.getEnabledProtocols());
        assertEquals(Arrays.asList(protocols), Arrays.asList(ssl.getEnabledProtocols()));

        assertEquals(p.getWantClientAuth(), ssl.getWantClientAuth());
        assertEquals(p.getNeedClientAuth(), ssl.getNeedClientAuth());
    }

    public void test_SSLSocket_setSSLParameters() throws Exception {
        SSLSocketFactory sf = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket ssl = (SSLSocket) sf.createSocket();
        String[] defaultCipherSuites = ssl.getEnabledCipherSuites();
        String[] defaultProtocols = ssl.getEnabledProtocols();
        String[] supportedCipherSuites = ssl.getSupportedCipherSuites();
        String[] supportedProtocols = ssl.getSupportedProtocols();

        {
            SSLParameters p = new SSLParameters();
            ssl.setSSLParameters(p);
            assertEquals(Arrays.asList(defaultCipherSuites),
                         Arrays.asList(ssl.getEnabledCipherSuites()));
            assertEquals(Arrays.asList(defaultProtocols),
                         Arrays.asList(ssl.getEnabledProtocols()));
        }

        {
            SSLParameters p = new SSLParameters(supportedCipherSuites,
                                                supportedProtocols);
            ssl.setSSLParameters(p);
            assertEquals(Arrays.asList(supportedCipherSuites),
                         Arrays.asList(ssl.getEnabledCipherSuites()));
            assertEquals(Arrays.asList(supportedProtocols),
                         Arrays.asList(ssl.getEnabledProtocols()));
        }
        {
            SSLParameters p = new SSLParameters();

            p.setNeedClientAuth(true);
            assertFalse(ssl.getNeedClientAuth());
            assertFalse(ssl.getWantClientAuth());
            ssl.setSSLParameters(p);
            assertTrue(ssl.getNeedClientAuth());
            assertFalse(ssl.getWantClientAuth());

            p.setWantClientAuth(true);
            assertTrue(ssl.getNeedClientAuth());
            assertFalse(ssl.getWantClientAuth());
            ssl.setSSLParameters(p);
            assertFalse(ssl.getNeedClientAuth());
            assertTrue(ssl.getWantClientAuth());

            p.setWantClientAuth(false);
            assertFalse(ssl.getNeedClientAuth());
            assertTrue(ssl.getWantClientAuth());
            ssl.setSSLParameters(p);
            assertFalse(ssl.getNeedClientAuth());
            assertFalse(ssl.getWantClientAuth());
        }
    }

    public void test_SSLSocket_close() throws Exception {
        TestSSLSocketPair pair = TestSSLSocketPair.create();
        SSLSocket server = pair.server;
        SSLSocket client = pair.client;
        assertFalse(server.isClosed());
        assertFalse(client.isClosed());
        InputStream input = client.getInputStream();
        OutputStream output = client.getOutputStream();
        server.close();
        client.close();
        assertTrue(server.isClosed());
        assertTrue(client.isClosed());

        // close after close is okay...
        server.close();
        client.close();

        // ...so are a lot of other operations...
        HandshakeCompletedListener l = new HandshakeCompletedListener () {
            public void handshakeCompleted(HandshakeCompletedEvent e) {}
        };
        client.addHandshakeCompletedListener(l);
        assertNotNull(client.getEnabledCipherSuites());
        assertNotNull(client.getEnabledProtocols());
        client.getEnableSessionCreation();
        client.getNeedClientAuth();
        assertNotNull(client.getSession());
        assertNotNull(client.getSSLParameters());
        assertNotNull(client.getSupportedProtocols());
        client.getUseClientMode();
        client.getWantClientAuth();
        client.removeHandshakeCompletedListener(l);
        client.setEnabledCipherSuites(new String[0]);
        client.setEnabledProtocols(new String[0]);
        client.setEnableSessionCreation(false);
        client.setNeedClientAuth(false);
        client.setSSLParameters(client.getSSLParameters());
        client.setWantClientAuth(false);

        // ...but some operations are expected to give SocketException...
        try {
            client.startHandshake();
            fail();
        } catch (SocketException expected) {
        }
        try {
            client.getInputStream();
            fail();
        } catch (SocketException expected) {
        }
        try {
            client.getOutputStream();
            fail();
        } catch (SocketException expected) {
        }
        try {
            input.read();
            fail();
        } catch (SocketException expected) {
        }
        try {
            input.read(null, -1, -1);
            fail();
        } catch (NullPointerException expected) {
            assertTrue(StandardNames.IS_RI);
        } catch (SocketException expected) {
            assertFalse(StandardNames.IS_RI);
        }
        try {
            output.write(-1);
            fail();
        } catch (SocketException expected) {
        }
        try {
            output.write(null, -1, -1);
            fail();
        } catch (NullPointerException expected) {
            assertTrue(StandardNames.IS_RI);
        } catch (SocketException expected) {
            assertFalse(StandardNames.IS_RI);
        }

        // ... and one gives IllegalArgumentException
        try {
            client.setUseClientMode(false);
            fail();
        } catch (IllegalArgumentException expected) {
        }

        pair.close();
    }

    /**
     * b/3350645 Test to confirm that an SSLSocket.close() performing
     * an SSL_shutdown does not throw an IOException if the peer
     * socket has been closed.
     */
    public void test_SSLSocket_shutdownCloseOnClosedPeer() throws Exception {
        TestSSLContext c = TestSSLContext.create();
        final Socket underlying = new Socket(c.host, c.port);
        final SSLSocket wrapping = (SSLSocket)
                c.clientContext.getSocketFactory().createSocket(underlying,
                                                                c.host.getHostName(),
                                                                c.port,
                                                                false);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Void> clientFuture = executor.submit(new Callable<Void>() {
            @Override public Void call() throws Exception {
                wrapping.startHandshake();
                wrapping.getOutputStream().write(42);
                // close the underlying socket,
                // so that no SSL shutdown is sent
                underlying.close();
                wrapping.close();
                return null;
            }
        });
        executor.shutdown();

        SSLSocket server = (SSLSocket) c.serverSocket.accept();
        server.startHandshake();
        server.getInputStream().read();
        // wait for thread to finish so we know client is closed.
        clientFuture.get();
        // close should cause an SSL_shutdown which will fail
        // because the peer has closed, but it shouldn't throw.
        server.close();
    }

    public void test_SSLSocket_setSoTimeout_basic() throws Exception {
        ServerSocket listening = new ServerSocket(0);

        Socket underlying = new Socket(listening.getInetAddress(), listening.getLocalPort());
        assertEquals(0, underlying.getSoTimeout());

        SSLSocketFactory sf = (SSLSocketFactory) SSLSocketFactory.getDefault();
        Socket wrapping = sf.createSocket(underlying, null, -1, false);
        assertEquals(0, wrapping.getSoTimeout());

        // setting wrapper sets underlying and ...
        int expectedTimeoutMillis = 1000;  // 10 was too small because it was affected by rounding
        wrapping.setSoTimeout(expectedTimeoutMillis);
        assertEquals(expectedTimeoutMillis, wrapping.getSoTimeout());
        assertEquals(expectedTimeoutMillis, underlying.getSoTimeout());

        // ... getting wrapper inspects underlying
        underlying.setSoTimeout(0);
        assertEquals(0, wrapping.getSoTimeout());
        assertEquals(0, underlying.getSoTimeout());
    }

    public void test_SSLSocket_setSoTimeout_wrapper() throws Exception {
        if (StandardNames.IS_RI) {
            // RI cannot handle this case
            return;
        }
        ServerSocket listening = new ServerSocket(0);

        // setSoTimeout applies to read, not connect, so connect first
        Socket underlying = new Socket(listening.getInetAddress(), listening.getLocalPort());
        Socket server = listening.accept();

        SSLSocketFactory sf = (SSLSocketFactory) SSLSocketFactory.getDefault();
        Socket clientWrapping = sf.createSocket(underlying, null, -1, false);

        underlying.setSoTimeout(1);
        try {
            clientWrapping.getInputStream().read();
            fail();
        } catch (SocketTimeoutException expected) {
        }

        clientWrapping.close();
        server.close();
        underlying.close();
        listening.close();
    }

    public void test_SSLSocket_setSoWriteTimeout() throws Exception {
        if (StandardNames.IS_RI) {
            // RI does not support write timeout on sockets
            return;
        }

        final TestSSLContext c = TestSSLContext.create();
        SSLSocket client = (SSLSocket) c.clientContext.getSocketFactory().createSocket();

        // Try to make the client SO_SNDBUF size as small as possible
        // (it can default to 512k or even megabytes).  Note that
        // socket(7) says that the kernel will double the request to
        // leave room for its own book keeping and that the minimal
        // value will be 2048. Also note that tcp(7) says the value
        // needs to be set before connect(2).
        int sendBufferSize = 1024;
        client.setSendBufferSize(sendBufferSize);
        sendBufferSize = client.getSendBufferSize();

        // In jb-mr2 it was found that we need to also set SO_RCVBUF
        // to a minimal size or the write would not block. While
        // tcp(2) says the value has to be set before listen(2), it
        // seems fine to set it before accept(2).
        final int recvBufferSize = 128;
        c.serverSocket.setReceiveBufferSize(recvBufferSize);

        client.connect(new InetSocketAddress(c.host, c.port));

        final SSLSocket server = (SSLSocket) c.serverSocket.accept();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Void> future = executor.submit(new Callable<Void>() {
            @Override public Void call() throws Exception {
                server.startHandshake();
                return null;
            }
        });
        executor.shutdown();
        client.startHandshake();

        // Reflection is used so this can compile on the RI
        String expectedClassName = "com.android.org.conscrypt.OpenSSLSocketImpl";
        Class actualClass = client.getClass();
        assertEquals(expectedClassName, actualClass.getName());
        Method setSoWriteTimeout = actualClass.getMethod("setSoWriteTimeout",
                                                         new Class[] { Integer.TYPE });
        setSoWriteTimeout.invoke(client, 1);


        try {
            // Add extra space to the write to exceed the send buffer
            // size and cause the write to block.
            final int extra = 1;
            client.getOutputStream().write(new byte[sendBufferSize + extra]);
            fail();
        } catch (SocketTimeoutException expected) {
        }

        future.get();
        client.close();
        server.close();
        c.close();
    }

    public void test_SSLSocket_reusedNpnSocket() throws Exception {
        if (StandardNames.IS_RI) {
            // RI does not support NPN/ALPN
            return;
        }

        byte[] npnProtocols = new byte[] {
                8, 'h', 't', 't', 'p', '/', '1', '.', '1'
        };

        final TestSSLContext c = TestSSLContext.create();
        SSLSocket client = (SSLSocket) c.clientContext.getSocketFactory().createSocket();

        // Reflection is used so this can compile on the RI
        String expectedClassName = "com.android.org.conscrypt.OpenSSLSocketImpl";
        Class<?> actualClass = client.getClass();
        assertEquals(expectedClassName, actualClass.getName());
        Method setNpnProtocols = actualClass.getMethod("setNpnProtocols", byte[].class);

        ExecutorService executor = Executors.newSingleThreadExecutor();

        // First connection with NPN set on client and server
        {
            setNpnProtocols.invoke(client, npnProtocols);
            client.connect(new InetSocketAddress(c.host, c.port));

            final SSLSocket server = (SSLSocket) c.serverSocket.accept();
            assertEquals(expectedClassName, server.getClass().getName());
            setNpnProtocols.invoke(server, npnProtocols);

            Future<Void> future = executor.submit(new Callable<Void>() {
                @Override
                public Void call() throws Exception {
                    server.startHandshake();
                    return null;
                }
            });
            client.startHandshake();

            future.get();
            client.close();
            server.close();
        }

        // Second connection with client NPN already set on the SSL context, but
        // without server NPN set.
        {
            SSLServerSocket serverSocket = (SSLServerSocket) c.serverContext
                    .getServerSocketFactory().createServerSocket(0);
            InetAddress host = InetAddress.getLocalHost();
            int port = serverSocket.getLocalPort();

            client = (SSLSocket) c.clientContext.getSocketFactory().createSocket();
            client.connect(new InetSocketAddress(host, port));

            final SSLSocket server = (SSLSocket) serverSocket.accept();

            Future<Void> future = executor.submit(new Callable<Void>() {
                @Override
                public Void call() throws Exception {
                    server.startHandshake();
                    return null;
                }
            });
            client.startHandshake();

            future.get();
            client.close();
            server.close();
            serverSocket.close();
        }

        c.close();
    }

    public void test_SSLSocket_interrupt() throws Exception {
        test_SSLSocket_interrupt_case(true, true);
        test_SSLSocket_interrupt_case(true, false);
        test_SSLSocket_interrupt_case(false, true);
        test_SSLSocket_interrupt_case(false, false);
    }

    private void test_SSLSocket_interrupt_case(boolean readUnderlying, boolean closeUnderlying)
            throws Exception {

        ServerSocket listening = new ServerSocket(0);

        Socket underlying = new Socket(listening.getInetAddress(), listening.getLocalPort());
        Socket server = listening.accept();

        SSLSocketFactory sf = (SSLSocketFactory) SSLSocketFactory.getDefault();
        Socket clientWrapping = sf.createSocket(underlying, null, -1, true);

        final Socket toRead = (readUnderlying) ? underlying : clientWrapping;
        final Socket toClose = (closeUnderlying) ? underlying : clientWrapping;

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Void> future = executor.submit(new Callable<Void>() {
            @Override public Void call() throws Exception {
                Thread.sleep(1 * 1000);
                toClose.close();
                return null;
            }
        });
        executor.shutdown();
        try {
            toRead.setSoTimeout(5 * 1000);
            toRead.getInputStream().read();
            fail();
        } catch (SocketTimeoutException e) {
            throw e;
        } catch (SocketException expected) {
        }
        future.get();

        server.close();
        underlying.close();
        listening.close();
    }

    /**
     * b/7014266 Test to confirm that an SSLSocket.close() on one
     * thread will interrupt another thread blocked reading on the same
     * socket.
     */
    public void test_SSLSocket_interrupt_read() throws Exception {
        TestSSLContext c = TestSSLContext.create();
        final Socket underlying = new Socket(c.host, c.port);
        final SSLSocket wrapping = (SSLSocket)
                c.clientContext.getSocketFactory().createSocket(underlying,
                                                                c.host.getHostName(),
                                                                c.port,
                                                                false);

        // Create our own thread group so we can inspect the stack state later.
        final ThreadGroup clientGroup = new ThreadGroup("client");
        ExecutorService executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                return new Thread(clientGroup, r);
            }
        });

        Future<Void> clientFuture = executor.submit(new Callable<Void>() {
            @Override public Void call() throws Exception {
                try {
                    wrapping.startHandshake();
                    assertFalse(StandardNames.IS_RI);
                    wrapping.setSoTimeout(5 * 1000);
                    assertEquals(-1, wrapping.getInputStream().read());
                } catch (Exception e) {
                    assertTrue(StandardNames.IS_RI);
                }
                return null;
            }
        });
        executor.shutdown();

        SSLSocket server = (SSLSocket) c.serverSocket.accept();
        server.startHandshake();

        /*
         * Wait for the client to at least be in the "read" method before
         * calling close()
         */
        Thread[] threads = new Thread[1];
        clientGroup.enumerate(threads);
        if (threads[0] != null) {
            boolean clientInRead = false;
            while (!clientInRead) {
                StackTraceElement[] elements = threads[0].getStackTrace();
                for (StackTraceElement element : elements) {
                    if ("read".equals(element.getMethodName())) {
                        clientInRead = true;
                        break;
                    }
                }
            }
        }

        wrapping.close();
        clientFuture.get();
        server.close();
    }

    public void test_TestSSLSocketPair_create() {
        TestSSLSocketPair test = TestSSLSocketPair.create();
        assertNotNull(test.c);
        assertNotNull(test.server);
        assertNotNull(test.client);
        assertTrue(test.server.isConnected());
        assertTrue(test.client.isConnected());
        assertFalse(test.server.isClosed());
        assertFalse(test.client.isClosed());
        assertNotNull(test.server.getSession());
        assertNotNull(test.client.getSession());
        assertTrue(test.server.getSession().isValid());
        assertTrue(test.client.getSession().isValid());
        test.close();
    }

    public void test_SSLSocket_ClientHello_size() throws Exception {
        // This test checks the size of ClientHello of the default SSLSocket. TLS/SSL handshakes
        // with older/unpatched F5/BIG-IP appliances are known to stall and time out when
        // the fragment containing ClientHello is between 256 and 511 (inclusive) bytes long.
        //
        // Since there's no straightforward way to obtain a ClientHello from SSLSocket, this test
        // does the following:
        // 1. Creates a listening server socket (a plain one rather than a TLS/SSL one).
        // 2. Creates a client SSLSocket, which connects to the server socket and initiates the
        //    TLS/SSL handshake.
        // 3. Makes the server socket accept an incoming connection on the server socket, and reads
        //    the first chunk of data received. This chunk is assumed to be the ClientHello.
        // NOTE: Steps 2 and 3 run concurrently.
        ServerSocket listeningSocket = null;
        ExecutorService executorService = Executors.newFixedThreadPool(2);

        // Some Socket operations are not interruptible via Thread.interrupt for some reason. To
        // work around, we unblock these sockets using Socket.close.
        final Socket[] sockets = new Socket[2];
        try {
            // 1. Create the listening server socket.
            listeningSocket = ServerSocketFactory.getDefault().createServerSocket(0);
            final ServerSocket finalListeningSocket = listeningSocket;
            // 2. (in background) Wait for an incoming connection and read its first chunk.
            final Future<byte[]> readFirstReceivedChunkFuture =
                    executorService.submit(new Callable<byte[]>() {
                        @Override
                        public byte[] call() throws Exception {
                            Socket socket = finalListeningSocket.accept();
                            sockets[1] = socket;
                            try {
                                byte[] buffer = new byte[64 * 1024];
                                int bytesRead = socket.getInputStream().read(buffer);
                                if (bytesRead == -1) {
                                    throw new EOFException("Failed to read anything");
                                }
                                return Arrays.copyOf(buffer, bytesRead);
                            } finally {
                                IoUtils.closeQuietly(socket);
                            }
                        }
                    });

            // 3. Create a client socket, connect it to the server socket, and start the TLS/SSL
            //    handshake.
            executorService.submit(new Callable<Void>() {
                @Override
                public Void call() throws Exception {
                    SSLContext sslContext = SSLContext.getInstance("TLS");
                    sslContext.init(null, null, null);
                    SSLSocket client = (SSLSocket) sslContext.getSocketFactory().createSocket();
                    sockets[0] = client;
                    try {
                        // Enable SNI extension on the socket (this is typically enabled by default)
                        // to increase the size of ClientHello.
                        try {
                            Method setHostname =
                                    client.getClass().getMethod("setHostname", String.class);
                            setHostname.invoke(client, "sslsockettest.androidcts.google.com");
                        } catch (NoSuchMethodException ignored) {}

                        // Enable Session Tickets extension on the socket (this is typically enabled
                        // by default) to increase the size of ClientHello.
                        try {
                            Method setUseSessionTickets =
                                    client.getClass().getMethod(
                                            "setUseSessionTickets", boolean.class);
                            setUseSessionTickets.invoke(client, true);
                        } catch (NoSuchMethodException ignored) {}

                        client.connect(finalListeningSocket.getLocalSocketAddress());
                        // Initiate the TLS/SSL handshake which is expected to fail as soon as the
                        // server socket receives a ClientHello.
                        try {
                            client.startHandshake();
                            fail();
                            return null;
                        } catch (IOException expected) {}
                        return null;
                    } finally {
                        IoUtils.closeQuietly(client);

                        // Cancel the reading task. If this task succeeded, then the reading task
                        // is done and this will have no effect. If this task failed prematurely,
                        // then the reading task might get unblocked (we're interrupting the thread
                        // it's running on), will fail early, and we'll thus save some time in this
                        // test.
                        readFirstReceivedChunkFuture.cancel(true);
                    }
                }
            });

            // Wait for the ClientHello to arrive
            byte[] clientHello = readFirstReceivedChunkFuture.get(10, TimeUnit.SECONDS);

            // Check for ClientHello length that may cause handshake to fail/time out with older
            // F5/BIG-IP appliances.
            assertEquals("TLS record type: handshake", 22, clientHello[0]);
            int fragmentLength = ((clientHello[3] & 0xff) << 8) | (clientHello[4] & 0xff);
            if ((fragmentLength >= 256) && (fragmentLength <= 511)) {
                fail("Fragment containing ClientHello is of dangerous length: "
                        + fragmentLength + " bytes");
            }
        } finally {
            executorService.shutdownNow();
            IoUtils.closeQuietly(listeningSocket);
            IoUtils.closeQuietly(sockets[0]);
            IoUtils.closeQuietly(sockets[1]);
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                fail("Timed out while waiting for the test to shut down");
            }
        }
    }

    public void test_SSLSocket_sendsTlsFallbackScsv_Fallback_Success() throws Exception {
        TestSSLContext context = TestSSLContext.create();

        final SSLSocket client = (SSLSocket)
            context.clientContext.getSocketFactory().createSocket(context.host, context.port);
        final SSLSocket server = (SSLSocket) context.serverSocket.accept();

        final String[] serverCipherSuites = server.getEnabledCipherSuites();
        final String[] clientCipherSuites = new String[serverCipherSuites.length + 1];
        System.arraycopy(serverCipherSuites, 0, clientCipherSuites, 0, serverCipherSuites.length);
        clientCipherSuites[serverCipherSuites.length] = StandardNames.CIPHER_SUITE_FALLBACK;

        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<Void> s = executor.submit(new Callable<Void>() {
                public Void call() throws Exception {
                    server.setEnabledProtocols(new String[] { "TLSv1.2" });
                    server.setEnabledCipherSuites(serverCipherSuites);
                    server.startHandshake();
                    return null;
                }
            });
        Future<Void> c = executor.submit(new Callable<Void>() {
                public Void call() throws Exception {
                    client.setEnabledProtocols(new String[] { "TLSv1.2" });
                    client.setEnabledCipherSuites(clientCipherSuites);
                    client.startHandshake();
                    return null;
                }
            });
        executor.shutdown();

        s.get();
        c.get();
        client.close();
        server.close();
        context.close();
    }

    // Confirms that communication without the TLS_FALLBACK_SCSV cipher works as it always did.
    public void test_SSLSocket_sendsNoTlsFallbackScsv_Fallback_Success() throws Exception {
        TestSSLContext context = TestSSLContext.create();

        final SSLSocket client = (SSLSocket)
            context.clientContext.getSocketFactory().createSocket(context.host, context.port);
        final SSLSocket server = (SSLSocket) context.serverSocket.accept();

        // Confirm absence of TLS_FALLBACK_SCSV.
        assertFalse(Arrays.asList(client.getEnabledCipherSuites())
                .contains(StandardNames.CIPHER_SUITE_FALLBACK));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<Void> s = executor.submit(new Callable<Void>() {
                public Void call() throws Exception {
                    server.setEnabledProtocols(new String[] { "TLSv1", "SSLv3" });
                    server.startHandshake();
                    return null;
                }
            });
        Future<Void> c = executor.submit(new Callable<Void>() {
                public Void call() throws Exception {
                    client.setEnabledProtocols(new String[] { "SSLv3" });
                    client.startHandshake();
                    return null;
                }
            });
        executor.shutdown();

        s.get();
        c.get();
        client.close();
        server.close();
        context.close();
    }

    public void test_SSLSocket_sendsTlsFallbackScsv_InappropriateFallback_Failure() throws Exception {
        TestSSLContext context = TestSSLContext.create();

        final SSLSocket client = (SSLSocket)
            context.clientContext.getSocketFactory().createSocket(context.host, context.port);
        final SSLSocket server = (SSLSocket) context.serverSocket.accept();

        final String[] serverCipherSuites = server.getEnabledCipherSuites();

        // Add TLS_FALLBACK_SCSV
        final String[] clientCipherSuites = new String[serverCipherSuites.length + 1];
        System.arraycopy(serverCipherSuites, 0, clientCipherSuites, 0, serverCipherSuites.length);
        clientCipherSuites[serverCipherSuites.length] = StandardNames.CIPHER_SUITE_FALLBACK;

        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<Void> s = executor.submit(new Callable<Void>() {
                public Void call() throws Exception {
                    server.setEnabledProtocols(new String[] { "TLSv1", "SSLv3" });
                    server.setEnabledCipherSuites(serverCipherSuites);
                    try {
                        server.startHandshake();
                        fail("Should result in inappropriate fallback");
                    } catch (SSLHandshakeException expected) {
                    }
                    return null;
                }
            });
        Future<Void> c = executor.submit(new Callable<Void>() {
                public Void call() throws Exception {
                    client.setEnabledProtocols(new String[] { "SSLv3" });
                    client.setEnabledCipherSuites(clientCipherSuites);
                    try {
                        client.startHandshake();
                        fail("Should receive TLS alert inappropriate fallback");
                    } catch (SSLHandshakeException expected) {
                    }
                    return null;
                }
            });
        executor.shutdown();

        s.get();
        c.get();
        client.close();
        server.close();
        context.close();
    }

    /**
     * Not run by default by JUnit, but can be run by Vogar by
     * specifying it explicitly (or with main method below)
     */
    public void stress_test_TestSSLSocketPair_create() {
        final boolean verbose = true;
        while (true) {
            TestSSLSocketPair test = TestSSLSocketPair.create();
            if (verbose) {
                System.out.println("client=" + test.client.getLocalPort()
                                   + " server=" + test.server.getLocalPort());
            } else {
                System.out.print("X");
            }

            /*
              We don't close on purpose in this stress test to add
              races in file descriptors reuse when the garbage
              collector runs concurrently and finalizes sockets
            */
            // test.close();

        }
    }

    public static void main (String[] args) {
        new SSLSocketTest().stress_test_TestSSLSocketPair_create();
    }
}
