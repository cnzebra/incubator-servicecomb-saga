/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.servicecomb.saga.alpha.server;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import org.apache.servicecomb.saga.alpha.core.CommandRepository;
import org.apache.servicecomb.saga.alpha.core.CompositeOmegaCallback;
import org.apache.servicecomb.saga.alpha.core.EventScanner;
import org.apache.servicecomb.saga.alpha.core.OmegaCallback;
import org.apache.servicecomb.saga.alpha.core.PendingTaskRunner;
import org.apache.servicecomb.saga.alpha.core.PushBackOmegaCallback;
import org.apache.servicecomb.saga.alpha.core.TxConsistentService;
import org.apache.servicecomb.saga.alpha.core.TxEventRepository;
import org.apache.servicecomb.saga.alpha.core.TxTimeoutRepository;
import org.apache.servicecomb.saga.alpha.server.tcc.GrpcTccEventService;
import org.apache.servicecomb.saga.alpha.server.tcc.callback.OmegaCallbackWrapper;
import org.apache.servicecomb.saga.alpha.server.tcc.callback.TccCallbackEngine;
import org.apache.servicecomb.saga.alpha.server.tcc.TransactionEventService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@EntityScan(basePackages = "org.apache.servicecomb.saga.alpha")
@Configuration
class AlphaConfig {
  private final BlockingQueue<Runnable> pendingCompensations = new LinkedBlockingQueue<>();
  private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

  @Value("${alpha.compensation.retry.delay:3000}")
  private int delay;

  @Bean
  Map<String, Map<String, OmegaCallback>> omegaCallbacks() {
    return new ConcurrentHashMap<>();
  }

  @Bean
  OmegaCallback omegaCallback(Map<String, Map<String, OmegaCallback>> callbacks) {
    return new PushBackOmegaCallback(pendingCompensations, new CompositeOmegaCallback(callbacks));
  }
  
  @Bean
  TxEventRepository springTxEventRepository(TxEventEnvelopeRepository eventRepo) {
    return new SpringTxEventRepository(eventRepo);
  }

  @Bean
  CommandRepository springCommandRepository(TxEventEnvelopeRepository eventRepo, CommandEntityRepository commandRepository) {
    return new SpringCommandRepository(eventRepo, commandRepository);
  }

  @Bean
  TxTimeoutRepository springTxTimeoutRepository(TxTimeoutEntityRepository timeoutRepo) {
    return new SpringTxTimeoutRepository(timeoutRepo);
  }

  @Bean
  ScheduledExecutorService compensationScheduler() {
    return scheduler;
  }

  @Bean
  GrpcServerConfig grpcServerConfig() { return new GrpcServerConfig(); }

  @Bean
  TxConsistentService txConsistentService(
      @Value("${alpha.event.pollingInterval:500}") int eventPollingInterval,
      ScheduledExecutorService scheduler,
      TxEventRepository eventRepository,
      CommandRepository commandRepository,
      TxTimeoutRepository timeoutRepository,
      OmegaCallback omegaCallback) {
        new EventScanner(scheduler,
            eventRepository, commandRepository, timeoutRepository,
            omegaCallback, eventPollingInterval).run();
        TxConsistentService consistentService = new TxConsistentService(eventRepository);
        return consistentService;
  }

  @Bean
  TransactionEventService transactionEventService(
      @Value("${alpha.server.storage:rdb}") String storage,
      @Qualifier("defaultTransactionEventService") TransactionEventService defaultTransactionEventService,
      @Qualifier("rdbTransactionEventService") TransactionEventService rdbTransactionEventService) {
    return "rdb".equals(storage) ? rdbTransactionEventService : defaultTransactionEventService;
  }

  @Bean
  TccCallbackEngine tccCallbackEngine(TransactionEventService transactionEventService) {
    return new TccCallbackEngine(new OmegaCallbackWrapper(), transactionEventService);
  }

  @Bean
  GrpcTccEventService grpcTccEventService(
      TransactionEventService transactionEventService,
      TccCallbackEngine tccCallbackEngine) {
    return new GrpcTccEventService(tccCallbackEngine, transactionEventService);
  }

  @Bean
  ServerStartable serverStartable(GrpcServerConfig serverConfig, TxConsistentService txConsistentService,
      Map<String, Map<String, OmegaCallback>> omegaCallbacks, GrpcTccEventService grpcTccEventService) {
    ServerStartable bootstrap = new GrpcStartable(serverConfig,
        new GrpcTxEventEndpointImpl(txConsistentService, omegaCallbacks), grpcTccEventService);
    new Thread(bootstrap::start).start();
    return bootstrap;
  }

  @PostConstruct
  void init() {
    new PendingTaskRunner(pendingCompensations, delay).run();
  }

  @PreDestroy
  void shutdown() {
    scheduler.shutdownNow();
  }
}
