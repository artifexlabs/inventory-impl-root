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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.artifexlabs.inventory.api.Item;
import io.artifexlabs.inventory.api.ItemTag;
import io.artifexlabs.inventory.api.UpcItemCreation;

/** The one-shot create-from-UPC against the memory backend. */
public class UpcCreationTest {

  private final static String GTIN = "0049000006346";

  private InMemoryAuditSink audit;
  private InMemoryInventorySystem system;
  private InMemoryAssetStore assets;

  private static <T> T await(CompletionStage<T> stage) throws InterruptedException, ExecutionException {
    return stage.toCompletableFuture().get();
  }

  @BeforeEach
  public void setUp() {
    this.audit = new InMemoryAuditSink();
    this.system = new InMemoryInventorySystem(this.audit, "upc-test");
    this.assets = new InMemoryAssetStore(this.system, this.audit, "upc-test");
  }

  private static UpcItemCreation spec(String containerId) {
    return new UpcItemCreation(GTIN, "Cola Can", "CocaCola Cola Can", "drink", "Carbonated soft drink", 355.0,
        containerId, List.of(new ItemTag("brand", "CocaCola"), new ItemTag("category", "drinks"),
            new ItemTag("source", "https://example.test/product/" + GTIN)));
  }

  @Test
  public void createsItemIdentityTagsAndImage() throws Exception {
    byte[] image = new byte[] {
        1, 2, 3, 4
    };
    var made = await(this.assets.createItemFromUpc(spec(null), "upc.jpg", "image/jpeg", image)).get();

    Item item = await(this.system.getItem(made.item().getId())).get();
    assertEquals("Cola Can", item.getName());
    assertEquals("CocaCola Cola Can", item.getDisplayName().get());
    assertEquals("Carbonated soft drink", item.getDescription().get());
    assertEquals(355.0, item.getWeight().get().grams(), 1e-9);
    assertEquals(3, item.getTags().size());
    assertTrue(item.getTags().contains(new ItemTag("category", "drinks")));

    // the scanned code resolves straight back to the item
    assertEquals(item.getId(), await(this.system.findByIdentity("upc", GTIN)).get().getId());
    // the catalog image landed as a normal asset
    assertEquals("upc.jpg", made.asset().filename());
    assertEquals(1, await(this.assets.listFor(item.getId())).size());

    assertTrue(this.audit.getEvents().stream().map(e -> e.getAction()).toList()
        .containsAll(List.of("item.create", "item.identity-add", "item.tag", "asset.attach")));
  }

  @Test
  public void imagelessAndContainedCreation() throws Exception {
    Item shelf = await(this.system.createItem("shelf", null, "container"));
    var made = await(this.assets.createItemFromUpc(spec(shelf.getId()), null, null, null)).get();
    assertEquals(Optional.of(shelf.getId()), await(this.system.getItem(made.item().getId())).get().getContainerId());
    assertEquals(null, made.asset(), "no image, no asset — still a complete item");
    assertTrue(await(this.assets.createItemFromUpc(spec("missing-container"), null, null, null)).isEmpty(),
        "unknown container refuses");
  }

  @Test
  public void aClaimedCodeRefusesTheSecondItem() throws Exception {
    await(this.assets.createItemFromUpc(spec(null), null, null, null));
    var refused = assertThrows(ExecutionException.class,
        () -> await(this.assets.createItemFromUpc(spec(null), null, null, null)));
    assertTrue(refused.getCause() instanceof IllegalStateException, "marker reuse must refuse loudly");
    assertFalse(await(this.system.getAllItems()).size() > 1, "the refused creation left no orphan item");
  }
}
