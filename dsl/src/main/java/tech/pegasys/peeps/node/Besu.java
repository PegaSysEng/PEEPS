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
package tech.pegasys.peeps.node;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.assertj.core.api.Assertions.assertThat;
import static tech.pegasys.peeps.util.HexFormatter.ensureHexPrefix;

import tech.pegasys.peeps.node.model.PrivacyTransactionReceipt;
import tech.pegasys.peeps.node.model.Transaction;
import tech.pegasys.peeps.node.model.TransactionReceipt;
import tech.pegasys.peeps.node.rpc.NodeJsonRpcClient;
import tech.pegasys.peeps.node.rpc.admin.NodeInfo;
import tech.pegasys.peeps.util.Await;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.common.collect.Lists;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.ContainerLaunchException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.containers.wait.strategy.Wait;

public class Besu {

  private static final Logger LOG = LogManager.getLogger();

  private static final String AM_I_ALIVE_ENDPOINT = "/liveness";
  private static final int ALIVE_STATUS_CODE = 200;

  private static final String BESU_IMAGE = "hyperledger/besu:latest";
  private static final int CONTAINER_HTTP_RPC_PORT = 8545;
  private static final int CONTAINER_WS_RPC_PORT = 8546;
  private static final int CONTAINER_P2P_PORT = 30303;
  private static final String CONTAINER_GENESIS_FILE = "/etc/besu/genesis.json";
  private static final String CONTAINER_PRIVACY_PUBLIC_KEY_FILE =
      "/etc/besu/privacy_public_key.pub";
  private static final String CONTAINER_NODE_PRIVATE_KEY_FILE = "/etc/besu/keys/node.priv";
  private static final String CONTAINER_PRIVACY_SIGNING_PRIVATE_KEY_FILE =
      "/etc/besu/keys/pmt_signing.priv";

  private final GenericContainer<?> besu;
  private final NodeJsonRpcClient jsonRpc;
  private String nodeId;
  private String enodeId;

  public Besu(final NodeConfiguration config) {

    final GenericContainer<?> container = new GenericContainer<>(BESU_IMAGE);
    final List<String> commandLineOptions = standardCommandLineOptions();

    addPeerToPeerHost(config, commandLineOptions);
    addCorsOrigins(config, commandLineOptions);
    addBootnodeAddress(config, commandLineOptions);
    addContainerNetwork(config, container);
    addContainerIpAddress(config, container);
    addNodePrivateKey(config, commandLineOptions, container);
    addGenesisFile(config, commandLineOptions, container);
    addPrivacy(config, commandLineOptions, container);

    LOG.info("Besu command line {}", commandLineOptions);

    this.besu =
        container.withCommand(commandLineOptions.toArray(new String[0])).waitingFor(liveliness());

    this.jsonRpc = new NodeJsonRpcClient(config.getVertx());
  }

  public void start() {
    try {
      besu.start();

      jsonRpc.bind(
          besu.getContainerId(),
          besu.getContainerIpAddress(),
          besu.getMappedPort(CONTAINER_HTTP_RPC_PORT));

      final NodeInfo info = jsonRpc.nodeInfo();
      nodeId = info.getId();
      enodeId = info.getEnode();

      // TODO validate the node has the expected state, e.g. consensus, genesis,
      // networkId,
      // protocol(s), ports, listen address

      logPortMappings();
      logContainerNetworkDetails();
    } catch (final ContainerLaunchException e) {
      LOG.error(besu.getLogs());
      throw e;
    }
  }

  public void stop() {
    if (besu != null) {
      besu.stop();
    }
    if (jsonRpc != null) {
      jsonRpc.close();
    }
  }

  public String getEnodeId() {
    return enodeId;
  }

  public void awaitConnectivity(final Besu... peers) {
    awaitPeerIdConnections(expectedPeerIds(peers));
  }

  // TODO these JSON-RPC call could do with encapsulating outside of Besu
  public PrivacyTransactionReceipt getPrivacyContractReceipt(final String receiptHash) {
    final Optional<PrivacyTransactionReceipt> potential = privacyContractReceipt(receiptHash);
    assertThat(potential).isNotNull();
    assertThat(potential).isPresent();
    return potential.get();
  }

  public TransactionReceipt getTransactionReceipt(final String receiptHash) {
    final Optional<TransactionReceipt> potential = transactiontReceipt(receiptHash);
    assertThat(potential).isNotNull();
    assertThat(potential).isPresent();
    return potential.get();
  }

  // TODO maybe tying together privacy functions?
  public Transaction getTransactionByHash(final String hash) {
    final Optional<Transaction> potential = transactionByHash(hash);
    assertThat(potential).isNotNull();
    assertThat(potential).isPresent();
    return potential.get();
  }

  private Optional<PrivacyTransactionReceipt> privacyContractReceipt(final String receiptHash) {
    // TODO find a way to avoid the additional call when successful
    Await.await(
        () -> {
          assertThat(jsonRpc.getPrivacyTransactionReceipt(receiptHash)).isPresent();
        },
        String.format(
            "Failed to retrieve the private transaction receipt with hash: %s", receiptHash));

    return jsonRpc.getPrivacyTransactionReceipt(receiptHash);
  }

  private Optional<TransactionReceipt> transactiontReceipt(final String receiptHash) {
    // TODO find a way to avoid the additional call when successful
    Await.await(
        () -> {
          assertThat(jsonRpc.getTransactionReceipt(receiptHash)).isPresent();
        },
        String.format("Failed to retrieve the transaction receipt with hash: %s", receiptHash));

    return jsonRpc.getTransactionReceipt(receiptHash);
  }

  private Optional<Transaction> transactionByHash(final String hash) {
    // TODO find a way to avoid the additional call when successful
    Await.await(
        () -> {
          assertThat(jsonRpc.getTransactionByHash(hash)).isPresent();
        },
        String.format("Failed to retrieve the transaction with hash: %s", hash));

    return jsonRpc.getTransactionByHash(hash);
  }

  public void log() {
    LOG.info("Besu Container {}", besu.getContainerId());
    LOG.info(besu.getLogs());
  }

  private String getNodeId() {
    checkNotNull(nodeId, "NodeId only exists after the node has started");
    return nodeId;
  }

  private void awaitPeerIdConnections(final Set<String> peerIds) {
    Await.await(
        () -> assertThat(jsonRpc.getConnectedPeerIds().containsAll(peerIds)).isTrue(),
        String.format("Failed to connect in time to peers: %s", peerIds));
  }

  private Set<String> expectedPeerIds(final Besu... peers) {
    return Arrays.stream(peers)
        .map(node -> ensureHexPrefix(node.getNodeId()))
        .collect(Collectors.toSet());
  }

  private HttpWaitStrategy liveliness() {
    return Wait.forHttp(AM_I_ALIVE_ENDPOINT)
        .forStatusCode(ALIVE_STATUS_CODE)
        .forPort(CONTAINER_HTTP_RPC_PORT);
  }

  private void logPortMappings() {
    LOG.info(
        "Besu Container {}, HTTP RPC port mapping: {} -> {}, WS RPC port mapping: {} -> {}, p2p port mapping: {} -> {}",
        besu.getContainerId(),
        CONTAINER_HTTP_RPC_PORT,
        besu.getMappedPort(CONTAINER_HTTP_RPC_PORT),
        CONTAINER_WS_RPC_PORT,
        besu.getMappedPort(CONTAINER_WS_RPC_PORT),
        CONTAINER_P2P_PORT,
        besu.getMappedPort(CONTAINER_P2P_PORT));
  }

  private void logContainerNetworkDetails() {
    if (besu.getNetwork() == null) {
      LOG.info("Besu Container {} has no network", besu.getContainerId());
    } else {
      LOG.info(
          "Besu Container {}, IP address: {}, Network: {}",
          besu.getContainerId(),
          besu.getContainerIpAddress(),
          besu.getNetwork().getId());
    }
  }

  private List<String> standardCommandLineOptions() {
    return Lists.newArrayList(
        "--logging",
        "DEBUG",
        "--miner-enabled",
        "--miner-coinbase",
        "1b23ba34ca45bb56aa67bc78be89ac00ca00da00",
        "--host-whitelist",
        "*",
        "--rpc-http-enabled",
        "--rpc-ws-enabled",
        "--rpc-http-apis",
        "ADMIN,ETH,NET,WEB3,EEA,PRIV");
  }

  private void addPeerToPeerHost(
      final NodeConfiguration config, final List<String> commandLineOptions) {
    commandLineOptions.add("--p2p-host");
    commandLineOptions.add(config.getIpAddress());
  }

  private void addBootnodeAddress(
      final NodeConfiguration config, final List<String> commandLineOptions) {
    config
        .getBootnodeEnodeAddress()
        .ifPresent(enode -> commandLineOptions.addAll(Lists.newArrayList("--bootnodes", enode)));
  }

  private void addContainerNetwork(
      final NodeConfiguration config, final GenericContainer<?> container) {
    container.withNetwork(config.getContainerNetwork());
  }

  private void addCorsOrigins(
      final NodeConfiguration config, final List<String> commandLineOptions) {

    config
        .getCors()
        .ifPresent(
            cors -> commandLineOptions.addAll(Lists.newArrayList("--rpc-http-cors-origins", cors)));
  }

  private void addNodePrivateKey(
      final NodeConfiguration config,
      final List<String> commandLineOptions,
      final GenericContainer<?> container) {
    config
        .getNodePrivateKeyFile()
        .ifPresent(
            file -> {
              container.withClasspathResourceMapping(
                  file, CONTAINER_NODE_PRIVATE_KEY_FILE, BindMode.READ_ONLY);
              commandLineOptions.addAll(
                  Lists.newArrayList("--node-private-key-file", CONTAINER_NODE_PRIVATE_KEY_FILE));
            });
  }

  private void addGenesisFile(
      final NodeConfiguration config,
      final List<String> commandLineOptions,
      final GenericContainer<?> container) {
    commandLineOptions.add("--genesis-file");
    commandLineOptions.add(CONTAINER_GENESIS_FILE);
    container.withClasspathResourceMapping(
        config.getGenesisFile(), CONTAINER_GENESIS_FILE, BindMode.READ_ONLY);
  }

  private void addPrivacy(
      final NodeConfiguration config,
      final List<String> commandLineOptions,
      final GenericContainer<?> container) {
    commandLineOptions.add("--privacy-enabled");
    commandLineOptions.add("--privacy-url");
    commandLineOptions.add(config.getPrivacyUrl());
    commandLineOptions.add("--privacy-public-key-file");
    commandLineOptions.add(CONTAINER_PRIVACY_PUBLIC_KEY_FILE);
    container.withClasspathResourceMapping(
        config.getPrivacyPublicKeyFile(), CONTAINER_PRIVACY_PUBLIC_KEY_FILE, BindMode.READ_ONLY);
    commandLineOptions.add("--privacy-marker-transaction-signing-key-file");
    commandLineOptions.add(CONTAINER_PRIVACY_SIGNING_PRIVATE_KEY_FILE);
    container.withClasspathResourceMapping(
        config.getPrivacyMarkerSigningPrivateKeyFile(),
        CONTAINER_PRIVACY_SIGNING_PRIVATE_KEY_FILE,
        BindMode.READ_ONLY);
  }

  private void addContainerIpAddress(
      final NodeConfiguration config, final GenericContainer<?> container) {
    container.withCreateContainerCmdModifier(
        modifier -> modifier.withIpv4Address(config.getIpAddress()));
  }
}
