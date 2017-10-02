/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package org.apache.geode.internal.protocol.acceptance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.awaitility.Awaitility;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.contrib.java.lang.system.RestoreSystemProperties;
import org.junit.experimental.categories.Category;
import org.junit.rules.TestName;

import org.apache.geode.cache.Cache;
import org.apache.geode.cache.CacheFactory;
import org.apache.geode.cache.RegionFactory;
import org.apache.geode.cache.server.CacheServer;
import org.apache.geode.distributed.ConfigurationProperties;
import org.apache.geode.internal.AvailablePortHelper;
import org.apache.geode.internal.cache.CacheServerImpl;
import org.apache.geode.internal.cache.tier.CommunicationMode;
import org.apache.geode.internal.cache.tier.sockets.AcceptorImpl;
import org.apache.geode.internal.net.SocketCreatorFactory;
import org.apache.geode.internal.protocol.protobuf.ClientProtocol;
import org.apache.geode.internal.protocol.MessageUtil;
import org.apache.geode.internal.protocol.exception.InvalidProtocolMessageException;
import org.apache.geode.internal.protocol.protobuf.HandshakeAPI;
import org.apache.geode.internal.protocol.protobuf.ProtobufSerializationService;
import org.apache.geode.internal.protocol.protobuf.ProtobufTestUtilities;
import org.apache.geode.internal.protocol.protobuf.serializer.ProtobufProtocolSerializer;
import org.apache.geode.internal.protocol.protobuf.utilities.ProtobufUtilities;
import org.apache.geode.test.junit.categories.IntegrationTest;

@Category(IntegrationTest.class)
public class CacheMaxConnectionJUnitTest {
  private static final String TEST_KEY = "testKey";
  private static final String TEST_VALUE = "testValue";
  private static final int TEST_PUT_CORRELATION_ID = 12355;
  private final String TEST_REGION = "testRegion";

  private Cache cache;
  private int cacheServerPort;
  private OutputStream outputStream;

  @Rule
  public final RestoreSystemProperties restoreSystemProperties = new RestoreSystemProperties();

  @Rule
  public TestName testName = new TestName();
  private ProtobufSerializationService serializationService;
  private ProtobufProtocolSerializer protobufProtocolSerializer;

  @Before
  public void setup() throws Exception {
    Properties properties = new Properties();
    CacheFactory cacheFactory = new CacheFactory(properties);
    cacheFactory.set(ConfigurationProperties.MCAST_PORT, "0");
    cacheFactory.set(ConfigurationProperties.ENABLE_CLUSTER_CONFIGURATION, "false");
    cacheFactory.set(ConfigurationProperties.USE_CLUSTER_CONFIGURATION, "false");
    cacheFactory.set(ConfigurationProperties.TCP_PORT, "0");
    cache = cacheFactory.create();

    CacheServer cacheServer = cache.addCacheServer();
    cacheServerPort = AvailablePortHelper.getRandomAvailableTCPPort();
    cacheServer.setPort(cacheServerPort);
    cacheServer.start();

    RegionFactory<Object, Object> regionFactory = cache.createRegionFactory();
    regionFactory.create(TEST_REGION);

    System.setProperty("geode.feature-protobuf-protocol", "true");

    serializationService = new ProtobufSerializationService();
    protobufProtocolSerializer = new ProtobufProtocolSerializer();
  }

  @After
  public void cleanup() throws IOException {
    cache.close();
    SocketCreatorFactory.close();
  }

  // 0 threads implies not selector.
  @Test
  public void testNewProtocolRespectsMaxConnectionLimit_notSelector() throws Exception {
    testNewProtocolRespectsMaxConnectionLimit(0, false);
  }

  @Test
  public void testNewProtocolRespectsMaxConnectionLimit_isSelector() throws Exception {
    testNewProtocolRespectsMaxConnectionLimit(4, true);
  }

  // Set the maximum connection limit, connect that many clients, check they all connected, check we
  // can't create another, and repeat once to be sure we're cleaning up.
  private void testNewProtocolRespectsMaxConnectionLimit(int threads, boolean isSelector)
      throws Exception {
    final int connections = 16;

    List<CacheServer> cacheServers = cache.getCacheServers();
    assertEquals(1, cacheServers.size());
    cacheServers.get(0).stop();

    CacheServer cacheServer = cache.addCacheServer();
    final int cacheServerPort = AvailablePortHelper.getRandomAvailableTCPPort();
    cacheServer.setPort(cacheServerPort);
    cacheServer.setMaxConnections(connections);
    cacheServer.setMaxThreads(threads);
    cacheServer.start();

    AcceptorImpl acceptor = ((CacheServerImpl) cacheServer).getAcceptor();

    if (isSelector) {
      assertTrue(acceptor.isSelector());
    } else {
      assertFalse(acceptor.isSelector());
    }

    validateSocketCreationAndDestruction(cacheServerPort, connections);

    // Once all connections are closed, the acceptor should have a connection count of 0.
    Awaitility.await().atMost(5, TimeUnit.SECONDS)
        .until(() -> acceptor.getClientServerCnxCount() == 0);

    // do it again, just to be sure there's no leak somewhere else.
    validateSocketCreationAndDestruction(cacheServerPort, connections);

    // Once all connections are closed, the acceptor should have a connection count of 0.
    Awaitility.await().atMost(5, TimeUnit.SECONDS)
        .until(() -> acceptor.getClientServerCnxCount() == 0);

  }

  // Start exactly as many that the server will support, check that they work.
  // test that creating one more causes it to be disconnected.
  // Close all the sockets when we're done.
  private void validateSocketCreationAndDestruction(int cacheServerPort, int connections)
      throws Exception {
    final Socket[] sockets = new Socket[connections];

    ExecutorService executor = Executors.newFixedThreadPool(connections);

    // Used to assert the exception is non-null.
    // The boolean is just because invokeAll doesn't take runnable and we need *some* return type.
    ArrayList<Callable<Boolean>> callables = new ArrayList<>();
    for (int i = 0; i < connections; i++) {
      final int j = i;
      callables.add(() -> {
        try {
          Socket socket = new Socket("localhost", cacheServerPort);
          sockets[j] = socket;

          Awaitility.await().atMost(5, TimeUnit.SECONDS).until(socket::isConnected);
          OutputStream outputStream = socket.getOutputStream();
          outputStream.write(CommunicationMode.ProtobufClientServerProtocol.getModeNumber());
          ProtobufTestUtilities.verifyHandshake(socket.getInputStream(), outputStream,
              HandshakeAPI.AuthenticationMode.NONE);

          ClientProtocol.Message putMessage =
              MessageUtil.makePutRequestMessage(serializationService, TEST_KEY, TEST_VALUE,
                  TEST_REGION, ProtobufUtilities.createMessageHeader(TEST_PUT_CORRELATION_ID));
          protobufProtocolSerializer.serialize(putMessage, outputStream);
          validatePutResponse(socket, protobufProtocolSerializer);
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
        return true;
      });
    }

    List<Future<Boolean>> futures = executor.invokeAll(callables);
    for (Future<?> f : futures) {
      f.get(1, TimeUnit.MINUTES); // throws if failure.
    }

    // try to start a new socket, expecting it to be disconnected.
    try (Socket socket = new Socket("localhost", cacheServerPort)) {
      Awaitility.await().atMost(5, TimeUnit.SECONDS).until(socket::isConnected);
      socket.getOutputStream()
          .write(CommunicationMode.ProtobufClientServerProtocol.getModeNumber());
      assertEquals(-1, socket.getInputStream().read()); // EOF implies disconnected.
    }

    for (Socket currentSocket : sockets) {
      currentSocket.close();
    }
  }

  private void validatePutResponse(Socket socket,
      ProtobufProtocolSerializer protobufProtocolSerializer) throws Exception {
    ClientProtocol.Response response =
        deserializeResponse(socket, protobufProtocolSerializer, TEST_PUT_CORRELATION_ID);
    assertEquals(ClientProtocol.Response.ResponseAPICase.PUTRESPONSE,
        response.getResponseAPICase());
  }

  private ClientProtocol.Response deserializeResponse(Socket socket,
      ProtobufProtocolSerializer protobufProtocolSerializer, int expectedCorrelationId)
      throws InvalidProtocolMessageException, IOException {
    ClientProtocol.Message message =
        protobufProtocolSerializer.deserialize(socket.getInputStream());
    assertEquals(expectedCorrelationId, message.getMessageHeader().getCorrelationId());
    assertEquals(ClientProtocol.Message.MessageTypeCase.RESPONSE, message.getMessageTypeCase());
    return message.getResponse();
  }
}
