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
package io.artifexlabs.inventory.impl.tck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.artifexlabs.inventory.api.AuditReader;
import io.artifexlabs.inventory.api.DataEntry;
import io.artifexlabs.inventory.api.DataInfo;
import io.artifexlabs.inventory.api.DataSystem;
import io.artifexlabs.inventory.api.DefaultItem;
import io.artifexlabs.inventory.api.HashAlgorithm;
import io.artifexlabs.inventory.api.InventorySystem;
import io.artifexlabs.inventory.api.Item;
import io.artifexlabs.inventory.api.MediaKind;

/**
 * What a manifest MEANS, asserted against a BACKEND rather than an implementation — the first member of Phase 20's
 * parity kit (PLAN.md Phase 20, step 0).
 *
 * Every assertion here is a behavior each backend owes identically. A subclass supplies the backend through
 * {@link #backends()} and nothing in this class reaches past the {@link DataSystem} / {@link InventorySystem}
 * interfaces, so the same assertions run unchanged against the in-memory twin and against real Postgres. A behavior
 * added here is proven by every backend in the same reactor pass; that is the whole point of writing it once.
 */
public abstract class DataSystemTck {

  private final static String HASH_A = "aaaa000000000000000000000000000000000000000000000000000000000001";
  private final static String HASH_B = "bbbb000000000000000000000000000000000000000000000000000000000002";

  /** One backend's pieces, wired and empty, as a subclass supplies them. */
  public record Backends(InventorySystem items, DataSystem data, AuditReader audit) {
  }

  /**
   * A FRESH backend for each test: whatever isolation the store needs (a new map, a truncated schema) happens here, so
   * no assertion below has to know which one it is running against.
   */
  protected abstract Backends backends() throws Exception;

  private InventorySystem items;
  private DataSystem data;
  private AuditReader auditReader;

  private DataSystem system() {
    return this.data;
  }

  @BeforeEach
  public void setUpBackends() throws Exception {
    Backends b = backends();
    this.items = b.items();
    this.data = b.data();
    this.auditReader = b.audit();
  }

  private static <T> T await(java.util.concurrent.CompletionStage<T> stage) throws Exception {
    return stage.toCompletableFuture().get(10, TimeUnit.SECONDS);
  }

  /** A medium that can hold files. */
  private String medium(String name) throws Exception {
    Item created = await(this.items.createItem(name, name, "disc"));
    await(this.items.updateItem(DefaultItem.builder(created)
        .dataInfo(new DataInfo(MediaKind.PHYSICAL_MEDIA, false, false, "shelf-3")).build()));
    return created.getId();
  }

  private static DataEntry file(String path, String hash) {
    return DataEntry.of(path, 100L, HashAlgorithm.SHA256, hash);
  }

  @Test
  public void aManifestIsASnapshotSoReplacingItLeavesNoOrphans() throws Exception {
    String disc = medium("backup-01");

    assertEquals(Optional.of(2),
        await(system().replaceManifest(disc, List.of(file("a.txt", HASH_A), file("b.txt", HASH_B)))));
    // the second description is the whole truth, not an addition to the first
    assertEquals(Optional.of(1), await(system().replaceManifest(disc, List.of(file("a.txt", HASH_A)))));

    assertEquals(List.of("a.txt"), await(system().entriesOf(disc, null, 0, 50)).stream().map(DataEntry::path).toList());
    assertEquals(1, await(system().summaryOf(disc)).entryCount());
  }

  @Test
  public void aCrateHasNoFiles() throws Exception {
    Item crate = await(this.items.createItem("crate", "crate", "container"));
    // no DataInfo at all: this is a physical object, and refusing says so
    assertTrue(await(system().replaceManifest(crate.getId(), List.of(file("a.txt", HASH_A)))).isEmpty());
    assertTrue(await(system().replaceManifest("no-such-item", List.of(file("a.txt", HASH_A)))).isEmpty());
  }

  @Test
  public void findByHashAnswersWhichDiscHasThisFile() throws Exception {
    String first = medium("backup-01");
    String second = medium("backup-02");
    await(system().replaceManifest(first, List.of(file("photos/cat.jpg", HASH_A))));
    await(system().replaceManifest(second, List.of(file("archive/2019/cat.jpg", HASH_A), file("other.bin", HASH_B))));

    List<DataSystem.DataLocation> found = await(system().findByHash(HashAlgorithm.SHA256, HASH_A));

    assertEquals(2, found.size(), "the same bytes live on both discs");
    assertEquals(List.of("backup-01", "backup-02"), found.stream().map(DataSystem.DataLocation::itemName).toList());
    assertEquals(List.of("photos/cat.jpg", "archive/2019/cat.jpg"),
        found.stream().map(DataSystem.DataLocation::path).toList(), "each says where it sits on its own medium");
  }

  @Test
  public void dedupeIsPerAlgorithmByNature() throws Exception {
    String disc = medium("backup-01");
    await(system().replaceManifest(disc, List.of(file("a.txt", HASH_A))));
    // the same digest text under a different algorithm is a different fact
    assertTrue(await(system().findByHash(HashAlgorithm.BLAKE3, HASH_A)).isEmpty());
    assertFalse(await(system().findByHash(HashAlgorithm.SHA256, HASH_A)).isEmpty());
  }

  @Test
  public void mirrorsAreSameContentAtSamePath() throws Exception {
    String original = medium("backup-01");
    String copy = medium("backup-01-copy");
    String elsewhere = medium("junk-drawer");

    await(system().replaceManifest(original, List.of(file("docs/a.txt", HASH_A), file("docs/b.txt", HASH_B))));
    await(system().replaceManifest(copy, List.of(file("docs/a.txt", HASH_A), file("docs/b.txt", HASH_B))));
    // same bytes, different place: not a mirror of anything
    await(system().replaceManifest(elsewhere, List.of(file("random/a.txt", HASH_A))));

    List<DataSystem.DataLocation> mirrors = await(system().findMirrorsOf(original));

    assertEquals(List.of("backup-01-copy", "backup-01-copy"),
        mirrors.stream().map(DataSystem.DataLocation::itemName).toList());
    assertTrue(mirrors.stream().noneMatch(m -> m.itemId().equals(elsewhere)),
        "same content at a different path is not a mirror");
  }

  @Test
  public void renamingAPathKeepsTheContentHash() throws Exception {
    String disc = medium("backup-01");
    await(system().replaceManifest(disc, List.of(file("typo/a.txt", HASH_A), file("typo/b.txt", HASH_B))));

    // renaming a DIRECTORY takes every descendant with it
    assertEquals(2, await(system().renamePath(disc, "typo", "docs")));

    List<DataEntry> after = await(system().entriesOf(disc, null, 0, 50));
    assertEquals(List.of("docs/a.txt", "docs/b.txt"), after.stream().map(DataEntry::path).toList());
    assertEquals(List.of(HASH_A, HASH_B), after.stream().map(DataEntry::hash).toList(),
        "the file did not change, only its name did");
    // and the file is still findable by content, which is the point
    assertEquals("docs/a.txt", await(system().findByHash(HashAlgorithm.SHA256, HASH_A)).get(0).path());
  }

  @Test
  public void renamingSomethingAbsentChangesNothing() throws Exception {
    String disc = medium("backup-01");
    await(system().replaceManifest(disc, List.of(file("a.txt", HASH_A))));
    assertEquals(0, await(system().renamePath(disc, "nope", "still-nope")));
    assertEquals(List.of("a.txt"), await(system().entriesOf(disc, null, 0, 50)).stream().map(DataEntry::path).toList());
  }

  @Test
  public void anArchiveIsBothAFileAndAContainedItem() throws Exception {
    String disc = medium("backup-01");
    DataEntry zip = new DataEntry("backups/2019.zip", 4096L, HashAlgorithm.SHA256, HASH_A, "application/zip", null,
        List.of(file("notes.txt", HASH_B)));

    assertEquals(Optional.of(2), await(system().replaceManifest(disc, List.of(zip))), "the archive and its contents");

    // it is still one file on the medium
    List<DataEntry> onDisc = await(system().entriesOf(disc, null, 0, 50));
    assertEquals(List.of("backups/2019.zip"), onDisc.stream().map(DataEntry::path).toList());

    // and it became an item of its own, contained by the medium
    Item mediumItem = await(this.items.getItem(disc)).orElseThrow();
    Item archive = mediumItem.getContainedItems().orElse(java.util.Set.of()).stream()
        .filter(i -> i.getName().equals("2019.zip")).findFirst().orElseThrow();
    assertTrue(archive.getDataInfo().map(DataInfo::archive).orElse(false), "flagged as an archive");

    // carrying its own manifest, with paths relative to the archive root
    assertEquals(List.of("notes.txt"),
        await(system().entriesOf(archive.getId(), null, 0, 50)).stream().map(DataEntry::path).toList());
    // a file inside an archive is findable like any other file
    assertEquals(1, await(system().findByHash(HashAlgorithm.SHA256, HASH_B)).size());
  }

  @Test
  public void searchAndPagingWalkTheListingInPathOrder() throws Exception {
    String disc = medium("backup-01");
    await(system().replaceManifest(disc,
        List.of(file("docs/a.txt", HASH_A), file("docs/b.txt", HASH_B), file("images/c.png", HASH_A))));

    assertEquals(List.of("docs/a.txt", "docs/b.txt"),
        await(system().entriesOf(disc, "docs/", 0, 50)).stream().map(DataEntry::path).toList());
    assertEquals(List.of("docs/a.txt"),
        await(system().entriesOf(disc, null, 0, 1)).stream().map(DataEntry::path).toList());
    assertEquals(List.of("docs/b.txt"),
        await(system().entriesOf(disc, null, 1, 1)).stream().map(DataEntry::path).toList());
    assertTrue(await(system().entriesOf(disc, null, 99, 25)).isEmpty(), "past the end is empty, not an error");
  }

  @Test
  public void theSummaryRollsUpWhatTheMediumHolds() throws Exception {
    String disc = medium("backup-01");
    DataEntry zip = new DataEntry("z.zip", 4096L, HashAlgorithm.SHA256, HASH_A, null, null,
        List.of(file("inner.txt", HASH_B)));
    await(system().replaceManifest(disc, List.of(file("a.txt", HASH_A), zip)));

    DataSystem.ManifestSummary summary = await(system().summaryOf(disc));
    assertEquals(2, summary.entryCount(), "files on the medium itself");
    assertEquals(4196L, summary.totalBytes());
    assertEquals(1, summary.archiveCount());
    assertEquals(DataSystem.ManifestSummary.EMPTY, await(system().summaryOf("no-such-item")));
  }

  @Test
  public void theAuditRowCarriesCountsNotEntries() throws Exception {
    String disc = medium("backup-01");

    await(system().replaceManifest(disc, List.of(file("a.txt", HASH_A), file("b.txt", HASH_B))));

    var replaced = await(this.auditReader.recent(50, 0)).stream().filter(e -> e.getAction().equals("data.replace"))
        .findFirst().orElseThrow();
    // audit_events is the replay feed every consumer pages: a manifest in
    // there would tax every replay forever (the Phase 15 archival lesson)
    assertEquals(2, replaced.getDetails().orElseThrow().getInteger("entries"));
    assertEquals(200L, replaced.getDetails().orElseThrow().getLong("bytes"));
    assertFalse(replaced.getDetails().orElseThrow().toString().contains(HASH_A), "no entries rode along");
  }
}
