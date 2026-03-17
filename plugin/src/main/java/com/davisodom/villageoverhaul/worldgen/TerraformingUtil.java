package com.davisodom.villageoverhaul.worldgen;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
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

    private static final int DEFAULT_VEGETATION_COMPONENT_RADIUS = 24;
    private static final int MIN_CONNECTED_VEGETATION_CLEAR_BLOCKS = 512;
    private static final int MAX_CONNECTED_VEGETATION_CLEAR_BLOCKS = 8192;
    private static final int MAX_POST_PASTE_SUPPORT_GAP = 3;
    private static final int MAX_UNDERSIDE_COMPACTION_DEPTH = 12;
    private static final int REQUIRED_FOUNDATION_SUPPORT_BAND = 3;
    private static final int MIN_MOSTLY_AIR_LAYER_AREA = 16;
    private static final double MOSTLY_AIR_LAYER_RETAINED_RATIO = 0.80D;
    
    // Vegetation materials that can be safely trimmed
    private static final Set<Material> TRIMMABLE_VEGETATION = new HashSet<>();
    private static final Set<Material> PRESERVED_SURFACE_MATERIALS = new HashSet<>();
    
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

        String[] preservedSurfaceNames = new String[]{
            "GRASS_BLOCK", "DIRT", "COARSE_DIRT", "ROOTED_DIRT", "PODZOL", "MYCELIUM",
            "SAND", "RED_SAND", "GRAVEL", "STONE", "ANDESITE", "DIORITE", "GRANITE",
            "CLAY", "TERRACOTTA", "MUD", "MOSS_BLOCK", "SANDSTONE", "RED_SANDSTONE"
        };
        for (String name : preservedSurfaceNames) {
            Material material = Material.matchMaterial(name);
            if (material != null) {
                PRESERVED_SURFACE_MATERIALS.add(material);
            }
        }
    }

    private static final class SurfaceColumn {
        private final int surfaceY;
        private final Material surfaceMaterial;
        private final boolean skippedCanopy;

        private SurfaceColumn(int surfaceY, Material surfaceMaterial, boolean skippedCanopy) {
            this.surfaceY = surfaceY;
            this.surfaceMaterial = surfaceMaterial;
            this.skippedCanopy = skippedCanopy;
        }
    }

    private static final class BackfillSupport {
        private final int supportY;
        private final Material fillMaterial;
        private final boolean encounteredVegetation;
        private final boolean stableSupportFound;

        private BackfillSupport(int supportY, Material fillMaterial,
                                boolean encounteredVegetation, boolean stableSupportFound) {
            this.supportY = supportY;
            this.fillMaterial = fillMaterial;
            this.encounteredVegetation = encounteredVegetation;
            this.stableSupportFound = stableSupportFound;
        }
    }

    private static final class VegetationNode {
        private final int x;
        private final int y;
        private final int z;
        private final int originX;
        private final int originY;
        private final int originZ;

        private VegetationNode(int x, int y, int z, int originX, int originY, int originZ) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.originX = originX;
            this.originY = originY;
            this.originZ = originZ;
        }
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
        List<Block> vegetationSeeds = new ArrayList<>();
        int maxTrimHeight = Math.max(height + 2, 4);

        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                for (int y = 0; y < maxTrimHeight; y++) {
                    Block block = world.getBlockAt(
                            origin.getBlockX() + x,
                            origin.getBlockY() + y,
                            origin.getBlockZ() + z
                    );

                    if (isVegetationMaterial(block.getType())) {
                        vegetationSeeds.add(block);
                    }
                }
            }
        }

        int trimmedCount = clearConnectedVegetation(
                world,
                vegetationSeeds,
                block -> false,
                Math.min(MAX_CONNECTED_VEGETATION_CLEAR_BLOCKS,
                        Math.max(MIN_CONNECTED_VEGETATION_CLEAR_BLOCKS, width * depth * Math.max(height, 4) * 4)),
                DEFAULT_VEGETATION_COMPONENT_RADIUS
        );

        if (trimmedCount > 0) {
            LOGGER.fine(String.format("[STRUCT] Trimmed %d vegetation blocks at %s (connected vegetation removal)", trimmedCount, origin));
        }

        return trimmedCount;
    }

    public static int clearConnectedVegetation(World world, Collection<Block> seedBlocks,
                                               Predicate<Block> skipBlock, int maxBlocks, int maxRadius) {
        if (seedBlocks == null || seedBlocks.isEmpty() || maxBlocks <= 0) {
            return 0;
        }

        Set<String> visited = new HashSet<>();
        int cleared = 0;

        for (Block seed : seedBlocks) {
            if (seed == null || cleared >= maxBlocks) {
                break;
            }
            if (!isVegetationMaterial(seed.getType()) || skipBlock.test(seed)) {
                continue;
            }

            List<Block> component = collectVegetationComponent(world, seed, skipBlock, visited, maxRadius);
            for (Block block : component) {
                block.setType(Material.AIR);
            }
            cleared += component.size();
        }

        return cleared;
    }

    public static boolean isVegetationMaterial(Material material) {
        if (material == null) {
            return false;
        }
        if (TRIMMABLE_VEGETATION.contains(material)) {
            return true;
        }

        String name = material.name();
        return (name.endsWith("_LEAVES")
                || name.endsWith("_LOG")
                || name.endsWith("_WOOD")
                || name.endsWith("_STEM")
                || name.endsWith("_HYPHAE")
                || name.endsWith("_SAPLING")
                || name.endsWith("_ROOTS")
                || name.contains("VINE")
                || name.contains("MUSHROOM")
                || name.contains("MOSS_CARPET"))
            && material != Material.GRASS_BLOCK;
    }

    private static List<Block> collectVegetationComponent(World world, Block seed, Predicate<Block> skipBlock,
                                                          Set<String> visited, int maxRadius) {
        Deque<VegetationNode> queue = new ArrayDeque<>();
        queue.add(new VegetationNode(seed.getX(), seed.getY(), seed.getZ(), seed.getX(), seed.getY(), seed.getZ()));

        List<Block> component = new ArrayList<>();
        int minY = getWorldMinHeight(world);
        int maxY = getWorldMaxHeight(world);

        while (!queue.isEmpty()) {
            VegetationNode node = queue.removeFirst();
            if (Math.max(Math.max(Math.abs(node.x - node.originX), Math.abs(node.y - node.originY)),
                    Math.abs(node.z - node.originZ)) > maxRadius) {
                continue;
            }

            String key = blockKey(node.x, node.y, node.z);
            if (!visited.add(key)) {
                continue;
            }
            if (node.y < minY || node.y > maxY) {
                continue;
            }

            Block block = world.getBlockAt(node.x, node.y, node.z);
            if (skipBlock.test(block) || !isVegetationMaterial(block.getType())) {
                continue;
            }

            component.add(block);

            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        queue.addLast(new VegetationNode(
                                node.x + dx,
                                node.y + dy,
                                node.z + dz,
                                node.originX,
                                node.originY,
                                node.originZ
                        ));
                    }
                }
            }
        }

        return component;
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
        int skippedCanopyColumns = 0;
        
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                int blockX = origin.getBlockX() + x;
                int blockZ = origin.getBlockZ() + z;
                SurfaceColumn surface = resolveSurfaceColumn(world, blockX, blockZ, world.getHighestBlockYAt(blockX, blockZ));
                if (surface.skippedCanopy) {
                    skippedCanopyColumns++;
                }
                int surfaceY = surface.surfaceY;
                Material surfaceMaterial = surface.surfaceMaterial;
                
                int yDiff = surfaceY - targetY;
                
                // Only fill gaps UPWARD - never dig down
                // If surface is higher than target, skip (let structure sit on natural terrain)
                if (yDiff < 0 && Math.abs(yDiff) <= MAX_VERTICAL_CHANGE) {
                    // Surface is below target - fill gap
                    for (int y = surfaceY + 1; y <= targetY; y++) {
                        Block fillBlock = world.getBlockAt(
                                blockX,
                                y,
                                blockZ
                        );
                        if (!fillBlock.getType().isSolid()) {
                            fillBlock.setType(determineFillMaterial(surfaceMaterial, y, targetY));
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

        if (skippedCanopyColumns > 0) {
            LOGGER.info(String.format("[STRUCT] skippedCanopyColumns=%d", skippedCanopyColumns));
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
        int skippedCanopyColumns = 0;
        
        // Only fill small gaps NEAR the surface (max 3 blocks down from foundation)
        int minFillY = foundationY - MAX_VERTICAL_CHANGE;
        
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
            int blockX = origin.getBlockX() + x;
            int blockZ = origin.getBlockZ() + z;
            SurfaceColumn surface = resolveSurfaceColumn(world, blockX, blockZ, world.getHighestBlockYAt(blockX, blockZ));
            if (surface.skippedCanopy) {
                skippedCanopyColumns++;
            }
            int surfaceY = surface.surfaceY;
            Material surfaceMat = surface.surfaceMaterial;
                
                // CRITICAL: ALWAYS solidify the foundation layer, even if terrain is higher
                // Check foundation block regardless of surface height
                Block foundationBlock = world.getBlockAt(
                blockX,
                        foundationY,
                blockZ
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
                            blockX,
                                y,
                            blockZ
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

        if (skippedCanopyColumns > 0) {
            LOGGER.info(String.format("[STRUCT] skippedCanopyColumns=%d", skippedCanopyColumns));
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
        
        // Normalize legacy grass, then preserve the dominant local top-surface material.
        if ("GRASS".equals(surfaceMaterial.name())) {
            return Material.GRASS_BLOCK;
        }
        if (PRESERVED_SURFACE_MATERIALS.contains(surfaceMaterial)) {
            return surfaceMaterial;
        }
        
        // Default to DIRT for all other cases
        return Material.DIRT;
    }

    private static SurfaceColumn resolveSurfaceColumn(World world, int x, int z, int startY) {
        int minY = world.getMinHeight();
        int highestY = Math.max(startY, world.getHighestBlockYAt(x, z));
        boolean skippedCanopy = false;
        Material fallback = Material.DIRT;

        for (int y = highestY; y >= minY; y--) {
            Material material = world.getBlockAt(x, y, z).getType();
            if (material.isAir()) {
                continue;
            }
            if (isCanopyMaterial(material)) {
                skippedCanopy = true;
                continue;
            }
            fallback = material;
            return new SurfaceColumn(y, material, skippedCanopy);
        }

        return new SurfaceColumn(startY, fallback, skippedCanopy);
    }

    private static boolean isCanopyMaterial(Material material) {
        String name = material.name();
        return name.endsWith("_LEAVES") || name.endsWith("_LOG") || name.endsWith("_STEM");
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
     * Fills the full footprint underside so interior air shelves do not remain.
     * 
     * @param world The world
     * @param origin Structure origin (southwest corner, ground level)
     * @param width Structure width (X direction)
     * @param depth Structure depth (Z direction)
     * @param structureMaxY Top Y of the placed structure bounds
     * @param fillMaterial Fallback material to use when no local surface material can be preserved
     * @return Number of blocks filled
     */
    public static int backfillFoundation(World world, Location origin, int width, int depth, int structureMaxY,
                                         Material fillMaterial) {
        int filled = 0;
        int skippedCanopyColumns = 0;
        int unstableSupportColumns = 0;
        Material[][] columnFillMaterials = new Material[width][depth];
        
        LOGGER.fine(String.format("[STRUCT] Backfilling foundation at %s (%dx%d)", origin, width, depth));
        
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                int blockX = origin.getBlockX() + x;
                int blockZ = origin.getBlockZ() + z;
                
                // Find natural terrain height at this position
                int structureBaseY = origin.getBlockY();
                BackfillSupport support = resolveBackfillSupport(world, blockX, blockZ, structureBaseY - 1, fillMaterial);
                if (support.encounteredVegetation) {
                    skippedCanopyColumns++;
                }
                if (!support.stableSupportFound) {
                    unstableSupportColumns++;
                }
                int terrainY = support.supportY;
                Material columnFillMaterial = support.fillMaterial;
                columnFillMaterials[x][z] = columnFillMaterial;
                int lowestPlacedY = findLowestPlacedBlockY(world, blockX, blockZ, structureBaseY, structureMaxY,
                    columnFillMaterial);
                int fillTopY = determineBackfillTopY(structureBaseY, lowestPlacedY);

                int fillBottomY = determineBackfillBottomY(world, blockX, blockZ, terrainY, fillTopY);

                if (fillBottomY > fillTopY) {
                    continue;
                }

                // Fill from the resolved support base up to the fill ceiling, compacting shallow
                // underside voids so large open structures do not expose hollow terrain shelves.
                for (int y = fillBottomY; y <= fillTopY; y++) {
                    Block block = world.getBlockAt(blockX, y, blockZ);
                    if (shouldFillBackfillBlock(block.getType())) {
                        block.setType(columnFillMaterial);
                        filled++;
                    }
                }
            }
        }

        int sealedUndersideBand = sealImmediateUndersideBand(world, origin, width, depth, fillMaterial,
            columnFillMaterials);
        filled += sealedUndersideBand;

        int filledLayers = fillMostlyAirLowerLayers(world, origin, width, depth, structureMaxY, fillMaterial,
            columnFillMaterials);
        filled += filledLayers;
        
        if (skippedCanopyColumns > 0) {
            LOGGER.info(String.format("[STRUCT] Backfill skipped canopy columns=%d", skippedCanopyColumns));
        }
        if (unstableSupportColumns > 0) {
            LOGGER.info(String.format("[STRUCT] Backfill fell back to unstable support columns=%d", unstableSupportColumns));
        }
        LOGGER.info(String.format("[STRUCT] Foundation backfilled: %d blocks placed", filled));
        return filled;
    }

    private static int sealImmediateUndersideBand(World world, Location origin, int width, int depth,
                                                  Material defaultFillMaterial, Material[][] columnFillMaterials) {
        int baseY = origin.getBlockY();
        int undersideY = baseY - 1;
        if (undersideY < getWorldMinHeight(world)) {
            return 0;
        }

        int filled = 0;
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                Block baseBlock = world.getBlockAt(origin.getBlockX() + x, baseY, origin.getBlockZ() + z);
                if (shouldFillBackfillBlock(baseBlock.getType()) || isSkippableGroundLikeFill(baseBlock.getType())) {
                    continue;
                }

                Block undersideBlock = world.getBlockAt(origin.getBlockX() + x, undersideY, origin.getBlockZ() + z);
                if (!shouldFillBackfillBlock(undersideBlock.getType())) {
                    continue;
                }

                Material fillForColumn = columnFillMaterials[x][z] != null ? columnFillMaterials[x][z] : defaultFillMaterial;
                undersideBlock.setType(fillForColumn);
                filled++;
            }
        }

        if (filled > 0) {
            LOGGER.info(String.format("[STRUCT] Sealed %d underside band block(s) at y=%d beneath pasted base y=%d",
                filled, undersideY, baseY));
        }
        return filled;
    }

    private static int fillMostlyAirLowerLayers(World world, Location origin, int width, int depth, int structureMaxY,
                                                Material defaultFillMaterial, Material[][] columnFillMaterials) {
        int footprintArea = width * depth;
        if (footprintArea < MIN_MOSTLY_AIR_LAYER_AREA) {
            LOGGER.fine(String.format("[STRUCT] Skipping mostly-air layer fill: footprint area %d < %d", footprintArea, MIN_MOSTLY_AIR_LAYER_AREA));
            return 0;
        }

        int baseY = origin.getBlockY();
        int filled = 0;
        int compactedLayers = 0;
        int previousAirBlocks = footprintArea;

        LOGGER.info(String.format("[STRUCT] Checking for mostly-air schematic layers: origin=%s dims=%dx%d baseY=%d maxY=%d retainedRatio=%.2f", 
            origin, width, depth, baseY, structureMaxY, MOSTLY_AIR_LAYER_RETAINED_RATIO));

        for (int y = baseY; y <= structureMaxY; y++) {
            int airBlocks = 0;
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < depth; z++) {
                    if (world.getBlockAt(origin.getBlockX() + x, y, origin.getBlockZ() + z).getType().isAir()) {
                        airBlocks++;
                    }
                }
            }

            double airRatio = (double) airBlocks / (double) footprintArea;
            double retainedRatio = previousAirBlocks <= 0 ? 0.0D : (double) airBlocks / (double) previousAirBlocks;
            LOGGER.info(String.format("[STRUCT] Mostly-air check y=%d air=%d/%d ratio=%.3f prevAir=%d retained=%.3f",
                    y, airBlocks, footprintArea, airRatio, previousAirBlocks, retainedRatio));
            if (airBlocks == 0 || retainedRatio < MOSTLY_AIR_LAYER_RETAINED_RATIO) {
                LOGGER.info(String.format("[STRUCT] Layer %d air dropped too sharply (retained=%.3f < %.3f), stopping fill scan",
                        y, retainedRatio, MOSTLY_AIR_LAYER_RETAINED_RATIO));
                break;
            }

            int filledThisLayer = 0;
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < depth; z++) {
                    Block block = world.getBlockAt(origin.getBlockX() + x, y, origin.getBlockZ() + z);
                    if (!shouldFillBackfillBlock(block.getType())) {
                        continue;
                    }

                    Material layerFillMaterial = columnFillMaterials[x][z] != null
                        ? columnFillMaterials[x][z]
                        : defaultFillMaterial;
                    block.setType(layerFillMaterial);
                    filled++;
                    filledThisLayer++;
                }
            }

            if (filledThisLayer > 0) {
                LOGGER.info(String.format("[STRUCT] Filled %d blocks in mostly-air layer y=%d", filledThisLayer, y));
            }
            compactedLayers++;
            previousAirBlocks = airBlocks;
        }

        if (compactedLayers > 0) {
            LOGGER.info(String.format("[STRUCT] Filled %d mostly-air schematic layer(s) inside footprint", compactedLayers));
        }

        return filled;
    }

    private static int determineBackfillTopY(int structureBaseY, int lowestPlacedY) {
        if (lowestPlacedY == Integer.MAX_VALUE || lowestPlacedY <= structureBaseY) {
            return structureBaseY;
        }

        int supportGap = lowestPlacedY - structureBaseY;
        if (supportGap <= MAX_POST_PASTE_SUPPORT_GAP) {
            return lowestPlacedY - 1;
        }

        // Large open air volumes above the base layer are more likely courtyards/interiors than
        // missing foundation blocks, so only seal the pasted footprint's base layer in that case.
        return structureBaseY;
    }

    private static int determineBackfillBottomY(World world, int x, int z, int terrainY, int fillTopY) {
        int fillBottomY = terrainY + 1;
        int lowerBoundY = Math.max(getWorldMinHeight(world), fillTopY - MAX_UNDERSIDE_COMPACTION_DEPTH);
        int scanStartY = Math.max(terrainY - 1, lowerBoundY);
        int consecutiveSupport = 0;
        boolean encounteredGap = false;
        int lowestGapY = Integer.MAX_VALUE;

        for (int y = scanStartY; y >= lowerBoundY; y--) {
            Material material = world.getBlockAt(x, y, z).getType();

            if (shouldFillBackfillBlock(material)) {
                encounteredGap = true;
                lowestGapY = Math.min(lowestGapY, y);
                consecutiveSupport = 0;
                continue;
            }

            if (!encounteredGap) {
                continue;
            }

            if (isValidBackfillSupport(material)) {
                consecutiveSupport++;
                if (consecutiveSupport >= REQUIRED_FOUNDATION_SUPPORT_BAND) {
                    fillBottomY = lowestGapY;
                    break;
                }
                continue;
            }

            consecutiveSupport = 0;
        }

        return fillBottomY;
    }

    private static int findLowestPlacedBlockY(World world, int x, int z, int structureBaseY, int structureMaxY,
                                              Material columnFillMaterial) {
        for (int y = structureBaseY; y <= structureMaxY; y++) {
            Material material = world.getBlockAt(x, y, z).getType();
            if (shouldFillBackfillBlock(material)) {
                continue;
            }
            if (material == columnFillMaterial || isSkippableGroundLikeFill(material)) {
                continue;
            }
            return y;
        }
        return Integer.MAX_VALUE;
    }

    private static boolean isSkippableGroundLikeFill(Material material) {
        return material == Material.DIRT
            || material == Material.GRASS_BLOCK
            || material == Material.COARSE_DIRT
            || material == Material.PODZOL
            || material == Material.GRAVEL
            || material == Material.SAND
            || material == Material.RED_SAND
            || material == Material.STONE
            || material == Material.DEEPSLATE
            || material == Material.ANDESITE
            || material == Material.DIORITE
            || material == Material.GRANITE
            || material == Material.CLAY;
    }

    private static BackfillSupport resolveBackfillSupport(World world, int x, int z, int startY,
                                                          Material defaultFillMaterial) {
        int minY = world.getMinHeight();
        boolean encounteredVegetation = false;
        Material fallbackSurface = null;
        int fallbackSupportY = Integer.MIN_VALUE;

        for (int y = startY; y >= minY; y--) {
            Material material = world.getBlockAt(x, y, z).getType();
            if (shouldFillBackfillBlock(material)) {
                if (isVegetationMaterial(material)) {
                    encounteredVegetation = true;
                }
                continue;
            }

            if (fallbackSurface == null) {
                fallbackSurface = material;
                fallbackSupportY = y;
            }
            if (!isValidBackfillSupport(material)) {
                continue;
            }
            if (!isStableBackfillSupport(world, x, y, z)) {
                continue;
            }

            return new BackfillSupport(
                    y,
                    determineBackfillMaterial(material, defaultFillMaterial),
                    encounteredVegetation,
                    true
            );
        }

        if (fallbackSurface != null) {
            return new BackfillSupport(
                    fallbackSupportY,
                    determineBackfillMaterial(fallbackSurface, defaultFillMaterial),
                    encounteredVegetation,
                    false
            );
        }

        return new BackfillSupport(minY - 1, defaultFillMaterial, encounteredVegetation, false);
    }

    private static boolean isValidBackfillSupport(Material material) {
        if (material == Material.WATER || material == Material.LAVA) {
            return false;
        }
        if (TRIMMABLE_VEGETATION.contains(material)) {
            return false;
        }
        if (material == Material.SNOW || material == Material.SNOW_BLOCK) {
            return false;
        }
        return isGoodFoundationMaterial(material);
    }

    private static boolean isStableBackfillSupport(World world, int x, int y, int z) {
        if (y <= world.getMinHeight()) {
            return true;
        }

        Material below = world.getBlockAt(x, y - 1, z).getType();
        if (below.isAir()) {
            return false;
        }
        if (TRIMMABLE_VEGETATION.contains(below)) {
            return false;
        }
        if (below == Material.WATER || below == Material.LAVA || below == Material.SNOW || below == Material.SNOW_BLOCK) {
            return false;
        }
        return below.isSolid();
    }

    private static boolean shouldFillBackfillBlock(Material material) {
        if (material == Material.WATER || material == Material.LAVA) {
            return false;
        }
        if (material.isAir()) {
            return true;
        }
        if (TRIMMABLE_VEGETATION.contains(material)) {
            return true;
        }
        return material == Material.SNOW || material == Material.SNOW_BLOCK || !material.isSolid();
    }

    private static Material determineBackfillMaterial(Material surfaceMaterial, Material defaultFillMaterial) {
        if (surfaceMaterial == null) {
            return defaultFillMaterial;
        }
        if ("GRASS".equals(surfaceMaterial.name())) {
            return Material.GRASS_BLOCK;
        }
        if (surfaceMaterial == Material.GRASS_BLOCK || PRESERVED_SURFACE_MATERIALS.contains(surfaceMaterial)) {
            return surfaceMaterial;
        }
        return defaultFillMaterial;
    }

    private static int getWorldMinHeight(World world) {
        try {
            return world.getMinHeight();
        } catch (RuntimeException e) {
            return -64;
        }
    }

    private static int getWorldMaxHeight(World world) {
        try {
            int maxHeight = world.getMaxHeight();
            if (maxHeight <= getWorldMinHeight(world)) {
                return 320;
            }
            return maxHeight;
        } catch (RuntimeException e) {
            return 320;
        }
    }

    private static String blockKey(int x, int y, int z) {
        return x + ":" + y + ":" + z;
    }
}
