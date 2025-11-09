package com.davisodom.villageoverhaul.model;

import org.bukkit.Location;

import java.util.Objects;
import java.util.UUID;

/**
 * Represents a placed building structure in a village.
 * Immutable value object with validation.
 */
public class Building {
    
    private final UUID buildingId;
    private final UUID villageId;
    private final String structureId;
    private final Location origin;
    private final int[] dimensions; // [width, height, depth]
    private final long placedTimestamp;
    private final boolean isMainBuilding;
    
    private Building(UUID buildingId, UUID villageId, String structureId, Location origin, 
                     int[] dimensions, long placedTimestamp, boolean isMainBuilding) {
        this.buildingId = Objects.requireNonNull(buildingId, "buildingId cannot be null");
        this.villageId = Objects.requireNonNull(villageId, "villageId cannot be null");
        this.structureId = Objects.requireNonNull(structureId, "structureId cannot be null");
        this.origin = Objects.requireNonNull(origin, "origin cannot be null");
        this.dimensions = Objects.requireNonNull(dimensions, "dimensions cannot be null");
        this.placedTimestamp = placedTimestamp;
        this.isMainBuilding = isMainBuilding;
        
        if (dimensions.length != 3) {
            throw new IllegalArgumentException("dimensions must be [width, height, depth]");
        }
        if (dimensions[0] <= 0 || dimensions[1] <= 0 || dimensions[2] <= 0) {
            throw new IllegalArgumentException("dimensions must be positive");
        }
    }
    
    public UUID getBuildingId() { return buildingId; }
    public UUID getVillageId() { return villageId; }
    public String getStructureId() { return structureId; }
    public Location getOrigin() { return origin.clone(); }
    public int[] getDimensions() { return dimensions.clone(); }
    public int getWidth() { return dimensions[0]; }
    public int getHeight() { return dimensions[1]; }
    public int getDepth() { return dimensions[2]; }
    public long getPlacedTimestamp() { return placedTimestamp; }
    public boolean isMainBuilding() { return isMainBuilding; }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Building)) return false;
        Building building = (Building) o;
        return buildingId.equals(building.buildingId);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(buildingId);
    }
    
    @Override
    public String toString() {
        return String.format("Building{id=%s, structure=%s, village=%s, origin=(%d,%d,%d)}", 
            buildingId, structureId, villageId, origin.getBlockX(), origin.getBlockY(), origin.getBlockZ());
    }
    
    /**
     * Builder for creating Building instances.
     */
    public static class Builder {
        private UUID buildingId = UUID.randomUUID();
        private UUID villageId;
        private String structureId;
        private Location origin;
        private int[] dimensions;
        private long placedTimestamp = System.currentTimeMillis();
        private boolean isMainBuilding = false;
        
        public Builder buildingId(UUID buildingId) {
            this.buildingId = buildingId;
            return this;
        }
        
        public Builder villageId(UUID villageId) {
            this.villageId = villageId;
            return this;
        }
        
        public Builder structureId(String structureId) {
            this.structureId = structureId;
            return this;
        }
        
        public Builder origin(Location origin) {
            this.origin = origin;
            return this;
        }
        
        public Builder dimensions(int width, int height, int depth) {
            this.dimensions = new int[]{width, height, depth};
            return this;
        }
        
        public Builder dimensions(int[] dimensions) {
            this.dimensions = dimensions;
            return this;
        }
        
        public Builder placedTimestamp(long placedTimestamp) {
            this.placedTimestamp = placedTimestamp;
            return this;
        }
        
        public Builder isMainBuilding(boolean isMainBuilding) {
            this.isMainBuilding = isMainBuilding;
            return this;
        }
        
        public Building build() {
            return new Building(buildingId, villageId, structureId, origin, dimensions, 
                              placedTimestamp, isMainBuilding);
        }
    }
}

