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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import io.artifexlabs.inventory.api.DataEntry;

/**
 * {@link ArchiveScanner} for the zip family, using only {@code java.util.zip} — no new dependency, which is why zip
 * comes first. Tar and its compressed variants need commons-compress and are a separate, deliberate decision.
 *
 * <p>
 * <b>Reads the central directory, not the stream.</b> {@link ZipFile} seeks to the index at the end of the archive, so
 * a 50 GB zip is described in the time it takes to read a few kilobytes. A {@code ZipInputStream} would have to
 * decompress every entry to learn its size, which is exactly the cost this avoids — and it is why a scan can happen at
 * ingest while hashing waits for the worker.
 */
public class ZipArchiveScanner implements ArchiveScanner {

  /**
   * Extensions the zip container format actually covers. {@code .jar}, {@code .war} and friends ARE zips, and treating
   * them as archives is correct — a jar full of classes is a directory of files by any measure that matters here.
   */
  private final static List<String> ZIP_SUFFIXES = List.of(".zip", ".jar", ".war", ".ear", ".apk", ".epub", ".cbz",
      ".odt", ".ods", ".odp", ".docx", ".xlsx", ".pptx");

  @Override
  public boolean handles(String fileName) {
    if (fileName == null)
      return false;
    String lower = fileName.toLowerCase(Locale.ROOT);
    return ZIP_SUFFIXES.stream().anyMatch(lower::endsWith);
  }

  @Override
  public List<DataEntry> list(Path archive) throws IOException {
    List<DataEntry> out = new ArrayList<>();
    try (ZipFile zip = new ZipFile(archive.toFile())) {
      Enumeration<? extends ZipEntry> entries = zip.entries();
      while (entries.hasMoreElements()) {
        ZipEntry e = entries.nextElement();
        String name = e.getName();
        if (name.isBlank())
          continue;
        // A zip already marks directories with a trailing slash, which is the
        // exact convention DataEntry uses — so an empty directory inside an
        // archive survives into the manifest with no translation at all.
        boolean directory = e.isDirectory();
        long size = directory || e.getSize() < 0 ? 0L : e.getSize();
        java.time.Instant modified = e.getLastModifiedTime() == null ? null : e.getLastModifiedTime().toInstant();
        try {
          // no hash: a zip carries CRC32, which is neither digest HashAlgorithm
          // knows and far too weak to identify content by. The bytes get hashed
          // later, through the ordinary claim/complete protocol.
          out.add(new DataEntry(name, size, null, null, null, modified, List.of()));
        } catch (IllegalArgumentException refused) {
          // an entry whose path escapes the archive root (zip-slip) or is
          // otherwise unusable: skip THAT entry, keep the rest of the listing.
          // Refusing the whole archive because one member is malicious would
          // lose the description of everything legitimate in it.
          continue;
        }
      }
    }
    return List.copyOf(out);
  }
}
