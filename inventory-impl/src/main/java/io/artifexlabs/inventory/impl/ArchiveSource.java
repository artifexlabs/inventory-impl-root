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

import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The inside of an archive, as a source of bytes for the archive's OWN item.
 *
 * <p>
 * {@link ZipArchiveScanner} describes what is in there; this hands the same paths back as content. The pairing is what
 * makes an archive a first-class medium: it is listed in minutes from the central directory and hashed later at
 * whatever pace the decompression costs, through the identical claim/complete protocol a loose disc uses.
 *
 * <p>
 * The handle stays open for the whole run, so a large archive's index is read once rather than per file.
 */
public class ArchiveSource implements ContentSource {

  private final Path archive;
  private final ZipFile zip;

  public ArchiveSource(Path archive) throws IOException {
    this.archive = requireNonNull(archive, "archive");
    this.zip = new ZipFile(archive.toFile());
  }

  /** The archive file itself is the medium here; if it is gone, so is everything in it. */
  @Override
  public boolean present() {
    return Files.isReadable(this.archive);
  }

  @Override
  public Stat stat(String path) {
    ZipEntry e = this.zip.getEntry(path);
    if (e == null || e.isDirectory())
      return null;
    Instant modified = e.getLastModifiedTime() == null ? null
        : e.getLastModifiedTime().toInstant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    // getSize() is the UNCOMPRESSED size, which is what the scanner recorded and
    // therefore what the manifest is describing
    return new Stat(e.getSize() < 0 ? 0L : e.getSize(), modified);
  }

  @Override
  public InputStream open(String path) throws IOException {
    ZipEntry e = this.zip.getEntry(path);
    if (e == null)
      throw new IOException("no such member: " + path);
    return this.zip.getInputStream(e);
  }

  @Override
  public void close() throws IOException {
    this.zip.close();
  }
}
