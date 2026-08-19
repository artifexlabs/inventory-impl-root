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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.Test;
import io.artifexlabs.inventory.api.InventoryUser;

public class AuthServicesTest {

  private static <T> T await(CompletionStage<T> stage) throws InterruptedException, ExecutionException {
    return stage.toCompletableFuture().get();
  }

  @Test
  public void testPasswordHashingRoundTrip() {
    String hash = Passwords.hash("s3cret");
    assertTrue(Passwords.verify("s3cret", hash));
    assertFalse(Passwords.verify("wrong", hash));
    assertFalse(Passwords.verify("s3cret", null));
  }

  @Test
  public void testUserStoreAuthenticate() throws Exception {
    InMemoryUserStore store = new InMemoryUserStore();
    InventoryUser admin = await(store.ensureUser("admin@example.com", "Admin", "s3cret", true));
    assertTrue(admin.isAdmin());

    // idempotent: second ensure returns the same user, no credential overwrite
    assertEquals(admin, await(store.ensureUser("admin@example.com", "Other", "different", false)));

    assertEquals(admin, await(store.authenticate("admin@example.com", "s3cret")).get());
    assertTrue(await(store.authenticate("admin@example.com", "wrong")).isEmpty());
    assertTrue(await(store.authenticate("nobody@example.com", "s3cret")).isEmpty());
  }

  @Test
  public void testTokenLifecycle() throws Exception {
    InMemoryUserStore store = new InMemoryUserStore();
    InventoryUser admin = await(store.ensureUser("admin@example.com", "Admin", "s3cret", true));
    InMemoryTokenService tokens = new InMemoryTokenService();

    String token = await(tokens.issue(admin));
    assertEquals(Ulid.LENGTH, token.length());
    assertEquals(admin, await(tokens.authenticate(token)).get());

    assertTrue(await(tokens.revoke(token)));
    assertTrue(await(tokens.authenticate(token)).isEmpty());
    assertFalse(await(tokens.revoke(token)));
    assertTrue(await(tokens.authenticate(null)).isEmpty());
  }

  @Test
  public void testUserAdminOperations() throws Exception {
    InMemoryUserStore store = new InMemoryUserStore();
    InventoryUser admin = await(store.ensureUser("admin@example.com", "Admin", "a", true));
    InventoryUser user = await(store.ensureUser("user@example.com", "User", "u", false));

    assertEquals(java.util.List.of(admin, user), await(store.list()));

    InventoryUser promoted = await(store.setAdmin(user.getId(), true)).get();
    assertTrue(promoted.isAdmin());
    assertTrue(await(store.setAdmin("no-such-id", true)).isEmpty());

    assertTrue(await(store.delete(user.getId())));
    assertFalse(await(store.delete(user.getId())));
    assertEquals(java.util.List.of(admin), await(store.list()));
  }

  @Test
  public void testFederatedIdentities() throws Exception {
    InMemoryUserStore store = new InMemoryUserStore();
    InventoryUser user = await(store.ensureUser("person@example.com", "Person", "pw", false));

    assertEquals(user, await(store.findByEmail("PERSON@example.com")).get());
    assertTrue(await(store.findByEmail("nobody@example.com")).isEmpty());

    assertTrue(await(store.findByIdentity("apple", "sub-1")).isEmpty());
    await(store.linkIdentity(user.getId(), "apple", "sub-1"));
    await(store.linkIdentity(user.getId(), "google", "sub-g"));
    assertEquals(user, await(store.findByIdentity("apple", "sub-1")).get());
    assertEquals(user, await(store.findByIdentity("google", "sub-g")).get());
    // same subject under a different provider is a different identity
    assertTrue(await(store.findByIdentity("google", "sub-1")).isEmpty());

    // identities die with the user
    assertTrue(await(store.delete(user.getId())));
    assertTrue(await(store.findByIdentity("apple", "sub-1")).isEmpty());
  }

  @Test
  public void testInMemorySinceCursor() throws Exception {
    InMemoryAuditSink sink = new InMemoryAuditSink();
    for (int i = 0; i < 5; i++)
      await(sink.record(new io.artifexlabs.inventory.api.DefaultAuditEvent(Ulid.next(),
          java.time.Instant.now(), "t", "item.create", "target-" + i, null)));

    var first = await(sink.since(0, 3));
    assertEquals(3, first.size());
    assertEquals("target-0", first.get(0).event().getTargetId());
    var rest = await(sink.since(first.get(2).seq(), 10));
    assertEquals(2, rest.size());
    assertEquals("target-4", rest.get(1).event().getTargetId());
    assertTrue(await(sink.since(rest.get(1).seq(), 10)).isEmpty());
  }

  @Test
  public void testTokenListing() throws Exception {
    InMemoryUserStore store = new InMemoryUserStore();
    InventoryUser admin = await(store.ensureUser("admin@example.com", "Admin", "a", true));
    InMemoryTokenService tokens = new InMemoryTokenService();

    String t1 = await(tokens.issue(admin));
    String t2 = await(tokens.issue(admin));
    await(tokens.revoke(t1));

    java.util.List<io.artifexlabs.inventory.api.TokenInfo> infos = await(tokens.tokensFor(admin.getId()));
    assertEquals(2, infos.size());
    assertTrue(infos.stream().anyMatch(i -> i.token().equals(t1) && i.revoked()));
    assertTrue(infos.stream().anyMatch(i -> i.token().equals(t2) && !i.revoked()));
    assertTrue(await(tokens.tokensFor("nobody")).isEmpty());
  }

  @Test
  public void testAuditReaderOverInMemorySink() throws Exception {
    InMemoryAuditSink sink = new InMemoryAuditSink();
    InMemoryInventorySystem system = new InMemoryInventorySystem(sink, "reader-test");
    io.artifexlabs.inventory.api.Item a = await(system.createItem("a", null, "t"));
    await(system.createItem("b", null, "t"));
    await(system.updateItem(io.artifexlabs.inventory.api.DefaultItem.builder(a).description("x").build()));

    assertEquals(3, await(sink.recent(10, 0)).size());
    assertEquals(2, await(sink.recent(2, 0)).size());
    assertEquals(1, await(sink.recent(10, 2)).size());
    assertEquals(2, await(sink.byTarget(a.getId(), 10)).size());
    assertEquals("item.update", await(sink.byTarget(a.getId(), 1)).get(0).getAction());
  }

  @Test
  public void testSeededDevToken() throws Exception {
    InMemoryUserStore store = new InMemoryUserStore();
    InventoryUser admin = await(store.ensureUser("admin@example.com", "Admin", "s3cret", true));
    InMemoryTokenService tokens = new InMemoryTokenService().seed("dev-token", admin);
    assertEquals(admin, await(tokens.authenticate("dev-token")).get());
  }
}
