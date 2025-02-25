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

package org.apache.hadoop.ozone.container.common.transport.server.ratis;

import com.google.common.annotations.VisibleForTesting;
import org.apache.hadoop.conf.StorageUnit;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.hdds.protocol.DatanodeDetails;
import org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos.ContainerCommandRequestProto;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos;
import org.apache.hadoop.hdds.protocol.proto.StorageContainerDatanodeProtocolProtos.PipelineReport;
import org.apache.hadoop.hdds.protocol.proto.StorageContainerDatanodeProtocolProtos.ClosePipelineInfo;
import org.apache.hadoop.hdds.protocol.proto.StorageContainerDatanodeProtocolProtos.PipelineAction;
import org.apache.hadoop.hdds.ratis.ContainerCommandRequestMessage;
import org.apache.hadoop.hdds.scm.HddsServerUtil;
import org.apache.hadoop.hdds.scm.pipeline.PipelineID;
import org.apache.hadoop.hdds.security.x509.SecurityConfig;
import org.apache.hadoop.hdds.security.x509.certificate.client.CertificateClient;
import org.apache.hadoop.hdds.tracing.TracingUtil;
import org.apache.hadoop.ozone.OzoneConfigKeys;

import org.apache.hadoop.ozone.OzoneConsts;
import org.apache.hadoop.ozone.container.common.interfaces.ContainerDispatcher;
import org.apache.hadoop.ozone.container.common.statemachine.StateContext;

import io.opentracing.Scope;
import org.apache.hadoop.ozone.container.common.transport.server.XceiverServerSpi;
import org.apache.hadoop.ozone.container.ozoneimpl.ContainerController;
import org.apache.ratis.RaftConfigKeys;
import org.apache.hadoop.hdds.ratis.RatisHelper;
import org.apache.ratis.conf.RaftProperties;
import org.apache.ratis.grpc.GrpcConfigKeys;
import org.apache.ratis.grpc.GrpcFactory;
import org.apache.ratis.grpc.GrpcTlsConfig;
import org.apache.ratis.netty.NettyConfigKeys;
import org.apache.ratis.protocol.*;
import org.apache.ratis.rpc.RpcType;
import org.apache.ratis.rpc.SupportedRpcType;
import org.apache.ratis.server.RaftServer;
import org.apache.ratis.server.RaftServerConfigKeys;
import org.apache.ratis.proto.RaftProtos;
import org.apache.ratis.proto.RaftProtos.RoleInfoProto;
import org.apache.ratis.proto.RaftProtos.ReplicationLevel;
import org.apache.ratis.server.protocol.TermIndex;
import org.apache.ratis.server.impl.RaftServerProxy;
import org.apache.ratis.util.SizeInBytes;
import org.apache.ratis.util.TimeDuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.ArrayList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Creates a ratis server endpoint that acts as the communication layer for
 * Ozone containers.
 */
public final class XceiverServerRatis implements XceiverServerSpi {
  private static final Logger LOG = LoggerFactory
      .getLogger(XceiverServerRatis.class);
  private static final AtomicLong CALL_ID_COUNTER = new AtomicLong();

  private static long nextCallId() {
    return CALL_ID_COUNTER.getAndIncrement() & Long.MAX_VALUE;
  }

  private int port;
  private final RaftServer server;
  private ThreadPoolExecutor chunkExecutor;
  private final ContainerDispatcher dispatcher;
  private final ContainerController containerController;
  private ClientId clientId = ClientId.randomId();
  private final StateContext context;
  private final ReplicationLevel replicationLevel;
  private long nodeFailureTimeoutMs;
  private final long cacheEntryExpiryInteval;
  private boolean isStarted = false;
  private DatanodeDetails datanodeDetails;
  private final OzoneConfiguration conf;
  // TODO: Remove the gids set when Ratis supports an api to query active
  // pipelines
  private final Set<RaftGroupId> raftGids = new HashSet<>();

  private XceiverServerRatis(DatanodeDetails dd, int port,
      ContainerDispatcher dispatcher, ContainerController containerController,
      StateContext context, GrpcTlsConfig tlsConfig, OzoneConfiguration conf)
      throws IOException {
    this.conf = conf;
    Objects.requireNonNull(dd, "id == null");
    datanodeDetails = dd;
    this.port = port;
    RaftProperties serverProperties = newRaftProperties();
    final int numWriteChunkThreads = conf.getInt(
        OzoneConfigKeys.DFS_CONTAINER_RATIS_NUM_WRITE_CHUNK_THREADS_KEY,
        OzoneConfigKeys.DFS_CONTAINER_RATIS_NUM_WRITE_CHUNK_THREADS_DEFAULT);
    chunkExecutor =
        new ThreadPoolExecutor(numWriteChunkThreads, numWriteChunkThreads,
            100, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1024),
            new ThreadPoolExecutor.CallerRunsPolicy());
    this.context = context;
    this.replicationLevel =
        conf.getEnum(OzoneConfigKeys.DFS_CONTAINER_RATIS_REPLICATION_LEVEL_KEY,
            OzoneConfigKeys.DFS_CONTAINER_RATIS_REPLICATION_LEVEL_DEFAULT);
    cacheEntryExpiryInteval = conf.getTimeDuration(OzoneConfigKeys.
            DFS_CONTAINER_RATIS_STATEMACHINEDATA_CACHE_EXPIRY_INTERVAL,
        OzoneConfigKeys.
            DFS_CONTAINER_RATIS_STATEMACHINEDATA_CACHE_EXPIRY_INTERVAL_DEFAULT,
        TimeUnit.MILLISECONDS);
    this.dispatcher = dispatcher;
    this.containerController = containerController;

    RaftServer.Builder builder =
        RaftServer.newBuilder().setServerId(RatisHelper.toRaftPeerId(dd))
            .setProperties(serverProperties)
            .setStateMachineRegistry(this::getStateMachine);
    if (tlsConfig != null) {
      builder.setParameters(GrpcFactory.newRaftParameters(tlsConfig));
    }
    this.server = builder.build();
  }

  private ContainerStateMachine getStateMachine(RaftGroupId gid) {
    return new ContainerStateMachine(gid, dispatcher, containerController,
        chunkExecutor, this, cacheEntryExpiryInteval,
        conf);
  }

  private RaftProperties newRaftProperties() {
    final RaftProperties properties = new RaftProperties();

    // Set rpc type
    final RpcType rpc = setRpcType(properties);

    // set raft segment size
    setRaftSegmentSize(properties);

    // set raft segment pre-allocated size
    final int raftSegmentPreallocatedSize =
        setRaftSegmentPreallocatedSize(properties);

    // Set max write buffer size, which is the scm chunk size
    final int maxChunkSize = setMaxWriteBuffer(properties);
    TimeUnit timeUnit;
    long duration;

    // set the configs enable and set the stateMachineData sync timeout
    RaftServerConfigKeys.Log.StateMachineData.setSync(properties, true);
    timeUnit = OzoneConfigKeys.
        DFS_CONTAINER_RATIS_STATEMACHINEDATA_SYNC_TIMEOUT_DEFAULT.getUnit();
    duration = conf.getTimeDuration(
        OzoneConfigKeys.DFS_CONTAINER_RATIS_STATEMACHINEDATA_SYNC_TIMEOUT,
        OzoneConfigKeys.
            DFS_CONTAINER_RATIS_STATEMACHINEDATA_SYNC_TIMEOUT_DEFAULT
            .getDuration(), timeUnit);
    final TimeDuration dataSyncTimeout =
        TimeDuration.valueOf(duration, timeUnit);
    RaftServerConfigKeys.Log.StateMachineData
        .setSyncTimeout(properties, dataSyncTimeout);

    // Set the server Request timeout
    setServerRequestTimeout(properties);

    // set timeout for a retry cache entry
    setTimeoutForRetryCache(properties);

    // Set the ratis leader election timeout
    setRatisLeaderElectionTimeout(properties);

    // Set the maximum cache segments
    RaftServerConfigKeys.Log.setMaxCachedSegmentNum(properties, 2);

    // set the node failure timeout
    setNodeFailureTimeout(properties);

    // Set the ratis storage directory
    String storageDir = HddsServerUtil.getOzoneDatanodeRatisDirectory(conf);
    RaftServerConfigKeys.setStorageDirs(properties,
        Collections.singletonList(new File(storageDir)));

    // For grpc set the maximum message size
    GrpcConfigKeys.setMessageSizeMax(properties,
        SizeInBytes.valueOf(maxChunkSize + raftSegmentPreallocatedSize));

    // Set the ratis port number
    if (rpc == SupportedRpcType.GRPC) {
      GrpcConfigKeys.Server.setPort(properties, port);
    } else if (rpc == SupportedRpcType.NETTY) {
      NettyConfigKeys.Server.setPort(properties, port);
    }

    long snapshotThreshold =
        conf.getLong(OzoneConfigKeys.DFS_RATIS_SNAPSHOT_THRESHOLD_KEY,
            OzoneConfigKeys.DFS_RATIS_SNAPSHOT_THRESHOLD_DEFAULT);
    RaftServerConfigKeys.Snapshot.
      setAutoTriggerEnabled(properties, true);
    RaftServerConfigKeys.Snapshot.
      setAutoTriggerThreshold(properties, snapshotThreshold);
    int maxPendingRequets = conf.getInt(
        OzoneConfigKeys.DFS_CONTAINER_RATIS_LEADER_NUM_PENDING_REQUESTS,
        OzoneConfigKeys.DFS_CONTAINER_RATIS_LEADER_NUM_PENDING_REQUESTS_DEFAULT
    );
    RaftServerConfigKeys.Write.setElementLimit(properties, maxPendingRequets);
    int logQueueNumElements =
        conf.getInt(OzoneConfigKeys.DFS_CONTAINER_RATIS_LOG_QUEUE_NUM_ELEMENTS,
            OzoneConfigKeys.DFS_CONTAINER_RATIS_LOG_QUEUE_NUM_ELEMENTS_DEFAULT);
    final int logQueueByteLimit = (int) conf.getStorageSize(
        OzoneConfigKeys.DFS_CONTAINER_RATIS_LOG_QUEUE_BYTE_LIMIT,
        OzoneConfigKeys.DFS_CONTAINER_RATIS_LOG_QUEUE_BYTE_LIMIT_DEFAULT,
        StorageUnit.BYTES);
    RaftServerConfigKeys.Log.setQueueElementLimit(
        properties, logQueueNumElements);
    RaftServerConfigKeys.Log.setQueueByteLimit(properties, logQueueByteLimit);

    int numSyncRetries = conf.getInt(
        OzoneConfigKeys.DFS_CONTAINER_RATIS_STATEMACHINEDATA_SYNC_RETRIES,
        OzoneConfigKeys.
            DFS_CONTAINER_RATIS_STATEMACHINEDATA_SYNC_RETRIES_DEFAULT);
    RaftServerConfigKeys.Log.StateMachineData.setSyncTimeoutRetry(properties,
        numSyncRetries);

    // Enable the StateMachineCaching
    RaftServerConfigKeys.Log.StateMachineData.setCachingEnabled(
        properties, true);

    RaftServerConfigKeys.Log.Appender.setInstallSnapshotEnabled(properties,
        false);

    int purgeGap = conf.getInt(
        OzoneConfigKeys.DFS_CONTAINER_RATIS_LOG_PURGE_GAP,
        OzoneConfigKeys.DFS_CONTAINER_RATIS_LOG_PURGE_GAP_DEFAULT);
    RaftServerConfigKeys.Log.setPurgeGap(properties, purgeGap);

    //Set the number of Snapshots Retained.
    RatisServerConfiguration ratisServerConfiguration =
        conf.getObject(RatisServerConfiguration.class);
    int numSnapshotsRetained =
        ratisServerConfiguration.getNumSnapshotsRetained();
    RaftServerConfigKeys.Snapshot.setRetentionFileNum(properties,
        numSnapshotsRetained);
    return properties;
  }

  private void setNodeFailureTimeout(RaftProperties properties) {
    TimeUnit timeUnit;
    long duration;
    timeUnit = OzoneConfigKeys.DFS_RATIS_SERVER_FAILURE_DURATION_DEFAULT
        .getUnit();
    duration = conf.getTimeDuration(
        OzoneConfigKeys.DFS_RATIS_SERVER_FAILURE_DURATION_KEY,
        OzoneConfigKeys.DFS_RATIS_SERVER_FAILURE_DURATION_DEFAULT
            .getDuration(), timeUnit);
    final TimeDuration nodeFailureTimeout =
        TimeDuration.valueOf(duration, timeUnit);
    RaftServerConfigKeys.Notification.setNoLeaderTimeout(properties,
        nodeFailureTimeout);
    RaftServerConfigKeys.Rpc.setSlownessTimeout(properties,
        nodeFailureTimeout);
    nodeFailureTimeoutMs = nodeFailureTimeout.toLong(TimeUnit.MILLISECONDS);
  }

  private void setRatisLeaderElectionTimeout(RaftProperties properties) {
    long duration;
    TimeUnit leaderElectionMinTimeoutUnit =
        OzoneConfigKeys.
            DFS_RATIS_LEADER_ELECTION_MINIMUM_TIMEOUT_DURATION_DEFAULT
            .getUnit();
    duration = conf.getTimeDuration(
        OzoneConfigKeys.DFS_RATIS_LEADER_ELECTION_MINIMUM_TIMEOUT_DURATION_KEY,
        OzoneConfigKeys.
            DFS_RATIS_LEADER_ELECTION_MINIMUM_TIMEOUT_DURATION_DEFAULT
            .getDuration(), leaderElectionMinTimeoutUnit);
    final TimeDuration leaderElectionMinTimeout =
        TimeDuration.valueOf(duration, leaderElectionMinTimeoutUnit);
    RaftServerConfigKeys.Rpc
        .setTimeoutMin(properties, leaderElectionMinTimeout);
    long leaderElectionMaxTimeout =
        leaderElectionMinTimeout.toLong(TimeUnit.MILLISECONDS) + 200;
    RaftServerConfigKeys.Rpc.setTimeoutMax(properties,
        TimeDuration.valueOf(leaderElectionMaxTimeout, TimeUnit.MILLISECONDS));
  }

  private void setTimeoutForRetryCache(RaftProperties properties) {
    TimeUnit timeUnit;
    long duration;
    timeUnit =
        OzoneConfigKeys.DFS_RATIS_SERVER_RETRY_CACHE_TIMEOUT_DURATION_DEFAULT
            .getUnit();
    duration = conf.getTimeDuration(
        OzoneConfigKeys.DFS_RATIS_SERVER_RETRY_CACHE_TIMEOUT_DURATION_KEY,
        OzoneConfigKeys.DFS_RATIS_SERVER_RETRY_CACHE_TIMEOUT_DURATION_DEFAULT
            .getDuration(), timeUnit);
    final TimeDuration retryCacheTimeout =
        TimeDuration.valueOf(duration, timeUnit);
    RaftServerConfigKeys.RetryCache
        .setExpiryTime(properties, retryCacheTimeout);
  }

  private void setServerRequestTimeout(RaftProperties properties) {
    TimeUnit timeUnit;
    long duration;
    timeUnit = OzoneConfigKeys.DFS_RATIS_SERVER_REQUEST_TIMEOUT_DURATION_DEFAULT
        .getUnit();
    duration = conf.getTimeDuration(
        OzoneConfigKeys.DFS_RATIS_SERVER_REQUEST_TIMEOUT_DURATION_KEY,
        OzoneConfigKeys.DFS_RATIS_SERVER_REQUEST_TIMEOUT_DURATION_DEFAULT
            .getDuration(), timeUnit);
    final TimeDuration serverRequestTimeout =
        TimeDuration.valueOf(duration, timeUnit);
    RaftServerConfigKeys.Rpc
        .setRequestTimeout(properties, serverRequestTimeout);
  }

  private int setMaxWriteBuffer(RaftProperties properties) {
    final int maxChunkSize = OzoneConsts.OZONE_SCM_CHUNK_MAX_SIZE;
    RaftServerConfigKeys.Log.setWriteBufferSize(properties,
        SizeInBytes.valueOf(maxChunkSize));
    return maxChunkSize;
  }

  private int setRaftSegmentPreallocatedSize(RaftProperties properties) {
    final int raftSegmentPreallocatedSize = (int) conf.getStorageSize(
        OzoneConfigKeys.DFS_CONTAINER_RATIS_SEGMENT_PREALLOCATED_SIZE_KEY,
        OzoneConfigKeys.DFS_CONTAINER_RATIS_SEGMENT_PREALLOCATED_SIZE_DEFAULT,
        StorageUnit.BYTES);
    int logAppenderQueueNumElements = conf.getInt(
        OzoneConfigKeys.DFS_CONTAINER_RATIS_LOG_APPENDER_QUEUE_NUM_ELEMENTS,
        OzoneConfigKeys
            .DFS_CONTAINER_RATIS_LOG_APPENDER_QUEUE_NUM_ELEMENTS_DEFAULT);
    final int logAppenderQueueByteLimit = (int) conf.getStorageSize(
        OzoneConfigKeys.DFS_CONTAINER_RATIS_LOG_APPENDER_QUEUE_BYTE_LIMIT,
        OzoneConfigKeys
            .DFS_CONTAINER_RATIS_LOG_APPENDER_QUEUE_BYTE_LIMIT_DEFAULT,
        StorageUnit.BYTES);
    RaftServerConfigKeys.Log.Appender
        .setBufferElementLimit(properties, logAppenderQueueNumElements);
    RaftServerConfigKeys.Log.Appender.setBufferByteLimit(properties,
        SizeInBytes.valueOf(logAppenderQueueByteLimit));
    RaftServerConfigKeys.Log.setPreallocatedSize(properties,
        SizeInBytes.valueOf(raftSegmentPreallocatedSize));
    return raftSegmentPreallocatedSize;
  }

  private void setRaftSegmentSize(RaftProperties properties) {
    final int raftSegmentSize = (int)conf.getStorageSize(
        OzoneConfigKeys.DFS_CONTAINER_RATIS_SEGMENT_SIZE_KEY,
        OzoneConfigKeys.DFS_CONTAINER_RATIS_SEGMENT_SIZE_DEFAULT,
        StorageUnit.BYTES);
    RaftServerConfigKeys.Log.setSegmentSizeMax(properties,
        SizeInBytes.valueOf(raftSegmentSize));
  }

  private RpcType setRpcType(RaftProperties properties) {
    final String rpcType = conf.get(
        OzoneConfigKeys.DFS_CONTAINER_RATIS_RPC_TYPE_KEY,
        OzoneConfigKeys.DFS_CONTAINER_RATIS_RPC_TYPE_DEFAULT);
    final RpcType rpc = SupportedRpcType.valueOfIgnoreCase(rpcType);
    RaftConfigKeys.Rpc.setType(properties, rpc);
    return rpc;
  }

  public static XceiverServerRatis newXceiverServerRatis(
      DatanodeDetails datanodeDetails, OzoneConfiguration ozoneConf,
      ContainerDispatcher dispatcher, ContainerController containerController,
      CertificateClient caClient, StateContext context) throws IOException {
    int localPort = ozoneConf.getInt(
        OzoneConfigKeys.DFS_CONTAINER_RATIS_IPC_PORT,
        OzoneConfigKeys.DFS_CONTAINER_RATIS_IPC_PORT_DEFAULT);

    // Get an available port on current node and
    // use that as the container port
    if (ozoneConf.getBoolean(OzoneConfigKeys
            .DFS_CONTAINER_RATIS_IPC_RANDOM_PORT,
        OzoneConfigKeys.DFS_CONTAINER_RATIS_IPC_RANDOM_PORT_DEFAULT)) {
      localPort = 0;
    }
    GrpcTlsConfig tlsConfig = RatisHelper.createTlsServerConfigForDN(
          new SecurityConfig(ozoneConf), caClient);

    return new XceiverServerRatis(datanodeDetails, localPort, dispatcher,
        containerController, context, tlsConfig, ozoneConf);
  }

  @Override
  public void start() throws IOException {
    if (!isStarted) {
      LOG.info("Starting {} {} at port {}", getClass().getSimpleName(),
          server.getId(), getIPCPort());
      chunkExecutor.prestartAllCoreThreads();
      server.start();

      int realPort =
          ((RaftServerProxy) server).getServerRpc().getInetSocketAddress()
              .getPort();

      if (port == 0) {
        LOG.info("{} {} is started using port {}", getClass().getSimpleName(),
            server.getId(), realPort);
        port = realPort;
      }

      //register the real port to the datanode details.
      datanodeDetails.setPort(DatanodeDetails
          .newPort(DatanodeDetails.Port.Name.RATIS,
              realPort));

      isStarted = true;
    }
  }

  @Override
  public void stop() {
    if (isStarted) {
      try {
        // shutdown server before the executors as while shutting down,
        // some of the tasks would be executed using the executors.
        server.close();
        chunkExecutor.shutdown();
        isStarted = false;
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
  }

  @Override
  public int getIPCPort() {
    return port;
  }

  /**
   * Returns the Replication type supported by this end-point.
   *
   * @return enum -- {Stand_Alone, Ratis, Chained}
   */
  @Override
  public HddsProtos.ReplicationType getServerType() {
    return HddsProtos.ReplicationType.RATIS;
  }

  @VisibleForTesting
  public RaftServer getServer() {
    return server;
  }

  private void processReply(RaftClientReply reply) throws IOException {
    // NotLeader exception is thrown only when the raft server to which the
    // request is submitted is not the leader. The request will be rejected
    // and will eventually be executed once the request comes via the leader
    // node.
    NotLeaderException notLeaderException = reply.getNotLeaderException();
    if (notLeaderException != null) {
      throw notLeaderException;
    }
    StateMachineException stateMachineException =
        reply.getStateMachineException();
    if (stateMachineException != null) {
      throw stateMachineException;
    }
  }

  @Override
  public void submitRequest(ContainerCommandRequestProto request,
      HddsProtos.PipelineID pipelineID) throws IOException {
    RaftClientReply reply;
    try (Scope scope = TracingUtil
        .importAndCreateScope(
            "XceiverServerRatis." + request.getCmdType().name(),
            request.getTraceID())) {

      RaftClientRequest raftClientRequest =
          createRaftClientRequest(request, pipelineID,
              RaftClientRequest.writeRequestType());
      try {
        reply = server.submitClientRequestAsync(raftClientRequest).get();
      } catch (Exception e) {
        throw new IOException(e.getMessage(), e);
      }
      processReply(reply);
    }
  }

  private RaftClientRequest createRaftClientRequest(
      ContainerCommandRequestProto request, HddsProtos.PipelineID pipelineID,
      RaftClientRequest.Type type) {
    return new RaftClientRequest(clientId, server.getId(),
        RaftGroupId.valueOf(PipelineID.getFromProtobuf(pipelineID).getId()),
        nextCallId(), ContainerCommandRequestMessage.toMessage(request, null),
        type, null);
  }

  private GroupInfoRequest createGroupInfoRequest(
      HddsProtos.PipelineID pipelineID) {
    return new GroupInfoRequest(clientId, server.getId(),
        RaftGroupId.valueOf(PipelineID.getFromProtobuf(pipelineID).getId()),
        nextCallId());
  }

  private void handlePipelineFailure(RaftGroupId groupId,
      RoleInfoProto roleInfoProto) {
    String msg;
    UUID datanode = RatisHelper.toDatanodeId(roleInfoProto.getSelf());
    RaftPeerId id = RaftPeerId.valueOf(roleInfoProto.getSelf().getId());
    switch (roleInfoProto.getRole()) {
    case CANDIDATE:
      msg = datanode + " is in candidate state for " +
          roleInfoProto.getCandidateInfo().getLastLeaderElapsedTimeMs() + "ms";
      break;
    case LEADER:
      StringBuilder sb = new StringBuilder();
      sb.append(datanode).append(" has not seen follower/s");
      for (RaftProtos.ServerRpcProto follower : roleInfoProto.getLeaderInfo()
          .getFollowerInfoList()) {
        if (follower.getLastRpcElapsedTimeMs() > nodeFailureTimeoutMs) {
          sb.append(" ").append(RatisHelper.toDatanodeId(follower.getId()))
              .append(" for ").append(follower.getLastRpcElapsedTimeMs())
              .append("ms");
        }
      }
      msg = sb.toString();
      break;
    default:
      LOG.error("unknown state:" + roleInfoProto.getRole());
      throw new IllegalStateException("node" + id + " is in illegal role "
          + roleInfoProto.getRole());
    }

    triggerPipelineClose(groupId, msg,
        ClosePipelineInfo.Reason.PIPELINE_FAILED, false);
  }

  private void triggerPipelineClose(RaftGroupId groupId, String detail,
      ClosePipelineInfo.Reason reasonCode, boolean triggerHB) {
    PipelineID pipelineID = PipelineID.valueOf(groupId.getUuid());
    ClosePipelineInfo.Builder closePipelineInfo =
        ClosePipelineInfo.newBuilder()
            .setPipelineID(pipelineID.getProtobuf())
            .setReason(reasonCode)
            .setDetailedReason(detail);

    PipelineAction action = PipelineAction.newBuilder()
        .setClosePipeline(closePipelineInfo)
        .setAction(PipelineAction.Action.CLOSE)
        .build();
    context.addPipelineActionIfAbsent(action);
    // wait for the next HB timeout or right away?
    if (triggerHB) {
      context.getParent().triggerHeartbeat();
    }
    LOG.error(
        "pipeline Action " + action.getAction() + "  on pipeline " + pipelineID
            + ".Reason : " + action.getClosePipeline().getDetailedReason());
  }

  @Override
  public boolean isExist(HddsProtos.PipelineID pipelineId) {
    return raftGids.contains(
        RaftGroupId.valueOf(PipelineID.getFromProtobuf(pipelineId).getId()));
  }

  @Override
  public List<PipelineReport> getPipelineReport() {
    try {
      Iterable<RaftGroupId> gids = server.getGroupIds();
      List<PipelineReport> reports = new ArrayList<>();
      for (RaftGroupId groupId : gids) {
        reports.add(PipelineReport.newBuilder()
            .setPipelineID(PipelineID.valueOf(groupId.getUuid()).getProtobuf())
            .build());
      }
      return reports;
    } catch (Exception e) {
      return null;
    }
  }

  @VisibleForTesting
  public List<PipelineID> getPipelineIds() {
    Iterable<RaftGroupId> gids = server.getGroupIds();
    List<PipelineID> pipelineIDs = new ArrayList<>();
    for (RaftGroupId groupId : gids) {
      pipelineIDs.add(PipelineID.valueOf(groupId.getUuid()));
      LOG.info("pipeline id {}", PipelineID.valueOf(groupId.getUuid()));
    }
    return pipelineIDs;
  }

  void handleNodeSlowness(RaftGroupId groupId, RoleInfoProto roleInfoProto) {
    handlePipelineFailure(groupId, roleInfoProto);
  }

  void handleNoLeader(RaftGroupId groupId, RoleInfoProto roleInfoProto) {
    handlePipelineFailure(groupId, roleInfoProto);
  }

  void handleApplyTransactionFailure(RaftGroupId groupId,
      RaftProtos.RaftPeerRole role) {
    UUID dnId = RatisHelper.toDatanodeId(getServer().getId());
    String msg =
        "Ratis Transaction failure in datanode " + dnId + " with role " + role
            + " .Triggering pipeline close action.";
    triggerPipelineClose(groupId, msg,
        ClosePipelineInfo.Reason.STATEMACHINE_TRANSACTION_FAILED, true);
  }
  /**
   * The fact that the snapshot contents cannot be used to actually catch up
   * the follower, it is the reason to initiate close pipeline and
   * not install the snapshot. The follower will basically never be able to
   * catch up.
   *
   * @param groupId raft group information
   * @param roleInfoProto information about the current node role and
   *                      rpc delay information.
   * @param firstTermIndexInLog After the snapshot installation is complete,
   * return the last included term index in the snapshot.
   */
  void handleInstallSnapshotFromLeader(RaftGroupId groupId,
                                       RoleInfoProto roleInfoProto,
                                       TermIndex firstTermIndexInLog) {
    LOG.warn("Install snapshot notification received from Leader with " +
        "termIndex: {}, terminating pipeline: {}",
        firstTermIndexInLog, groupId);
    handlePipelineFailure(groupId, roleInfoProto);
  }

  /**
   * Notify the Datanode Ratis endpoint of Ratis log failure.
   * Expected to be invoked from the Container StateMachine
   * @param groupId the Ratis group/pipeline for which log has failed
   * @param t exception encountered at the time of the failure
   *
   */
  @VisibleForTesting
  public void handleNodeLogFailure(RaftGroupId groupId, Throwable t) {
    String msg = (t == null) ? "Unspecified failure reported in Ratis log"
        : t.getMessage();

    triggerPipelineClose(groupId, msg,
        ClosePipelineInfo.Reason.PIPELINE_LOG_FAILED, true);
  }

  public long getMinReplicatedIndex(PipelineID pipelineID) throws IOException {
    Long minIndex;
    GroupInfoReply reply = getServer()
        .getGroupInfo(createGroupInfoRequest(pipelineID.getProtobuf()));
    minIndex = RatisHelper.getMinReplicatedIndex(reply.getCommitInfos());
    return minIndex == null ? -1 : minIndex.longValue();
  }

  void notifyGroupRemove(RaftGroupId gid) {
    raftGids.remove(gid);
  }

  void notifyGroupAdd(RaftGroupId gid) {
    raftGids.add(gid);
  }
}
