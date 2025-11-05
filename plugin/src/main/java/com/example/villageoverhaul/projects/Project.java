package com.example.villageoverhaul.projects;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Represents a village building project funded by player trades.
 * 
 * Projects track:
 * - Cost and progress in Millz (smallest currency unit)
 * - Per-player contribution tracking
 * - Lifecycle: PENDING → ACTIVE → COMPLETE
 * - Unlock effects when completed
 * 
 * Thread-safe for concurrent contributions.
 */
public class Project {
    
    public enum Status {
        PENDING,   // Not yet published/visible
        ACTIVE,    // Accepting contributions
        COMPLETE   // Fully funded and upgraded
    }
    
    private final UUID id;
    private final UUID villageId;
    private final String buildingRef;
    private final long costMillz;
    private final List<String> unlockEffects;
    
    private volatile long progressMillz;
    private volatile Status status;
    private final Map<UUID, Long> contributors; // playerId → millz contributed
    private final Instant createdAt;
    private Instant completedAt;
    
    /**
     * Create a new project
     * 
     * @param villageId Village that owns this project
     * @param buildingRef Reference to building definition (e.g., "blacksmith_tier_2")
     * @param costMillz Total cost in Millz (must be > 0)
     * @param unlockEffects Effects applied on completion (e.g., ["trade_slots:+2", "profession:master_blacksmith"])
     */
    public Project(UUID villageId, String buildingRef, long costMillz, List<String> unlockEffects) {
        if (costMillz <= 0) {
            throw new IllegalArgumentException("Project cost must be positive: " + costMillz);
        }
        if (villageId == null || buildingRef == null || buildingRef.isBlank()) {
            throw new IllegalArgumentException("Village ID and building reference are required");
        }
        
        this.id = UUID.randomUUID();
        this.villageId = villageId;
        this.buildingRef = buildingRef;
        this.costMillz = costMillz;
        this.unlockEffects = new ArrayList<>(unlockEffects != null ? unlockEffects : Collections.emptyList());
        
        this.progressMillz = 0;
        this.status = Status.PENDING;
        this.contributors = new ConcurrentHashMap<>();
        this.createdAt = Instant.now();
    }
    
    /**
     * Load project from persistence
     */
    public Project(UUID id, UUID villageId, String buildingRef, long costMillz, 
                   long progressMillz, Status status, Map<UUID, Long> contributors,
                   List<String> unlockEffects, Instant createdAt, Instant completedAt) {
        this.id = id;
        this.villageId = villageId;
        this.buildingRef = buildingRef;
        this.costMillz = costMillz;
        this.progressMillz = progressMillz;
        this.status = status;
        this.contributors = new ConcurrentHashMap<>(contributors);
        this.unlockEffects = new ArrayList<>(unlockEffects);
        this.createdAt = createdAt;
        this.completedAt = completedAt;
    }
    
    // Getters
    
    public UUID getId() { return id; }
    public UUID getVillageId() { return villageId; }
    public String getBuildingRef() { return buildingRef; }
    public long getCostMillz() { return costMillz; }
    public long getProgressMillz() { return progressMillz; }
    public Status getStatus() { return status; }
    public Map<UUID, Long> getContributors() { return Collections.unmodifiableMap(contributors); }
    public List<String> getUnlockEffects() { return Collections.unmodifiableList(unlockEffects); }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }
    
    /**
     * Calculate completion percentage (0-100)
     */
    public int getCompletionPercent() {
        if (costMillz == 0) return 100;
        return (int) Math.min(100, (progressMillz * 100) / costMillz);
    }
    
    /**
     * Check if project is fully funded
     */
    public boolean isFullyFunded() {
        return progressMillz >= costMillz;
    }
    
    /**
     * Activate the project (make it visible and accepting contributions)
     * 
     * @return true if activated, false if already active/complete
     */
    public synchronized boolean activate() {
        if (status == Status.PENDING) {
            status = Status.ACTIVE;
            return true;
        }
        return false;
    }
    
    /**
     * Add a contribution from a player
     * 
     * @param playerId Player making the contribution
     * @param millz Amount in Millz (must be > 0)
     * @return Contribution result with overflow info
     * @throws IllegalStateException if project is not ACTIVE
     * @throws IllegalArgumentException if amount is invalid
     */
    public synchronized ContributionResult contribute(UUID playerId, long millz) {
        if (status != Status.ACTIVE) {
            throw new IllegalStateException("Project is not active: " + status);
        }
        if (millz <= 0) {
            throw new IllegalArgumentException("Contribution must be positive: " + millz);
        }
        
        long remainingNeeded = costMillz - progressMillz;
        long actualContribution = Math.min(millz, remainingNeeded);
        long overflow = millz - actualContribution;
        
        // Update progress
        progressMillz += actualContribution;
        
        // Track contributor
        contributors.merge(playerId, actualContribution, Long::sum);
        
        // Auto-complete if fully funded
        boolean completed = false;
        if (progressMillz >= costMillz) {
            status = Status.COMPLETE;
            completedAt = Instant.now();
            completed = true;
        }
        
        return new ContributionResult(actualContribution, overflow, completed);
    }
    
    /**
     * Result of a contribution attempt
     */
    public static class ContributionResult {
        private final long accepted;
        private final long overflow;
        private final boolean completed;
        
        public ContributionResult(long accepted, long overflow, boolean completed) {
            this.accepted = accepted;
            this.overflow = overflow;
            this.completed = completed;
        }
        
        public long getAccepted() { return accepted; }
        public long getOverflow() { return overflow; }
        public boolean isCompleted() { return completed; }
    }
    
    @Override
    public String toString() {
        return String.format("Project{id=%s, village=%s, building=%s, progress=%d/%d (%d%%), status=%s}",
                id, villageId, buildingRef, progressMillz, costMillz, getCompletionPercent(), status);
    }
}
