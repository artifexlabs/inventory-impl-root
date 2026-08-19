/*
 * @formatter:off
 * Copyright © 2019 admin (admin@artifexlabs.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * @formatter:on
 */
package io.artifexlabs.inventory.impl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

/** The fake printer: a socket server capturing exactly what arrives. */
public class Tcp9100TransportTest {

  @Test
  public void deliversBytesVerbatim() throws Exception {
    byte[] payload = new byte[] { 0x1B, 0x40, 0x47, 0x10, 0x00, (byte) 0xFF, 0x1A };
    try (ServerSocket fake = new ServerSocket(0)) {
      CompletableFuture<byte[]> received = CompletableFuture.supplyAsync(() -> {
        try (var socket = fake.accept()) {
          return socket.getInputStream().readAllBytes();
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      });
      new Tcp9100Transport("localhost", fake.getLocalPort()).send(payload);
      assertArrayEquals(payload, received.get());
    }
  }

  @Test
  public void unreachablePrinterThrows() {
    assertThrows(IOException.class,
        () -> new Tcp9100Transport("localhost", 1, 250).send(new byte[] { 1 }));
  }
}
