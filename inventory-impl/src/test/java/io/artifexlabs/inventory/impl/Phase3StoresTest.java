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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.artifexlabs.inventory.api.AssetInfo;
import io.artifexlabs.inventory.api.DefaultItem;
import io.artifexlabs.inventory.api.Item;
import io.artifexlabs.inventory.api.LatLong;

/**
 * Phase 3 stores, revised for Phase 15: what used to be a Location is now a container with coordinates, so "location"
 * behavior (a place that holds things and knows where it is) is exercised through the one containment tree plus
 * coordinate inheritance. Asset lifecycle is unchanged except for the attach/update timestamps.
 */
public class Phase3StoresTest {
  private InMemoryAuditSink audit;
  private InMemoryInventorySystem items;
  private InMemoryAssetStore assets;

  private static <T> T await(CompletionStage<T> stage) throws InterruptedException, ExecutionException {
    return stage.toCompletableFuture().get();
  }

  @BeforeEach
  public void setUp() {
    this.audit = new InMemoryAuditSink();
    this.items = new InMemoryInventorySystem(this.audit, "p3-test");
    this.assets = new InMemoryAssetStore(this.items, this.audit, "p3-test");
  }

  @Test
  public void testPlacesAreContainersWithCoordinates() throws Exception {
    Item garage = await(this.items.createItem("Garage", null, "location"));
    assertTrue(await(this.items.updateItem(DefaultItem.builder(garage).coordinates(new LatLong(33.7, -84.4)).build())));
    Item box = await(this.items.createItem("box", null, "container"));
    Item wrench = await(this.items.createItem("wrench", null, "tool"));

    assertTrue(await(this.items.addToContainer(garage.getId(), box.getId())));
    assertTrue(await(this.items.addToContainer(box.getId(), wrench.getId())));

    // three-deep inheritance: the wrench pins where the garage pins
    assertEquals(Optional.of(new LatLong(33.7, -84.4)), await(this.items.effectiveCoordinates(wrench.getId())));
    // an own pin beats the chain
    assertTrue(await(this.items.updateItem(DefaultItem.builder(await(this.items.getItem(box.getId())).get())
        .coordinates(new LatLong(10.0, 10.0)).build())));
    assertEquals(Optional.of(new LatLong(10.0, 10.0)), await(this.items.effectiveCoordinates(wrench.getId())));
    // a root with no pin anywhere resolves to empty
    Item loose = await(this.items.createItem("loose", null, "tool"));
    assertTrue(await(this.items.effectiveCoordinates(loose.getId())).isEmpty());
  }

  @Test
  public void testDeletingAContainerOrphansItsContents() throws Exception {
    Item shelf = await(this.items.createItem("shelf", null, "location"));
    Item jar = await(this.items.createItem("jar", null, "container"));
    assertTrue(await(this.items.addToContainer(shelf.getId(), jar.getId())));

    // deleting the shelf must not delete the jar — it becomes a root
    assertTrue(await(this.items.deleteItem(shelf.getId())));
    Item orphan = await(this.items.getItem(jar.getId())).get();
    assertTrue(orphan.getContainerId().isEmpty(), "orphaned, not cascaded");
  }

  @Test
  public void testAssetLifecycle() throws Exception {
    Item box = await(this.items.createItem("box", null, "container"));
    byte[] photo = new byte[] {
        1, 2, 3, 4, 5
    };

    AssetInfo info = await(this.assets.store(box.getId(), "photo.jpg", "image/jpeg", photo)).get();
    assertEquals(5, info.sizeBytes());
    assertEquals(info.attachedAt(), info.updatedAt(), "fresh asset: updatedAt tracks attachedAt");
    assertFalse(info.isRevised());
    assertArrayEquals(photo, await(this.assets.get(info.id())).get().data());
    assertEquals(1, await(this.assets.listFor(box.getId())).size());

    // storing to a nonexistent item is refused
    assertTrue(await(this.assets.store("missing", "f", "t", photo)).isEmpty());

    assertTrue(await(this.assets.delete(info.id())));
    assertFalse(await(this.assets.delete(info.id())));
    assertTrue(this.audit.getEvents().stream().anyMatch(e -> e.getAction().equals("asset.attach")));
    assertTrue(this.audit.getEvents().stream().anyMatch(e -> e.getAction().equals("asset.delete")));
  }

  @Test
  public void testAssetKindRoundTripsAndDefaults() throws Exception {
    Item wall = await(this.items.createItem("wall", null, "location"));
    byte[] plan = new byte[] {
        9, 9, 9
    };
    AssetInfo map = await(this.assets.store(wall.getId(), "plan.png", "image/png", plan, null, "map")).get();
    assertEquals("map", map.kind());
    AssetInfo photo = await(this.assets.store(wall.getId(), "pic.png", "image/png", plan)).get();
    assertEquals("photo", photo.kind(), "kind defaults to photo");
    // replace preserves the kind
    AssetInfo replaced = await(this.assets.replace(map.id(), "plan2.png", "image/png", plan, null)).get();
    assertEquals("map", replaced.kind());
  }

  @Test
  public void testCreateItemFromPhoto() throws Exception {
    // EXIF pins the created place itself: 33°44'56"N 84°23'24"W
    byte[] jpeg = GpsJpeg.withGps(33, 44, 56, "N", 84, 23, 24, "W");
    var made = await(
        this.assets.createItemFromPhoto("Garage", null, "location", null, "garage.jpg", "image/jpeg", jpeg, null, null))
        .get();
    assertEquals("location", made.item().getType());
    assertTrue(made.item().getCoordinates().isPresent(), "EXIF GPS pinned the place");
    assertEquals(made.item().getId(), made.asset().itemId(), "photo attached to the created item");
    assertEquals("photo", made.asset().kind());
    assertEquals(1, await(this.assets.listFor(made.item().getId())).size());
    assertTrue(this.audit.getEvents().stream().anyMatch(e -> e.getAction().equals("item.create")));
    assertTrue(this.audit.getEvents().stream().anyMatch(e -> e.getAction().equals("asset.attach")));

    // contained variant: created inside an existing container
    var inside = await(this.assets.createItemFromPhoto("Shelf", null, "location", made.item().getId(), "shelf.png",
        "image/png", new byte[] {
            1
        }, null, "map")).get();
    assertEquals(Optional.of(made.item().getId()), inside.item().getContainerId());
    assertEquals("map", inside.asset().kind());

    // unknown container refuses the whole thing
    assertTrue(
        await(this.assets.createItemFromPhoto("x", null, "location", "missing", "f.png", "image/png", new byte[] {
            1
        }, null, null)).isEmpty());
  }

  @Test
  public void testAssetReplaceArchivesTheSupersededBytes() throws Exception {
    Item box = await(this.items.createItem("box", null, "container"));
    byte[] first = new byte[] {
        1, 1, 1
    };
    byte[] second = new byte[] {
        2, 2, 2, 2
    };
    AssetInfo original = await(this.assets.store(box.getId(), "photo.jpg", "image/jpeg", first)).get();

    AssetInfo revised = await(this.assets.replace(original.id(), "photo-v2.jpg", "image/jpeg", second, null)).get();
    assertEquals(original.id(), revised.id(), "same asset id — references keep working");
    assertEquals(original.attachedAt(), revised.attachedAt(), "attach date never moves");
    assertTrue(revised.isRevised());
    assertEquals(4, revised.sizeBytes());
    assertArrayEquals(second, await(this.assets.get(original.id())).get().data());

    // the old bytes are recoverable from the ARCHIVE; the audit event carries
    // only the reference (blobs must never ride the consumer replay feed)
    var replaceEvent = this.audit.getEvents().stream().filter(e -> e.getAction().equals("asset.replace")).findFirst()
        .orElseThrow();
    var details = replaceEvent.getDetails().orElseThrow();
    assertTrue(details.getString("archiveId") != null, "audit references the archive");
    assertTrue(details.getBinary("archivedBytes") == null, "audit must NOT carry the bytes");
    assertEquals("photo.jpg", details.getJsonObject("replaced").getString("filename"));
    var archived = this.assets.archivedVersions(original.id());
    assertEquals(1, archived.size());
    assertArrayEquals(first, archived.get(0).data());
    assertEquals(details.getString("archiveId"), archived.get(0).archiveId());
    assertEquals(replaceEvent.getId(), archived.get(0).auditEventId(), "archive links back to its event");

    // replacing a nonexistent asset is refused
    assertTrue(await(this.assets.replace("missing", "f", "t", second, null)).isEmpty());
  }

  @Test
  public void testAssetsOfDeletedItemArePruned() throws Exception {
    Item box = await(this.items.createItem("box", null, "container"));
    AssetInfo info = await(this.assets.store(box.getId(), "photo.jpg", "image/jpeg", new byte[] {
        9
    })).get();
    await(this.items.deleteItem(box.getId()));
    assertTrue(await(this.assets.get(info.id())).isEmpty());
    assertTrue(await(this.assets.listFor(box.getId())).isEmpty());
  }
}
