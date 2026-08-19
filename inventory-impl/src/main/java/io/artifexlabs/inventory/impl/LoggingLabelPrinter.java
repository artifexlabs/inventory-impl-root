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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import io.artifexlabs.inventory.api.Item;
import io.artifexlabs.inventory.api.LabelPrinter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link LabelPrinter}: no hardware, just a log line. Stands in until
 * a real vendor connector exists, and keeps the print flow exercisable.
 *
 * @author mykel
 *
 */
public class LoggingLabelPrinter implements LabelPrinter {
  private final static Logger log = LoggerFactory.getLogger(LoggingLabelPrinter.class);

  @Override
  public CompletionStage<Boolean> printLabel(Item item, byte[] qrPng) {
    log.info("PRINT LABEL: item={} name='{}' qrPngBytes={}", item.getId(), item.getName(),
        qrPng == null ? 0 : qrPng.length);
    return CompletableFuture.completedStage(true);
  }

  @Override
  public CompletionStage<Boolean> feed() {
    log.info("FEED TAPE (extend and cut)");
    return CompletableFuture.completedStage(true);
  }
}
