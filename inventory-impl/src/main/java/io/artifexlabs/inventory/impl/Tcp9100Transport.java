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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Stage 3 of the label pipeline: deliver encoded printer commands over raw
 * TCP (the JetDirect/port-9100 convention both the Brother P750W's print
 * server and networked Zebras speak). Fire-and-forget: the printer does not
 * acknowledge at this layer.
 */
public class Tcp9100Transport {

  private final String host;
  private final int port;
  private final int connectTimeoutMs;

  public Tcp9100Transport(String host, int port) {
    this(host, port, 5000);
  }

  public Tcp9100Transport(String host, int port, int connectTimeoutMs) {
    this.host = host;
    this.port = port;
    this.connectTimeoutMs = connectTimeoutMs;
  }

  public void send(byte[] payload) throws IOException {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(this.host, this.port), this.connectTimeoutMs);
      socket.getOutputStream().write(payload);
      socket.getOutputStream().flush();
    }
  }
}
