package com.davisodom.villageoverhaul.model;

import java.util.BitSet;
import java.util.Objects;
import java.util.UUID;

/**
 * Verified persistence model for a 3D volume representing a placed structure.
 * Stores exact bounds and optional per-layer occupancy flags for precise spatial queries.
 * 
 * Part of Phase 4.5 Foundational Rewrite (R002).
 * 
 * Design:
 * - Immutable bounds (minX/maxX, minY/maxY, minZ/maxZ)
 * - Optional per-block occupancy bitmap for fine-grained queries
 * - Efficient spatial queries: contains(x,y,z), contains2D(x,z,yMin..yMax), expand(buffer)
 * - Persisted alongside PlacementReceipt for ground-truth validation
 */
public class VolumeMask {
    
    // Identifiers
    private final String structureId;
    private final UUID villageId;
    
    // Exact 3D bounds (inclusive)
    private final int minX;
    private final int maxX;
    private final int minY;
    private final int maxY;
    private final int minZ;
    private final int maxZ;
    
    // Dimensions (cached for performance)
    private final int width;   // maxX - minX + 1
    private final int height;  // maxY - minY + 1
    private final int depth;   // maxZ - minZ + 1
    
    // Optional per-block occupancy bitmap
    // If null, assumes full occupancy (all blocks in AABB are occupied)
    // If present, bit at index (x,y,z) is set if block is occupied
    private final BitSet occupancy;
    
    // Timestamp
    private final long timestamp;
    
    private VolumeMask(Builder builder) {
        this.structureId = Objects.requireNonNull(builder.structureId, "structureId cannot be null");
        this.villageId = Objects.requireNonNull(builder.villageId, "villageId cannot be null");
        
        this.minX = builder.minX;
        this.maxX = builder.maxX;
        this.minY = builder.minY;
        this.maxY = builder.maxY;
        this.minZ = builder.minZ;
        this.maxZ = builder.maxZ;
        
        // Validate bounds
        if (maxX < minX || maxY < minY || maxZ < minZ) {
            throw new IllegalArgumentException("Invalid bounds: max must be >= min");
        }
        
        this.width = maxX - minX + 1;
        this.height = maxY - minY + 1;
        this.depth = maxZ - minZ + 1;
        
        this.occupancy = builder.occupancy;
        this.timestamp = builder.timestamp;
        
        // Validate occupancy bitmap size if present
        if (occupancy != null) {
            int expectedSize = width * height * depth;
            // BitSet doesn't have exact size, but we can check if it's reasonable
            if (occupancy.length() > expectedSize) {
                throw new IllegalArgumentException("Occupancy bitmap larger than expected: " + occupancy.length() + " > " + expectedSize);
            }
        }
    }
    
    // Getters
    public String getStructureId() { return structureId; }
    public UUID getVillageId() { return villageId; }
    
    public int getMinX() { return minX; }
    public int getMaxX() { return maxX; }
    public int getMinY() { return minY; }
    public int getMaxY() { return maxY; }
    public int getMinZ() { return minZ; }
    public int getMaxZ() { return maxZ; }
    
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public int getDepth() { return depth; }
    
    public long getTimestamp() { return timestamp; }
    
    /**
     * Check if a point is contained within this volume.
     * @param x world X coordinate
     * @param y world Y coordinate
     * @param z world Z coordinate
     * @return true if the point is within bounds and (if occupancy bitmap exists) the block is occupied
     */
    public boolean contains(int x, int y, int z) {
        // First check AABB bounds
        if (x < minX || x > maxX || y < minY || y > maxY || z < minZ || z > maxZ) {
            return false;
        }
        
        // If no occupancy bitmap, assume full occupancy
        if (occupancy == null) {
            return true;
        }
        
        // Check occupancy bitmap
        int index = getOccupancyIndex(x, y, z);
        return occupancy.get(index);
    }
    
    /**
     * Check if a 2D column intersects this volume within a given Y range.
     * Useful for pathfinding to check if a (x,z) location is blocked by a structure.
     * 
     * @param x world X coordinate
     * @param z world Z coordinate
     * @param yMin minimum Y to check (inclusive)
     * @param yMax maximum Y to check (inclusive)
     * @return true if any block in the column (x, yMin..yMax, z) is contained in this volume
     */
    public boolean contains2D(int x, int z, int yMin, int yMax) {
        // Quick AABB check for X/Z
        if (x < minX || x > maxX || z < minZ || z > maxZ) {
            return false;
        }
        
        // Clamp Y range to volume bounds
        int checkYMin = Math.max(yMin, minY);
        int checkYMax = Math.min(yMax, maxY);
        
        if (checkYMax < checkYMin) {
            return false; // No overlap in Y
        }
        
        // If no occupancy bitmap, any Y overlap means intersection
        if (occupancy == null) {
            return true;
        }
        
        // Check each Y level in range
        for (int y = checkYMin; y <= checkYMax; y++) {
            if (contains(x, y, z)) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Create an expanded version of this volume mask with a buffer zone.
     * Useful for creating obstacle zones around structures (e.g., buffer=2 for pathfinding).
     * 
     * @param buffer number of blocks to expand in all directions (X, Y, Z)
     * @return new VolumeMask with expanded bounds (occupancy bitmap not preserved)
     */
    public VolumeMask expand(int buffer) {
        if (buffer < 0) {
            throw new IllegalArgumentException("Buffer must be non-negative");
        }
        
        if (buffer == 0) {
            return this; // No expansion needed
        }
        
        return new Builder()
                .structureId(structureId)
                .villageId(villageId)
                .bounds(
                        minX - buffer, maxX + buffer,
                        minY - buffer, maxY + buffer,
                        minZ - buffer, maxZ + buffer
                )
                .timestamp(timestamp)
                // Note: expanded volume assumes full occupancy (no bitmap)
                .build();
    }
    
    /**
     * Create a VolumeMask from a PlacementReceipt.
     * Assumes full occupancy (no per-block bitmap).
     * 
     * @param receipt placement receipt with exact bounds
     * @return new VolumeMask matching receipt bounds
     */
    public static VolumeMask fromReceipt(PlacementReceipt receipt) {
        return new Builder()
                .structureId(receipt.getStructureId()) // Already a string
                .villageId(receipt.getVillageId())
                .bounds(
                        receipt.getMinX(), receipt.getMaxX(),
                        receipt.getMinY(), receipt.getMaxY(),
                        receipt.getMinZ(), receipt.getMaxZ()
                )
                .timestamp(receipt.getTimestamp())
                .build();
    }
    
    /**
     * Calculate 1D index for occupancy bitmap from (x,y,z) world coordinates.
     * Layout: [x + width * (z + depth * y)]
     */
    private int getOccupancyIndex(int x, int y, int z) {
        int relX = x - minX;
        int relY = y - minY;
        int relZ = z - minZ;
        return relX + width * (relZ + depth * relY);
    }
    
    /**
     * Get a compact summary for logging.
     */
    public String getSummary() {
        String occupancyStr = occupancy == null ? "full" : String.format("bitmap(%d bits)", occupancy.cardinality());
        return String.format("VolumeMask[%s bounds=(%d..%d, %d..%d, %d..%d) dims=%dx%dx%d occupancy=%s]",
                structureId, minX, maxX, minY, maxY, minZ, maxZ,
                width, height, depth, occupancyStr);
    }
    
    @Override
    public String toString() {
        return getSummary();
    }
    
    /**
     * Builder for VolumeMask.
     */
    public static class Builder {
        private String structureId;
        private UUID villageId;
        
        private int minX;
        private int maxX;
        private int minY;
        private int maxY;
        private int minZ;
        private int maxZ;
        
        private BitSet occupancy;
        private long timestamp = System.currentTimeMillis();
        
        public Builder structureId(String structureId) {
            this.structureId = structureId;
            return this;
        }
        
        public Builder villageId(UUID villageId) {
            this.villageId = villageId;
            return this;
        }
        
        public Builder bounds(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
            this.minX = minX;
            this.maxX = maxX;
            this.minY = minY;
            this.maxY = maxY;
            this.minZ = minZ;
            this.maxZ = maxZ;
            return this;
        }
        
        public Builder occupancy(BitSet occupancy) {
            this.occupancy = occupancy;
            return this;
        }
        
        public Builder timestamp(long timestamp) {
            this.timestamp = timestamp;
            return this;
        }
        
        public VolumeMask build() {
            return new VolumeMask(this);
        }
    }
}
