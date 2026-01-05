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
        // Helper to add by name when the constant may not exist in the runtime.
        java.util.function.Consumer<String> addIfPresent = name -> {
            Material m = Material.matchMaterial(name);
            if (m != null) TRIMMABLE_VEGETATION.add(m);
        };

        // Grass and ferns
        addIfPresent.accept("SHORT_GRASS");
        addIfPresent.accept("TALL_GRASS");
        addIfPresent.accept("FERN");
        addIfPresent.accept("LARGE_FERN");
        addIfPresent.accept("DEAD_BUSH");
        
        // Flowers
        addIfPresent.accept("DANDELION");
        addIfPresent.accept("POPPY");
        addIfPresent.accept("AZURE_BLUET");
        addIfPresent.accept("ALLIUM");
        addIfPresent.accept("OXEYE_DAISY");
        addIfPresent.accept("CORNFLOWER");
        addIfPresent.accept("LILY_OF_THE_VALLEY");
        addIfPresent.accept("SUNFLOWER");
        addIfPresent.accept("LILAC");
        addIfPresent.accept("ROSE_BUSH");
        addIfPresent.accept("PEONY");
        
        // Other vegetation
        addIfPresent.accept("SUGAR_CANE");
        addIfPresent.accept("VINE");
        addIfPresent.accept("WEEPING_VINES");
        addIfPresent.accept("TWISTING_VINES");
        addIfPresent.accept("KELP");
        addIfPresent.accept("SEAGRASS");
        addIfPresent.accept("TALL_SEAGRASS");
        
        // Tree logs (all wood types)
        addIfPresent.accept("OAK_LOG");
        addIfPresent.accept("SPRUCE_LOG");
        addIfPresent.accept("BIRCH_LOG");
        addIfPresent.accept("JUNGLE_LOG");
        addIfPresent.accept("ACACIA_LOG");
        addIfPresent.accept("DARK_OAK_LOG");
        addIfPresent.accept("MANGROVE_LOG");
        addIfPresent.accept("CHERRY_LOG");
        
        // Tree leaves (all types)
        addIfPresent.accept("OAK_LEAVES");
        addIfPresent.accept("SPRUCE_LEAVES");
        addIfPresent.accept("BIRCH_LEAVES");
        addIfPresent.accept("JUNGLE_LEAVES");
        addIfPresent.accept("ACACIA_LEAVES");
        addIfPresent.accept("DARK_OAK_LEAVES");
        addIfPresent.accept("MANGROVE_LEAVES");
        addIfPresent.accept("CHERRY_LEAVES");
        addIfPresent.accept("AZALEA_LEAVES");
        addIfPresent.accept("FLOWERING_AZALEA_LEAVES");
        
        // Saplings
        addIfPresent.accept("OAK_SAPLING");
        addIfPresent.accept("SPRUCE_SAPLING");
        addIfPresent.accept("BIRCH_SAPLING");
        addIfPresent.accept("JUNGLE_SAPLING");
        addIfPresent.accept("ACACIA_SAPLING");
        addIfPresent.accept("DARK_OAK_SAPLING");
        addIfPresent.accept("MANGROVE_PROPAGULE");
        addIfPresent.accept("CHERRY_SAPLING");
        
        // Mushrooms
        addIfPresent.accept("BROWN_MUSHROOM");
        addIfPresent.accept("RED_MUSHROOM");
        addIfPresent.accept("BROWN_MUSHROOM_BLOCK");
        addIfPresent.accept("RED_MUSHROOM_BLOCK");
        addIfPresent.accept("MUSHROOM_STEM");
    }
    
    /**
     * Trim vegetation in the footprint area.
     * Removes tall grass, flowers, and small plants at ground level only.
     * Does NOT trim trees/tall vegetation above ground - structure will overlay them.
     * 
     * @param world Target world
     * @param origin Southwest corner of footprint
     * @param width Footprint width (X)
     * @param depth Footprint depth (Z)
     * @param height Maximum height to check (UNUSED - kept for API compatibility)
     * @return Number of blocks trimmed
     */
    public static int trimVegetation(World world, Location origin, int width, int depth, int height) {
        int trimmedCount = 0;
        
        // Only trim 0-2 blocks above ground level (grass, flowers, small plants)
        // This prevents clearing entire tree columns which creates visible patches
        int maxTrimHeight = 3; // Relative to origin Y
        
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                for (int y = 0; y < maxTrimHeight; y++) {
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
            LOGGER.fine(String.format("[STRUCT] Trimmed %d vegetation blocks at %s (ground level only)", trimmedCount, origin));
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
     * CRITICAL: Ensures foundation layer (foundationY) is completely solid.
     * 
     * @param world Target world
     * @param origin Southwest corner of footprint
     * @param width Footprint width (X)
     * @param depth Footprint depth (Z)
     * @param foundationY Target Y level for foundation (MUST be solid after this call)
     * @return Number of blocks filled
     */
    public static int fillGaps(World world, Location origin, int width, int depth, int foundationY) {
        return fillGapsWithLimit(world, origin, width, depth, foundationY, MAX_TERRAFORM_BLOCKS);
    }
    
    /**
     * Fill gaps with custom limit.
     * CRITICAL FIX: Fill UP TO AND INCLUDING foundationY to ensure solid foundation corners.
     * CRITICAL FIX 2: Replace unsuitable foundation materials (SNOW, GRASS, etc.) with DIRT.
     * T065: Preserve surface materials (grass stays grass) to prevent dirt scars.
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
                
                // T065: Capture surface material for preservation
                Material surfaceMat = world.getBlockAt(
                        origin.getBlockX() + x,
                        surfaceY,
                        origin.getBlockZ() + z
                ).getType();
                
                // CRITICAL: ALWAYS solidify the foundation layer, even if terrain is higher
                // Check foundation block regardless of surface height
                Block foundationBlock = world.getBlockAt(
                        origin.getBlockX() + x,
                        foundationY,
                        origin.getBlockZ() + z
                );
                
                // Replace unsuitable foundation materials with appropriate surface-preserving material
                if (!isGoodFoundationMaterial(foundationBlock.getType())) {
                    // T065: Use surface-appropriate material instead of always DIRT
                    Material fillMaterial = determineFillMaterial(surfaceMat, foundationY, foundationY);
                    foundationBlock.setType(fillMaterial);
                    filledCount++;
                    
                    if (filledCount > maxBlocks) {
                        LOGGER.warning(String.format("[STRUCT] Gap filling exceeded limit at %s: %d blocks (max %d)",
                                origin, filledCount, maxBlocks));
                        return -1;
                    }
                }
                
                // Additionally, fill gaps BELOW foundation if terrain is lower
                if (surfaceY >= minFillY && surfaceY < foundationY) {
                    // Calculate the highest fill Y (the new exposed surface)
                    int topFillY = foundationY - 1;
                    
                    // Fill from surface UP TO (but not including) foundation level (already handled above)
                    for (int y = surfaceY + 1; y < foundationY; y++) {
                        Block block = world.getBlockAt(
                                origin.getBlockX() + x,
                                y,
                                origin.getBlockZ() + z
                        );
                        
                        // Fill if block is not solid (including AIR, SHORT_GRASS, etc.)
                        if (!block.getType().isSolid() && block.getType() != Material.WATER) {
                            // T065: Use surface-preserving material for top layer, DIRT for underground
                            Material fillMaterial = determineFillMaterial(surfaceMat, y, topFillY);
                            block.setType(fillMaterial);
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
            LOGGER.info(String.format("[STRUCT] Filled %d gap blocks at %s (foundation layer solidified)", filledCount, origin));
                    }
        
        return filledCount;
    }
    
    /**
     * Check if a material is suitable for structure foundations.
     * Unsuitable materials will be replaced with DIRT during terraforming.
     */
    private static boolean isGoodFoundationMaterial(Material material) {
        // Good foundation materials: solid earth/stone blocks
        return material == Material.DIRT ||
               material == Material.GRASS_BLOCK ||
               material == Material.STONE ||
               material == Material.DEEPSLATE ||
               material == Material.ANDESITE ||
               material == Material.DIORITE ||
               material == Material.GRANITE ||
               material == Material.SANDSTONE ||
               material == Material.RED_SANDSTONE ||
               material == Material.TERRACOTTA ||
               material == Material.CLAY ||
               material == Material.COARSE_DIRT ||
               material == Material.PODZOL ||
               material == Material.GRAVEL ||
               material == Material.SAND ||
               material == Material.RED_SAND;
        
        // Unsuitable: SNOW, SNOW_BLOCK, ICE, SHORT_GRASS, FLOWERS, LOGS, LEAVES, AIR, etc.
    }
    
    /**
     * T065: Determine the appropriate fill material to prevent "dirt scars".
     * Preserves grass-like surfaces when the fill block will be the top exposed block.
     * 
     * @param surfaceMaterial The material at the original surface level
     * @param fillY The Y level being filled
     * @param topY The highest Y level that will be filled (the new surface)
     * @return The appropriate material to use for filling
     */
    private static Material determineFillMaterial(Material surfaceMaterial, int fillY, int topY) {
        // If this is NOT the top layer, always use DIRT (underground)
        if (fillY < topY) {
            return Material.DIRT;
        }
        
        // For the top layer, preserve grass-family surfaces to prevent dirt scars
        // GRASS_BLOCK -> GRASS_BLOCK (preserves the grassy appearance)
        if (surfaceMaterial == Material.GRASS_BLOCK) {
            return Material.GRASS_BLOCK;
        }
        
        // PODZOL -> PODZOL (preserves taiga/mega spruce biome appearance)
        if (surfaceMaterial == Material.PODZOL) {
            return Material.PODZOL;
        }
        
        // MYCELIUM -> MYCELIUM (preserves mushroom biome appearance)
        if (surfaceMaterial == Material.MYCELIUM) {
            return Material.MYCELIUM;
        }
        
        // COARSE_DIRT stays COARSE_DIRT (preserves badlands/mesa appearance)
        if (surfaceMaterial == Material.COARSE_DIRT) {
            return Material.COARSE_DIRT;
        }
        
        // Sand/red sand preservation for deserts and beaches
        if (surfaceMaterial == Material.SAND) {
            return Material.SAND;
        }
        if (surfaceMaterial == Material.RED_SAND) {
            return Material.RED_SAND;
        }
        
        // Gravel preservation
        if (surfaceMaterial == Material.GRAVEL) {
            return Material.GRAVEL;
        }
        
        // Default to DIRT for all other cases
        return Material.DIRT;
    }
    
    /**
     * Prepare site using exact AABB bounds from PlacementReceipt.
     * This ensures terraforming operates on the same footprint that will be verified.
     * 
     * @param world Target world
     * @param bounds Exact AABB bounds: {minX, maxX, minY, maxY, minZ, maxZ}
     * @return true if site preparation succeeded within limits
     */
    public static boolean prepareSiteWithBounds(World world, int[] bounds) {
        if (bounds.length != 6) {
            throw new IllegalArgumentException("bounds must have 6 elements: minX, maxX, minY, maxY, minZ, maxZ");
        }
        
        int minX = bounds[0];
        int maxX = bounds[1];
        int minY = bounds[2];
        int maxY = bounds[3];
        int minZ = bounds[4];
        int maxZ = bounds[5];
        
        int width = maxX - minX + 1;
        int depth = maxZ - minZ + 1;
        int height = maxY - minY + 1;
        
        Location origin = new Location(world, minX, minY, minZ);
        
        LOGGER.fine(String.format("[STRUCT] prepareSiteWithBounds: bounds=(%d..%d, %d..%d, %d..%d) dims=%dx%dx%d",
                minX, maxX, minY, maxY, minZ, maxZ, width, height, depth));
        
        // Delegate to existing prepareSite implementation
        return prepareSiteImpl(world, origin, width, depth, height);
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
        return prepareSiteImpl(world, origin, width, depth, height);
    }
    
    /**
     * Internal implementation of site preparation.
     */
    private static boolean prepareSiteImpl(World world, Location origin, int width, int depth, int height) {
        int footprintArea = width * depth;
        boolean isLargeStructure = footprintArea > LARGE_STRUCTURE_THRESHOLD;
        int maxBlocks = isLargeStructure ? MAX_TERRAFORM_BLOCKS_LARGE : MAX_TERRAFORM_BLOCKS;
        
        LOGGER.fine(String.format("[STRUCT] Preparing site at %s (%dx%dx%d), footprint=%d, large=%s, maxBlocks=%d", 
                origin, width, depth, height, footprintArea, isLargeStructure, maxBlocks));
        
        // Step 0: HARD VETO on ANY water in footprint or surrounding area
        // Check footprint + 2-block margin to prevent water flow into building
        int waterBlocks = 0;
        int checkMargin = 2; // Check 2 blocks beyond footprint edges
        
        for (int x = -checkMargin; x < width + checkMargin; x++) {
            for (int z = -checkMargin; z < depth + checkMargin; z++) {
                Location checkLoc = origin.clone().add(x, 0, z);
                int y = world.getHighestBlockYAt(checkLoc);
                Material surfaceMat = world.getBlockAt(checkLoc.getBlockX(), y, checkLoc.getBlockZ()).getType();
                
                if (surfaceMat == Material.WATER || surfaceMat == Material.LAVA) {
                    waterBlocks++;
                    // Immediate rejection - ANY water/lava = site unsuitable
                    LOGGER.info(String.format("[STRUCT] Site rejected at %s: fluid detected (%s at %d, %d, %d)",
                            origin, surfaceMat, checkLoc.getBlockX(), y, checkLoc.getBlockZ()));
                    return false;
                }
            }
        }
        
        LOGGER.fine(String.format("[STRUCT] Site has no water/lava in footprint or margin (checked %d blocks)", 
                (width + 2 * checkMargin) * (depth + 2 * checkMargin)));
        
        // Step 1: Always trim vegetation (trees inside buildings are bad)
        int trimmed = trimVegetation(world, origin, width, depth, height);
        
        int targetY = origin.getBlockY();
        
        // For very large structures, skip grading but ALWAYS fill foundation gaps
        if (isLargeStructure) {
            LOGGER.info(String.format("[STRUCT] Large structure detected (%dx%d), skipping grading but filling foundation gaps",
                    width, depth));
            
            // Fill gaps beneath AND AT foundation level to prevent floating structures
            // CRITICAL: Use targetY (foundation level), not targetY - 1
            int filled = fillGapsWithLimit(world, origin, width, depth, targetY, maxBlocks);
            
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
        
        // Step 3: Fill any remaining gaps - CRITICAL: use targetY (foundation level), not targetY - 1
        int filled = fillGapsWithLimit(world, origin, width, depth, targetY, maxBlocks - graded);
        
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
