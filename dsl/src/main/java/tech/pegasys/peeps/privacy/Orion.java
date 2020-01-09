/*
 * Copyright 2019 ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package tech.pegasys.peeps.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static tech.pegasys.peeps.privacy.OrionConfigurationFile.write;
import static tech.pegasys.peeps.privacy.rpc.send.SendPayload.generateUniquePayload;

import tech.pegasys.peeps.network.NetworkMember;
import tech.pegasys.peeps.privacy.rpc.OrionRpc;
import tech.pegasys.peeps.util.ClasspathResources;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.ContainerLaunchException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

public class Orion implements NetworkMember {

  private static final Logger LOG = LogManager.getLogger();

  private static final String CONTAINER_WORKING_DIRECTORY_PREFIX = "/opt/orion/";
  private static final String CONTAINER_CONFIG_FILE = "/orion.conf";
  private static final String AM_I_ALIVE_ENDPOINT = "/upcheck";

  // TODO there should be the 'latest' version
  private static final String ORION_IMAGE = "pegasyseng/orion:develop";

  private static final int CONTAINER_PEER_TO_PEER_PORT = 8080;
  private static final int CONTAINER_HTTP_RPC_PORT = 8888;
  private static final int ALIVE_STATUS_CODE = 200;

  private final GenericContainer<?> orion;
  private final OrionRpc rpc;

  // TODO stronger typing than String
  private final String orionNetworkAddress;
  private final String networkRpcAddress;

  // TODO typing for key?
  private final String id;

  public Orion(final OrionConfiguration config) {

    write(config);

    final GenericContainer<?> container = new GenericContainer<>(ORION_IMAGE);
    addContainerNetwork(config, container);
    addContainerIpAddress(config, container);
    addPrivateKeys(config, container);
    addPublicKeys(config, container);

    this.orion =
        container
            .withCommand(CONTAINER_CONFIG_FILE)
            .withCopyFileToContainer(
                MountableFile.forHostPath(config.getFileSystemConfigurationFile()),
                CONTAINER_CONFIG_FILE)
            .waitingFor(liveliness());

    this.orionNetworkAddress =
        String.format("http://%s:%s", config.getIpAddress(), CONTAINER_PEER_TO_PEER_PORT);

    this.networkRpcAddress =
        String.format("http://%s:%s", config.getIpAddress(), CONTAINER_HTTP_RPC_PORT);

    // TODO just using the first key, selecting the identity could be an option for
    // multi-key Orion
    this.id = ClasspathResources.read(config.getPublicKeys().get(0));
    this.rpc = new OrionRpc(config.getVertx(), id);
  }

  public void awaitConnectivity(final List<Orion> peers) {
    excludeSelf(peers).parallelStream().forEach(peer -> awaitConnectivity(peer));
  }

  @Override
  public void start() {
    try {
      orion.start();

      rpc.bind(
          orion.getContainerId(),
          orion.getContainerIpAddress(),
          orion.getMappedPort(CONTAINER_HTTP_RPC_PORT));

      // TODO validate the node has the expected state, e.g. consensus, genesis,
      // networkId,
      // protocol(s), ports, listen address

      logOrionDetails();
      logPortMappings();
      logContainerNetworkDetails();
    } catch (final ContainerLaunchException e) {
      LOG.error(orion.getLogs());
      throw e;
    }
  }

  @Override
  public void stop() {
    if (orion != null) {
      orion.stop();
    }
    if (rpc != null) {
      rpc.close();
    }
  }

  public String getPeerNetworkAddress() {
    return orionNetworkAddress;
  }

  public String getNetworkRpcAddress() {
    return networkRpcAddress;
  }

  // TODO would be nice to not expose this
  public String getId() {
    return id;
  }

  // TODO stronger typing than String
  public String getPayload(final String receipt) {
    return rpc.receive(receipt);
  }

  private Set<Orion> excludeSelf(final List<Orion> peers) {
    return peers
        .parallelStream()
        .filter(peer -> !orionNetworkAddress.equals(peer.orionNetworkAddress))
        .collect(Collectors.toSet());
  }

  private void awaitConnectivity(final Orion peer) {
    final String message = generateUniquePayload();

    LOG.info(
        "Sending payload: %s, from Orion: %s, to Orion: %s",
        message, networkRpcAddress, peer.networkRpcAddress);

    final String receipt = rpc.send(peer.id, message);
    assertThat(receipt).isNotBlank();

    assertReceived(rpc, receipt, message);
    assertReceived(peer.rpc, receipt, message);
  }

  private void assertReceived(final OrionRpc rpc, final String receipt, final String sentMessage) {
    assertThat(rpc.receive(receipt)).isEqualTo(sentMessage);
  }

  private void addPrivateKeys(
      final OrionConfiguration config, final GenericContainer<?> container) {
    for (final String key : config.getPrivateKeys()) {
      container.withClasspathResourceMapping(
          key, containerWorkingDirectory(key), BindMode.READ_ONLY);
    }
  }

  private void addPublicKeys(final OrionConfiguration config, final GenericContainer<?> container) {
    for (final String key : config.getPublicKeys()) {
      container.withClasspathResourceMapping(
          key, containerWorkingDirectory(key), BindMode.READ_ONLY);
    }
  }

  private String containerWorkingDirectory(final String relativePath) {
    return CONTAINER_WORKING_DIRECTORY_PREFIX + relativePath;
  }

  private void logOrionDetails() {
    LOG.info("Orion Container {}, ID: {}", orion.getContainerId(), id);
  }

  private void logContainerNetworkDetails() {
    if (orion.getNetwork() == null) {
      LOG.info("Orion Container {} has no network", orion.getContainerId());
    } else {
      LOG.info(
          "Orion Container {}, IP address: {}, Network: {}",
          orion.getContainerId(),
          orion.getContainerIpAddress(),
          orion.getNetwork().getId());
    }
  }

  private void logPortMappings() {
    LOG.info(
        "Orion Container {}, HTTP RPC port mapping: {} -> {}, p2p port mapping: {} -> {}",
        orion.getContainerId(),
        CONTAINER_HTTP_RPC_PORT,
        orion.getMappedPort(CONTAINER_HTTP_RPC_PORT),
        CONTAINER_PEER_TO_PEER_PORT,
        orion.getMappedPort(CONTAINER_PEER_TO_PEER_PORT));
  }

  private HttpWaitStrategy liveliness() {
    return Wait.forHttp(AM_I_ALIVE_ENDPOINT)
        .forStatusCode(ALIVE_STATUS_CODE)
        .forPort(CONTAINER_HTTP_RPC_PORT);
  }

  private void addContainerNetwork(
      final OrionConfiguration config, final GenericContainer<?> container) {
    container.withNetwork(config.getContainerNetwork());
  }

  private void addContainerIpAddress(
      final OrionConfiguration config, final GenericContainer<?> container) {
    container.withCreateContainerCmdModifier(
        modifier -> modifier.withIpv4Address(config.getIpAddress()));
  }
}
