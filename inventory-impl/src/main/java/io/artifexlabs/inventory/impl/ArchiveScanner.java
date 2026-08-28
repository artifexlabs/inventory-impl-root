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
import java.util.List;

import io.artifexlabs.inventory.api.DataEntry;

/**
 * Reads what is INSIDE an archive without extracting it.
 *
 * <p>
 * This lives in the impl rather than the api deliberately: the api never touches a filesystem, and listing an archive
 * is unavoidably file work. What crosses back is only {@link DataEntry} values — the same shape a {@code find}-shaped
 * manifest produces — so an archive's contents enter the system through exactly the path a loose directory's do.
 *
 * <p>
 * <b>Listing is cheap; hashing is not.</b> A zip's central directory already carries every entry's path, size and
 * mtime, so a whole archive can be described in the time it takes to read a few kilobytes at the end of the file. The
 * CONTENT of those entries still costs a decompression pass, and that runs later through the ordinary
 * {@link io.artifexlabs.inventory.api.DataHashing} protocol against the archive's own item. That split is the whole
 * reason archives can participate at all: an archived copy of a directory matches the loose original structurally on
 * the day it is scanned, and matches it by content whenever the hasher gets there.
 */
public interface ArchiveScanner {

  /** Whether this scanner recognises the file by name. Cheap, and wrong only in ways the listing then refuses. */
  boolean handles(String fileName);

  /**
   * List the archive's contents as manifest entries, paths relative to the ARCHIVE root rather than the medium's.
   *
   * <p>
   * Entries come back unhashed. A zip stores a CRC32, which is neither of the digests
   * {@link io.artifexlabs.inventory.api.HashAlgorithm} knows and is far too weak to identify content with, so it is
   * deliberately ignored rather than misrepresented as a hash.
   *
   * @throws IOException if the archive is unreadable or corrupt — the caller records that as an unreadable entry rather
   *                     than failing the whole medium
   */
  List<DataEntry> list(Path archive) throws IOException;
}
