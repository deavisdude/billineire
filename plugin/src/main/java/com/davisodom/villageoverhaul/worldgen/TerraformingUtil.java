package com.davisodom.villageoverhaul.worldgen;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Utilities for minor, localized terraforming to achieve natural structure placement.
 * Supports light grading, filling, and vegetation trimming only.
 * Forbids large artificial platforms or cliff cuts.
 */
public class TerraformingUtil {
    
    private static final Logger LOGGER = Logger.getLogger(TerraformingUtil.class.getName());
    
    // Maximum blocks to grade/fill in a single operation (for small structures)
    private static final int MAX_TERRAFORM_BLOCKS = 300;
    
    // For large structures (> 30x30 footprint), use a higher limit or skip terraforming
    private static final int LARGE_STRUCTURE_THRESHOLD = 900; // 30x30
    private static final int MAX_TERRAFORM_BLOCKS_LARGE = 3000;
    
    // Maximum vertical change for grading
    private static final int MAX_VERTICAL_CHANGE = 3;
    
    // Vegetation materials that can be safely trimmed
    private static final Set<Material> TRIMMABLE_VEGETATION = new HashSet<>();
    
    static {
        // Grass and ferns
        TRIMMABLE_VEGETATION.add(Material.SHORT_GRASS);
        TRIMMABLE_VEGETATION.add(Material.TALL_GRASS);
        TRIMMABLE_VEGETATION.add(Material.FERN);
        TRIMMABLE_VEGETATION.add(Material.LARGE_FERN);
        TRIMMABLE_VEGETATION.add(Material.DEAD_BUSH);
        
        // Flowers
        TRIMMABLE_VEGETATION.add(Material.DANDELION);
        TRIMMABLE_VEGETATION.add(Material.POPPY);
        TRIMMABLE_VEGETATION.add(Material.AZURE_BLUET);
        TRIMMABLE_VEGETATION.add(Material.ALLIUM);
        TRIMMABLE_VEGETATION.add(Material.OXEYE_DAISY);
        TRIMMABLE_VEGETATION.add(Material.CORNFLOWER);
        TRIMMABLE_VEGETATION.add(Material.LILY_OF_THE_VALLEY);
        TRIMMABLE_VEGETATION.add(Material.SUNFLOWER);
        TRIMMABLE_VEGETATION.add(Material.LILAC);
        TRIMMABLE_VEGETATION.add(Material.ROSE_BUSH);
        TRIMMABLE_VEGETATION.add(Material.PEONY);
        
        // Other vegetation
        TRIMMABLE_VEGETATION.add(Material.SUGAR_CANE);
        TRIMMABLE_VEGETATION.add(Material.VINE);
        TRIMMABLE_VEGETATION.add(Material.WEEPING_VINES);
        TRIMMABLE_VEGETATION.add(Material.TWISTING_VINES);
        TRIMMABLE_VEGETATION.add(Material.KELP);
        TRIMMABLE_VEGETATION.add(Material.SEAGRASS);
        TRIMMABLE_VEGETATION.add(Material.TALL_SEAGRASS);
        
        // Tree logs (all wood types)
        TRIMMABLE_VEGETATION.add(Material.OAK_LOG);
        TRIMMABLE_VEGETATION.add(Material.SPRUCE_LOG);
        TRIMMABLE_VEGETATION.add(Material.BIRCH_LOG);
        TRIMMABLE_VEGETATION.add(Material.JUNGLE_LOG);
        TRIMMABLE_VEGETATION.add(Material.ACACIA_LOG);
        TRIMMABLE_VEGETATION.add(Material.DARK_OAK_LOG);
        TRIMMABLE_VEGETATION.add(Material.MANGROVE_LOG);
        TRIMMABLE_VEGETATION.add(Material.CHERRY_LOG);
        
        // Tree leaves (all types)
        TRIMMABLE_VEGETATION.add(Material.OAK_LEAVES);
        TRIMMABLE_VEGETATION.add(Material.SPRUCE_LEAVES);
        TRIMMABLE_VEGETATION.add(Material.BIRCH_LEAVES);
        TRIMMABLE_VEGETATION.add(Material.JUNGLE_LEAVES);
        TRIMMABLE_VEGETATION.add(Material.ACACIA_LEAVES);
        TRIMMABLE_VEGETATION.add(Material.DARK_OAK_LEAVES);
        TRIMMABLE_VEGETATION.add(Material.MANGROVE_LEAVES);
        TRIMMABLE_VEGETATION.add(Material.CHERRY_LEAVES);
        TRIMMABLE_VEGETATION.add(Material.AZALEA_LEAVES);
        TRIMMABLE_VEGETATION.add(Material.FLOWERING_AZALEA_LEAVES);
        
        // Saplings
        TRIMMABLE_VEGETATION.add(Material.OAK_SAPLING);
        TRIMMABLE_VEGETATION.add(Material.SPRUCE_SAPLING);
        TRIMMABLE_VEGETATION.add(Material.BIRCH_SAPLING);
        TRIMMABLE_VEGETATION.add(Material.JUNGLE_SAPLING);
        TRIMMABLE_VEGETATION.add(Material.ACACIA_SAPLING);
        TRIMMABLE_VEGETATION.add(Material.DARK_OAK_SAPLING);
        TRIMMABLE_VEGETATION.add(Material.MANGROVE_PROPAGULE);
        TRIMMABLE_VEGETATION.add(Material.CHERRY_SAPLING);
        
        // Mushrooms
        TRIMMABLE_VEGETATION.add(Material.BROWN_MUSHROOM);
        TRIMMABLE_VEGETATION.add(Material.RED_MUSHROOM);
        TRIMMABLE_VEGETATION.add(Material.BROWN_MUSHROOM_BLOCK);
        TRIMMABLE_VEGETATION.add(Material.RED_MUSHROOM_BLOCK);
        TRIMMABLE_VEGETATION.add(Material.MUSHROOM_STEM);
    }
    
    /**
     * Trim vegetation in the footprint area.
     * Removes tall grass, flowers, and other decorative plants.
     * 
     * @param world Target world
     * @param origin Southwest corner of footprint
     * @param width Footprint width (X)
     * @param depth Footprint depth (Z)
     * @param height Maximum height to check
     * @return Number of blocks trimmed
     */
    public static int trimVegetation(World world, Location origin, int width, int depth, int height) {
        int trimmedCount = 0;
        
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                for (int y = 0; y < height; y++) {
                    Block block = world.getBlockAt(
                            origin.getBlockX() + x,
                            origin.getBlockY() + y,
                            origin.getBlockZ() + z
                    );
                    
                    if (TRIMMABLE_VEGETATION.contains(block.getType())) {
                        block.setType(Material.AIR);
                        trimmedCount++;
                    }
                }
            }
        }
        
        if (trimmedCount > 0) {
            LOGGER.fine(String.format("[STRUCT] Trimmed %d vegetation blocks at %s", trimmedCount, origin));
        }
        
        return trimmedCount;
    }
    
    /**
     * Perform light grading to smooth foundation area.
     * Fills small gaps and levels minor bumps within constraints.
     * 
     * @param world Target world
     * @param origin Southwest corner of footprint
     * @param width Footprint width (X)
     * @param depth Footprint depth (Z)
     * @param targetY Target Y level for foundation
     * @return Number of blocks modified
     */
    public static int lightGrading(World world, Location origin, int width, int depth, int targetY) {
        return lightGradingWithLimit(world, origin, width, depth, targetY, MAX_TERRAFORM_BLOCKS);
    }
    
    /**
     * Perform light grading with custom limit.
     */
    private static int lightGradingWithLimit(World world, Location origin, int width, int depth, int targetY, int maxBlocks) {
        int modifiedCount = 0;
        
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                int surfaceY = world.getHighestBlockYAt(
                        origin.getBlockX() + x,
                        origin.getBlockZ() + z
                );
                
                int yDiff = surfaceY - targetY;
                
                // Only fill gaps UPWARD - never dig down
                // If surface is higher than target, skip (let structure sit on natural terrain)
                if (yDiff < 0 && Math.abs(yDiff) <= MAX_VERTICAL_CHANGE) {
                    // Surface is below target - fill gap
                    for (int y = surfaceY + 1; y <= targetY; y++) {
                        Block fillBlock = world.getBlockAt(
                                origin.getBlockX() + x,
                                y,
                                origin.getBlockZ() + z
                        );
                        if (!fillBlock.getType().isSolid()) {
                            fillBlock.setType(Material.DIRT);
                            modifiedCount++;
                        }
                    }
                }
                // Note: We intentionally DO NOT remove blocks when surfaceY > targetY
                // This prevents structures from being dug into hillsides
            }
        }
        
        if (modifiedCount > maxBlocks) {
            LOGGER.warning(String.format("[STRUCT] Terraforming exceeded limit at %s: %d blocks (max %d)",
                    origin, modifiedCount, maxBlocks));
            return -1; // Indicate failure
        }
        
        if (modifiedCount > 0) {
            LOGGER.fine(String.format("[STRUCT] Graded %d blocks at %s (target Y=%d)", modifiedCount, origin, targetY));
        }
        
        return modifiedCount;
    }
    
    /**
     * Check if a block can be safely removed during grading.
     * Excludes valuable/structural blocks.
     */
    private static boolean canSafelyRemove(Material material) {
        // Allow removal of common terrain blocks only
        return material == Material.DIRT ||
               material == Material.GRASS_BLOCK ||
               material == Material.COARSE_DIRT ||
               material == Material.PODZOL ||
               material == Material.GRAVEL ||
               material == Material.SAND ||
               material == Material.RED_SAND ||
               TRIMMABLE_VEGETATION.contains(material);
    }
    
    /**
     * Fill small gaps in foundation area.
     * ONLY fills small air pockets near the surface (within 3 blocks below foundation level).
     * Does NOT build pillars from bedrock!
     * 
     * @param world Target world
     * @param origin Southwest corner of footprint
     * @param width Footprint width (X)
     * @param depth Footprint depth (Z)
     * @param foundationY Target Y level for foundation
     * @return Number of blocks filled
     */
    public static int fillGaps(World world, Location origin, int width, int depth, int foundationY) {
        return fillGapsWithLimit(world, origin, width, depth, foundationY, MAX_TERRAFORM_BLOCKS);
    }
    
    /**
     * Fill gaps with custom limit.
     */
    private static int fillGapsWithLimit(World world, Location origin, int width, int depth, int foundationY, int maxBlocks) {
        int filledCount = 0;
        
        // Only fill small gaps NEAR the surface (max 3 blocks down from foundation)
        int minFillY = foundationY - MAX_VERTICAL_CHANGE;
        
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                // Find actual surface at this column
                int surfaceY = world.getHighestBlockYAt(
                        origin.getBlockX() + x,
                        origin.getBlockZ() + z
                );
                
                // Only fill if surface is close to foundation level
                if (surfaceY >= minFillY && surfaceY < foundationY) {
                    // Fill from surface to foundation
                    for (int y = surfaceY; y < foundationY; y++) {
                        Block block = world.getBlockAt(
                                origin.getBlockX() + x,
                                y,
                                origin.getBlockZ() + z
                        );
                        
                        if (!block.getType().isSolid() && block.getType() != Material.WATER) {
                            block.setType(Material.DIRT);
                            filledCount++;
                            
                            // Safety check
                            if (filledCount > maxBlocks) {
                                LOGGER.warning(String.format("[STRUCT] Gap filling exceeded limit at %s: %d blocks (max %d)",
                                        origin, filledCount, maxBlocks));
                                return -1;
                            }
                        }
                    }
                }
            }
        }
        
        if (filledCount > 0) {
            LOGGER.fine(String.format("[STRUCT] Filled %d gap blocks at %s", filledCount, origin));
        }
        
        return filledCount;
    }
    
    /**
     * Attempt to prepare a site with minimal terraforming.
     * Combines vegetation trimming and light grading.
     * For very large structures (>30x30), skip grading but ALWAYS fill foundation gaps.
     * 
     * @param world Target world
     * @param origin Southwest corner of footprint
     * @param width Footprint width (X)
     * @param depth Footprint depth (Z)
     * @param height Structure height (Y)
     * @return true if site preparation succeeded within limits
     */
    public static boolean prepareSite(World world, Location origin, int width, int depth, int height) {
        int footprintArea = width * depth;
        boolean isLargeStructure = footprintArea > LARGE_STRUCTURE_THRESHOLD;
        int maxBlocks = isLargeStructure ? MAX_TERRAFORM_BLOCKS_LARGE : MAX_TERRAFORM_BLOCKS;
        
        LOGGER.fine(String.format("[STRUCT] Preparing site at %s (%dx%dx%d), footprint=%d, large=%s, maxBlocks=%d", 
                origin, width, depth, height, footprintArea, isLargeStructure, maxBlocks));
        
        // Step 0: Check for water in footprint - reject if > 20% of footprint is water
        int waterBlocks = 0;
        int maxWaterBlocks = (width * depth) / 5; // 20% threshold
        
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                Location checkLoc = origin.clone().add(x, 0, z);
                int y = world.getHighestBlockYAt(checkLoc);
                Material surfaceMat = world.getBlockAt(checkLoc.getBlockX(), y, checkLoc.getBlockZ()).getType();
                
                if (surfaceMat == Material.WATER) {
                    waterBlocks++;
                    if (waterBlocks > maxWaterBlocks) {
                        LOGGER.info(String.format("[STRUCT] Site rejected at %s: too much water (%d/%d blocks, max %d)",
                                origin, waterBlocks, footprintArea, maxWaterBlocks));
                        return false;
                    }
                }
            }
        }
        
        if (waterBlocks > 0) {
            LOGGER.fine(String.format("[STRUCT] Site has %d water blocks (%.1f%%), within acceptable threshold",
                    waterBlocks, (waterBlocks * 100.0) / footprintArea));
        }
        
        // Step 1: Always trim vegetation (trees inside buildings are bad)
        int trimmed = trimVegetation(world, origin, width, depth, height);
        
        int targetY = origin.getBlockY();
        
        // For very large structures, skip grading but ALWAYS fill foundation gaps
        if (isLargeStructure) {
            LOGGER.info(String.format("[STRUCT] Large structure detected (%dx%d), skipping grading but filling foundation gaps",
                    width, depth));
            
            // Fill gaps beneath foundation to prevent floating structures
            int filled = fillGapsWithLimit(world, origin, width, depth, targetY - 1, maxBlocks);
            
            if (filled < 0) {
                LOGGER.warning(String.format("[STRUCT] Foundation filling exceeded limits at %s", origin));
                // Continue anyway - better to have some floating than no structure
            }
            
            int totalModified = trimmed + (filled > 0 ? filled : 0);
            LOGGER.info(String.format("[STRUCT] Site prepared (large): trimmed=%d, foundation filled=%d",
                    trimmed, filled > 0 ? filled : 0));
            return true;
        }
        
        // Step 2: Light grading (only for small/medium structures)
        int graded = lightGradingWithLimit(world, origin, width, depth, targetY, maxBlocks);
        
        if (graded < 0) {
            LOGGER.warning(String.format("[STRUCT] Site preparation failed at %s: grading exceeded limits", origin));
            return false;
        }
        
        // Step 3: Fill any remaining gaps
        int filled = fillGapsWithLimit(world, origin, width, depth, targetY - 1, maxBlocks - graded);
        
        if (filled < 0) {
            LOGGER.warning(String.format("[STRUCT] Site preparation failed at %s: filling exceeded limits", origin));
            return false;
        }
        
        int totalModified = trimmed + graded + filled;
        
        LOGGER.info(String.format("[STRUCT] Site prepared at %s: %d blocks modified (trimmed=%d, graded=%d, filled=%d)",
                origin, totalModified, trimmed, graded, filled));
        
        return true;
    }
    
    /**
     * Backfill foundation AFTER structure placement.
     * Fills any AIR blocks below the structure down to solid ground.
     * This fixes floating structures caused by terrain variations.
     * Only fills EXTERIOR perimeter, not interior areas.
     * ONLY fills where terrain is within reasonable distance (max 3 blocks gap).
     * 
     * @param world The world
     * @param origin Structure origin (southwest corner, ground level)
     * @param width Structure width (X direction)
     * @param depth Structure depth (Z direction)
     * @param fillMaterial Material to use for backfilling (typically DIRT)
     * @return Number of blocks filled
     */
    public static int backfillFoundation(World world, Location origin, int width, int depth, Material fillMaterial) {
        int filled = 0;
        int maxGap = 3; // Only fill gaps up to 3 blocks (prevents walls on steep slopes)
        
        LOGGER.fine(String.format("[STRUCT] Backfilling foundation at %s (%dx%d)", origin, width, depth));
        
        // Only fill the PERIMETER of the structure (exterior edges only)
        // This prevents dirt from appearing inside buildings
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                // Skip interior blocks - only process perimeter (2 block wide border)
                boolean isPerimeter = (x < 2 || x >= width - 2 || z < 2 || z >= depth - 2);
                if (!isPerimeter) {
                    continue;
                }
                
                Location surfaceLoc = origin.clone().add(x, 0, z);
                
                // Find natural terrain height at this position
                int terrainY = world.getHighestBlockYAt(surfaceLoc);
                int structureBaseY = surfaceLoc.getBlockY();
                int gapSize = structureBaseY - terrainY;
                
                // Only fill if gap is reasonable (1-3 blocks)
                // Skip if terrain is higher than structure (no gap) or gap is too large (steep slope)
                if (gapSize < 1 || gapSize > maxGap) {
                    continue;
                }
                
                // Fill from terrain UP to structure base
                for (int y = terrainY + 1; y < structureBaseY; y++) {
                    Block block = world.getBlockAt(surfaceLoc.getBlockX(), y, surfaceLoc.getBlockZ());
                    if (block.getType().isAir()) {
                        block.setType(fillMaterial);
                        filled++;
                    }
                }
            }
        }
        
        LOGGER.info(String.format("[STRUCT] Foundation backfilled: %d blocks placed (max gap: %d)", filled, maxGap));
        return filled;
    }
}