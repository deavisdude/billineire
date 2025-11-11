package com.davisodom.villageoverhaul.worldgen;

import org.bukkit.Location;

/**
 * Result of a structure placement operation.
 * Contains the actual placed location and rotation applied.
 * T021b: Required to properly track building footprints after re-seating.
 */
public class PlacementResult {
    private final Location actualLocation;
    private final int rotationDegrees;  // 0, 90, 180, or 270
    
    public PlacementResult(Location actualLocation, int rotationDegrees) {
        this.actualLocation = actualLocation;
        this.rotationDegrees = rotationDegrees;
    }
    
    public Location getActualLocation() {
        return actualLocation;
    }
    
    public int getRotationDegrees() {
        return rotationDegrees;
    }
    
    /**
     * Get effective width after rotation.
     * For 90/270 degree rotations, width and depth swap.
     */
    public int getEffectiveWidth(int originalWidth, int originalDepth) {
        if (rotationDegrees == 90 || rotationDegrees == 270) {
            return originalDepth;
        }
        return originalWidth;
    }
    
    /**
     * Get effective depth after rotation.
     * For 90/270 degree rotations, width and depth swap.
     */
    public int getEffectiveDepth(int originalWidth, int originalDepth) {
        if (rotationDegrees == 90 || rotationDegrees == 270) {
            return originalWidth;
        }
        return originalDepth;
    }
}
