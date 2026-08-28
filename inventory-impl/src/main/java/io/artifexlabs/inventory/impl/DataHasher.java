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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;

import io.artifexlabs.inventory.api.DataHashing;
import io.artifexlabs.inventory.api.HashAlgorithm;

/**
 * The half of hashing that touches a filesystem: claim work, read bytes, hand back digests.
 *
 * <p>
 * Everything else in stage 2 is protocol — deliberately testable without a disc. This is the piece that makes the
 * protocol do anything, and it is a plain blocking loop on purpose. Hashing a medium is IO-bound on the medium, not on
 * concurrency: a single reader saturates an optical drive or a spinning disk, and two readers on one spindle are slower
 * than one. Run it on a worker thread, never on an event loop.
 *
 * <p>
 * <b>Three ways a file does not get hashed, and they are not the same.</b>
 * <ul>
 * <li><b>The medium is absent.</b> The run stops having marked nothing. An unplugged disc is not a disc full of damage,
 * and recording it as such would poison the repair index.
 * <li><b>The file is unreadable or gone.</b> Recorded through {@link DataHashing#unreadable}, which is an OUTCOME: the
 * medium finishes, and the file becomes a repair target a sibling copy may be able to satisfy.
 * <li><b>The file no longer matches the manifest.</b> Left alone. Its size or mtime moved, so the description is stale,
 * and the answer is to re-describe the medium — not to attach a digest to a row that names a different file. The claim
 * is deliberately not released: it lapses with the lease, which is what stops this run from picking the same file up
 * again and looping on it forever.
 * </ul>
 *
 * <p>
 * A run begins by reclaiming expired claims and ends by sweeping the directory rollup, so restarting after a crash is
 * the whole recovery procedure and the content answers stay current without anyone remembering a second command.
 */
public class DataHasher {

  /** 1 MiB: big enough that sequential reads off spinning or optical media are not syscall-bound. */
  private final static int BUFFER = 1 << 20;

  /**
   * What one run did. {@code stale} is counted apart from {@code unreadable} because they call for opposite responses:
   * damage wants a repair from another copy, staleness wants a fresh manifest.
   */
  public record Outcome(int hashed, int unreadable, int stale, boolean mediumAbsent) {

    /** Nothing to do, or nothing doable — either way the caller should not loop on this medium immediately. */
    public boolean idle() {
      return this.hashed == 0 && this.unreadable == 0;
    }
  }

  private final DataHashing hashing;
  private final HashAlgorithm algorithm;
  private final int batchSize;
  private final long leaseSeconds;

  /** Defaults sized for a shelf-full of discs: SHA-256, 64 files a claim, an hour's lease. */
  public DataHasher(DataHashing hashing) {
    this(hashing, HashAlgorithm.SHA256, 64, 3600);
  }

  public DataHasher(DataHashing hashing, HashAlgorithm algorithm, int batchSize, long leaseSeconds) {
    this.hashing = requireNonNull(hashing, "hashing");
    this.algorithm = requireNonNull(algorithm, "algorithm");
    if (batchSize < 1)
      throw new IllegalArgumentException("batchSize must be positive");
    this.batchSize = batchSize;
    this.leaseSeconds = leaseSeconds;
  }

  /**
   * Hash what is left of one medium, blocking until the queue is empty or the medium goes away.
   *
   * <p>
   * Safe to call again after any interruption at all — a kill, a reboot, an unplugged drive. The queue is in the store,
   * so a second call resumes rather than restarts.
   */
  public Outcome hash(String itemId, ContentSource source, String worker) {
    requireNonNull(source, "source");
    if (!source.present())
      return new Outcome(0, 0, 0, true);
    // whatever a previous run died holding comes back first
    join(this.hashing.reclaimStale(itemId, this.leaseSeconds));

    Set<String> refused = new HashSet<>();
    int hashed = 0, damaged = 0, stale = 0;
    while (true) {
      List<DataHashing.PendingFile> batch = join(this.hashing.claim(itemId, worker, this.batchSize));
      if (batch.isEmpty())
        break;
      int actedOn = 0;
      for (DataHashing.PendingFile file : batch) {
        // a claim that returns only files this run already refused means the
        // manifest is stale for everything left; stop rather than spin
        if (refused.contains(file.path()))
          continue;
        actedOn++;
        if (!source.present())
          return new Outcome(hashed, damaged, stale, true);
        ContentSource.Stat now;
        try {
          now = source.stat(file.path());
        } catch (IOException unreadableStat) {
          join(this.hashing.unreadable(itemId, file.path(), reason(unreadableStat)));
          damaged++;
          continue;
        }
        if (now == null) {
          join(
              this.hashing.unreadable(itemId, file.path(), "gone: the manifest lists it, the medium does not hold it"));
          damaged++;
          continue;
        }
        if (now.sizeBytes() != file.sizeBytes() || !Objects.equals(now.modifiedAt(), file.modifiedAt())) {
          // the file moved on since the manifest; see the class note
          refused.add(file.path());
          stale++;
          continue;
        }
        String digest;
        try (InputStream in = source.open(file.path())) {
          digest = digestOf(in);
        } catch (IOException unreadableRead) {
          join(this.hashing.unreadable(itemId, file.path(), reason(unreadableRead)));
          damaged++;
          continue;
        }
        // complete re-checks size and mtime on its side too: the medium may have
        // been re-described while those bytes were being read
        if (join(this.hashing.complete(itemId, file, this.algorithm, digest))) {
          hashed++;
        } else {
          refused.add(file.path());
          stale++;
        }
      }
      if (actedOn == 0)
        break;
    }
    // Fold what was just read into the tree. Sweeping here rather than making it
    // the operator's job is the difference between MERKLE answers that are
    // current and MERKLE answers nobody remembered to compute — and a sweep over
    // a half-hashed medium is not premature: directories with unhashed
    // descendants simply get no digest, so the result is a true statement about
    // this moment either way.
    join(this.hashing.rollUp(itemId));
    return new Outcome(hashed, damaged, stale, false);
  }

  private String digestOf(InputStream in) throws IOException {
    MessageDigest md = digest();
    byte[] buffer = new byte[BUFFER];
    for (int read; (read = in.read(buffer)) > 0;)
      md.update(buffer, 0, read);
    return hex(md.digest());
  }

  private MessageDigest digest() {
    // BLAKE3 is a reserved escape hatch in HashAlgorithm with no JCA provider in
    // a stock JVM; fail loudly rather than hash a medium with the wrong function
    if (this.algorithm != HashAlgorithm.SHA256)
      throw new IllegalStateException("no JCA provider for " + this.algorithm.algorithmName());
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException missing) {
      throw new IllegalStateException("no JCA provider for SHA-256", missing);
    }
  }

  private static String hex(byte[] bytes) {
    StringBuilder sb = new StringBuilder(bytes.length * 2);
    for (byte b : bytes)
      sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
    return sb.toString();
  }

  /** Short enough for a repair listing, specific enough to act on. */
  private static String reason(IOException failure) {
    String message = failure.getMessage();
    return failure.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
  }

  private static <T> T join(CompletionStage<T> stage) {
    try {
      return stage.toCompletableFuture().get();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("hashing interrupted", interrupted);
    } catch (ExecutionException failed) {
      Throwable cause = failed.getCause() == null ? failed : failed.getCause();
      throw new IllegalStateException("hashing failed: " + cause.getMessage(), cause);
    }
  }
}
