/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.apache.hadoop.ozone.container.server;

import com.google.common.collect.Maps;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.RandomUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.hadoop.hdds.HddsConfigKeys;
import org.apache.hadoop.hdds.client.BlockID;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.hdds.protocol.DatanodeDetails;
import org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos;
import org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos.ContainerCommandRequestProto;
import org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos.ContainerCommandResponseProto;
import org.apache.hadoop.hdds.scm.TestUtils;
import org.apache.hadoop.hdds.scm.XceiverClientGrpc;
import org.apache.hadoop.hdds.scm.XceiverClientRatis;
import org.apache.hadoop.hdds.scm.XceiverClientSpi;
import org.apache.hadoop.hdds.scm.pipeline.Pipeline;
import org.apache.hadoop.hdds.scm.storage.ContainerProtocolCalls;
import org.apache.hadoop.hdds.security.token.BlockTokenVerifier;
import org.apache.hadoop.hdds.security.token.OzoneBlockTokenIdentifier;
import org.apache.hadoop.hdds.security.x509.SecurityConfig;
import org.apache.hadoop.metrics2.lib.DefaultMetricsSystem;
import org.apache.hadoop.ozone.OzoneConfigKeys;
import org.apache.hadoop.ozone.RatisTestHelper;
import org.apache.hadoop.ozone.client.CertificateClientTestImpl;
import org.apache.hadoop.ozone.container.ContainerTestHelper;
import org.apache.hadoop.ozone.container.common.helpers.ContainerMetrics;
import org.apache.hadoop.ozone.container.common.impl.ContainerSet;
import org.apache.hadoop.ozone.container.common.impl.HddsDispatcher;
import org.apache.hadoop.ozone.container.common.interfaces.ContainerDispatcher;
import org.apache.hadoop.ozone.container.common.interfaces.Handler;
import org.apache.hadoop.ozone.container.common.statemachine.DatanodeStateMachine;
import org.apache.hadoop.ozone.container.common.statemachine.StateContext;
import org.apache.hadoop.ozone.container.common.transport.server.XceiverServerGrpc;
import org.apache.hadoop.ozone.container.common.transport.server.XceiverServerSpi;
import org.apache.hadoop.ozone.container.common.transport.server.ratis.XceiverServerRatis;
import org.apache.hadoop.ozone.container.common.volume.VolumeSet;
import org.apache.hadoop.ozone.container.ozoneimpl.ContainerController;
import org.apache.hadoop.ozone.container.replication.GrpcReplicationService;
import org.apache.hadoop.ozone.container.replication.OnDemandContainerReplicationSource;
import org.apache.hadoop.ozone.security.OzoneBlockTokenSecretManager;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.test.GenericTestUtils;
import org.apache.hadoop.test.LambdaTestUtils;
import org.apache.hadoop.util.Time;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos.BlockTokenSecretProto.AccessModeProto;

import org.apache.ratis.rpc.RpcType;
import org.apache.ratis.util.function.CheckedBiConsumer;
import org.junit.After;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.Mockito;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.apache.hadoop.hdds.HddsConfigKeys.HDDS_BLOCK_TOKEN_ENABLED;
import static org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos.Result.SUCCESS;
import static org.apache.hadoop.hdds.scm.ScmConfigKeys.HDDS_DATANODE_DIR_KEY;
import static org.apache.hadoop.ozone.OzoneConfigKeys.OZONE_SECURITY_ENABLED_KEY;
import static org.apache.hadoop.ozone.container.ContainerTestHelper.*;
import static org.apache.ratis.rpc.SupportedRpcType.GRPC;
import static org.junit.Assert.*;

/**
 * Test Container servers when security is enabled.
 */
public class TestSecureContainerServer {
  static final String TEST_DIR
      = GenericTestUtils.getTestDir("dfs").getAbsolutePath() + File.separator;
  private static final OzoneConfiguration CONF = new OzoneConfiguration();
  private static CertificateClientTestImpl caClient;

  private GrpcReplicationService createReplicationService(
      ContainerController containerController) {
    return new GrpcReplicationService(
        new OnDemandContainerReplicationSource(containerController));
  }

  @BeforeClass
  static public void setup() throws Exception {
    DefaultMetricsSystem.setMiniClusterMode(true);
    CONF.set(HddsConfigKeys.HDDS_METADATA_DIR_NAME, TEST_DIR);
    CONF.setBoolean(OZONE_SECURITY_ENABLED_KEY, true);
    CONF.setBoolean(HDDS_BLOCK_TOKEN_ENABLED, true);
    caClient = new CertificateClientTestImpl(CONF);
  }

  @After
  public void cleanUp() {
    FileUtils.deleteQuietly(new File(CONF.get(HDDS_DATANODE_DIR_KEY)));
  }

  @Test
  public void testClientServer() throws Exception {
    DatanodeDetails dd = TestUtils.randomDatanodeDetails();
    ContainerSet containerSet = new ContainerSet();
    ContainerController controller = new ContainerController(
        containerSet, null);
    HddsDispatcher hddsDispatcher = createDispatcher(dd,
        UUID.randomUUID(), CONF);
    runTestClientServer(1, (pipeline, conf) -> conf
            .setInt(OzoneConfigKeys.DFS_CONTAINER_IPC_PORT,
                pipeline.getFirstNode()
                    .getPort(DatanodeDetails.Port.Name.STANDALONE).getValue()),
        XceiverClientGrpc::new,
        (dn, conf) -> new XceiverServerGrpc(dd, conf,
            hddsDispatcher, caClient,
            createReplicationService(controller)), (dn, p) -> {}, (p) -> {});
  }

  private static HddsDispatcher createDispatcher(DatanodeDetails dd, UUID scmId,
      OzoneConfiguration conf) throws IOException {
    ContainerSet containerSet = new ContainerSet();
    conf.set(HDDS_DATANODE_DIR_KEY,
        Paths.get(TEST_DIR, "dfs", "data", "hdds",
            RandomStringUtils.randomAlphabetic(4)).toString());
    VolumeSet volumeSet = new VolumeSet(dd.getUuidString(), conf);
    DatanodeStateMachine stateMachine = Mockito.mock(
        DatanodeStateMachine.class);
    StateContext context = Mockito.mock(StateContext.class);
    Mockito.when(stateMachine.getDatanodeDetails()).thenReturn(dd);
    Mockito.when(context.getParent()).thenReturn(stateMachine);
    ContainerMetrics metrics = ContainerMetrics.create(conf);
    Map<ContainerProtos.ContainerType, Handler> handlers = Maps.newHashMap();
    for (ContainerProtos.ContainerType containerType :
        ContainerProtos.ContainerType.values()) {
      handlers.put(containerType,
          Handler.getHandlerForContainerType(containerType, conf, context,
              containerSet, volumeSet, metrics));
    }
    HddsDispatcher hddsDispatcher = new HddsDispatcher(
        conf, containerSet, volumeSet, handlers, context, metrics,
        new BlockTokenVerifier(new SecurityConfig((conf)), caClient));
    hddsDispatcher.setScmId(scmId.toString());
    return hddsDispatcher;
  }

  @FunctionalInterface
  interface CheckedBiFunction<LEFT, RIGHT, OUT, THROWABLE extends Throwable> {
    OUT apply(LEFT left, RIGHT right) throws THROWABLE;
  }

  @Test
  public void testClientServerRatisGrpc() throws Exception {
    runTestClientServerRatis(GRPC, 1);
    runTestClientServerRatis(GRPC, 3);
  }

  static XceiverServerRatis newXceiverServerRatis(
      DatanodeDetails dn, OzoneConfiguration conf) throws IOException {
    conf.setInt(OzoneConfigKeys.DFS_CONTAINER_RATIS_IPC_PORT,
        dn.getPort(DatanodeDetails.Port.Name.RATIS).getValue());
    final String dir = TEST_DIR + dn.getUuid();
    conf.set(OzoneConfigKeys.DFS_CONTAINER_RATIS_DATANODE_STORAGE_DIR, dir);
    final ContainerDispatcher dispatcher = createDispatcher(dn,
        UUID.randomUUID(), conf);
    return XceiverServerRatis.newXceiverServerRatis(dn, conf, dispatcher,
        new ContainerController(new ContainerSet(), Maps.newHashMap()),
        caClient, null);
  }

  static void runTestClientServerRatis(RpcType rpc, int numNodes)
      throws Exception {
    runTestClientServer(numNodes,
        (pipeline, conf) -> RatisTestHelper.initRatisConf(rpc, conf),
        XceiverClientRatis::newXceiverClientRatis,
        TestSecureContainerServer::newXceiverServerRatis,
        (dn, p) -> RatisTestHelper.initXceiverServerRatis(rpc, dn, p),
        (p) -> {});
  }

  static void runTestClientServer(
      int numDatanodes,
      CheckedBiConsumer<Pipeline, OzoneConfiguration, IOException> initConf,
      CheckedBiFunction<Pipeline, OzoneConfiguration, XceiverClientSpi,
          IOException> createClient,
      CheckedBiFunction<DatanodeDetails, OzoneConfiguration, XceiverServerSpi,
          IOException> createServer,
      CheckedBiConsumer<DatanodeDetails, Pipeline, IOException> initServer,
      Consumer<Pipeline> stopServer)
      throws Exception {
    final List<XceiverServerSpi> servers = new ArrayList<>();
    XceiverClientSpi client = null;
    final Pipeline pipeline =
        ContainerTestHelper.createPipeline(numDatanodes);
    try {
      initConf.accept(pipeline, CONF);

      for (DatanodeDetails dn : pipeline.getNodes()) {
        final XceiverServerSpi s = createServer.apply(dn, CONF);
        servers.add(s);
        s.start();
        initServer.accept(dn, pipeline);
      }
      client = createClient.apply(pipeline, CONF);
      client.connect();

      long testContainerID = getTestContainerID();
      BlockID testBlockID = getTestBlockID(testContainerID);

      //create the container
      ContainerProtocolCalls.createContainer(client, testContainerID, null);

      // Test 1: Test putBlock failure without block token.
      final ContainerCommandRequestProto request =
          getPutBlockRequest(pipeline, null, getWriteChunkRequest(pipeline,
              testBlockID, 1024, null).getWriteChunk());
      Assert.assertNotNull(request.getTraceID());

      XceiverClientSpi finalClient = client;
      if (finalClient instanceof XceiverClientGrpc) {
        ContainerCommandResponseProto resp =
            finalClient.sendCommand(request);
        assertNotEquals(resp.getResult(), ContainerProtos.Result.SUCCESS);
        String msg = resp.getMessage();
        assertTrue(msg, msg.contains("Block token verification failed"));
      } else {
        IOException e = LambdaTestUtils.intercept(IOException.class,
            () -> finalClient.sendCommand(request));
        Throwable rootCause = ExceptionUtils.getRootCause(e);
        String msg = rootCause.getMessage();
        assertTrue(msg, msg.contains("Block token verification failed"));
      }

      // Test 2: Test putBlock succeeded with valid block token.
      long expiryTime = Time.monotonicNow() + 60 * 60 * 24;
      String omCertSerialId =
          caClient.getCertificate().getSerialNumber().toString();
      OzoneBlockTokenSecretManager secretManager =
          new OzoneBlockTokenSecretManager(new SecurityConfig(CONF),
          expiryTime, omCertSerialId);
      secretManager.start(caClient);
      Token<OzoneBlockTokenIdentifier> token = secretManager.generateToken(
          testBlockID.getContainerBlockID().toString(),
          EnumSet.allOf(AccessModeProto.class), RandomUtils.nextLong());

      final ContainerCommandRequestProto request2 =
          getPutBlockRequest(pipeline, token.encodeToUrlString(),
              getWriteChunkRequest(pipeline, testBlockID, 1024,
                  token.encodeToUrlString()).getWriteChunk());
      Assert.assertNotNull(request2.getTraceID());
      ContainerCommandResponseProto resp2 = finalClient.sendCommand(request2);
      assertEquals(SUCCESS, resp2.getResult());
    } finally {
      if (client != null) {
        client.close();
      }
      if (pipeline != null) {
        stopServer.accept(pipeline);
      }
      servers.stream().forEach(XceiverServerSpi::stop);
    }
  }
}