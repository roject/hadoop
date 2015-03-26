/**
* Licensed to the Apache Software Foundation (ASF) under one
* or more contributor license agreements.  See the NOTICE file
* distributed with this work for additional information
* regarding copyright ownership.  The ASF licenses this file
* to you under the Apache License, Version 2.0 (the
* "License"); you may not use this file except in compliance
* with the License.  You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package org.apache.hadoop.yarn;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.ipc.ProtobufRpcEngine;
import org.apache.hadoop.ipc.RPC;
import org.apache.hadoop.ipc.Server;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.security.SecurityUtil;
import org.apache.hadoop.yarn.api.ApplicationClientProtocol;
import org.apache.hadoop.yarn.api.ContainerManagementProtocol;
import org.apache.hadoop.yarn.api.ContainerManagementProtocolPB;
import org.apache.hadoop.yarn.api.protocolrecords.GetContainerStatusesRequest;
import org.apache.hadoop.yarn.api.protocolrecords.GetContainerStatusesResponse;
import org.apache.hadoop.yarn.api.protocolrecords.GetNewApplicationRequest;
import org.apache.hadoop.yarn.api.protocolrecords.StartContainerRequest;
import org.apache.hadoop.yarn.api.protocolrecords.StartContainersRequest;
import org.apache.hadoop.yarn.api.protocolrecords.StartContainersResponse;
import org.apache.hadoop.yarn.api.protocolrecords.StopContainersRequest;
import org.apache.hadoop.yarn.api.protocolrecords.StopContainersResponse;
import org.apache.hadoop.yarn.api.records.ApplicationAttemptId;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.api.records.ContainerLaunchContext;
import org.apache.hadoop.yarn.api.records.ContainerState;
import org.apache.hadoop.yarn.api.records.ContainerStatus;
import org.apache.hadoop.yarn.api.records.NodeId;
import org.apache.hadoop.yarn.api.records.Priority;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.api.records.Token;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.exceptions.YarnException;
import org.apache.hadoop.yarn.factories.RecordFactory;
import org.apache.hadoop.yarn.factory.providers.RecordFactoryProvider;
import org.apache.hadoop.yarn.ipc.HadoopYarnProtoRPC;
import org.apache.hadoop.yarn.ipc.RPCUtil;
import org.apache.hadoop.yarn.ipc.YarnRPC;
import org.apache.hadoop.yarn.security.ContainerTokenIdentifier;
import org.apache.hadoop.yarn.server.api.CollectorNodemanagerProtocol;
import org.apache.hadoop.yarn.server.api.protocolrecords.GetTimelineCollectorContextRequest;
import org.apache.hadoop.yarn.server.api.protocolrecords.GetTimelineCollectorContextResponse;
import org.apache.hadoop.yarn.server.api.protocolrecords.ReportNewCollectorInfoRequest;
import org.apache.hadoop.yarn.server.api.protocolrecords.ReportNewCollectorInfoResponse;
import org.apache.hadoop.yarn.server.api.records.AppCollectorsMap;
import org.apache.hadoop.yarn.util.Records;
import org.junit.Assert;
import org.junit.Test;

public class TestRPC {

  private static final String EXCEPTION_MSG = "test error";
  private static final String EXCEPTION_CAUSE = "exception cause";
  private static final RecordFactory recordFactory = RecordFactoryProvider.getRecordFactory(null);

  public static final String ILLEGAL_NUMBER_MESSAGE =
      "collectors' number in ReportNewCollectorInfoRequest is not ONE.";

  public static final String DEFAULT_COLLECTOR_ADDR = "localhost:0";

  public static final ApplicationId DEFAULT_APP_ID =
      ApplicationId.newInstance(0, 0);

  @Test
  public void testUnknownCall() {
    Configuration conf = new Configuration();
    conf.set(YarnConfiguration.IPC_RPC_IMPL, HadoopYarnProtoRPC.class
        .getName());
    YarnRPC rpc = YarnRPC.create(conf);
    String bindAddr = "localhost:0";
    InetSocketAddress addr = NetUtils.createSocketAddr(bindAddr);
    Server server = rpc.getServer(ContainerManagementProtocol.class,
        new DummyContainerManager(), addr, conf, null, 1);
    server.start();

    // Any unrelated protocol would do
    ApplicationClientProtocol proxy = (ApplicationClientProtocol) rpc.getProxy(
        ApplicationClientProtocol.class, NetUtils.getConnectAddress(server), conf);

    try {
      proxy.getNewApplication(Records
          .newRecord(GetNewApplicationRequest.class));
      Assert.fail("Excepted RPC call to fail with unknown method.");
    } catch (YarnException e) {
      Assert.assertTrue(e.getMessage().matches(
          "Unknown method getNewApplication called on.*"
              + "org.apache.hadoop.yarn.proto.ApplicationClientProtocol"
              + "\\$ApplicationClientProtocolService\\$BlockingInterface protocol."));
    } catch (Exception e) {
      e.printStackTrace();
    } finally {
      server.stop();
    }
  }

  @Test
  public void testRPCOnCollectorNodeManagerProtocol() throws IOException {
    Configuration conf = new Configuration();
    conf.set(YarnConfiguration.IPC_RPC_IMPL, HadoopYarnProtoRPC.class
        .getName());
    YarnRPC rpc = YarnRPC.create(conf);
    String bindAddr = "localhost:0";
    InetSocketAddress addr = NetUtils.createSocketAddr(bindAddr);
    Server server = rpc.getServer(CollectorNodemanagerProtocol.class,
        new DummyNMCollectorService(), addr, conf, null, 1);
    server.start();

    // Test unrelated protocol wouldn't get response
    ApplicationClientProtocol unknownProxy = (ApplicationClientProtocol) rpc.getProxy(
        ApplicationClientProtocol.class, NetUtils.getConnectAddress(server), conf);

    try {
      unknownProxy.getNewApplication(Records
          .newRecord(GetNewApplicationRequest.class));
      Assert.fail("Excepted RPC call to fail with unknown method.");
    } catch (YarnException e) {
      Assert.assertTrue(e.getMessage().matches(
          "Unknown method getNewApplication called on.*"
              + "org.apache.hadoop.yarn.proto.ApplicationClientProtocol"
              + "\\$ApplicationClientProtocolService\\$BlockingInterface protocol."));
    } catch (Exception e) {
      e.printStackTrace();
    }

    // Test CollectorNodemanagerProtocol get proper response
    CollectorNodemanagerProtocol proxy = (CollectorNodemanagerProtocol)rpc.getProxy(
        CollectorNodemanagerProtocol.class, NetUtils.getConnectAddress(server), conf);
    // Verify request with DEFAULT_APP_ID and DEFAULT_COLLECTOR_ADDR get
    // normally response.
    try {
      ReportNewCollectorInfoRequest request =
          ReportNewCollectorInfoRequest.newInstance(
              DEFAULT_APP_ID, DEFAULT_COLLECTOR_ADDR);
      proxy.reportNewCollectorInfo(request);
    } catch (YarnException e) {
      Assert.fail("RPC call failured is not expected here.");
    }

    // Verify empty request get YarnException back (by design in
    // DummyNMCollectorService)
    try {
      proxy.reportNewCollectorInfo(Records
          .newRecord(ReportNewCollectorInfoRequest.class));
      Assert.fail("Excepted RPC call to fail with YarnException.");
    } catch (YarnException e) {
      Assert.assertTrue(e.getMessage().contains(ILLEGAL_NUMBER_MESSAGE));
    }

    // Verify request with a valid app ID
    try {
      GetTimelineCollectorContextRequest request =
          GetTimelineCollectorContextRequest.newInstance(
              ApplicationId.newInstance(0, 1));
      GetTimelineCollectorContextResponse response =
          proxy.getTimelineCollectorContext(request);
      Assert.assertEquals("test_user_id", response.getUserId());
      Assert.assertEquals("test_flow_id", response.getFlowId());
      Assert.assertEquals("test_flow_run_id", response.getFlowRunId());
    } catch (YarnException | IOException e) {
      Assert.fail("RPC call failured is not expected here.");
    }

    // Verify request with an invalid app ID
    try {
      GetTimelineCollectorContextRequest request =
          GetTimelineCollectorContextRequest.newInstance(
              ApplicationId.newInstance(0, 2));
      proxy.getTimelineCollectorContext(request);
      Assert.fail("RPC call failured is expected here.");
    } catch (YarnException | IOException e) {
      Assert.assertTrue(e instanceof  YarnException);
      Assert.assertTrue(e.getMessage().contains("The application is not found."));
    }
    server.stop();
  }

  @Test
  public void testHadoopProtoRPC() throws Exception {
    test(HadoopYarnProtoRPC.class.getName());
  }

  private void test(String rpcClass) throws Exception {
    Configuration conf = new Configuration();
    conf.set(YarnConfiguration.IPC_RPC_IMPL, rpcClass);
    YarnRPC rpc = YarnRPC.create(conf);
    String bindAddr = "localhost:0";
    InetSocketAddress addr = NetUtils.createSocketAddr(bindAddr);
    Server server = rpc.getServer(ContainerManagementProtocol.class,
            new DummyContainerManager(), addr, conf, null, 1);
    server.start();
    RPC.setProtocolEngine(conf, ContainerManagementProtocolPB.class, ProtobufRpcEngine.class);
    ContainerManagementProtocol proxy = (ContainerManagementProtocol)
        rpc.getProxy(ContainerManagementProtocol.class,
            NetUtils.getConnectAddress(server), conf);
    ContainerLaunchContext containerLaunchContext =
        recordFactory.newRecordInstance(ContainerLaunchContext.class);

    ApplicationId applicationId = ApplicationId.newInstance(0, 0);
    ApplicationAttemptId applicationAttemptId =
        ApplicationAttemptId.newInstance(applicationId, 0);
    ContainerId containerId =
        ContainerId.newContainerId(applicationAttemptId, 100);
    NodeId nodeId = NodeId.newInstance("localhost", 1234);
    Resource resource = Resource.newInstance(1234, 2);
    ContainerTokenIdentifier containerTokenIdentifier =
        new ContainerTokenIdentifier(containerId, "localhost", "user",
          resource, System.currentTimeMillis() + 10000, 42, 42,
          Priority.newInstance(0), 0);
    Token containerToken = newContainerToken(nodeId, "password".getBytes(),
          containerTokenIdentifier);

    StartContainerRequest scRequest =
        StartContainerRequest.newInstance(containerLaunchContext,
          containerToken);
    List<StartContainerRequest> list = new ArrayList<StartContainerRequest>();
    list.add(scRequest);
    StartContainersRequest allRequests =
        StartContainersRequest.newInstance(list);
    proxy.startContainers(allRequests);

    List<ContainerId> containerIds = new ArrayList<ContainerId>();
    containerIds.add(containerId);
    GetContainerStatusesRequest gcsRequest =
        GetContainerStatusesRequest.newInstance(containerIds);
    GetContainerStatusesResponse response =
        proxy.getContainerStatuses(gcsRequest);
    List<ContainerStatus> statuses = response.getContainerStatuses();

    //test remote exception
    boolean exception = false;
    try {
      StopContainersRequest stopRequest =
          recordFactory.newRecordInstance(StopContainersRequest.class);
      stopRequest.setContainerIds(containerIds);
      proxy.stopContainers(stopRequest);
      } catch (YarnException e) {
      exception = true;
      Assert.assertTrue(e.getMessage().contains(EXCEPTION_MSG));
      Assert.assertTrue(e.getMessage().contains(EXCEPTION_CAUSE));
      System.out.println("Test Exception is " + e.getMessage());
    } catch (Exception ex) {
      ex.printStackTrace();
    } finally {
      server.stop();
    }
    Assert.assertTrue(exception);
    Assert.assertNotNull(statuses.get(0));
    Assert.assertEquals(ContainerState.RUNNING, statuses.get(0).getState());
  }

  public class DummyContainerManager implements ContainerManagementProtocol {

    private List<ContainerStatus> statuses = new ArrayList<ContainerStatus>();

    @Override
    public GetContainerStatusesResponse getContainerStatuses(
        GetContainerStatusesRequest request)
    throws YarnException {
      GetContainerStatusesResponse response =
          recordFactory.newRecordInstance(GetContainerStatusesResponse.class);
      response.setContainerStatuses(statuses);
      return response;
    }

    @Override
    public StartContainersResponse startContainers(
        StartContainersRequest requests) throws YarnException {
      StartContainersResponse response =
          recordFactory.newRecordInstance(StartContainersResponse.class);
      for (StartContainerRequest request : requests.getStartContainerRequests()) {
        Token containerToken = request.getContainerToken();
        ContainerTokenIdentifier tokenId = null;

        try {
          tokenId = newContainerTokenIdentifier(containerToken);
        } catch (IOException e) {
          throw RPCUtil.getRemoteException(e);
        }
        ContainerStatus status =
            recordFactory.newRecordInstance(ContainerStatus.class);
        status.setState(ContainerState.RUNNING);
        status.setContainerId(tokenId.getContainerID());
        status.setExitStatus(0);
        statuses.add(status);

      }
      return response;
    }

    @Override
    public StopContainersResponse stopContainers(StopContainersRequest request)
    throws YarnException {
      Exception e = new Exception(EXCEPTION_MSG,
          new Exception(EXCEPTION_CAUSE));
      throw new YarnException(e);
    }
  }

  public static ContainerTokenIdentifier newContainerTokenIdentifier(
      Token containerToken) throws IOException {
    org.apache.hadoop.security.token.Token<ContainerTokenIdentifier> token =
        new org.apache.hadoop.security.token.Token<ContainerTokenIdentifier>(
            containerToken.getIdentifier()
                .array(), containerToken.getPassword().array(), new Text(
                containerToken.getKind()),
            new Text(containerToken.getService()));
    return token.decodeIdentifier();
  }

  public static Token newContainerToken(NodeId nodeId, byte[] password,
      ContainerTokenIdentifier tokenIdentifier) {
    // RPC layer client expects ip:port as service for tokens
    InetSocketAddress addr =
        NetUtils.createSocketAddrForHost(nodeId.getHost(), nodeId.getPort());
    // NOTE: use SecurityUtil.setTokenService if this becomes a "real" token
    Token containerToken =
        Token.newInstance(tokenIdentifier.getBytes(),
          ContainerTokenIdentifier.KIND.toString(), password, SecurityUtil
            .buildTokenService(addr).toString());
    return containerToken;
  }

  // A dummy implementation for CollectorNodemanagerProtocol for test purpose,
  // it only can accept one appID, collectorAddr pair or throw exceptions
  public class DummyNMCollectorService
      implements CollectorNodemanagerProtocol {

    @Override
    public ReportNewCollectorInfoResponse reportNewCollectorInfo(
        ReportNewCollectorInfoRequest request)
        throws YarnException, IOException {
      List<AppCollectorsMap> appCollectors = request.getAppCollectorsList();
      if (appCollectors.size() == 1) {
        // check default appID and collectorAddr
        AppCollectorsMap appCollector = appCollectors.get(0);
        Assert.assertEquals(appCollector.getApplicationId(),
            DEFAULT_APP_ID);
        Assert.assertEquals(appCollector.getCollectorAddr(),
            DEFAULT_COLLECTOR_ADDR);
      } else {
        throw new YarnException(ILLEGAL_NUMBER_MESSAGE);
      }

      ReportNewCollectorInfoResponse response =
          recordFactory.newRecordInstance(ReportNewCollectorInfoResponse.class);
      return response;
    }

    @Override
    public GetTimelineCollectorContextResponse getTimelineCollectorContext(
        GetTimelineCollectorContextRequest request)
        throws  YarnException, IOException {
      if (request.getApplicationId().getId() == 1) {
         return GetTimelineCollectorContextResponse.newInstance(
                "test_user_id", "test_flow_id", "test_flow_run_id");
      } else {
        throw new YarnException("The application is not found.");
      }
    }
  }

}
