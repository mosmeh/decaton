/*
 * Copyright 2020 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.decaton.processor.runtime.internal;

import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.decaton.processor.runtime.ProcessorProperties;
import com.linecorp.decaton.processor.runtime.Property;
import com.linecorp.decaton.processor.runtime.internal.AssignmentManager.AssignmentConfig;
import com.linecorp.decaton.processor.runtime.internal.AssignmentManager.AssignmentStore;
import com.linecorp.decaton.processor.runtime.internal.CommitManager.OffsetsStore;
import com.linecorp.decaton.processor.runtime.internal.ConsumeManager.PartitionStates;
import com.linecorp.decaton.processor.runtime.internal.Utils.Task;

public class PartitionContexts implements OffsetsStore, AssignmentStore, PartitionStates, AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(PartitionContexts.class);

    private final SubscriptionScope scope;
    private final Processors<?> processors;
    private final Property<Long> processingRateProp;
    private final int maxPendingRecords;
    private final Map<TopicPartition, PartitionContext> contexts;

    private final AtomicBoolean reloadRequested;

    public PartitionContexts(SubscriptionScope scope, Processors<?> processors) {
        this.scope = scope;
        this.processors = processors;

        processingRateProp = scope.props().get(ProcessorProperties.CONFIG_PROCESSING_RATE);
        // We don't support dynamic reload of this value so fix at the time of boot-up.
        maxPendingRecords = scope.props().get(ProcessorProperties.CONFIG_MAX_PENDING_RECORDS).value();
        contexts = new HashMap<>();
        reloadRequested = new AtomicBoolean(false);

        scope.props().get(ProcessorProperties.CONFIG_PARTITION_CONCURRENCY).listen((oldVal, newVal) -> {
            // This listener will be called at listener registration.
            // It's not necessary to reload contexts at listener registration because PartitionContexts hasn't been instantiated at that time.
            if (oldVal == null) {
                return;
            }

            if (!reloadRequested.getAndSet(true)) {
                logger.info("Requested reload partition.concurrency oldValue={}, newValue={}", oldVal, newVal);
            }
        });
    }

    public PartitionContext get(TopicPartition tp) {
        return contexts.get(tp);
    }

    @Override
    public Set<TopicPartition> assignedPartitions() {
        return contexts.keySet();
    }

    @Override
    public void addPartitions(Map<TopicPartition, AssignmentConfig> partitions) {
        for (Entry<TopicPartition, AssignmentConfig> entry : partitions.entrySet()) {
            TopicPartition tp = entry.getKey();
            AssignmentConfig conf = entry.getValue();
            initContext(tp, conf.paused());
        }
    }

    @Override
    public void removePartition(Collection<TopicPartition> partitions) {
        destroyProcessors(partitions);
        cleanupPartitions(partitions);
    }

    private void cleanupPartitions(Collection<TopicPartition> partitions) {
        for (TopicPartition tp : partitions) {
            try {
                contexts.remove(tp).close();
            } catch (Exception e) {
                logger.warn("Failed to close partition context {}", tp, e);
            }
        }
    }

    /**
     * Instantiate new {@link PartitionContext} and put it to contexts
     *
     * @param tp partition to be instantiated
     * @param paused denotes the instantiated partition should be paused
     * @return instantiated context
     */
    // visible for testing
    PartitionContext initContext(TopicPartition tp, boolean paused) {
        PartitionContext context = instantiateContext(tp);
        if (paused) {
            context.pause();
        }
        contexts.put(tp, context);
        return context;
    }

    /**
     * Destroy all processors without removing context from contexts
     */
    public void destroyAllProcessors() {
        destroyProcessors(contexts.keySet());
    }


    @Override
    public Map<TopicPartition, OffsetAndMetadata> commitReadyOffsets() {
        Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
        for (PartitionContext context : contexts.values()) {
            if (!context.revoking()) {
                context.offsetWaitingCommit().ifPresent(
                        offset -> offsets.put(context.topicPartition(),
                                              new OffsetAndMetadata(offset + 1, null)));
            }
        }
        return offsets;
    }

    @Override
    public void storeCommittedOffsets(Map<TopicPartition, OffsetAndMetadata> offsets) {
        for (Entry<TopicPartition, OffsetAndMetadata> entry : offsets.entrySet()) {
            TopicPartition tp = entry.getKey();
            long offset = entry.getValue().offset();
            // PartitionContext manages their "completed" offset so its minus 1 from committed offset
            // which indicates the offset to "fetch next".
            contexts.get(tp).updateCommittedOffset(offset - 1);
        }
    }

    @Override
    public void markRevoking(Collection<TopicPartition> partitions) {
        contexts.forEach((tp, ctx) -> {
            if (partitions.contains(tp)) {
                ctx.revoking(true);
            }
        });
    }

    @Override
    public void unmarkRevoking(Collection<TopicPartition> partitions) {
        contexts.forEach((tp, ctx) -> {
            if (partitions.contains(tp)) {
                ctx.revoking(false);
            }
        });
    }

    public int totalPendingTasks() {
        return contexts.values().stream()
                       .mapToInt(PartitionContext::pendingTasksCount)
                       .sum();
    }

    public void updateHighWatermarks() {
        contexts.values().forEach(PartitionContext::updateHighWatermark);
    }

    private boolean shouldPartitionPaused(int pendingRecords) {
        return pendingRecords >= maxPendingRecords;
    }

    // visible for testing
    PartitionContext instantiateContext(TopicPartition tp) {
        PartitionScope partitionScope = new PartitionScope(scope, tp);
        return new PartitionContext(partitionScope, processors, maxPendingRecords);
    }

    // visible for testing
    boolean pausingAllProcessing() {
        return processingRateProp.value() == RateLimiter.PAUSED || reloadRequested.get();
    }

    @Override
    public void updatePartitionsStatus() {
        updateHighWatermarks();
    }

    @Override
    public List<TopicPartition> partitionsNeedsPause() {
        boolean pausingAll = pausingAllProcessing();
        return contexts.values().stream()
                       .filter(c -> !c.revoking())
                       .filter(c -> !c.paused())
                       .filter(c -> pausingAll || shouldPartitionPaused(c.pendingTasksCount()))
                       .map(PartitionContext::topicPartition)
                       .collect(toList());
    }

    @Override
    public List<TopicPartition> partitionsNeedsResume() {
        boolean pausingAll = pausingAllProcessing();
        return contexts.values().stream()
                       .filter(c -> !c.revoking())
                       .filter(PartitionContext::paused)
                       .filter(c -> !pausingAll && !shouldPartitionPaused(c.pendingTasksCount()))
                       .map(PartitionContext::topicPartition)
                       .collect(toList());
    }

    @Override
    public void partitionsPaused(List<TopicPartition> partitions) {
        for (TopicPartition tp : partitions) {
            contexts.get(tp).pause();
        }
    }

    @Override
    public void partitionsResumed(List<TopicPartition> partitions) {
        for (TopicPartition tp : partitions) {
            contexts.get(tp).resume();
        }
    }

    /**
     * Waits for all pending tasks if property-reload is requested, then recreate all partition contexts with latest property values.
     * This method must be called from only subscription thread.
     */
    public void maybeHandlePropertyReload() {
        if (reloadRequested.get()) {
            if (totalPendingTasks() > 0) {
                logger.debug("Waiting pending tasks for property reload.");
                return;
            }
            // it's ok to check-and-set reloadRequested without synchronization
            // because this field is set to false only in this method, and this method is called from only subscription thread.
            reloadRequested.set(false);
            logger.info("Completed waiting pending tasks. Start reloading partition contexts");
            reloadContexts();
        }
    }

    private void reloadContexts() {
        // Save current topicPartitions into copy to update contexts map while iterating over this copy.
        Set<TopicPartition> topicPartitions = new HashSet<>(contexts.keySet());

        logger.info("Start dropping partition contexts");
        removePartition(topicPartitions);
        logger.info("Finished dropping partition contexts. Start recreating partition contexts");
        Map<TopicPartition, AssignmentConfig> configs = topicPartitions.stream().collect(
                toMap(Function.identity(), tp -> new AssignmentConfig(true)));
        addPartitions(configs);
        logger.info("Completed reloading property");
    }

    private void destroyProcessors(Collection<TopicPartition> partitions) {
        Utils.runInParallel("DestroyProcessors",
                            partitions.stream().map(contexts::get).map(context -> (Task) () -> {
                                try {
                                    context.destroyProcessors();
                                } catch (Exception e) {
                                    logger.error("Failed to close partition context for {}", context.topicPartition(), e);
                                }
                            }).collect(toList()))
             .join();
    }

    @Override
    public void close() {
        cleanupPartitions(new ArrayList<>(contexts.keySet()));
    }
}
