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

import java.util.Optional;
import java.util.concurrent.CompletionStage;

import io.artifexlabs.inventory.api.InventoryUser;

/**
 * Credential-verifying user lookup, backing the login flow. Not part of the
 * public API: credentials are a storage concern, so this stays in impl.
 *
 * @author mykel
 *
 */
public interface UserStore {

  /** Verify credentials; empty on unknown email or wrong password. */
  CompletionStage<Optional<InventoryUser>> authenticate(String email, String password);

  /** Create the user if absent (idempotent); returns the stored user. */
  CompletionStage<InventoryUser> ensureUser(String email, String displayName, String password, boolean admin);

  /** All users, admin view. */
  CompletionStage<java.util.List<InventoryUser>> list();

  /** Delete a user by id; true if it existed. Their tokens die with them. */
  CompletionStage<Boolean> delete(String id);

  /** Grant or revoke the admin flag; the updated user, empty if unknown id. */
  CompletionStage<Optional<InventoryUser>> setAdmin(String id, boolean admin);

  /** Case-insensitive email lookup; empty if unknown. */
  CompletionStage<Optional<InventoryUser>> findByEmail(String email);

  /** The user owning a federated identity; empty if the identity is unknown. */
  CompletionStage<Optional<InventoryUser>> findByIdentity(String provider, String subject);

  /** Attach a federated identity to a user (idempotent for the same owner). */
  CompletionStage<Void> linkIdentity(String userId, String provider, String subject);
}
