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

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;

import io.artifexlabs.inventory.api.DataEntry;
import io.artifexlabs.inventory.api.DataHashing;
import io.artifexlabs.inventory.api.DataInfo;
import io.artifexlabs.inventory.api.DataSystem;
import io.artifexlabs.inventory.api.DefaultItem;
import io.artifexlabs.inventory.api.HashAlgorithm;
import io.artifexlabs.inventory.api.InventorySystem;
import io.artifexlabs.inventory.api.Item;
import io.artifexlabs.inventory.api.MediaKind;

import org.junit.jupiter.api.Test;

/**
 * The claim/complete protocol, written once and proven by every backend.
 *
 * <p>
 * These behaviours only matter because hashing a real medium takes WEEKS: a disc is unplugged, a machine reboots, a
 * worker is killed, and none of that may lose a file or hash one twice. The drill below is stage 2's gate, and it is
 * the same shape as the projector's kill-and-restart test, for the same reason — a durable queue is only durable if you
 * have actually killed it.
 */
public abstract class DataHashingTck {

  /** Everything a backend must supply: the two systems, sharing one store. */
  public record Backends(InventorySystem items, DataSystem data, DataHashing hashing) {
  }

  protected abstract Backends backends() throws Exception;

  private Backends b;

  private DataSystem data() {
    return backends0().data();
  }

  private DataHashing hashing() {
    return backends0().hashing();
  }

  private Backends backends0() {
    if (this.b == null)
      try {
        this.b = backends();
      } catch (Exception e) {
        throw new IllegalStateException("backend setup failed", e);
      }
    return this.b;
  }

  private static <T> T await(CompletionStage<T> stage) throws InterruptedException, ExecutionException {
    return stage.toCompletableFuture().get();
  }

  private String medium(String name) throws Exception {
    Item created = await(backends0().items().createItem(name, name, "disc"));
    await(backends0().items().updateItem(DefaultItem.builder(created)
        .dataInfo(new DataInfo(MediaKind.PHYSICAL_MEDIA, false, false, "shelf-1")).build()));
    return created.getId();
  }

  /** An unhashed entry: what a find-shaped manifest actually produces. */
  private static DataEntry pending(String path, long size) {
    return new DataEntry(path, size, null, null, null, Instant.parse("2026-08-01T00:00:00Z"), List.of());
  }

  private static String digestFor(String path) {
    // deterministic stand-in; the protocol never inspects the bytes
    String h = Integer.toHexString(path.hashCode());
    return (h.repeat(64 / Math.max(h.length(), 1) + 1)).substring(0, 64);
  }

  private List<DataEntry> tenFiles() {
    List<DataEntry> out = new ArrayList<>();
    for (int i = 0; i < 10; i++)
      out.add(pending("data/f" + i + ".bin", 1000L + i));
    return out;
  }

  @Test
  public void aFreshManifestIsEntirelyPending() throws Exception {
    String disc = medium("disc");
    await(data().replaceManifest(disc, tenFiles()));

    DataHashing.Progress p = await(hashing().progressOf(disc));
    assertEquals(10, p.pending(), "a find-shaped manifest describes everything and hashes nothing");
    assertEquals(0, p.done());
    assertFalse(p.complete());
    assertEquals(0.0, p.fraction(), 0.001);
  }

  @Test
  public void anEmptyDirectoryIsNotWorkToHash() throws Exception {
    String disc = medium("disc");
    List<DataEntry> withDir = new ArrayList<>(tenFiles());
    // an empty directory earns an entry so it can be part of a structure hash
    withDir.add(pending("data/empty/", 0L));
    await(data().replaceManifest(disc, withDir));

    assertEquals(10, await(hashing().progressOf(disc)).pending(),
        "a directory marker describes a PLACE, not bytes; counting it makes a medium that can never finish");
    List<DataHashing.PendingFile> all = await(hashing().claim(disc, "w", 50));
    assertTrue(all.stream().noneMatch(f -> f.path().endsWith("/")),
        "and it is never handed to a worker, which would only report it unreadable: " + all);
  }

  @Test
  public void claimingTwiceNeverHandsOutTheSameFile() throws Exception {
    String disc = medium("disc");
    await(data().replaceManifest(disc, tenFiles()));

    List<DataHashing.PendingFile> first = await(hashing().claim(disc, "worker-a", 4));
    List<DataHashing.PendingFile> second = await(hashing().claim(disc, "worker-b", 4));

    assertEquals(4, first.size());
    assertEquals(4, second.size());
    Set<String> overlap = new HashSet<>(first.stream().map(DataHashing.PendingFile::path).toList());
    overlap.retainAll(second.stream().map(DataHashing.PendingFile::path).toList());
    assertTrue(overlap.isEmpty(), "two workers on one medium must never receive the same file: " + overlap);
  }

  @Test
  public void completingRecordsTheDigestAndAdvancesProgress() throws Exception {
    String disc = medium("disc");
    await(data().replaceManifest(disc, tenFiles()));
    DataHashing.PendingFile one = await(hashing().claim(disc, "w", 1)).get(0);

    assertTrue(await(hashing().complete(disc, one, HashAlgorithm.SHA256, digestFor(one.path()))));

    DataHashing.Progress p = await(hashing().progressOf(disc));
    assertEquals(1, p.done());
    assertEquals(9, p.pending());
    assertEquals(0, p.claimed());
    List<DataEntry> entries = await(data().entriesOf(disc, null, 0, 50));
    DataEntry hashed = entries.stream().filter(e -> e.path().equals(one.path())).findFirst().orElseThrow();
    assertEquals(digestFor(one.path()), hashed.hash(), "the digest has to reach the manifest, not just the queue");
  }

  @Test
  public void aFileThatChangedUnderneathIsRefused() throws Exception {
    String disc = medium("disc");
    await(data().replaceManifest(disc, tenFiles()));
    DataHashing.PendingFile claimed = await(hashing().claim(disc, "w", 1)).get(0);

    // the medium is re-described while the worker is reading: same path, new size
    List<DataEntry> changed = new ArrayList<>(tenFiles());
    changed.set(0, pending(claimed.path(), 999999L));
    await(data().replaceManifest(disc, changed));

    assertFalse(await(hashing().complete(disc, claimed, HashAlgorithm.SHA256, digestFor(claimed.path()))),
        "a digest computed against the file this medium USED to hold must not land on the row that replaced it");
  }

  @Test
  public void unreadableIsAnOutcomeAndStopsBlockingTheMedium() throws Exception {
    String disc = medium("disc");
    await(data().replaceManifest(disc, tenFiles()));
    DataHashing.PendingFile bad = await(hashing().claim(disc, "w", 1)).get(0);

    await(hashing().unreadable(disc, bad.path(), "I/O error: bad sector"));

    DataHashing.Progress p = await(hashing().progressOf(disc));
    assertEquals(1, p.unreadable());
    assertEquals(0, p.claimed(), "an unreadable file releases its claim, or the medium never finishes");
    assertEquals(9, p.pending());
    // and it is not handed out again on the next pass
    List<DataHashing.PendingFile> next = await(hashing().claim(disc, "w", 10));
    assertTrue(next.stream().noneMatch(f -> f.path().equals(bad.path())),
        "retrying a permanently bad sector forever would stall the run");
  }

  @Test
  public void staleClaimsComeBackSoADeadWorkerDoesNotStrandWork() throws Exception {
    String disc = medium("disc");
    await(data().replaceManifest(disc, tenFiles()));
    await(hashing().claim(disc, "worker-that-dies", 3));
    assertEquals(3, await(hashing().progressOf(disc)).claimed());

    // lease 0: everything currently held is by definition expired
    assertEquals(3, await(hashing().reclaimStale(disc, 0)));

    DataHashing.Progress p = await(hashing().progressOf(disc));
    assertEquals(0, p.claimed());
    assertEquals(10, p.pending(), "work a dead worker held must return, or the medium never completes");
  }

  /**
   * Stage 2's gate. Hash a medium in batches, killing the worker between them, and prove afterwards that every file was
   * hashed exactly once. This is the projector's drill applied to a different queue, for the same reason: a durable
   * queue is only durable if it has actually been killed.
   */
  @Test
  public void killAndRestartHashesEveryFileExactlyOnce() throws Exception {
    String disc = medium("disc");
    await(data().replaceManifest(disc, tenFiles()));

    List<String> hashedByAnyone = new ArrayList<>();
    int restarts = 0;
    while (!await(hashing().progressOf(disc)).complete()) {
      restarts++;
      // a "worker": claims a couple, finishes ONE, then dies mid-batch
      String worker = "worker-" + restarts;
      List<DataHashing.PendingFile> batch = await(hashing().claim(disc, worker, 2));
      if (!batch.isEmpty()) {
        DataHashing.PendingFile done = batch.get(0);
        assertTrue(await(hashing().complete(disc, done, HashAlgorithm.SHA256, digestFor(done.path()))));
        hashedByAnyone.add(done.path());
      }
      // the rest of the batch is stranded by the "crash" — recovered by lease
      assertTrue(await(hashing().reclaimStale(disc, 0)) >= 0);
      if (restarts > 50)
        throw new AssertionError("the run is not converging: " + await(hashing().progressOf(disc)));
    }

    DataHashing.Progress p = await(hashing().progressOf(disc));
    assertEquals(10, p.done(), "every file hashed");
    assertEquals(0, p.pending() + p.claimed(), "nothing left behind");
    assertEquals(10, new HashSet<>(hashedByAnyone).size(), "and none of them hashed twice: " + hashedByAnyone);
    assertEquals(1.0, p.fraction(), 0.001);
  }

  // ---- the rollup, and the answers it unlocks (PLAN.md Phase 23 stage 3) ----
  //
  // Everything below needs rollUp to have run. That is not a testing detail: on
  // both backends MERKLE and CONTENT identity live in the directory rollup, and
  // the rollup is a sweep rather than an incremental counter (decision 1). A
  // backend that answered these WITHOUT a sweep would be quietly ahead of the
  // other, which is the divergence this kit exists to prevent.

  /** A file with a distinct, well-formed digest keyed by {@code seed} — the content, without any bytes. */
  private static DataEntry withHash(String path, long size, String seed) {
    String h = Integer.toHexString(seed.hashCode()).replace("-", "f");
    return new DataEntry(path, size, HashAlgorithm.SHA256, h.repeat(64 / h.length() + 1).substring(0, 64), null,
        Instant.parse("2026-08-01T00:00:00Z"), List.of());
  }

  /** Ten hashed files under {@code prefix}; the contents depend on the file NAME, not on where the folder sits. */
  private static List<DataEntry> tenHashedUnder(String prefix) {
    List<DataEntry> out = new ArrayList<>();
    for (int i = 0; i < 10; i++)
      out.add(withHash(prefix + "/f" + i + ".bin", 100L + i, "content-" + i));
    return out;
  }

  private List<DataSystem.SectionMatch> sections(DataSystem.Match match) throws Exception {
    return await(data().findDuplicateSections(DataSystem.SectionQuery.sane(null, match)));
  }

  private static boolean groupSpans(List<DataSystem.SectionMatch> found, String... itemIds) {
    return found.stream().anyMatch(m -> {
      List<String> at = m.locations().stream().map(DataSystem.SectionLocation::itemId).toList();
      for (String id : itemIds)
        if (!at.contains(id))
          return false;
      return true;
    });
  }

  @Test
  public void contentIdentityDoesNotExistUntilTheSweepHasRun() throws Exception {
    String disc = medium("swept");
    String twin = medium("swept-twin");
    await(data().replaceManifest(disc, tenHashedUnder("photos")));
    await(data().replaceManifest(twin, tenHashedUnder("photos")));

    assertTrue(sections(DataSystem.Match.MERKLE).isEmpty(),
        "the files are hashed but nothing has folded them into a tree yet");

    assertTrue(await(hashing().rollUp(disc)) > 0);
    await(hashing().rollUp(twin));

    assertTrue(groupSpans(sections(DataSystem.Match.MERKLE), disc, twin), "and now the fold exists");
  }

  /**
   * Gate (a): a subtree that MOVED is found by content, and provably invisible to the path-equality rule that
   * {@code findOverlappingMedia} still uses. This is the question the old mirror query could not answer at all.
   */
  @Test
  public void aRelocatedSubtreeIsFoundByMerkleAndMissedByPathEquality() throws Exception {
    String here = medium("disc-here");
    String there = medium("disc-there");
    await(data().replaceManifest(here, tenHashedUnder("photos/2019")));
    // the same ten files, same names, same bytes, buried somewhere else
    await(data().replaceManifest(there, tenHashedUnder("backup/old/photos/2019")));
    await(hashing().rollUp(here));
    await(hashing().rollUp(there));

    assertTrue(groupSpans(sections(DataSystem.Match.MERKLE), here, there),
        "moved is still the same folder, by name and by content");
    assertTrue(await(data().findOverlappingMedia(here)).isEmpty(),
        "and the medium-level question deliberately says no: nothing is in the same PLACE");
  }

  /** Gate (b): dropping file names finds a rename that MERKLE correctly refuses to. */
  @Test
  public void aRenamedFileSurvivesContentMatchingAndBreaksMerkleMatching() throws Exception {
    String original = medium("disc-original");
    String renamed = medium("disc-renamed");
    await(data().replaceManifest(original, tenHashedUnder("stuff")));
    List<DataEntry> tweaked = new ArrayList<>(tenHashedUnder("stuff"));
    // one file renamed; the bytes behind it are untouched
    tweaked.set(3, withHash("stuff/renamed.bin", 103L, "content-3"));
    await(data().replaceManifest(renamed, tweaked));
    await(hashing().rollUp(original));
    await(hashing().rollUp(renamed));

    assertFalse(groupSpans(sections(DataSystem.Match.MERKLE), original, renamed),
        "MERKLE carries file names, so a rename makes it a different folder — as designed");
    assertTrue(groupSpans(sections(DataSystem.Match.CONTENT), original, renamed),
        "CONTENT drops names, which is the whole reason the noisier variant exists");
  }

  /** Gate (c): the three cases the old per-file return type could not tell apart. */
  @Test
  public void identicalAndContainsDistinguishACopyFromASuperset() throws Exception {
    String disc = medium("overlap-disc");
    String twin = medium("overlap-twin");
    String bigger = medium("overlap-bigger");
    await(data().replaceManifest(disc, tenHashedUnder("data")));
    await(data().replaceManifest(twin, tenHashedUnder("data")));
    List<DataEntry> more = new ArrayList<>(tenHashedUnder("data"));
    more.add(withHash("data/extra.bin", 500L, "content-extra"));
    await(data().replaceManifest(bigger, more));
    for (String m : List.of(disc, twin, bigger))
      await(hashing().rollUp(m));

    List<DataSystem.MediumOverlap> found = await(data().findOverlappingMedia(disc));
    DataSystem.MediumOverlap sameTree = found.stream().filter(o -> o.itemId().equals(twin)).findFirst().orElseThrow();
    DataSystem.MediumOverlap superset = found.stream().filter(o -> o.itemId().equals(bigger)).findFirst().orElseThrow();

    assertTrue(sameTree.identical(), "the same tree all the way down");
    assertTrue(sameTree.contains());
    assertFalse(superset.identical(), "a superset is NOT the same tree, and conflating them loses the answer");
    assertTrue(superset.contains(), "but everything we hold is on it — the 'which backup is newer' answer");
    assertEquals(11, superset.theirEntries());
    assertEquals(10, superset.ourEntries());
  }

  /**
   * Gate (d): partial equality has to be sound BY CONSTRUCTION.
   *
   * <p>
   * An unreadable file contributes nothing to a Merkle, so a damaged folder hashes exactly like an intact folder
   * holding only the readable half. Comparing Merkles alone would call those two copies of each other. They are not.
   */
  @Test
  public void identicallyDamagedMediaMatchAndADamagedOneNeverMatchesAnIntactOne() throws Exception {
    String damaged = medium("damaged-one");
    String alsoDamaged = medium("damaged-two");
    String intact = medium("intact");
    // ten files, the tenth unreadable on two of the three media
    await(data().replaceManifest(damaged, tenHashedUnder("data")));
    await(data().replaceManifest(alsoDamaged, tenHashedUnder("copy/data")));
    // the intact medium holds only the nine that the other two could actually read
    await(data().replaceManifest(intact, tenHashedUnder("data").subList(0, 9)));
    await(hashing().unreadable(damaged, "data/f9.bin", "I/O error: bad sector"));
    await(hashing().unreadable(alsoDamaged, "copy/data/f9.bin", "I/O error: bad sector"));
    for (String m : List.of(damaged, alsoDamaged, intact))
      await(hashing().rollUp(m));

    List<DataSystem.SectionMatch> found = sections(DataSystem.Match.MERKLE);
    assertTrue(groupSpans(found, damaged, alsoDamaged),
        "identically damaged, and it survives relocation for the same reason content does");
    assertFalse(groupSpans(found, damaged, intact),
        "the digests agree because the missing file contributes nothing — the damage is what tells them apart");
  }

  @Test
  public void aDamagedFileIsAnAskableRepairTargetAndAnUnrecoverableOneSaysSo() throws Exception {
    String broken = medium("repair-broken");
    String sibling = medium("repair-sibling");
    await(data().replaceManifest(broken, List.of(withHash("docs/keep.txt", 10L, "keep"), pending("docs/lost.txt", 20L),
        pending("docs/orphan.txt", 30L))));
    // a sibling copy that CAN read the file this one could not
    await(data().replaceManifest(sibling,
        List.of(withHash("docs/keep.txt", 10L, "keep"), withHash("docs/lost.txt", 20L, "lost"))));
    await(hashing().unreadable(broken, "docs/lost.txt", "I/O error: bad sector"));
    await(hashing().unreadable(broken, "docs/orphan.txt", "permission denied"));

    List<DataHashing.Repair> repairs = await(hashing().findRepairs(broken));

    assertEquals(List.of("docs/lost.txt", "docs/orphan.txt"),
        repairs.stream().map(DataHashing.Repair::path).sorted().toList());
    DataHashing.Repair recoverable = repairs.stream().filter(r -> r.path().equals("docs/lost.txt")).findFirst()
        .orElseThrow();
    assertEquals(List.of(sibling), recoverable.availableOn().stream().map(DataSystem.DataLocation::itemId).toList(),
        "this is why unreadable is an outcome and not an error");
    assertEquals("I/O error: bad sector", recoverable.reason());
    DataHashing.Repair lost = repairs.stream().filter(r -> r.path().equals("docs/orphan.txt")).findFirst()
        .orElseThrow();
    assertTrue(lost.availableOn().isEmpty(),
        "nothing catalogued holds it: the most important row in the answer, and an inner join would drop it");
  }

  @Test
  public void repairTargetsSurviveReDescribingTheMedium() throws Exception {
    String disc = medium("repair-rescan");
    await(data().replaceManifest(disc, List.of(pending("docs/lost.txt", 20L), pending("docs/fine.txt", 10L))));
    await(hashing().unreadable(disc, "docs/lost.txt", "I/O error: bad sector"));

    // the disc is re-scanned; the same two files, described identically
    await(data().replaceManifest(disc, List.of(pending("docs/lost.txt", 20L), pending("docs/fine.txt", 10L))));

    List<DataHashing.Repair> repairs = await(hashing().findRepairs(disc));
    assertEquals(1, repairs.size(),
        "the repair index is rebuilt only by reading the medium again; losing it on every re-scan would undo the "
            + "reason unreadable is an outcome");
    assertEquals("I/O error: bad sector", repairs.get(0).reason());
  }

  @Test
  public void aSweepIsIdempotentAndReflectsWhateverIsTrueRightNow() throws Exception {
    String disc = medium("sweep-twice");
    List<DataEntry> half = new ArrayList<>(tenHashedUnder("data").subList(0, 5));
    half.addAll(tenFiles().subList(5, 10));
    await(data().replaceManifest(disc, half));

    await(hashing().rollUp(disc));
    assertTrue(sections(DataSystem.Match.MERKLE).isEmpty(),
        "half a subtree has no content identity, so a sweep mid-run reports the truth rather than a guess");

    await(data().replaceManifest(disc, tenHashedUnder("data")));
    int first = await(hashing().rollUp(disc));
    int second = await(hashing().rollUp(disc));
    assertEquals(first, second, "running it twice costs time and changes nothing");
  }

  @Test
  public void aMediumWithUnreadableFilesFinishesButIsNotIntact() throws Exception {
    String disc = medium("disc");
    await(data().replaceManifest(disc, tenFiles()));
    for (DataHashing.PendingFile f : await(hashing().claim(disc, "w", 10)))
      if (f.path().endsWith("f3.bin"))
        await(hashing().unreadable(disc, f.path(), "permission denied"));
      else
        await(hashing().complete(disc, f, HashAlgorithm.SHA256, digestFor(f.path())));

    DataHashing.Progress p = await(hashing().progressOf(disc));
    assertTrue(p.complete(), "nothing is pending, so the run is over");
    assertEquals(9, p.done());
    assertEquals(1, p.unreadable(),
        "counted apart from done: a medium that finished with damage is finished but NOT intact");
  }
}
