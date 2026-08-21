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

import io.artifexlabs.inventory.api.Ulid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.artifexlabs.inventory.api.DefaultItem;
import io.artifexlabs.inventory.api.Item;
import io.artifexlabs.inventory.api.Weight;

public class InMemoryInventorySystemTest {
  private InMemoryAuditSink audit;
  private InMemoryInventorySystem system;

  private static <T> T await(java.util.concurrent.CompletionStage<T> stage)
      throws InterruptedException, ExecutionException {
    return stage.toCompletableFuture().get();
  }

  @BeforeEach
  public void setUp() {
    this.audit = new InMemoryAuditSink();
    this.system = new InMemoryInventorySystem(this.audit, "test-user");
  }

  @Test
  public void testCreateAndGet() throws Exception {
    Item created = await(this.system.createItem("wrench", "Big Wrench", "tool"));
    assertEquals(Ulid.LENGTH, created.getId().length());
    assertEquals("wrench", created.getName());
    assertEquals("Big Wrench", created.getDisplayName().get());
    assertEquals("tool", created.getType());

    assertEquals(created, await(this.system.getItem(created.getId())).get());
    assertEquals(List.of(created), await(this.system.getAllItems()));
  }

  @Test
  public void testGetItemsOfType() throws Exception {
    await(this.system.createItem("wrench", null, "tool"));
    await(this.system.createItem("hammer", null, "tool"));
    await(this.system.createItem("cd", null, "data"));

    assertEquals(2, await(this.system.getItemsOfType("tool")).size());
    assertEquals(1, await(this.system.getItemsOfType("data")).size());
    assertTrue(await(this.system.getItemsOfType("nothing")).isEmpty());
  }

  @Test
  public void testUpdate() throws Exception {
    Item created = await(this.system.createItem("wrench", null, "tool"));
    Item updated = DefaultItem.builder(created).description("a good wrench").quantity(4L)
        .weight(Weight.ofPounds(1.5)).build();

    assertTrue(await(this.system.updateItem(updated)));
    Item read = await(this.system.getItem(created.getId())).get();
    assertEquals("a good wrench", read.getDescription().get());
    assertEquals(4L, read.getQuantity().get());
  }

  @Test
  public void testUpdateOfMissingItemFails() throws Exception {
    Item ghost = DefaultItem.builder().id(Ulid.next()).name("ghost").timestamp(java.time.Instant.now()).build();
    assertFalse(await(this.system.updateItem(ghost)));
    assertTrue(this.audit.getEvents().isEmpty());
  }

  @Test
  public void testDelete() throws Exception {
    Item created = await(this.system.createItem("wrench", null, "tool"));
    assertTrue(await(this.system.deleteItem(created.getId())));
    assertTrue(await(this.system.getItem(created.getId())).isEmpty());
    assertFalse(await(this.system.deleteItem(created.getId())));
  }

  @Test
  public void testContainmentOperations() throws Exception {
    Item box = await(this.system.createItem("box", null, "container"));
    Item bin = await(this.system.createItem("bin", null, "container"));
    Item wrench = await(this.system.createItem("wrench", null, "tool"));

    assertTrue(await(this.system.addToContainer(box.getId(), wrench.getId())));
    assertEquals(box.getId(), await(this.system.getContainer(wrench.getId())).get().getId());
    assertTrue(await(this.system.getItem(box.getId())).get().isContainer());

    // single-parent tree: adding to a second container RE-PARENTS
    assertTrue(await(this.system.addToContainer(bin.getId(), wrench.getId())));
    assertEquals(bin.getId(), await(this.system.getContainer(wrench.getId())).get().getId());
    assertFalse(await(this.system.getItem(box.getId())).get().isContainer());

    assertTrue(await(this.system.moveToContainer(wrench.getId(), box.getId())));
    assertEquals(box.getId(), await(this.system.getContainer(wrench.getId())).get().getId());

    assertTrue(await(this.system.removeFromContainer(box.getId(), wrench.getId())));
    assertTrue(await(this.system.getContainer(wrench.getId())).isEmpty());
    assertFalse(await(this.system.removeFromContainer(box.getId(), wrench.getId())));

    assertFalse(await(this.system.addToContainer(box.getId(), box.getId())));
    assertFalse(await(this.system.addToContainer("missing", wrench.getId())));
    assertFalse(await(this.system.moveToContainer(wrench.getId(), "missing")));

    // a container can never be placed inside its own descendant
    assertTrue(await(this.system.addToContainer(box.getId(), bin.getId())));
    assertFalse(await(this.system.addToContainer(bin.getId(), box.getId())), "cycle refused");
    // ...and updateItem cannot smuggle the same cycle in via containerId
    Item boxNow = await(this.system.getItem(box.getId())).get();
    assertFalse(await(this.system.updateItem(
        io.artifexlabs.inventory.api.DefaultItem.builder(boxNow).containerId(bin.getId()).build())),
        "update-path cycle refused");

    assertTrue(this.audit.getEvents().stream().map(e -> e.getAction())
        .anyMatch(a -> a.equals("item.contain") || a.equals("item.move") || a.equals("item.uncontain")));
  }

  @Test
  public void testIdentityClaimResolveAndRelease() throws Exception {
    Item wrench = await(this.system.createItem("wrench", null, "tool"));
    var upc = new io.artifexlabs.inventory.api.ItemIdentity("upc", "012345678905");
    var nfc = new io.artifexlabs.inventory.api.ItemIdentity("NFC-UID", " 04:A2:B3 "); // normalizes

    assertTrue(await(this.system.addIdentity(wrench.getId(), upc)));
    assertTrue(await(this.system.addIdentity(wrench.getId(), nfc)));
    assertEquals(wrench.getId(), await(this.system.findByIdentity("upc", "012345678905")).get().getId());
    // kind normalized to lowercase, value trimmed — the scan resolves
    assertEquals(wrench.getId(), await(this.system.findByIdentity("nfc-uid", "04:A2:B3")).get().getId());
    assertEquals(List.of(new io.artifexlabs.inventory.api.ItemIdentity("nfc-uid", "04:A2:B3"), upc),
        await(this.system.identitiesOf(wrench.getId())));

    // idempotent re-claim by the same item
    assertTrue(await(this.system.addIdentity(wrench.getId(), upc)));

    // a marker reused on a second item is refused loudly
    Item other = await(this.system.createItem("other", null, "tool"));
    var refused = org.junit.jupiter.api.Assertions.assertThrows(ExecutionException.class,
        () -> await(this.system.addIdentity(other.getId(), upc)));
    assertTrue(refused.getCause() instanceof IllegalStateException);
    assertEquals(wrench.getId(), await(this.system.findByIdentity("upc", "012345678905")).get().getId());

    // release, then the marker is free
    assertTrue(await(this.system.removeIdentity(wrench.getId(), upc)));
    assertTrue(await(this.system.findByIdentity("upc", "012345678905")).isEmpty());
    assertFalse(await(this.system.removeIdentity(wrench.getId(), upc)), "already released");
    assertTrue(await(this.system.addIdentity(other.getId(), upc)), "freed marker claimable");

    // unknown item refuses; unknown marker resolves empty
    assertFalse(await(this.system.addIdentity("missing", nfc)));
    assertTrue(await(this.system.findByIdentity("qr", "nope")).isEmpty());

    assertTrue(this.audit.getEvents().stream().map(e -> e.getAction())
        .anyMatch("item.identity-add"::equals));
    assertTrue(this.audit.getEvents().stream().map(e -> e.getAction())
        .anyMatch("item.identity-remove"::equals));
  }

  @Test
  public void testIdentitiesDieWithTheirItem() throws Exception {
    Item wrench = await(this.system.createItem("wrench", null, "tool"));
    var upc = new io.artifexlabs.inventory.api.ItemIdentity("upc", "012345678905");
    assertTrue(await(this.system.addIdentity(wrench.getId(), upc)));
    assertTrue(await(this.system.deleteItem(wrench.getId())));
    assertTrue(await(this.system.findByIdentity("upc", "012345678905")).isEmpty(), "cascade with the item");
    assertEquals(List.of(), await(this.system.identitiesOf(wrench.getId())));
  }

  @Test
  public void testEveryMutationIsAudited() throws Exception {
    Item created = await(this.system.createItem("wrench", null, "tool"));
    await(this.system.updateItem(DefaultItem.builder(created).description("d").build()));
    await(this.system.deleteItem(created.getId()));

    assertEquals(List.of("item.create", "item.update", "item.delete"),
        this.audit.getEvents().stream().map(e -> e.getAction()).toList());
    assertTrue(this.audit.getEvents().stream().allMatch(e -> e.getTargetId().equals(created.getId())));
    assertTrue(this.audit.getEvents().stream().allMatch(e -> e.getPrincipal().equals("test-user")));
    assertTrue(this.audit.getEvents().stream().allMatch(e -> e.getDetails().isPresent()));
  }
}
