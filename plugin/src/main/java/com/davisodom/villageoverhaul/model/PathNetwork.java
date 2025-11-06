package com.davisodom.villageoverhaul.model;

import org.bukkit.Location;
import org.bukkit.block.Block;

import java.util.*;

/**
 * Represents a path network connecting buildings in a village.
 * Thread-safe, immutable after construction.
 */
public class PathNetwork {
    
    private final UUID villageId;
    private final List<PathSegment> segments;
    private final long generatedTimestamp;
    private final int totalBlocksPlaced;
    
    private PathNetwork(UUID villageId, List<PathSegment> segments, long generatedTimestamp, int totalBlocksPlaced) {
        this.villageId = Objects.requireNonNull(villageId, "villageId cannot be null");
        this.segments = Collections.unmodifiableList(new ArrayList<>(segments));
        this.generatedTimestamp = generatedTimestamp;
        this.totalBlocksPlaced = totalBlocksPlaced;
    }
    
    public UUID getVillageId() { return villageId; }
    public List<PathSegment> getSegments() { return segments; }
    public long getGeneratedTimestamp() { return generatedTimestamp; }
    public int getTotalBlocksPlaced() { return totalBlocksPlaced; }
    
    /**
     * Check if two locations are connected by any path segment.
     */
    public boolean areConnected(Location a, Location b) {
        for (PathSegment segment : segments) {
            if ((segment.getStart().distance(a) < 2 && segment.getEnd().distance(b) < 2) ||
                (segment.getStart().distance(b) < 2 && segment.getEnd().distance(a) < 2)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Calculate connectivity percentage (0.0-1.0).
     * Based on how many buildings are reachable from the main building.
     */
    public double calculateConnectivity(List<Location> buildingLocations, Location mainBuilding) {
        if (buildingLocations.isEmpty()) {
            return 1.0;
        }
        
        int connected = 0;
        for (Location building : buildingLocations) {
            if (building.equals(mainBuilding) || areConnected(mainBuilding, building)) {
                connected++;
            }
        }
        
        return (double) connected / buildingLocations.size();
    }
    
    @Override
    public String toString() {
        return String.format("PathNetwork{village=%s, segments=%d, blocks=%d}", 
            villageId, segments.size(), totalBlocksPlaced);
    }
    
    /**
     * Builder for creating PathNetwork instances.
     */
    public static class Builder {
        private UUID villageId;
        private final List<PathSegment> segments = new ArrayList<>();
        private long generatedTimestamp = System.currentTimeMillis();
        private int totalBlocksPlaced = 0;
        
        public Builder villageId(UUID villageId) {
            this.villageId = villageId;
            return this;
        }
        
        public Builder addSegment(PathSegment segment) {
            this.segments.add(segment);
            this.totalBlocksPlaced += segment.getBlocks().size();
            return this;
        }
        
        public Builder generatedTimestamp(long generatedTimestamp) {
            this.generatedTimestamp = generatedTimestamp;
            return this;
        }
        
        public PathNetwork build() {
            return new PathNetwork(villageId, segments, generatedTimestamp, totalBlocksPlaced);
        }
    }
    
    /**
     * Represents a single path segment between two locations.
     */
    public static class PathSegment {
        private final Location start;
        private final Location end;
        private final List<Block> blocks;
        
        public PathSegment(Location start, Location end, List<Block> blocks) {
            this.start = Objects.requireNonNull(start, "start cannot be null");
            this.end = Objects.requireNonNull(end, "end cannot be null");
            this.blocks = Collections.unmodifiableList(new ArrayList<>(blocks));
        }
        
        public Location getStart() { return start.clone(); }
        public Location getEnd() { return end.clone(); }
        public List<Block> getBlocks() { return blocks; }
        
        @Override
        public String toString() {
            return String.format("PathSegment{start=(%d,%d,%d), end=(%d,%d,%d), blocks=%d}",
                start.getBlockX(), start.getBlockY(), start.getBlockZ(),
                end.getBlockX(), end.getBlockY(), end.getBlockZ(),
                blocks.size());
        }
    }
}

