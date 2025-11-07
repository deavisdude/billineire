package com.davisodom.villageoverhaul.worldgen;

import com.davisodom.villageoverhaul.worldgen.TerrainClassifier.Classification;
import com.davisodom.villageoverhaul.worldgen.TerrainClassifier.ClassificationResult;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.logging.Logger;

/**
 * Validates sites for structure placement.
 * Checks foundation solidity, interior clearance, and entrance accessibility.
 */
public class SiteValidator {
    
    private static final Logger LOGGER = Logger.getLogger(SiteValidator.class.getName());
    
    // Maximum allowed slope for foundation (blocks per horizontal distance)
    private static final double MAX_FOUNDATION_SLOPE = 0.25;
    
    // Minimum percentage of solid blocks required in foundation
    private static final double MIN_FOUNDATION_SOLIDITY = 0.85;
    
    // Minimum air blocks required at entrance
    private static final int MIN_ENTRANCE_AIR_HEIGHT = 3;
    
    /**
     * Validate a site for structure placement.
     * 
     * @param world Target world
     * @param origin Proposed placement origin (southwest corner, ground level)
     * @param width Structure width (X axis)
     * @param depth Structure depth (Z axis)
     * @param height Structure height (Y axis)
     * @return Validation result with pass/fail and details
     */
    public ValidationResult validateSite(World world, Location origin, int width, int depth, int height) {
        ValidationResult result = new ValidationResult();
        
        // Check foundation solidity with terrain classification
        ClassificationResult classificationResult = new ClassificationResult();
        boolean foundationOk = validateFoundation(world, origin, width, depth, classificationResult);
        result.foundationOk = foundationOk;
        result.classificationResult = classificationResult;
        
        // Check interior air space
        boolean interiorAirOk = validateInteriorAir(world, origin, width, depth, height);
        result.interiorAirOk = interiorAirOk;
        
        // Check entrance accessibility
        boolean entranceOk = validateEntranceAccess(world, origin, width, depth);
        result.entranceOk = entranceOk;
        
        result.passed = foundationOk && interiorAirOk && entranceOk;
        
        if (!result.passed) {
            LOGGER.fine(String.format("[STRUCT] Site validation failed at %s: foundation=%b, interior=%b, entrance=%b, classification: %s",
                    origin, foundationOk, interiorAirOk, entranceOk, classificationResult));
        }
        
        return result;
    }
    
    /**
     * Validate foundation solidity and acceptable slope with terrain classification.
     */
    private boolean validateFoundation(World world, Location origin, int width, int depth, 
                                      ClassificationResult classificationResult) {
        int solidCount = 0;
        int totalCount = 0;
        double maxSlope = 0.0;
        
        Integer minY = null;
        Integer maxY = null;
        
        // Sample foundation blocks and classify terrain
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                int worldX = origin.getBlockX() + x;
                int worldY = origin.getBlockY() - 1;
                int worldZ = origin.getBlockZ() + z;
                
                Block block = world.getBlockAt(worldX, worldY, worldZ);
                totalCount++;
                
                // Classify terrain at this position
                Classification classification = TerrainClassifier.classify(world, worldX, worldY, worldZ);
                classificationResult.increment(classification);
                
                // Reject if not acceptable (FLUID, STEEP, or BLOCKED)
                if (classification != Classification.ACCEPTABLE) {
                    // Early rejection for unacceptable terrain
                    continue;
                }
                
                // Check if block is solid
                if (block.getType().isSolid()) {
                    solidCount++;
                    
                    // Track Y variation for slope calculation
                    int y = block.getY();
                    if (minY == null || y < minY) minY = y;
                    if (maxY == null || y > maxY) maxY = y;
                }
            }
        }
        
        // Calculate slope
        if (minY != null && maxY != null) {
            int yDiff = maxY - minY;
            int horizontalDist = Math.max(width, depth);
            maxSlope = (double) yDiff / horizontalDist;
        }
        
        double solidity = (double) solidCount / totalCount;
        
        boolean solidityOk = solidity >= MIN_FOUNDATION_SOLIDITY;
        boolean slopeOk = maxSlope <= MAX_FOUNDATION_SLOPE;
        
        // Reject if any unacceptable terrain found
        boolean terrainOk = classificationResult.getRejected() == 0;
        
        LOGGER.fine(String.format("[STRUCT] Foundation check: solidity=%.2f (required %.2f), slope=%.3f (max %.3f), classification: %s",
                solidity, MIN_FOUNDATION_SOLIDITY, maxSlope, MAX_FOUNDATION_SLOPE, classificationResult));
        
        return solidityOk && slopeOk && terrainOk;
    }
    
    /**
     * Validate interior air space for structures (no obstructions at entrance level).
     */
    private boolean validateInteriorAir(World world, Location origin, int width, int depth, int height) {
        int airCount = 0;
        int totalCount = 0;
        
        // Check first 3 vertical layers for air space (entrance/room level)
        int checkHeight = Math.min(height, 3);
        
        for (int y = 0; y < checkHeight; y++) {
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < depth; z++) {
                    Block block = world.getBlockAt(
                            origin.getBlockX() + x,
                            origin.getBlockY() + y,
                            origin.getBlockZ() + z
                    );
                    
                    totalCount++;
                    
                    if (block.getType() == Material.AIR || block.getType() == Material.CAVE_AIR) {
                        airCount++;
                    }
                }
            }
        }
        
        // Require at least 50% air space in entrance area
        double airRatio = (double) airCount / totalCount;
        boolean passed = airRatio >= 0.5;
        
        LOGGER.fine(String.format("[STRUCT] Interior air check: %.2f%% air (required 50%%)", airRatio * 100));
        
        return passed;
    }
    
    /**
     * Validate entrance accessibility (ensure at least one entrance has clear access).
     */
    private boolean validateEntranceAccess(World world, Location origin, int width, int depth) {
        // Check all four sides for at least one clear entrance
        boolean hasAccess = false;
        
        // Check south side (Z+)
        hasAccess |= checkSideAccess(world, origin.getBlockX(), origin.getBlockZ() + depth, width, true);
        
        // Check north side (Z-)
        hasAccess |= checkSideAccess(world, origin.getBlockX(), origin.getBlockZ() - 1, width, true);
        
        // Check east side (X+)
        hasAccess |= checkSideAccess(world, origin.getBlockX() + width, origin.getBlockZ(), depth, false);
        
        // Check west side (X-)
        hasAccess |= checkSideAccess(world, origin.getBlockX() - 1, origin.getBlockZ(), depth, false);
        
        LOGGER.fine(String.format("[STRUCT] Entrance access check: %b", hasAccess));
        
        return hasAccess;
    }
    
    /**
     * Check if a side has clear access (air blocks at entrance height).
     */
    private boolean checkSideAccess(World world, int x, int z, int length, boolean alongX) {
        for (int i = 0; i < length; i++) {
            int checkX = alongX ? x + i : x;
            int checkZ = alongX ? z : z + i;
            
            // Check for MIN_ENTRANCE_AIR_HEIGHT air blocks
            int airCount = 0;
            for (int y = 0; y < MIN_ENTRANCE_AIR_HEIGHT; y++) {
                Block block = world.getBlockAt(checkX, world.getHighestBlockYAt(checkX, checkZ) + y, checkZ);
                if (block.getType() == Material.AIR || block.getType() == Material.CAVE_AIR) {
                    airCount++;
                }
            }
            
            if (airCount >= MIN_ENTRANCE_AIR_HEIGHT) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Result of site validation.
     */
    public static class ValidationResult {
        public boolean passed = false;
        public boolean foundationOk = false;
        public boolean interiorAirOk = false;
        public boolean entranceOk = false;
        public ClassificationResult classificationResult = null;
        
        @Override
        public String toString() {
            String classStr = classificationResult != null ? ", classification: " + classificationResult : "";
            return String.format("ValidationResult{passed=%b, foundation=%b, interior=%b, entrance=%b%s}",
                    passed, foundationOk, interiorAirOk, entranceOk, classStr);
        }
    }
}
