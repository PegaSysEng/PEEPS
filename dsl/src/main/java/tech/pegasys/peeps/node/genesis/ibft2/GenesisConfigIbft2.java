/*
 * Copyright 2020 ConsenSys AG.
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
package tech.pegasys.peeps.node.genesis.ibft2;

import tech.pegasys.peeps.node.genesis.GenesisConfig;

import com.fasterxml.jackson.annotation.JsonGetter;

public class GenesisConfigIbft2 extends GenesisConfig {

  private final Ibft2Config consensusConfig;

  public GenesisConfigIbft2(final long chainId, final Ibft2Config consensusConfig) {
    super(chainId);
    this.consensusConfig = consensusConfig;
  }

  @JsonGetter("ibft2")
  public Ibft2Config getConsensusConfig() {
    return consensusConfig;
  }
}
