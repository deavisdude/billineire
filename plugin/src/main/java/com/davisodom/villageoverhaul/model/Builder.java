package com.davisodom.villageoverhaul.model;

import org.bukkit.Location;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Represents an NPC builder entity with state machine progress.
 * Immutable value object following Constitution XI builder pattern.
 * 
 * State machine flow:
 * IDLE → WALKING_TO_BUILDING → REQUESTING_MATERIALS → GATHERING_MATERIALS →
 * CLEARING_SITE → PLACING_BLOCKS → COMPLETING → STUCK
 */
public class Builder {
    
    /**
     * Builder state machine states per Constitution XI.
     */
    public enum State {
        IDLE,                   // Waiting for build orders
        WALKING_TO_BUILDING,    // Pathfinding to construction site
        REQUESTING_MATERIALS,   // Checking inventory and requesting items from storage
        GATHERING_MATERIALS,    // Walking to warehouse/chest to collect blocks
        CLEARING_SITE,          // Removing existing blocks that conflict with blueprint
        PLACING_BLOCKS,         // Sequentially placing blocks from schematic
        COMPLETING,             // Finalizing structure and cleaning up
        STUCK                   // Error recovery state
    }
    
    private final UUID builderId;
    private final UUID villageId;
    private final UUID targetBuildingId;
    private final State state;
    private final Location currentLocation;
    private final long lastCheckpointAt;
    private final Map<String, Integer> inventory; // materialType -> count
    private final String pathCacheKey;
    private final Integer currentWaypointIndex;
    private final String stuckReason;
    
    private Builder(UUID builderId, UUID villageId, UUID targetBuildingId, State state,
                   Location currentLocation, long lastCheckpointAt, Map<String, Integer> inventory,
                   String pathCacheKey, Integer currentWaypointIndex, String stuckReason) {
        this.builderId = Objects.requireNonNull(builderId, "builderId cannot be null");
        this.villageId = Objects.requireNonNull(villageId, "villageId cannot be null");
        this.targetBuildingId = Objects.requireNonNull(targetBuildingId, "targetBuildingId cannot be null");
        this.state = Objects.requireNonNull(state, "state cannot be null");
        this.currentLocation = Objects.requireNonNull(currentLocation, "currentLocation cannot be null");
        this.lastCheckpointAt = lastCheckpointAt;
        this.inventory = Collections.unmodifiableMap(new HashMap<>(inventory));
        this.pathCacheKey = pathCacheKey;
        this.currentWaypointIndex = currentWaypointIndex;
        this.stuckReason = stuckReason;
    }
    
    public UUID getBuilderId() { return builderId; }
    public UUID getVillageId() { return villageId; }
    public UUID getTargetBuildingId() { return targetBuildingId; }
    public State getState() { return state; }
    public Location getCurrentLocation() { return currentLocation.clone(); }
    public long getLastCheckpointAt() { return lastCheckpointAt; }
    public Map<String, Integer> getInventory() { return inventory; }
    public String getPathCacheKey() { return pathCacheKey; }
    public Integer getCurrentWaypointIndex() { return currentWaypointIndex; }
    public String getStuckReason() { return stuckReason; }
    
    /**
     * Check if this builder is in an active working state.
     */
    public boolean isActive() {
        return state != State.IDLE && state != State.COMPLETING && state != State.STUCK;
    }
    
    /**
     * Check if this builder needs material gathering.
     */
    public boolean needsMaterials() {
        return state == State.REQUESTING_MATERIALS || state == State.GATHERING_MATERIALS;
    }
    
    /**
     * Create a new builder with updated state.
     * Immutable pattern: returns a new instance.
     */
    public Builder withState(State newState, long checkpointTimestamp) {
        return new Builder(builderId, villageId, targetBuildingId, newState, currentLocation,
                          checkpointTimestamp, inventory, pathCacheKey, currentWaypointIndex,
                          newState == State.STUCK ? stuckReason : null);
    }
    
    /**
     * Create a new builder with updated location.
     */
    public Builder withLocation(Location newLocation) {
        return new Builder(builderId, villageId, targetBuildingId, state, newLocation,
                          lastCheckpointAt, inventory, pathCacheKey, currentWaypointIndex, stuckReason);
    }
    
    /**
     * Create a new builder with updated inventory.
     */
    public Builder withInventory(Map<String, Integer> newInventory) {
        return new Builder(builderId, villageId, targetBuildingId, state, currentLocation,
                          lastCheckpointAt, newInventory, pathCacheKey, currentWaypointIndex, stuckReason);
    }
    
    /**
     * Create a new builder with updated path cache.
     */
    public Builder withPathCache(String cacheKey, Integer waypointIndex) {
        return new Builder(builderId, villageId, targetBuildingId, state, currentLocation,
                          lastCheckpointAt, inventory, cacheKey, waypointIndex, stuckReason);
    }
    
    /**
     * Create a new builder marked as stuck.
     */
    public Builder asStuck(String reason, long checkpointTimestamp) {
        return new Builder(builderId, villageId, targetBuildingId, State.STUCK, currentLocation,
                          checkpointTimestamp, inventory, pathCacheKey, currentWaypointIndex, reason);
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Builder builder = (Builder) o;
        return builderId.equals(builder.builderId);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(builderId);
    }
    
    @Override
    public String toString() {
        return "Builder{id=" + builderId + ", village=" + villageId + ", state=" + state + 
               ", location=" + currentLocation + ", checkpoint=" + lastCheckpointAt + "}";
    }
    
    /**
     * Builder pattern for creating new Builder instances.
     */
    public static class BuilderBuilder {
        private UUID builderId;
        private UUID villageId;
        private UUID targetBuildingId;
        private State state = State.IDLE;
        private Location currentLocation;
        private long lastCheckpointAt = System.currentTimeMillis();
        private Map<String, Integer> inventory = new HashMap<>();
        private String pathCacheKey = null;
        private Integer currentWaypointIndex = null;
        private String stuckReason = null;
        
        public BuilderBuilder builderId(UUID builderId) {
            this.builderId = builderId;
            return this;
        }
        
        public BuilderBuilder villageId(UUID villageId) {
            this.villageId = villageId;
            return this;
        }
        
        public BuilderBuilder targetBuildingId(UUID targetBuildingId) {
            this.targetBuildingId = targetBuildingId;
            return this;
        }
        
        public BuilderBuilder state(State state) {
            this.state = state;
            return this;
        }
        
        public BuilderBuilder currentLocation(Location currentLocation) {
            this.currentLocation = currentLocation;
            return this;
        }
        
        public BuilderBuilder lastCheckpointAt(long lastCheckpointAt) {
            this.lastCheckpointAt = lastCheckpointAt;
            return this;
        }
        
        public BuilderBuilder inventory(Map<String, Integer> inventory) {
            this.inventory = inventory;
            return this;
        }
        
        public BuilderBuilder pathCacheKey(String pathCacheKey) {
            this.pathCacheKey = pathCacheKey;
            return this;
        }
        
        public BuilderBuilder currentWaypointIndex(Integer currentWaypointIndex) {
            this.currentWaypointIndex = currentWaypointIndex;
            return this;
        }
        
        public BuilderBuilder stuckReason(String stuckReason) {
            this.stuckReason = stuckReason;
            return this;
        }
        
        public Builder build() {
            return new Builder(builderId, villageId, targetBuildingId, state, currentLocation,
                             lastCheckpointAt, inventory, pathCacheKey, currentWaypointIndex, stuckReason);
        }
    }
    
    public static BuilderBuilder builder() {
        return new BuilderBuilder();
    }
}
