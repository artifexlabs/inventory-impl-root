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

  /**
   * Overlap is still same content at the same PATH, and that is deliberate.
   *
   * <p>
   * The rule did not stop being useful when {@code findDuplicateSections} arrived; it stopped being the only rule. "Is
   * this disc a faithful copy of that one" wants location to matter — a file that moved is not in the same place —
   * while section matching exists precisely to ignore it. Both rules are asserted, next to each other, so a later
   * reader cannot "fix" one of them into the other.
   */
  @Test
  public void overlapIsSameContentAtTheSamePathWhileSectionsIgnoreLocation() throws Exception {
    String original = medium("backup-01");
    String copy = medium("backup-01-copy");
    String elsewhere = medium("junk-drawer");

    await(system().replaceManifest(original, List.of(file("docs/a.txt", HASH_A), file("docs/b.txt", HASH_B))));
    await(system().replaceManifest(copy, List.of(file("docs/a.txt", HASH_A), file("docs/b.txt", HASH_B))));
    // the same two files, as a folder, somewhere else entirely
    await(system().replaceManifest(elsewhere, List.of(file("random/a.txt", HASH_A), file("random/b.txt", HASH_B))));

    List<DataSystem.MediumOverlap> overlap = await(system().findOverlappingMedia(original));

    DataSystem.MediumOverlap best = overlap.get(0);
    assertEquals("backup-01-copy", best.itemName());
    assertEquals(2, best.sharedEntries(), "both files, at the same paths, with the same digests");
    assertEquals(200L, best.sharedBytes());
    assertTrue(best.contains(), "everything we hold is on theirs, so theirs is a superset");
    assertTrue(overlap.stream().noneMatch(o -> o.itemId().equals(elsewhere)),
        "same content at a DIFFERENT path is not overlap — which is exactly what sections is for");

    // and the other rule, on the same three media
    List<DataSystem.SectionMatch> sections = await(system().findDuplicateSections(
        new DataSystem.SectionQuery(null, DataSystem.Match.STRUCTURE, DataSystem.Scope.BOTH, 1, 0L, 0, 0, 50)));
    assertTrue(sections.stream().anyMatch(m -> m.locations().stream().anyMatch(l -> l.itemId().equals(elsewhere))),
        "the relocated copy IS found by section matching, because location is what that question ignores");
  }

  @Test
  public void aMediumNobodyHasHashedOverlapsNothing() throws Exception {
    String one = medium("unhashed-one");
    String two = medium("unhashed-two");
    await(system().replaceManifest(one, List.of(sized("docs/a.txt", 100L))));
    await(system().replaceManifest(two, List.of(sized("docs/a.txt", 100L))));

    assertTrue(await(system().findOverlappingMedia(one)).isEmpty(),
        "same path is not evidence of same content — that is what the digest is for, and there isn't one yet");
  }

  // ---- duplicated sections (PLAN.md Phase 23 stage 1) ----------------------
  //
  // The question findMirrorsOf structurally could not answer. It compares files
  // at the SAME path, so a folder copied elsewhere is invisible to it; here a
  // subtree carries its own identity and location is irrelevant.

  private static DataEntry sized(String path, long bytes) {
    return new DataEntry(path, bytes, null, null, null, null, List.of());
  }

  /** Ten files so a section clears the default size floor of 8. */
  private static List<DataEntry> tenUnder(String prefix) {
    List<DataEntry> out = new java.util.ArrayList<>();
    for (int i = 0; i < 10; i++)
      out.add(sized(prefix + "/f" + i + ".bin", 100L + i));
    return out;
  }

  @Test
  public void aRelocatedSubtreeIsFoundEvenThoughItsPathDiffers() throws Exception {
    String discA = medium("disc-a");
    String discB = medium("disc-b");
    await(system().replaceManifest(discA, tenUnder("photos/2019")));
    // same ten files, same names and sizes, buried somewhere else entirely
    await(system().replaceManifest(discB, tenUnder("backup/old/photos/2019")));

    List<DataSystem.SectionMatch> found = await(
        system().findDuplicateSections(DataSystem.SectionQuery.sane(null, DataSystem.Match.STRUCTURE)));

    assertTrue(found.stream().anyMatch(m -> m.subtreeFiles() == 10), "the ten-file subtree should be reported");
    DataSystem.SectionMatch match = found.stream().filter(m -> m.subtreeFiles() == 10).findFirst().orElseThrow();
    List<String> paths = match.locations().stream().map(DataSystem.SectionLocation::path).sorted().toList();
    assertEquals(List.of("backup/old/photos/2019", "photos/2019"), paths,
        "the SAME folder in a different place is the same folder — this is the whole point");
  }

  @Test
  public void theSizeFloorSuppressesCoincidentalMatches() throws Exception {
    String discA = medium("disc-a");
    String discB = medium("disc-b");
    // one identical file each: a real match, and a meaningless one
    await(system().replaceManifest(discA, List.of(sized("project/pom.xml", 500L))));
    await(system().replaceManifest(discB, List.of(sized("other/pom.xml", 500L))));

    List<DataSystem.SectionMatch> floored = await(
        system().findDuplicateSections(DataSystem.SectionQuery.sane(null, DataSystem.Match.STRUCTURE)));
    assertTrue(floored.isEmpty(),
        "a 'duplicated section' of one pom.xml is coincidence: 7,492 such directories exist on one real tree");

    List<DataSystem.SectionMatch> unfloored = await(system().findDuplicateSections(
        new DataSystem.SectionQuery(null, DataSystem.Match.STRUCTURE, DataSystem.Scope.BOTH, 1, 0L, 0, 0, 50)));
    assertTrue(!unfloored.isEmpty(), "and without the floor it IS reported — the floor is doing the work, not luck");
  }

  @Test
  public void duplicationWithinOneMediumIsFound() throws Exception {
    String disc = medium("snapshot-disc");
    // the shape a snapshotting filesystem produces: generations of one tree on
    // ONE medium, which is where that inventory's duplication actually lives
    List<DataEntry> both = new java.util.ArrayList<>(tenUnder("snapshots/5160/home"));
    both.addAll(tenUnder("snapshots/5161/home"));
    await(system().replaceManifest(disc, both));

    List<DataSystem.SectionMatch> found = await(system().findDuplicateSections(new DataSystem.SectionQuery(null,
        DataSystem.Match.STRUCTURE, DataSystem.Scope.WITHIN_MEDIUM, 8, 0L, 0, 0, 50)));

    DataSystem.SectionMatch match = found.stream().filter(m -> m.subtreeFiles() == 10).findFirst()
        .orElseThrow(() -> new AssertionError("a subtree repeated on the same medium must be found"));
    assertEquals(2, match.locations().size());
    assertEquals(List.of(disc, disc), match.locations().stream().map(DataSystem.SectionLocation::itemId).toList(),
        "both copies are on the same medium: self-matching is excluded by PATH, not by item");
  }

  @Test
  public void differentSizesUnderTheSameNamesAreNotAMatch() throws Exception {
    String discA = medium("disc-a");
    String discB = medium("disc-b");
    await(system().replaceManifest(discA, tenUnder("book")));
    List<DataEntry> resized = new java.util.ArrayList<>();
    for (int i = 0; i < 10; i++)
      resized.add(sized("book/f" + i + ".bin", 9000L + i)); // same names, different sizes
    await(system().replaceManifest(discB, resized));

    List<DataSystem.SectionMatch> found = await(
        system().findDuplicateSections(DataSystem.SectionQuery.sane(null, DataSystem.Match.STRUCTURE)));
    assertTrue(found.stream().noneMatch(m -> m.locations().size() > 1),
        "decision 2: size is in the structure digest, so identically-named but differently-sized trees differ");
  }

  @Test
  public void merkleMatchingIsEmptyUntilFilesAreHashed() throws Exception {
    String discA = medium("disc-a");
    String discB = medium("disc-b");
    await(system().replaceManifest(discA, tenUnder("photos")));
    await(system().replaceManifest(discB, tenUnder("photos")));

    assertTrue(
        await(system().findDuplicateSections(DataSystem.SectionQuery.sane(null, DataSystem.Match.MERKLE))).isEmpty(),
        "a content Merkle cannot exist before the bytes are read; empty is the honest answer");
  }

  // ---- hashes survive a re-describe -------------------------------------

  @Test
  public void reDescribingAMediumKeepsHashesForFilesThatDidNotChange() throws Exception {
    String disc = medium("backup-01");
    // as if the hasher had already run over this medium
    await(system().replaceManifest(disc, List.of(file("docs/a.txt", HASH_A), file("docs/b.txt", HASH_B))));

    // re-scanned: same files, same sizes, and this time the manifest carries no
    // hashes at all — which is what a find-shaped scan produces
    await(system().replaceManifest(disc, List.of(sized("docs/a.txt", 100L), sized("docs/b.txt", 100L))));

    List<DataEntry> after = await(system().entriesOf(disc, null, 0, 50));
    assertEquals(2, after.size());
    assertTrue(after.stream().allMatch(e -> e.hash() != null),
        "hashing a medium takes weeks; re-scanning it must not throw that away");
    assertEquals(HASH_A, after.stream().filter(e -> e.path().equals("docs/a.txt")).findFirst().orElseThrow().hash());
  }

  @Test
  public void aFileWhoseSizeChangedLosesItsHash() throws Exception {
    String disc = medium("backup-01");
    await(system().replaceManifest(disc, List.of(file("docs/a.txt", HASH_A), file("docs/b.txt", HASH_B))));

    // a.txt is a different file now, and only its size says so
    await(system().replaceManifest(disc, List.of(sized("docs/a.txt", 999L), sized("docs/b.txt", 100L))));

    List<DataEntry> after = await(system().entriesOf(disc, null, 0, 50));
    DataEntry changed = after.stream().filter(e -> e.path().equals("docs/a.txt")).findFirst().orElseThrow();
    DataEntry same = after.stream().filter(e -> e.path().equals("docs/b.txt")).findFirst().orElseThrow();
    assertEquals(null, changed.hash(),
        "keeping a digest for a file whose size moved would be a lie nothing could later detect");
    assertEquals(HASH_B, same.hash(), "and its unchanged neighbour is untouched");
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

  /** Every archive item this one contains. */
  private List<Item> archiveChildren(String itemId) throws Exception {
    return await(this.items.getItem(itemId)).orElseThrow().getContainedItems().orElse(java.util.Set.of()).stream()
        .filter(i -> i.getDataInfo().map(DataInfo::archive).orElse(false)).toList();
  }

  /** An archive at {@code path} whose single member is {@code member}. */
  private static DataEntry archive(String path, long bytes, DataEntry member) {
    return new DataEntry(path, bytes, null, null, "application/zip", null, List.of(member));
  }

  @Test
  public void reDescribingAMediumKeepsTheHashesInsideAnArchive() throws Exception {
    String disc = medium("backup-01");
    await(system().replaceManifest(disc, List.of(archive("backups/2019.zip", 4096L, file("notes.txt", HASH_B)))));
    String before = archiveChildren(disc).get(0).getId();

    // re-scanned by find, which knows every size and nothing about any content
    await(system().replaceManifest(disc, List.of(archive("backups/2019.zip", 4096L, sized("notes.txt", 100L)))));

    String after = archiveChildren(disc).get(0).getId();
    assertEquals(before, after,
        "an archive still at the same path is the same archive; a fresh item id strands every hash under it");
    assertEquals(HASH_B, await(system().entriesOf(after, null, 0, 50)).get(0).hash(),
        "weeks of hashing inside an archive must survive a re-describe exactly as the medium's own hashes do");
  }

  @Test
  public void twoArchivesSharingABasenameDoNotCrossTheirContents() throws Exception {
    String disc = medium("backup-01");
    // identity is the PATH, and mintArchive names an item after the basename
    // alone — so these two are indistinguishable by name and containment
    await(system().replaceManifest(disc, List.of(archive("alpha/backup.zip", 4096L, file("a.txt", HASH_A)),
        archive("beta/backup.zip", 8192L, file("b.txt", HASH_B)))));

    await(system().replaceManifest(disc, List.of(archive("alpha/backup.zip", 4096L, sized("a.txt", 100L)),
        archive("beta/backup.zip", 8192L, sized("b.txt", 100L)))));

    java.util.Map<String, String> hashByMember = new java.util.LinkedHashMap<>();
    for (Item child : archiveChildren(disc)) {
      DataEntry inside = await(system().entriesOf(child.getId(), null, 0, 50)).get(0);
      hashByMember.put(inside.path(), inside.hash());
    }
    assertEquals(java.util.Map.of("a.txt", HASH_A, "b.txt", HASH_B), hashByMember,
        "each backup.zip keeps its own contents; matching by name would swap them");
  }

  @Test
  public void renamingAnArchivesPathKeepsWhatWasHashedInsideIt() throws Exception {
    String disc = medium("backup-01");
    await(system().replaceManifest(disc, List.of(archive("backups/2019.zip", 4096L, file("notes.txt", HASH_B)))));
    String before = archiveChildren(disc).get(0).getId();

    assertEquals(1, await(system().renamePath(disc, "backups/2019.zip", "archive/2019.zip")));
    // the next scan describes it at its corrected path, hashing nothing
    await(system().replaceManifest(disc, List.of(archive("archive/2019.zip", 4096L, sized("notes.txt", 100L)))));

    assertEquals(before, archiveChildren(disc).get(0).getId(),
        "a rename corrects a path; it does not make the archive a different archive");
    assertEquals(HASH_B, await(system().entriesOf(before, null, 0, 50)).get(0).hash());
  }

  @Test
  public void anArchiveTheMediumNoLongerHoldsIsReaped() throws Exception {
    String disc = medium("backup-01");
    await(system().replaceManifest(disc,
        List.of(archive("backups/2019.zip", 4096L, file("notes.txt", HASH_B)), file("a.txt", HASH_A))));
    assertEquals(1, archiveChildren(disc).size());

    // the zip was deleted off the disc between scans
    await(system().replaceManifest(disc, List.of(file("a.txt", HASH_A))));

    assertTrue(archiveChildren(disc).isEmpty(),
        "reuse must not become never-clean-up: an archive that is genuinely gone takes its item with it");
    assertTrue(await(system().findByHash(HashAlgorithm.SHA256, HASH_B)).isEmpty(),
        "and its contents stop being findable, because they are no longer on any medium");
  }

  @Test
  public void aNestedArchiveIsReapedWithItsContainer() throws Exception {
    String disc = medium("backup-01");
    DataEntry inner = archive("inner.zip", 512L, file("deep.txt", HASH_B));
    await(system().replaceManifest(disc, List.of(archive("outer.zip", 4096L, inner))));
    assertEquals(1, archiveChildren(archiveChildren(disc).get(0).getId()).size(),
        "archives nest, and so do their items");

    await(system().replaceManifest(disc, List.of(file("a.txt", HASH_A))));

    assertTrue(archiveChildren(disc).isEmpty());
    assertTrue(await(system().findByHash(HashAlgorithm.SHA256, HASH_B)).isEmpty(),
        "the nested archive goes too, and only in that order: its container's rows are what referenced it");
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
