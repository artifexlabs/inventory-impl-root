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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import io.artifexlabs.inventory.api.DataEntry;
import io.artifexlabs.inventory.api.DataTree;
import io.artifexlabs.inventory.api.HashAlgorithm;

/**
 * The reference half of structure-analysis.py's conformance check: run the REAL {@code DataTree.build} over a
 * find-shaped fixture and print every directory exactly as {@code structure-analysis.py dump} does. The two outputs
 * must be identical, or the Python fold is not computing MerkleHash's structure hash and its answers are fiction.
 *
 * <pre>
 *   java -cp inventory-api/target/classes:$(cat api-cp.txt) StructureConformance.java fixture.tsv /fixture
 * </pre>
 *
 * Not a test in the reactor on purpose: it is run by hand against a checked-in fixture whenever either side changes,
 * and results/README.md records the last run and the inventory-api version it was run against.
 */
public class StructureConformance {

  public static void main(String[] args) throws Exception {
    String root = args[1].endsWith("/") ? args[1] : args[1] + "/";
    List<DataEntry> entries = new ArrayList<>();
    for (String line : Files.readAllLines(Path.of(args[0]))) {
      if (line.isBlank())
        continue;
      String[] f = line.split("\t", -1);
      String path = f[2].startsWith(root) ? f[2].substring(root.length()) : f[2];
      long size = f[0].isEmpty() ? 0L : Long.parseLong(f[0]);
      entries.add(new DataEntry(path, size, null, null, null, null, List.of()));
    }
    List<String> out = new ArrayList<>();
    for (DataTree.Node n : DataTree.build(HashAlgorithm.SHA256, entries))
      out.add(n.path() + "\t" + n.depth() + "\t" + HexFormat.of().formatHex(n.structureHash()) + "\t"
          + n.subtreeFiles() + "\t" + n.subtreeBytes());
    out.sort(null);
    for (String s : out)
      System.out.println(s);
  }
}
