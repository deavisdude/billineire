package com.davisodom.villageoverhaul.model;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Represents an asynchronous placement queue for building construction.
 * Stores deterministically ordered block placements (row-by-row, layer-by-layer)
 * for main-thread batched commits per Constitution XI.
 * 
 * Preparation happens off-thread, commits happen on main thread in small batches.
 * Provides visible construction progress to players.
 */
public class PlacementQueue {
    
    /**
     * Queue processing status.
     */
    public enum Status {
        PREPARING,  // Queue being built off-thread
        READY,      // Queue prepared and ready for commits
        COMMITTING, // Actively placing blocks on main thread
        COMPLETE,   // All blocks placed successfully
        ABORTED     // Queue aborted due to error
    }
    
    /**
     * Single block placement entry with deterministic ordering.
     */
    public static class Entry {
        private final int x;
        private final int y;
        private final int z;
        private final Material material;
        private final BlockData blockData;
        private final int layer;
        private final int row;
        private final int orderIndex; // Global ordering within queue
        
        public Entry(int x, int y, int z, Material material, BlockData blockData, int layer, int row, int orderIndex) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.material = Objects.requireNonNull(material, "material cannot be null");
            this.blockData = blockData; // nullable for simple blocks
            this.layer = layer;
            this.row = row;
            this.orderIndex = orderIndex;
        }
        
        public int getX() { return x; }
        public int getY() { return y; }
        public int getZ() { return z; }
        public Material getMaterial() { return material; }
        public BlockData getBlockData() { return blockData; }
        public int getLayer() { return layer; }
        public int getRow() { return row; }
        public int getOrderIndex() { return orderIndex; }
        
        @Override
        public String toString() {
            return "Entry{pos=(" + x + "," + y + "," + z + "), mat=" + material + 
                   ", layer=" + layer + ", row=" + row + ", order=" + orderIndex + "}";
        }
    }
    
    private final UUID queueId;
    private final UUID buildingId;
    private final List<Entry> entries;
    private final Status status;
    private final int batchSize;
    private final int currentIndex; // Next entry to commit
    private final long createdAt;
    private final long lastCommitAt;
    private final String abortReason;
    
    private PlacementQueue(UUID queueId, UUID buildingId, List<Entry> entries, Status status,
                          int batchSize, int currentIndex, long createdAt, long lastCommitAt,
                          String abortReason) {
        this.queueId = Objects.requireNonNull(queueId, "queueId cannot be null");
        this.buildingId = Objects.requireNonNull(buildingId, "buildingId cannot be null");
        this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
        this.status = Objects.requireNonNull(status, "status cannot be null");
        this.batchSize = batchSize;
        this.currentIndex = currentIndex;
        this.createdAt = createdAt;
        this.lastCommitAt = lastCommitAt;
        this.abortReason = abortReason;
        
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        if (currentIndex < 0 || currentIndex > entries.size()) {
            throw new IllegalArgumentException("currentIndex out of range");
        }
        if (status == Status.ABORTED && abortReason == null) {
            throw new IllegalArgumentException("abortReason required when status is ABORTED");
        }
    }
    
    public UUID getQueueId() { return queueId; }
    public UUID getBuildingId() { return buildingId; }
    public List<Entry> getEntries() { return entries; }
    public Status getStatus() { return status; }
    public int getBatchSize() { return batchSize; }
    public int getCurrentIndex() { return currentIndex; }
    public long getCreatedAt() { return createdAt; }
    public long getLastCommitAt() { return lastCommitAt; }
    public String getAbortReason() { return abortReason; }
    
    /**
     * Get total number of blocks in the queue.
     */
    public int getTotalBlocks() {
        return entries.size();
    }
    
    /**
     * Get number of blocks already placed.
     */
    public int getBlocksPlaced() {
        return currentIndex;
    }
    
    /**
     * Get number of blocks remaining.
     */
    public int getBlocksRemaining() {
        return entries.size() - currentIndex;
    }
    
    /**
     * Calculate completion percentage (0.0 - 1.0).
     */
    public double getPercentComplete() {
        if (entries.isEmpty()) return 1.0;
        return (double) currentIndex / entries.size();
    }
    
    /**
     * Check if queue is finished (all blocks placed or aborted).
     */
    public boolean isFinished() {
        return status == Status.COMPLETE || status == Status.ABORTED;
    }
    
    /**
     * Check if queue is ready for commits.
     */
    public boolean isReadyForCommit() {
        return status == Status.READY || status == Status.COMMITTING;
    }
    
    /**
     * Get the next batch of entries to commit.
     * Returns up to batchSize entries starting from currentIndex.
     */
    public List<Entry> getNextBatch() {
        if (currentIndex >= entries.size()) {
            return Collections.emptyList();
        }
        int endIndex = Math.min(currentIndex + batchSize, entries.size());
        return entries.subList(currentIndex, endIndex);
    }
    
    /**
     * Get current layer and row from the current index.
     */
    public ConstructionProgress getProgress() {
        if (entries.isEmpty() || currentIndex >= entries.size()) {
            return new ConstructionProgress(0, 0, getPercentComplete());
        }
        Entry currentEntry = entries.get(currentIndex);
        return new ConstructionProgress(currentEntry.getLayer(), currentEntry.getRow(), getPercentComplete());
    }
    
    /**
     * Create a new queue with updated status.
     */
    public PlacementQueue withStatus(Status newStatus, long timestamp) {
        return new PlacementQueue(queueId, buildingId, entries, newStatus, batchSize,
                                 currentIndex, createdAt, timestamp, abortReason);
    }
    
    /**
     * Create a new queue with advanced index after a successful batch commit.
     */
    public PlacementQueue withAdvancedIndex(int newIndex, long timestamp) {
        Status newStatus = (newIndex >= entries.size()) ? Status.COMPLETE : Status.COMMITTING;
        return new PlacementQueue(queueId, buildingId, entries, newStatus, batchSize,
                                 newIndex, createdAt, timestamp, abortReason);
    }
    
    /**
     * Create a new queue marked as aborted.
     */
    public PlacementQueue asAborted(String reason, long timestamp) {
        return new PlacementQueue(queueId, buildingId, entries, Status.ABORTED, batchSize,
                                 currentIndex, createdAt, timestamp, reason);
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PlacementQueue that = (PlacementQueue) o;
        return queueId.equals(that.queueId);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(queueId);
    }
    
    @Override
    public String toString() {
        return "PlacementQueue{id=" + queueId + ", building=" + buildingId + ", status=" + status +
               ", blocks=" + entries.size() + ", placed=" + currentIndex + ", batch=" + batchSize +
               ", progress=" + String.format("%.1f%%", getPercentComplete() * 100) + "}";
    }
    
    /**
     * Progress snapshot for visible construction feedback.
     */
    public record ConstructionProgress(int currentLayer, int currentRow, double percentComplete) {}
    
    /**
     * Builder pattern for creating new PlacementQueue instances.
     */
    public static class PlacementQueueBuilder {
        private UUID queueId;
        private UUID buildingId;
        private List<Entry> entries = new ArrayList<>();
        private Status status = Status.PREPARING;
        private int batchSize = 50; // Default: 50 blocks per tick
        private int currentIndex = 0;
        private long createdAt = System.currentTimeMillis();
        private long lastCommitAt = System.currentTimeMillis();
        private String abortReason = null;
        
        public PlacementQueueBuilder queueId(UUID queueId) {
            this.queueId = queueId;
            return this;
        }
        
        public PlacementQueueBuilder buildingId(UUID buildingId) {
            this.buildingId = buildingId;
            return this;
        }
        
        public PlacementQueueBuilder entries(List<Entry> entries) {
            this.entries = entries;
            return this;
        }
        
        public PlacementQueueBuilder addEntry(Entry entry) {
            this.entries.add(entry);
            return this;
        }
        
        public PlacementQueueBuilder status(Status status) {
            this.status = status;
            return this;
        }
        
        public PlacementQueueBuilder batchSize(int batchSize) {
            this.batchSize = batchSize;
            return this;
        }
        
        public PlacementQueueBuilder currentIndex(int currentIndex) {
            this.currentIndex = currentIndex;
            return this;
        }
        
        public PlacementQueueBuilder createdAt(long createdAt) {
            this.createdAt = createdAt;
            return this;
        }
        
        public PlacementQueueBuilder lastCommitAt(long lastCommitAt) {
            this.lastCommitAt = lastCommitAt;
            return this;
        }
        
        public PlacementQueueBuilder abortReason(String abortReason) {
            this.abortReason = abortReason;
            return this;
        }
        
        public PlacementQueue build() {
            return new PlacementQueue(queueId, buildingId, entries, status, batchSize,
                                     currentIndex, createdAt, lastCommitAt, abortReason);
        }
    }
    
    public static PlacementQueueBuilder builder() {
        return new PlacementQueueBuilder();
    }
}
