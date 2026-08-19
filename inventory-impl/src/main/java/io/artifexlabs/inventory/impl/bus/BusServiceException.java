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
package io.artifexlabs.inventory.impl.bus;

/**
 * A refused or failed bus action. The code is HTTP-aligned (400, 401, 403,
 * 404, 409, 503) and becomes the Vert.x reply failure code, so the HTTP
 * gateway translates one-to-one without a mapping table.
 */
public class BusServiceException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final int code;

  public BusServiceException(int code, String message) {
    super(message);
    this.code = code;
  }

  public int code() {
    return this.code;
  }

  public static BusServiceException badRequest(String message) {
    return new BusServiceException(400, message);
  }

  public static BusServiceException unauthorized(String message) {
    return new BusServiceException(401, message);
  }

  public static BusServiceException forbidden(String message) {
    return new BusServiceException(403, message);
  }

  public static BusServiceException notFound(String message) {
    return new BusServiceException(404, message);
  }

  public static BusServiceException conflict(String message) {
    return new BusServiceException(409, message);
  }

  public static BusServiceException unavailable(String message) {
    return new BusServiceException(503, message);
  }
}
