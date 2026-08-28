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
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * A mounted medium: manifest paths resolved under one root.
 *
 * <p>
 * The root is the MOUNT POINT, not part of any path in the manifest — a disc described at {@code /mnt/disc1} and later
 * mounted at {@code /Volumes/disc1} is the same disc, and nothing stored about it says otherwise.
 */
public class DirectorySource implements ContentSource {

  private final Path root;

  public DirectorySource(Path root) {
    this.root = requireNonNull(root, "root").toAbsolutePath().normalize();
  }

  @Override
  public boolean present() {
    return Files.isDirectory(this.root);
  }

  @Override
  public Stat stat(String path) throws IOException {
    Path file = resolve(path);
    try {
      // NOFOLLOW_LINKS: a symlink's target may not even be on this medium, and
      // hashing it would attribute another disc's bytes to this one
      BasicFileAttributes a = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (a.isSymbolicLink())
        // Recorded, not silently skipped: a manifest should not contain symlinks
        // at all (`find -type f` already excludes them), and one that does would
        // otherwise sit pending forever and the medium would never finish. The
        // reason says what it is, so the repair list stays readable.
        throw new IOException("symbolic link: its target's bytes belong to whichever medium actually holds them");
      if (!a.isRegularFile())
        throw new IOException("not a regular file: " + (a.isDirectory() ? "directory" : "device, socket or fifo"));
      return new Stat(a.size(), a.lastModifiedTime().toInstant());
    } catch (NoSuchFileException gone) {
      return null;
    }
  }

  @Override
  public InputStream open(String path) throws IOException {
    return Files.newInputStream(resolve(path));
  }

  @Override
  public void close() {
    // nothing held open: a directory is not a handle
  }

  /**
   * Manifest path to a real path, refusing anything that would leave the root.
   *
   * <p>
   * {@link io.artifexlabs.inventory.api.DataEntry} already refuses {@code ..} on the way in, so this cannot trigger for
   * anything the system stored — which is exactly why it is here. The day something else writes an entry, the hasher
   * must not be the component that reads outside the medium.
   */
  private Path resolve(String path) throws IOException {
    Path resolved = this.root.resolve(path).normalize();
    if (!resolved.startsWith(this.root))
      throw new IOException("path escapes the medium root: " + path);
    return resolved;
  }
}
