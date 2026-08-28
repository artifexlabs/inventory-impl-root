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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import io.artifexlabs.inventory.api.DataEntry;
import io.artifexlabs.inventory.api.DataHashing;
import io.artifexlabs.inventory.api.DataInfo;
import io.artifexlabs.inventory.api.DataSystem;
import io.artifexlabs.inventory.api.DefaultItem;
import io.artifexlabs.inventory.api.HashAlgorithm;
import io.artifexlabs.inventory.api.Item;
import io.artifexlabs.inventory.api.MediaKind;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The worker that reads real bytes off a real tree.
 *
 * <p>
 * Everything else in stage 2 is protocol, proven without a filesystem on purpose. This is the one place a file is
 * actually opened, so it is where the three not-hashed outcomes have to be told apart for real: an absent medium marks
 * nothing, a missing file is damage, and a file that no longer matches its manifest is left alone.
 */
public class DataHasherTest {

  private InMemoryInventorySystem items;
  private InMemoryDataSystem data;
  private InMemoryDataHashing hashing;
  private DataHasher hasher;

  @BeforeEach
  public void setUp() {
    InMemoryAuditSink audit = new InMemoryAuditSink();
    this.items = new InMemoryInventorySystem(audit, "tester@example.com");
    this.data = new InMemoryDataSystem(this.items, audit, "tester@example.com");
    this.hashing = new InMemoryDataHashing(this.data, "tester@example.com");
    // batch of 2, so a run takes several claims rather than one
    this.hasher = new DataHasher(this.hashing, HashAlgorithm.SHA256, 2, 3600);
  }

  private static <T> T await(CompletionStage<T> stage) throws Exception {
    return stage.toCompletableFuture().get(10, TimeUnit.SECONDS);
  }

  private String medium(String name) throws Exception {
    Item created = await(this.items.createItem(name, name, "disc"));
    await(this.items.updateItem(DefaultItem.builder(created)
        .dataInfo(new DataInfo(MediaKind.PHYSICAL_MEDIA, false, false, "shelf-1")).build()));
    return created.getId();
  }

  private static Path write(Path root, String relative, String contents) throws IOException {
    Path file = root.resolve(relative);
    Files.createDirectories(file.getParent());
    Files.writeString(file, contents);
    return file;
  }

  /** What a find-shaped scan of this tree produces: every path, its size, its mtime, no hashes. */
  private static List<DataEntry> scan(Path root) throws IOException {
    List<DataEntry> out = new ArrayList<>();
    try (Stream<Path> walk = Files.walk(root)) {
      for (Path p : walk.filter(Files::isRegularFile).toList())
        out.add(new DataEntry(root.relativize(p).toString(), Files.size(p), null, null, null,
            Files.getLastModifiedTime(p).toInstant(), List.of()));
    }
    out.sort(java.util.Comparator.comparing(DataEntry::path));
    return out;
  }

  private static String sha256(byte[] bytes) throws Exception {
    StringBuilder sb = new StringBuilder();
    for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes))
      sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
    return sb.toString();
  }

  private String hashOf(String itemId, String path) throws Exception {
    return await(this.data.entriesOf(itemId, null, 0, 100)).stream().filter(e -> e.path().equals(path)).findFirst()
        .orElseThrow().hash();
  }

  @Test
  public void hashesEveryFileAndTheDigestsAreTheRealOnes() throws Exception {
    Path root = Files.createTempDirectory("medium");
    write(root, "docs/a.txt", "alpha");
    write(root, "docs/b.txt", "beta");
    write(root, "images/c.bin", "gamma");
    String disc = medium("disc-1");
    await(this.data.replaceManifest(disc, scan(root)));

    DataHasher.Outcome outcome;
    try (ContentSource source = new DirectorySource(root)) {
      outcome = this.hasher.hash(disc, source, "worker-a");
    }

    assertEquals(new DataHasher.Outcome(3, 0, 0, false), outcome);
    assertTrue(await(this.hashing.progressOf(disc)).complete());
    assertEquals(sha256("alpha".getBytes(StandardCharsets.UTF_8)), hashOf(disc, "docs/a.txt"),
        "the stored digest must be the digest of the bytes on the medium, not of anything about the row");
    // and the point of all of it: the file is now findable by content
    assertEquals(1,
        await(this.data.findByHash(HashAlgorithm.SHA256, sha256("gamma".getBytes(StandardCharsets.UTF_8)))).size());
  }

  @Test
  public void aRunLeavesTheContentIdentityCurrentWithoutBeingAskedTwice() throws Exception {
    Path root = Files.createTempDirectory("medium");
    write(root, "docs/a.txt", "alpha");
    write(root, "docs/b.txt", "beta");
    String disc = medium("disc-1");
    await(this.data.replaceManifest(disc, scan(root)));

    try (ContentSource source = new DirectorySource(root)) {
      this.hasher.hash(disc, source, "worker-a");
    }

    // no separate sweep command: the worker folded what it read into the tree
    String twin = medium("disc-2");
    await(this.data.replaceManifest(twin, scan(root)));
    try (ContentSource source = new DirectorySource(root)) {
      this.hasher.hash(twin, source, "worker-b");
    }
    List<DataSystem.SectionMatch> found = await(this.data.findDuplicateSections(
        new DataSystem.SectionQuery(null, DataSystem.Match.MERKLE, DataSystem.Scope.BOTH, 2, 0L, 0, 0, 50)));
    assertTrue(found.stream().anyMatch(m -> m.locations().size() == 2),
        "the two discs hold the same folder, and nobody had to remember to run a rollup: " + found);
  }

  @Test
  public void anAbsentMediumMarksNothingAtAll() throws Exception {
    Path root = Files.createTempDirectory("medium");
    write(root, "a.txt", "alpha");
    String disc = medium("disc-1");
    await(this.data.replaceManifest(disc, scan(root)));

    // the disc was described, then taken off the machine
    DataHasher.Outcome outcome;
    try (ContentSource source = new DirectorySource(root.resolve("not-mounted"))) {
      outcome = this.hasher.hash(disc, source, "worker-a");
    }

    assertTrue(outcome.mediumAbsent());
    DataHashing.Progress p = await(this.hashing.progressOf(disc));
    assertEquals(0, p.unreadable(),
        "an unplugged disc is not a disc full of damage; marking it so would poison the repair index");
    assertEquals(1, p.pending(), "the work is still waiting for the day the disc comes back");
  }

  @Test
  public void aFileThatVanishedBetweenScanAndHashIsDamageNotFailure() throws Exception {
    Path root = Files.createTempDirectory("medium");
    write(root, "a.txt", "alpha");
    Path doomed = write(root, "b.txt", "beta");
    String disc = medium("disc-1");
    await(this.data.replaceManifest(disc, scan(root)));
    Files.delete(doomed);

    DataHasher.Outcome outcome;
    try (ContentSource source = new DirectorySource(root)) {
      outcome = this.hasher.hash(disc, source, "worker-a");
    }

    assertEquals(1, outcome.hashed());
    assertEquals(1, outcome.unreadable());
    DataHashing.Progress p = await(this.hashing.progressOf(disc));
    assertTrue(p.complete(), "one bad file must not stop the medium finishing");
    assertEquals(1, p.unreadable(), "counted apart from done: this medium is finished but NOT intact");
    assertNull(hashOf(disc, "b.txt"));
  }

  @Test
  public void aFileThatChangedSinceTheManifestIsLeftAloneRatherThanMisattributed() throws Exception {
    Path root = Files.createTempDirectory("medium");
    write(root, "a.txt", "alpha");
    Path moved = write(root, "b.txt", "beta");
    String disc = medium("disc-1");
    await(this.data.replaceManifest(disc, scan(root)));
    Files.writeString(moved, "beta, but longer than the manifest remembers");

    DataHasher.Outcome outcome;
    try (ContentSource source = new DirectorySource(root)) {
      outcome = this.hasher.hash(disc, source, "worker-a");
    }

    assertEquals(1, outcome.hashed());
    assertEquals(1, outcome.stale(), "counted apart from damage: staleness wants a fresh manifest, not a repair");
    assertEquals(0, outcome.unreadable());
    assertNull(hashOf(disc, "b.txt"),
        "hashing it would attach the digest of THESE bytes to a row describing different ones");
    // the run has to end; the claim lapses with the lease rather than being retried in a loop
    assertFalse(await(this.hashing.progressOf(disc)).complete());
  }

  @Test
  public void aSymlinkIsNamedRatherThanHashedOrSkipped() throws Exception {
    Path root = Files.createTempDirectory("medium");
    Path real = write(root, "a.txt", "alpha");
    Path link = root.resolve("shortcut.txt");
    try {
      Files.createSymbolicLink(link, real);
    } catch (UnsupportedOperationException | IOException notPermitted) {
      org.junit.jupiter.api.Assumptions.abort("this filesystem will not make symlinks");
    }
    String disc = medium("disc-1");
    // a find-shaped scan uses -type f and would not list this; something else might
    await(
        this.data.replaceManifest(disc,
            List.of(new DataEntry("a.txt", Files.size(real), null, null, null,
                Files.getLastModifiedTime(real).toInstant(), List.of()),
                new DataEntry("shortcut.txt", 0L, null, null, null, null, List.of()))));

    DataHasher.Outcome outcome;
    try (ContentSource source = new DirectorySource(root)) {
      outcome = this.hasher.hash(disc, source, "worker-a");
    }

    assertEquals(1, outcome.hashed());
    assertEquals(1, outcome.unreadable(),
        "hashing it would attribute another medium's bytes to this one, and skipping it would leave the medium "
            + "pending forever");
    assertTrue(await(this.hashing.progressOf(disc)).complete());
    assertNull(hashOf(disc, "shortcut.txt"));
  }

  @Test
  public void aSecondRunOverAFinishedMediumDoesNothing() throws Exception {
    Path root = Files.createTempDirectory("medium");
    write(root, "a.txt", "alpha");
    String disc = medium("disc-1");
    await(this.data.replaceManifest(disc, scan(root)));
    try (ContentSource source = new DirectorySource(root)) {
      this.hasher.hash(disc, source, "worker-a");
      DataHasher.Outcome again = this.hasher.hash(disc, source, "worker-a");
      assertEquals(new DataHasher.Outcome(0, 0, 0, false), again, "the queue is in the store, so a re-run resumes");
      assertTrue(again.idle());
    }
  }

  @Test
  public void anArchiveIsHashedByTheSameWorkerThroughItsOwnItem() throws Exception {
    Path root = Files.createTempDirectory("medium");
    Path zip = root.resolve("backup.zip");
    try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
      for (String member : List.of("notes/one.txt", "notes/two.txt")) {
        ZipEntry e = new ZipEntry(member);
        e.setTime(1700000000000L);
        out.putNextEntry(e);
        out.write(("contents of " + member).getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
      }
    }
    // the medium holds one file, which is also a container: the scanner supplies
    // its listing from the central directory, so this costs no decompression
    List<DataEntry> inside = new ZipArchiveScanner().list(zip);
    DataEntry archive = new DataEntry("backup.zip", Files.size(zip), null, null, "application/zip",
        Files.getLastModifiedTime(zip).toInstant(), inside);
    String disc = medium("disc-1");
    await(this.data.replaceManifest(disc, List.of(archive)));
    String archiveId = await(this.items.getItem(disc)).orElseThrow().getContainedItems().orElseThrow().iterator().next()
        .getId();

    DataHasher.Outcome outcome;
    try (ContentSource source = new ArchiveSource(zip)) {
      outcome = this.hasher.hash(archiveId, source, "worker-a");
    }

    assertEquals(2, outcome.hashed());
    assertEquals(sha256("contents of notes/one.txt".getBytes(StandardCharsets.UTF_8)),
        hashOf(archiveId, "notes/one.txt"),
        "an archive is hashed through the identical claim/complete protocol a loose disc uses");
    // the medium's own row for the zip is a separate, still-unhashed file
    assertNull(hashOf(disc, "backup.zip"), "the container file and its contents are different work");
  }
}
