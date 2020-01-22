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
package tech.pegasys.peeps.node.genesis;

import static org.assertj.core.api.Assertions.assertThat;

import tech.pegasys.peeps.json.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.DecodeException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class BesuGenesisFile {

  private static final Logger LOG = LogManager.getLogger();

  private final Path genesisFile;

  public BesuGenesisFile(final Path genesisFile) {
    this.genesisFile = genesisFile;
  }

  public void ensureExists(final Genesis genesis) {

    if (Files.exists(genesisFile)) {
      final Genesis existing;
      try {

        existing = Json.decode(Buffer.buffer(Files.readAllBytes(genesisFile)), Genesis.class);
      } catch (DecodeException e) {
        throw new IllegalStateException(
            String.format(
                "Problem decoding an existing Besu config file from the file system: %s, %s",
                genesisFile, e.getLocalizedMessage()));
      } catch (IOException e) {
        throw new IllegalStateException(
            String.format(
                "Problem reading an existing Besu config file in the file system: %s, %s",
                genesisFile, e.getLocalizedMessage()));
      }

      assertThat(genesis).usingRecursiveComparison().isEqualTo(existing);

      return;
    }

    final String encodedBesuGenesis = Json.encode(genesis);
    LOG.info(
        "Creating Besu genesis file\n\tLocation: {} \n\tContents: {}",
        genesisFile,
        encodedBesuGenesis);

    try {
      Files.write(
          genesisFile,
          encodedBesuGenesis.getBytes(StandardCharsets.UTF_8),
          StandardOpenOption.CREATE_NEW);
    } catch (final IOException e) {
      throw new IllegalStateException(
          String.format(
              "Problem creating the Besu config file in the file system: %s, %s",
              genesisFile, e.getLocalizedMessage()));
    }
  }

  public Path getGenesisFile() {
    return genesisFile;
  }
}
