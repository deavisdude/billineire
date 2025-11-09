package com.davisodom.villageoverhaul.obs;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Observability infrastructure for metrics and structured logging
 * 
 * Provides:
 * - Tick time counters per subsystem
 * - Operation counters and latencies
 * - Correlation IDs for tracing
 * - Debug flags
 * 
 * Constitution compliance:
 * - Principle VIII: Observability, Testing, and QA Discipline
 */
public class Metrics {
    
    private final Logger logger;
    private final Map<String, Long> counters;
    private final Map<String, TickTimeStats> tickTimeStats;
    private final Map<UUID, TickTimeStats> villageTickTimeStats; // Per-village metrics for ≤2ms budget
    private boolean debugEnabled;
    
    public Metrics(Logger logger) {
        this.logger = logger;
        this.counters = new ConcurrentHashMap<>();
        this.tickTimeStats = new ConcurrentHashMap<>();
        this.villageTickTimeStats = new ConcurrentHashMap<>();
        this.debugEnabled = false;
    }
    
    /**
     * Increment a counter
     */
    public void increment(String counterName) {
        counters.merge(counterName, 1L, Long::sum);
    }
    
    /**
     * Increment a counter by a specific amount
     */
    public void increment(String counterName, long amount) {
        counters.merge(counterName, amount, Long::sum);
    }
    
    /**
     * Get current counter value
     */
    public long getCounter(String counterName) {
        return counters.getOrDefault(counterName, 0L);
    }
    
    /**
     * Record tick time for a subsystem (microseconds)
     */
    public void recordTickTime(String subsystem, long micros) {
        tickTimeStats.computeIfAbsent(subsystem, k -> new TickTimeStats())
            .record(micros);
    }
    
    /**
     * Record tick time for a specific village (microseconds)
     * Constitution: Per-village tick cost ≤ 2ms amortized
     */
    public void recordVillageTickTime(UUID villageId, long micros) {
        villageTickTimeStats.computeIfAbsent(villageId, k -> new TickTimeStats())
            .record(micros);
            
        // Warn if village exceeds 2ms budget (2000 micros)
        if (micros > 2000) {
            logger.warning(String.format("Village %s exceeded 2ms tick budget: %d μs", 
                villageId, micros));
        }
    }
    
    /**
     * Get tick time statistics for a specific village
     */
    public TickTimeStats getVillageTickTimeStats(UUID villageId) {
        return villageTickTimeStats.get(villageId);
    }
    
    /**
     * Get all village tick time stats
     */
    public Map<UUID, TickTimeStats> getAllVillageTickTimeStats() {
        return new HashMap<>(villageTickTimeStats);
    }
    
    /**
     * Get tick time statistics for a subsystem
     */
    public TickTimeStats getTickTimeStats(String subsystem) {
        return tickTimeStats.get(subsystem);
    }
    
    /**
     * Get all tick time stats
     */
    public Map<String, TickTimeStats> getAllTickTimeStats() {
        return new HashMap<>(tickTimeStats);
    }
    
    /**
     * Generate a correlation ID for tracing
     */
    public String generateCorrelationId() {
        return UUID.randomUUID().toString();
    }
    
    /**
     * Log with correlation ID
     */
    public void logWithCorrelation(String correlationId, String message) {
        logger.info(String.format("[%s] %s", correlationId, message));
    }
    
    /**
     * Enable/disable debug logging
     */
    public void setDebugEnabled(boolean enabled) {
        this.debugEnabled = enabled;
        logger.info("Debug logging " + (enabled ? "enabled" : "disabled"));
    }
    
    /**
     * Check if debug is enabled
     */
    public boolean isDebugEnabled() {
        return debugEnabled;
    }
    
    /**
     * Log debug message (only if debug enabled)
     */
    public void debug(String message) {
        if (debugEnabled) {
            logger.info("[DEBUG] " + message);
        }
    }
    
    /**
     * Get metrics snapshot for reporting
     */
    public MetricsSnapshot getSnapshot() {
        return new MetricsSnapshot(
            new HashMap<>(counters),
            new HashMap<>(tickTimeStats)
        );
    }
    
    /**
     * Reset all metrics (for testing)
     */
    public void reset() {
        counters.clear();
        tickTimeStats.clear();
    }
    
    /**
     * Tick time statistics with p95/p99
     */
    public static class TickTimeStats {
        private long count = 0;
        private long totalMicros = 0;
        private long minMicros = Long.MAX_VALUE;
        private long maxMicros = 0;
        
        // Simple rolling window for percentiles
        private final long[] recentSamples = new long[100];
        private int sampleIndex = 0;
        
        public synchronized void record(long micros) {
            count++;
            totalMicros += micros;
            minMicros = Math.min(minMicros, micros);
            maxMicros = Math.max(maxMicros, micros);
            
            // Store in rolling window
            recentSamples[sampleIndex] = micros;
            sampleIndex = (sampleIndex + 1) % recentSamples.length;
        }
        
        public long getCount() { return count; }
        public long getAverageMicros() { return count > 0 ? totalMicros / count : 0; }
        public long getMinMicros() { return minMicros == Long.MAX_VALUE ? 0 : minMicros; }
        public long getMaxMicros() { return maxMicros; }
        
        /**
         * Calculate p95 from recent samples
         */
        public long getP95Micros() {
            return getPercentile(95);
        }
        
        /**
         * Calculate p99 from recent samples
         */
        public long getP99Micros() {
            return getPercentile(99);
        }
        
        private long getPercentile(int percentile) {
            long[] sorted = recentSamples.clone();
            java.util.Arrays.sort(sorted);
            int index = (int) Math.ceil(sorted.length * percentile / 100.0) - 1;
            return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
        }
    }
    
    /**
     * Metrics snapshot for serialization
     */
    public static class MetricsSnapshot {
        public final Map<String, Long> counters;
        public final Map<String, TickTimeStats> tickTimeStats;
        
        public MetricsSnapshot(Map<String, Long> counters, Map<String, TickTimeStats> tickTimeStats) {
            this.counters = counters;
            this.tickTimeStats = tickTimeStats;
        }
    }
}

