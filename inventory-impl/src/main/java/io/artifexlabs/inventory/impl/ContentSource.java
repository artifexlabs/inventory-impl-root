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
import java.io.InputStream;
import java.time.Instant;

/**
 * Where one scope's bytes come from — a mounted directory, or the inside of an archive.
 *
 * <p>
 * This is the seam that keeps {@link io.artifexlabs.inventory.api.DataHashing} free of filesystems. The protocol hands
 * out paths and takes back digests; something has to turn a path into bytes, and that something differs between a disc
 * on a shelf and a zip sitting on it. {@link DataHasher} is written once against this interface, so an archive is
 * hashed by exactly the code that hashes a loose tree.
 *
 * <p>
 * <b>{@link #present()} is not a detail.</b> A medium that is not mounted is not a medium full of unreadable files.
 * Recording every path on an unplugged disc as damaged would destroy the repair index that unreadable-recording exists
 * to build, so the hasher asks first and stops rather than marking anything.
 */
public interface ContentSource extends AutoCloseable {

  /** What the source says about a path right now, as opposed to what the manifest remembers. */
  record Stat(long sizeBytes, Instant modifiedAt) {
  }

  /** Whether the medium is here at all. Cheap: the hasher re-asks between files. */
  boolean present();

  /**
   * Size and mtime as they are at this moment, or {@code null} when the path is simply not there.
   *
   * <p>
   * Absent and unreadable are different answers and the hasher treats them differently in the reason it records, so
   * they must not be collapsed here.
   */
  Stat stat(String path) throws IOException;

  /** The bytes. The caller closes the stream; it does not close the source. */
  InputStream open(String path) throws IOException;

  @Override
  void close() throws IOException;
}
