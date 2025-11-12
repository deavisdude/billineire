package com.davisodom.villageoverhaul.model;

import org.bukkit.Material;
import org.bukkit.World;

import java.util.Objects;
import java.util.UUID;

/**
 * Canonical placement receipt for a structure paste operation.
 * Provides ground-truth proof of paste alignment and exact world bounds.
 * 
 * Part of Phase 4.5 Foundational Rewrite (R001).
 */
public class PlacementReceipt {
    
    // Identifiers
    private final String structureId;
    private final UUID villageId;
    private final String worldName;
    
    // Exact AABB in world coordinates (inclusive bounds)
    private final int minX;
    private final int maxX;
    private final int minY;
    private final int maxY;
    private final int minZ;
    private final int maxZ;
    
    // Transform parameters
    private final int originX;
    private final int originY;
    private final int originZ;
    private final int rotation; // 0, 90, 180, or 270 degrees
    
    // Effective dimensions (post-rotation)
    private final int effectiveWidth;  // X-axis extent
    private final int effectiveDepth;  // Z-axis extent
    private final int height;          // Y-axis extent
    
    // Foundation corner samples (proof of paste alignment)
    // Order: NW, NE, SE, SW (clockwise from top-left when viewed from above)
    private final CornerSample[] foundationCorners;
    
    // Timestamp
    private final long timestamp;
    
    /**
     * Corner sample with coordinates and block type.
     */
    public static class CornerSample {
        private final int x;
        private final int y;
        private final int z;
        private final Material blockType;
        
        public CornerSample(int x, int y, int z, Material blockType) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.blockType = Objects.requireNonNull(blockType, "blockType cannot be null");
        }
        
        public int getX() { return x; }
        public int getY() { return y; }
        public int getZ() { return z; }
        public Material getBlockType() { return blockType; }
        
        public boolean isNonAirSolid() {
            return blockType != Material.AIR && 
                   blockType != Material.CAVE_AIR && 
                   blockType != Material.VOID_AIR &&
                   blockType.isSolid();
        }
        
        @Override
        public String toString() {
            return String.format("(%d,%d,%d)=%s", x, y, z, blockType.name());
        }
    }
    
    private PlacementReceipt(Builder builder) {
        this.structureId = Objects.requireNonNull(builder.structureId, "structureId cannot be null");
        this.villageId = Objects.requireNonNull(builder.villageId, "villageId cannot be null");
        this.worldName = Objects.requireNonNull(builder.worldName, "worldName cannot be null");
        
        this.minX = builder.minX;
        this.maxX = builder.maxX;
        this.minY = builder.minY;
        this.maxY = builder.maxY;
        this.minZ = builder.minZ;
        this.maxZ = builder.maxZ;
        
        this.originX = builder.originX;
        this.originY = builder.originY;
        this.originZ = builder.originZ;
        this.rotation = builder.rotation;
        
        this.effectiveWidth = builder.effectiveWidth;
        this.effectiveDepth = builder.effectiveDepth;
        this.height = builder.height;
        
        this.foundationCorners = Objects.requireNonNull(builder.foundationCorners, "foundationCorners cannot be null");
        if (foundationCorners.length != 4) {
            throw new IllegalArgumentException("Must provide exactly 4 foundation corner samples");
        }
        
        this.timestamp = builder.timestamp;
        
        // Validation
        if (maxX < minX || maxY < minY || maxZ < minZ) {
            throw new IllegalArgumentException("Invalid bounds: max must be >= min");
        }
        if (rotation % 90 != 0 || rotation < 0 || rotation >= 360) {
            throw new IllegalArgumentException("Rotation must be 0, 90, 180, or 270");
        }
        if (effectiveWidth <= 0 || effectiveDepth <= 0 || height <= 0) {
            throw new IllegalArgumentException("Dimensions must be positive");
        }
    }
    
    // Getters
    public String getStructureId() { return structureId; }
    public UUID getVillageId() { return villageId; }
    public String getWorldName() { return worldName; }
    
    public int getMinX() { return minX; }
    public int getMaxX() { return maxX; }
    public int getMinY() { return minY; }
    public int getMaxY() { return maxY; }
    public int getMinZ() { return minZ; }
    public int getMaxZ() { return maxZ; }
    
    public int getOriginX() { return originX; }
    public int getOriginY() { return originY; }
    public int getOriginZ() { return originZ; }
    public int getRotation() { return rotation; }
    
    public int getEffectiveWidth() { return effectiveWidth; }
    public int getEffectiveDepth() { return effectiveDepth; }
    public int getHeight() { return height; }
    
    public CornerSample[] getFoundationCorners() { return foundationCorners.clone(); }
    public long getTimestamp() { return timestamp; }
    
    /**
     * Verify that all foundation corners contain non-air solid blocks.
     * @return true if all corners are valid (non-air solid blocks)
     */
    public boolean verifyFoundationCorners() {
        for (CornerSample corner : foundationCorners) {
            if (!corner.isNonAirSolid()) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Get a compact summary line for logging.
     * Format: [structureId @ (x,y,z) rot=NNN° bounds=(minX..maxX, minY..maxY, minZ..maxZ) dims=WxHxD]
     */
    public String getReceiptSummary() {
        return String.format("%s @ (%d,%d,%d) rot=%d° bounds=(%d..%d, %d..%d, %d..%d) dims=%dx%dx%d",
                structureId, originX, originY, originZ, rotation,
                minX, maxX, minY, maxY, minZ, maxZ,
                effectiveWidth, height, effectiveDepth);
    }
    
    @Override
    public String toString() {
        return String.format("PlacementReceipt{%s, village=%s, world=%s, corners=%s}",
                getReceiptSummary(), villageId, worldName,
                java.util.Arrays.toString(foundationCorners));
    }
    
    /**
     * Builder for PlacementReceipt.
     */
    public static class Builder {
        private String structureId;
        private UUID villageId;
        private String worldName;
        
        private int minX;
        private int maxX;
        private int minY;
        private int maxY;
        private int minZ;
        private int maxZ;
        
        private int originX;
        private int originY;
        private int originZ;
        private int rotation;
        
        private int effectiveWidth;
        private int effectiveDepth;
        private int height;
        
        private CornerSample[] foundationCorners;
        private long timestamp = System.currentTimeMillis();
        
        public Builder structureId(String structureId) {
            this.structureId = structureId;
            return this;
        }
        
        public Builder villageId(UUID villageId) {
            this.villageId = villageId;
            return this;
        }
        
        public Builder world(World world) {
            this.worldName = world.getName();
            return this;
        }
        
        public Builder worldName(String worldName) {
            this.worldName = worldName;
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
        
        public Builder origin(int x, int y, int z) {
            this.originX = x;
            this.originY = y;
            this.originZ = z;
            return this;
        }
        
        public Builder rotation(int rotation) {
            this.rotation = rotation;
            return this;
        }
        
        public Builder dimensions(int width, int height, int depth) {
            this.effectiveWidth = width;
            this.height = height;
            this.effectiveDepth = depth;
            return this;
        }
        
        public Builder foundationCorners(CornerSample[] corners) {
            this.foundationCorners = corners;
            return this;
        }
        
        public Builder timestamp(long timestamp) {
            this.timestamp = timestamp;
            return this;
        }
        
        public PlacementReceipt build() {
            return new PlacementReceipt(this);
        }
    }
}
