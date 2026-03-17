package com.davisodom.villageoverhaul.metrics;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lightweight counters for pathfinding performance instrumentation.
 */
public class PerfCounters {

    private final AtomicLong nodesExploredTotal = new AtomicLong();
    private final AtomicLong pathSearches = new AtomicLong();
    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong cacheMisses = new AtomicLong();
    private final AtomicLong invalidationEvents = new AtomicLong();
    private final AtomicLong plannerQueueWaitMs = new AtomicLong();

    public void recordPathSearch(int nodesExplored) {
        pathSearches.incrementAndGet();
        nodesExploredTotal.addAndGet(Math.max(0, nodesExplored));
    }

    public void recordCacheHit() {
        cacheHits.incrementAndGet();
    }

    public void recordCacheMiss() {
        cacheMisses.incrementAndGet();
    }

    public void recordInvalidationEvent() {
        invalidationEvents.incrementAndGet();
    }

    public void recordPlannerQueueWait(long waitMs) {
        if (waitMs > 0L) {
            plannerQueueWaitMs.addAndGet(waitMs);
        }
    }

    public Snapshot snapshot(int cacheEntries) {
        long searches = pathSearches.get();
        long nodes = nodesExploredTotal.get();
        long hits = cacheHits.get();
        long misses = cacheMisses.get();
        long invalidations = invalidationEvents.get();
        long queueWaitMs = plannerQueueWaitMs.get();
        double avgNodesPerPath = searches > 0L ? (double) nodes / (double) searches : 0.0D;
        return new Snapshot(nodes, avgNodesPerPath, hits, misses, Math.max(0, cacheEntries), invalidations,
            queueWaitMs, searches);
    }

    public void reset() {
        nodesExploredTotal.set(0L);
        pathSearches.set(0L);
        cacheHits.set(0L);
        cacheMisses.set(0L);
        invalidationEvents.set(0L);
        plannerQueueWaitMs.set(0L);
    }

    public static final class Snapshot {
        private final long nodesExploredTotal;
        private final double avgNodesPerPath;
        private final long cacheHits;
        private final long cacheMisses;
        private final int cacheEntries;
        private final long invalidationEvents;
        private final long plannerQueueWaitMs;
        private final long pathSearches;

        public Snapshot(long nodesExploredTotal, double avgNodesPerPath, long cacheHits, long cacheMisses,
                        int cacheEntries, long invalidationEvents, long plannerQueueWaitMs, long pathSearches) {
            this.nodesExploredTotal = nodesExploredTotal;
            this.avgNodesPerPath = avgNodesPerPath;
            this.cacheHits = cacheHits;
            this.cacheMisses = cacheMisses;
            this.cacheEntries = cacheEntries;
            this.invalidationEvents = invalidationEvents;
            this.plannerQueueWaitMs = plannerQueueWaitMs;
            this.pathSearches = pathSearches;
        }

        public static Snapshot empty() {
            return new Snapshot(0L, 0.0D, 0L, 0L, 0, 0L, 0L, 0L);
        }

        public long getNodesExploredTotal() {
            return nodesExploredTotal;
        }

        public double getAvgNodesPerPath() {
            return avgNodesPerPath;
        }

        public long getCacheHits() {
            return cacheHits;
        }

        public long getCacheMisses() {
            return cacheMisses;
        }

        public int getCacheEntries() {
            return cacheEntries;
        }

        public long getInvalidationEvents() {
            return invalidationEvents;
        }

        public long getPlannerQueueWaitMs() {
            return plannerQueueWaitMs;
        }

        public long getPathSearches() {
            return pathSearches;
        }

        public double getCacheHitRate() {
            long totalLookups = cacheHits + cacheMisses;
            if (totalLookups <= 0L) {
                return 0.0D;
            }
            return (double) cacheHits / (double) totalLookups;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("nodesExploredTotal", nodesExploredTotal);
            values.put("avgNodesPerPath", avgNodesPerPath);
            values.put("cacheHitRate", getCacheHitRate());
            values.put("cacheHits", cacheHits);
            values.put("cacheMisses", cacheMisses);
            values.put("cacheEntries", cacheEntries);
            values.put("invalidationEvents", invalidationEvents);
            values.put("plannerQueueWaitMs", plannerQueueWaitMs);
            values.put("pathSearches", pathSearches);
            return values;
        }
    }
}