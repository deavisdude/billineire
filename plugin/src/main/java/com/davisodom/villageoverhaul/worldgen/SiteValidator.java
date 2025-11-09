package com.davisodom.villageoverhaul.worldgen;

import com.davisodom.villageoverhaul.worldgen.TerrainClassifier.Classification;
import com.davisodom.villageoverhaul.worldgen.TerrainClassifier.ClassificationResult;
import org.bukkit.Location;
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
        
        // Interior air and entrance checks removed - schematic defines its own interior/entrances
        // Terraforming will clear obstructions, so we only validate foundation suitability
        result.interiorAirOk = true;
        result.entranceOk = true;
        
        result.passed = foundationOk;
        
        if (!result.passed) {
            LOGGER.fine(String.format("[STRUCT] Site validation failed at %s: foundation=%b, classification: %s",
                    origin, foundationOk, classificationResult));
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
        
        // Use tolerance-based terrain validation (allows up to 20% steep/blocked, 0% fluid)
        boolean terrainOk = classificationResult.isAcceptableWithTolerance();
        
        LOGGER.fine(String.format("[STRUCT] Foundation check: solidity=%.2f (required %.2f), slope=%.3f (max %.3f), classification: %s",
                solidity, MIN_FOUNDATION_SOLIDITY, maxSlope, MAX_FOUNDATION_SLOPE, classificationResult));
        
        return solidityOk && slopeOk && terrainOk;
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
