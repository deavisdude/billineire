package com.davisodom.villageoverhaul.worldgen;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.EnumSet;
import java.util.Set;

/**
 * Terrain classification API for village placement.
 * 
 * Classifies terrain as ACCEPTABLE, FLUID, STEEP, or BLOCKED to enforce
 * Constitution v1.4.0, Principle XII constraints:
 * - No building placement on water/lava
 * - No building on steep slopes
 * - No building on unsupported foundations (air/leaves/logs)
 * 
 * @see com.davisodom.villageoverhaul.worldgen.SiteValidator
 */
public class TerrainClassifier {
    
    /**
     * Terrain classification categories for structure placement.
     */
    public enum Classification {
        /** Suitable for structure placement */
        ACCEPTABLE,
        
        /** Water, lava, or other fluids (NON-NEGOTIABLE rejection) */
        FLUID,
        
        /** Steep slope exceeding threshold (>2 blocks height delta in 3x3) */
        STEEP,
        
        /** Air, leaves, logs, or other unsupported foundations */
        BLOCKED
    }
    
    /**
     * Fluid materials that are never acceptable for building foundations.
     */
    private static final Set<Material> FLUIDS = EnumSet.of(
            Material.WATER,
            Material.LAVA,
            Material.BUBBLE_COLUMN
    );
    
    /**
     * Materials that cannot support structure foundations (unsupported).
     */
    private static final Set<Material> UNSUPPORTED = EnumSet.of(
            Material.AIR,
            Material.CAVE_AIR,
            Material.VOID_AIR,
            Material.OAK_LEAVES,
            Material.SPRUCE_LEAVES,
            Material.BIRCH_LEAVES,
            Material.JUNGLE_LEAVES,
            Material.ACACIA_LEAVES,
            Material.DARK_OAK_LEAVES,
            Material.MANGROVE_LEAVES,
            Material.CHERRY_LEAVES,
            Material.AZALEA_LEAVES,
            Material.FLOWERING_AZALEA_LEAVES,
            Material.OAK_LOG,
            Material.SPRUCE_LOG,
            Material.BIRCH_LOG,
            Material.JUNGLE_LOG,
            Material.ACACIA_LOG,
            Material.DARK_OAK_LOG,
            Material.MANGROVE_LOG,
            Material.CHERRY_LOG,
            Material.CRIMSON_STEM,
            Material.WARPED_STEM
    );
    
    /**
     * Maximum acceptable height delta within 3x3 area (blocks).
     * 
     * Relaxed from 2 to 4 blocks to allow natural terrain variation.
     * This permits gentle hills while still rejecting cliffs/mountains.
     */
    private static final int MAX_SLOPE_DELTA = 4;
    
    /**
     * Check if a block is acceptable for structure placement.
     * 
     * @param block Block to check
     * @return true if acceptable, false otherwise
     */
    public static boolean isAcceptable(Block block) {
        return classify(block) == Classification.ACCEPTABLE;
    }
    
    /**
     * Classify a single block for structure placement suitability.
     * 
     * @param block Block to classify
     * @return Classification category
     */
    public static Classification classify(Block block) {
        Material material = block.getType();
        
        // Check for fluids (highest priority rejection)
        if (FLUIDS.contains(material)) {
            return Classification.FLUID;
        }
        
        // Check for unsupported foundations
        if (UNSUPPORTED.contains(material)) {
            return Classification.BLOCKED;
        }
        
        // Check for steep slopes (requires world context)
        // Note: Single block classification cannot determine slope
        // Use classify(world, x, y, z) for slope detection
        
        return Classification.ACCEPTABLE;
    }
    
    /**
     * Classify terrain at coordinates with slope detection.
     * 
     * Checks 3x3 area around the position for height variance.
     * If variance > MAX_SLOPE_DELTA, classifies as STEEP.
     * 
     * @param world World to check
     * @param x X coordinate
     * @param y Y coordinate (foundation level)
     * @param z Z coordinate
     * @return Classification category
     */
    public static Classification classify(World world, int x, int y, int z) {
        Block block = world.getBlockAt(x, y, z);
        
        // First check block material classification
        Classification materialClassification = classify(block);
        if (materialClassification != Classification.ACCEPTABLE) {
            return materialClassification;
        }
        
        // Check slope in 3x3 area around the block
        int minY = y;
        int maxY = y;
        
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int checkY = world.getHighestBlockYAt(x + dx, z + dz);
                minY = Math.min(minY, checkY);
                maxY = Math.max(maxY, checkY);
            }
        }
        
        int heightDelta = maxY - minY;
        if (heightDelta > MAX_SLOPE_DELTA) {
            return Classification.STEEP;
        }
        
        return Classification.ACCEPTABLE;
    }
    
    /**
     * Classification result with detailed counts for logging.
     */
    public static class ClassificationResult {
        public int acceptable = 0;
        public int fluid = 0;
        public int steep = 0;
        public int blocked = 0;
        
        /**
         * Increment counter for given classification.
         */
        public void increment(Classification classification) {
            switch (classification) {
                case ACCEPTABLE:
                    acceptable++;
                    break;
                case FLUID:
                    fluid++;
                    break;
                case STEEP:
                    steep++;
                    break;
                case BLOCKED:
                    blocked++;
                    break;
            }
        }
        
        /**
         * Get total rejected tiles.
         */
        public int getRejected() {
            return fluid + steep + blocked;
        }
        
        /**
         * Get total sampled tiles.
         */
        public int getTotal() {
            return acceptable + fluid + steep + blocked;
        }
        
        /**
         * Check if site is acceptable with tolerance.
         * 
         * Hard veto: ANY fluid tiles = reject (water/lava are unacceptable)
         * Soft tolerance: Up to 20% non-acceptable tiles allowed (steep/blocked)
         * 
         * This allows buildings on slightly uneven terrain or near sparse vegetation
         * while still preventing placement in water or on cliffs.
         * 
         * @return true if site passes tolerance checks
         */
        public boolean isAcceptableWithTolerance() {
            // Hard veto on any fluid
            if (fluid > 0) {
                return false;
            }
            
            int total = getTotal();
            if (total == 0) {
                return true; // No samples (shouldn't happen, but handle gracefully)
            }
            
            // Allow up to 20% steep/blocked tiles
            int nonFluidRejected = steep + blocked;
            double rejectionRate = (double) nonFluidRejected / total;
            return rejectionRate <= 0.20;
        }
        
        /**
         * Format for logging.
         */
        @Override
        public String toString() {
            return String.format("fluid=%d, steep=%d, blocked=%d", fluid, steep, blocked);
        }
    }
}
