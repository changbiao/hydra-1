/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.addthis.hydra.job;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import com.addthis.basis.util.JitterClock;
import com.addthis.basis.util.Parameter;

import com.addthis.hydra.job.mq.HostState;
import com.addthis.hydra.job.mq.JobKey;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import org.slf4j.Logger;

import org.slf4j.LoggerFactory;
public class SpawnQueuesByPriority extends TreeMap<Integer, LinkedList<SpawnQueueItem>> {

    private static Logger log = LoggerFactory.getLogger(SpawnQueuesByPriority.class);
    private final Lock queueLock = new ReentrantLock();

    /* Internal map used to record outgoing task kicks that will not immediately be visible in the HostState */
    private final HashMap<String, Integer> hostAvailSlots = new HashMap<>();

    private static final int SPAWN_QUEUE_AVAIL_REFRESH = Parameter.intValue("spawn.queue.avail.refresh", 60_000); // Periodically refresh hostAvailSlots to the actual availableSlots count
    private static final int SPAWN_QUEUE_NEW_TASK_LAST_SLOT_DELAY = Parameter.intValue("spawn.queue.new.task.last.slot.delay", 90_000); // New tasks can't take the last slot of a host unless they wait this long

    private long lastAvailSlotsUpdate = 0;

    private static final boolean ENABLE_TASK_MIGRATION = Parameter.boolValue("task.migration.enable", true); // Whether tasks can migrate at all
    private static final long TASK_MIGRATION_MIN_BYTES = Parameter.longValue("task.migration.min.bytes", 50_000_000); // Tasks this small can always migrate
    private static final long TASK_MIGRATION_MAX_BYTES = Parameter.longValue("task.migration.max.bytes", 10_000_000_000L); // Tasks up to this big can migrate if they stay in the queue long enough
    private static final long TASK_MIGRATION_LIMIT_GROWTH_INTERVAL = Parameter.longValue("task.migration.limit.growth.interval", 1_200_000); // The byte limit raises to the max value if tasks are queued this long (20 minutes)
    private static final long TASK_MIGRATION_INTERVAL_PER_HOST = Parameter.longValue("task.migration.interval", 240_000); // Only migrate a task to a particular host once per interval
    private final Cache<String, Boolean> migrateHosts; // Use cache ttl to mark hosts that have recently performed or received a migration
    private final AtomicBoolean stoppedJob = new AtomicBoolean(false); // When tasks are stopped, track this behavior so that the queue can be modified as soon as possible

    /* This comparator should only be used within a block that is synchronized on hostAvailSlots.
    It does not internally synchronize to save a bunch of extra lock operations.*/
    private final Comparator<HostState> hostStateComparator = new Comparator<HostState>() {
        @Override
        public int compare(HostState o1, HostState o2) {
            int hostAvailSlots1 = hostAvailSlots.containsKey(o1.getHostUuid()) ? hostAvailSlots.get(o1.getHostUuid()) : 0;
            int hostAvailSlots2 = hostAvailSlots.containsKey(o2.getHostUuid()) ? hostAvailSlots.get(o2.getHostUuid()) : 0;
            if (hostAvailSlots1 != hostAvailSlots2) {
                return Integer.compare(-hostAvailSlots1, -hostAvailSlots2); // Return hosts with large number of slots first
            } else {
                return Double.compare(o1.getMeanActiveTasks(), o2.getMeanActiveTasks()); // Return hosts with small meanActiveTask value first
            }
        }
    };

    public SpawnQueuesByPriority() {
        super(new Comparator<Integer>() {
            public int compare(Integer int1, Integer int2) {
                return -int1.compareTo(int2);
            }
        });
        migrateHosts = CacheBuilder.newBuilder().expireAfterWrite(TASK_MIGRATION_INTERVAL_PER_HOST, TimeUnit.MILLISECONDS).build();
    }

    public void lock() {
        queueLock.lock();
    }

    public void unlock() {
        queueLock.unlock();
    }

    public boolean tryLock() {
        return queueLock.tryLock();
    }

    public boolean addTaskToQueue(int priority, JobKey task, boolean canIgnoreQuiesce, boolean toHead) {
        queueLock.lock();
        try {
            LinkedList<SpawnQueueItem> queue = this.get(priority);
            if (queue == null) {
                queue = new LinkedList<>();
                this.put(priority, queue);
            }
            if (toHead) {
                queue.add(0, new SpawnQueueItem(task, canIgnoreQuiesce));
                return true;
            }
            return queue.add(new SpawnQueueItem(task, canIgnoreQuiesce));
        } finally {
            queueLock.unlock();
        }
    }

    public boolean remove(int priority, JobKey task) {
        queueLock.lock();
        try {

            LinkedList<SpawnQueueItem> queue = get(priority);
            if (queue != null) {
                ListIterator<SpawnQueueItem> iter = queue.listIterator();
                while (iter.hasNext()) {
                    JobKey nextKey = iter.next();
                    if (nextKey != null && nextKey.matches(task)) {
                        iter.remove();
                        return true;
                    }
                }
            }
            return false;
        } finally {
            queueLock.unlock();
        }
    }

    public int getTaskQueuedCount(int priority) {
        queueLock.lock();
        try {
            LinkedList<SpawnQueueItem> queueForPriority = this.get(priority);
            if (queueForPriority != null) {
                return queueForPriority.size();
            }
            return 0;
        } finally {
            queueLock.unlock();
        }
    }

    /**
     * Add an open slot to a host, probably in response to a task finishing
     *
     * @param hostID The host UUID to update
     */
    public void markHostAvailable(String hostID) {
        if (hostID == null) {
            return;
        }
        synchronized (hostAvailSlots) {
            if (hostAvailSlots.containsKey(hostID)) {
                hostAvailSlots.put(hostID, hostAvailSlots.get(hostID) + 1);
            } else {
                hostAvailSlots.put(hostID, 1);
            }
        }
    }

    /**
     * Out of a list of possible hosts to run a task, find the best one.
     * @param inputHosts The legal hosts for a task
     * @param requireAvailableSlot Whether to require at least one available slot
     * @return One of the hosts, if one with free slots is found; null otherwise
     */
    public HostState findBestHostToRunTask(List<HostState> inputHosts, boolean requireAvailableSlot) {
        if (inputHosts == null || inputHosts.isEmpty()) {
            return null;
        }
        synchronized (hostAvailSlots) {
            HostState bestHost = Collections.min(inputHosts, hostStateComparator);
            if (bestHost != null) {
                if (!requireAvailableSlot || hostAvailSlots.containsKey(bestHost.getHostUuid()) && hostAvailSlots.get(bestHost.getHostUuid()) > 0) {
                    return bestHost;
                }
            }
            return null;
        }
    }

    /**
     * Update the available slots for each host if it has been sufficiently long since the last update.
     *
     * @param hosts The hosts to input
     */
    public void updateAllHostAvailSlots(List<HostState> hosts) {
        synchronized (hostAvailSlots) {
            if (JitterClock.globalTime() - lastAvailSlotsUpdate < SPAWN_QUEUE_AVAIL_REFRESH) {
                return;
            }
            hostAvailSlots.clear();
            for (HostState host : hosts) {
                String hostID = host.getHostUuid();
                if (hostID != null) {
                    hostAvailSlots.put(hostID, host.getAvailableTaskSlots());
                }
            }
        }
        lastAvailSlotsUpdate = JitterClock.globalTime();
        if (log.isTraceEnabled()) {
            log.trace("[SpawnQueuesByPriority] Host Avail Slots: " + hostAvailSlots);
        }
    }

    /**
     * Use the record of which hosts have pending task kicks to decide if a task should be sent to a host
     *
     * @param hostID The host UUID to check
     * @return True if a new task should kick
     */
    public boolean shouldKickTaskOnHost(String hostID) {
        synchronized (hostAvailSlots) {
            return hostAvailSlots.containsKey(hostID) && hostAvailSlots.get(hostID) > 0;
        }
    }

    /**
     * Inform the queue that a task command is being sent to a host
     *  @param hostID  The host UUID to update
     *
     */
    public void markHostTaskActive(String hostID) {
        synchronized (hostAvailSlots) {
            int curr = hostAvailSlots.containsKey(hostID) ? hostAvailSlots.get(hostID) : 0;
            hostAvailSlots.put(hostID, Math.max(curr - 1, 0));
        }
    }

    public boolean isMigrationEnabled() {
        return ENABLE_TASK_MIGRATION;
    }

    /**
     * Decide whether a task should be migrated based on the time of last migration and the size of the task
     *
     * @param task         The task to be migrated
     * @param targetHostId The host ID being considered for migration
     * @return True if the task should be migrated there
     */
    public boolean shouldMigrateTaskToHost(JobTask task, String targetHostId) {
        String taskHost;
        if (task == null || targetHostId == null || task.getByteCount() == 0 || (taskHost = task.getHostUUID()) == null) {
            return false; // Suspicious tasks should not be migrated
        }
        return shouldKickTaskOnHost(targetHostId) && migrateHosts.getIfPresent(taskHost) == null && migrateHosts.getIfPresent(targetHostId) == null;
    }

    /**
     * Record the fact that a migration happened between two hosts, preventing additional migrations on either host for a period of time
     *
     * @param sourceHostId The host that the task is migrating from
     * @param targetHostId The host that the task is migrating to
     */
    public void markMigrationBetweenHosts(String sourceHostId, String targetHostId) {
        migrateHosts.put(sourceHostId, true);
        migrateHosts.put(targetHostId, true);
    }

    /**
     * Decide whether a task of the given size should be migrated, given how long it has been queued
     *
     * @param byteCount   The size of the task in bytes
     * @param timeOnQueue How long the task has been queued in millis
     * @return True if the task should be allowed to migrate
     */
    public boolean checkSizeAgeForMigration(long byteCount, long timeOnQueue) {
        double intervalPercentage = Math.min(1, (double) (timeOnQueue) / TASK_MIGRATION_LIMIT_GROWTH_INTERVAL);
        // The limit is TASK_MIGRATION_MIN_BYTES for recently-queued tasks, then slowly grows to TASK_MIGRATION_MAX_BYTES
        return byteCount < (long) (TASK_MIGRATION_MIN_BYTES + intervalPercentage * (TASK_MIGRATION_MAX_BYTES - TASK_MIGRATION_MIN_BYTES));
    }

    public static long getTaskMigrationMaxBytes() {
        return TASK_MIGRATION_MAX_BYTES;
    }

    public static long getTaskMigrationLimitGrowthInterval() {
        return TASK_MIGRATION_LIMIT_GROWTH_INTERVAL;
    }

    /**
     * When a job is stopped, we need to release the queue lock as quickly as possible to ensure that we can remove
     * tasks from the job as soon as possible. The stoppedJob variable enables this behavior.
     *
     * @return True if a job was stopped since the last queue iteration
     */
    public boolean getStoppedJob() {
        return stoppedJob.get();
    }

    public void setStoppedJob(boolean stopped) {
        stoppedJob.set(stopped);
    }

    public boolean shouldKickNewTaskOnHost(long timeOnQueue, HostState host) {
        synchronized (hostAvailSlots) {
            if (hostAvailSlots.containsKey(host.getHostUuid()) && hostAvailSlots.get(host.getHostUuid()) <= 1) {
                if (host.getMaxTaskSlots() == 1) {
                    // If a host has only one slot to begin with, allow tasks to kick there.
                    return true;
                }
                // Otherwise, don't let new tasks take the last slot for a set period
                return timeOnQueue > SPAWN_QUEUE_NEW_TASK_LAST_SLOT_DELAY;
            }
            return true;
        }
    }
}
