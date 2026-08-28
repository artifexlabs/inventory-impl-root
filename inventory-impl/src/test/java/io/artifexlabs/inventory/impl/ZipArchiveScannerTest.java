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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import io.artifexlabs.inventory.api.DataEntry;
import io.artifexlabs.inventory.api.DataTree;
import io.artifexlabs.inventory.api.HashAlgorithm;

import org.junit.jupiter.api.Test;

/** Listing an archive without extracting it, and what that buys. */
public class ZipArchiveScannerTest {

  private final ArchiveScanner scanner = new ZipArchiveScanner();

  private static Path zipOf(Path dir, String name, String... paths) throws IOException {
    Path zip = dir.resolve(name);
    try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
      for (String p : paths) {
        ZipEntry e = new ZipEntry(p);
        e.setTime(1700000000000L);
        out.putNextEntry(e);
        if (!p.endsWith("/"))
          out.write(("contents of " + p).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        out.closeEntry();
      }
    }
    return zip;
  }

  @Test
  public void listsEveryEntryWithSizeAndTimeButNoHash() throws Exception {
    Path dir = Files.createTempDirectory("zip");
    Path zip = zipOf(dir, "backup.zip", "docs/a.txt", "docs/b.txt");

    List<DataEntry> entries = this.scanner.list(zip);

    assertEquals(2, entries.size());
    DataEntry a = entries.stream().filter(e -> e.path().equals("docs/a.txt")).findFirst().orElseThrow();
    assertTrue(a.sizeBytes() > 0, "the central directory knows the size without decompressing anything");
    assertTrue(a.modified().isPresent());
    assertNull(a.hash(), "a zip carries CRC32, which is neither digest we know and far too weak to identify content");
  }

  @Test
  public void anEmptyDirectoryInsideAnArchiveSurvives() throws Exception {
    Path dir = Files.createTempDirectory("zip");
    Path zip = zipOf(dir, "backup.zip", "docs/a.txt", "docs/empty/");

    List<DataEntry> entries = this.scanner.list(zip);

    DataEntry empty = entries.stream().filter(DataEntry::isDirectory).findFirst()
        .orElseThrow(() -> new AssertionError("zip marks directories with a trailing slash — the same convention "
            + "DataEntry uses, so this should need no translation at all"));
    assertEquals("docs/empty/", empty.path());
  }

  @Test
  public void anArchivedCopyOfADirectoryMatchesTheLooseOriginalStructurally() throws Exception {
    Path dir = Files.createTempDirectory("zip");
    Path zip = zipOf(dir, "photos.zip", "photos/2019/a.jpg", "photos/2019/b.jpg");
    List<DataEntry> inside = this.scanner.list(zip);

    // the same two files, loose on some other medium, with the same sizes
    List<DataEntry> loose = inside.stream()
        .map(e -> new DataEntry(e.path(), e.sizeBytes(), null, null, null, e.modifiedAt(), List.of())).toList();

    var fromZip = DataTree.build(HashAlgorithm.SHA256, inside).stream().filter(n -> n.path().equals("photos/2019"))
        .findFirst().orElseThrow();
    var fromDisk = DataTree.build(HashAlgorithm.SHA256, loose).stream().filter(n -> n.path().equals("photos/2019"))
        .findFirst().orElseThrow();

    org.junit.jupiter.api.Assertions.assertArrayEquals(fromZip.structureHash(), fromDisk.structureHash(),
        "THE reason archives participate: a zipped copy of a folder is the same folder, and this is known on the day "
            + "it is scanned rather than after weeks of hashing");
  }

  @Test
  public void recognisesTheZipFamilyIncludingJars() {
    assertTrue(this.scanner.handles("backup.zip"));
    assertTrue(this.scanner.handles("Library.EPUB"), "matching is case-insensitive");
    assertTrue(this.scanner.handles("app.jar"), "a jar full of classes is a directory of files by any useful measure");
    assertFalse(this.scanner.handles("movie.mkv"));
    assertFalse(this.scanner.handles("backup.tar.gz"), "tar needs commons-compress and is a separate decision");
    assertFalse(this.scanner.handles(null));
  }

  @Test
  public void aCorruptArchiveFailsLoudlyRatherThanReportingItselfEmpty() throws Exception {
    Path dir = Files.createTempDirectory("zip");
    Path notAZip = dir.resolve("broken.zip");
    Files.writeString(notAZip, "this is definitely not a zip file");

    assertThrows(IOException.class, () -> this.scanner.list(notAZip),
        "an empty listing would silently claim the archive holds nothing, which is worse than an error");
  }

  @Test
  public void aMemberWhoseNameEscapesTheArchiveIsSkippedNotFatal() throws Exception {
    Path dir = Files.createTempDirectory("zip");
    // zip-slip: a member that would write outside the extraction root
    Path zip = zipOf(dir, "evil.zip", "docs/fine.txt", "../../etc/passwd");

    List<DataEntry> entries = this.scanner.list(zip);

    assertEquals(1, entries.size(), "the malicious member is dropped");
    assertEquals("docs/fine.txt", entries.get(0).path(),
        "and everything legitimate in the archive is still described — refusing the whole listing would lose it");
  }
}
