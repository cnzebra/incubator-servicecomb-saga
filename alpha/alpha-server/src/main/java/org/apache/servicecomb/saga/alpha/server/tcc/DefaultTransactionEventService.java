/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.apache.servicecomb.saga.alpha.server.tcc;

import java.lang.invoke.MethodHandles;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.servicecomb.saga.alpha.server.tcc.jpa.ParticipatedEvent;
import org.apache.servicecomb.saga.common.TransactionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Manage TCC transaction event.
 */
@Component("defaultTransactionEventService")
public final class DefaultTransactionEventService implements TransactionEventService {

  private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private final Map<String, Set<ParticipatedEvent>> REGISTRY = new ConcurrentHashMap<>();

  /**
   * Register participate event.
   *
   * @param participatedEvent participated event
   */

  @Override
  public boolean addEvent(ParticipatedEvent participatedEvent) {

    REGISTRY
        .computeIfAbsent(participatedEvent.getGlobalTxId(), key -> new LinkedHashSet<>())
        .add(participatedEvent);

    LOG.info("Registered participated event, global tx: {}, local tx: {}, parent id: {}, "
            + "confirm: {}, cancel: {}, status: {}, service [{}] instanceId [{}]",
        participatedEvent.getGlobalTxId(), participatedEvent.getLocalTxId(), participatedEvent.getParentTxId(),
        participatedEvent.getConfirmMethod(), participatedEvent.getCancelMethod(), participatedEvent.getStatus(),
        participatedEvent.getServiceName(), participatedEvent.getInstanceId());

    // TODO We need to updated the event which transactionStatus is failed.
    return true;
  }

  /**
   * Retrieve participate event from registry.
   *
   * @param globalTxId global transaction id
   * @return participate events
   */
  @Override
  public Set<ParticipatedEvent> getEventByGlobalTxId(String globalTxId) {
    return REGISTRY.get(globalTxId);
  }

  @Override
  public void migration(String globalTxId, String localTxId) {
  }
}
