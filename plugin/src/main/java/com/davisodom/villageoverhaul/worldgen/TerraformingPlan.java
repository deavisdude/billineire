package com.davisodom.villageoverhaul.worldgen;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.*;
import java.util.logging.Logger;

/**
 * Deferred terraforming plan that records operations without immediately modifying blocks.
 * Supports commit/rollback semantics to prevent orphaned terraforming pads.
 * 
 * T051: Fix unused terraforming pads and footprint misalignment
 * 
 * Usage:
 * 1. Create plan with TerraformingPlan.forSite(world, origin, dimensions)
 * 2. Call plan() to compute operations (returns false if site is unsuitable)
 * 3. Call commit() only after placement decision is finalized
 * 4. If placement is abandoned, simply discard the plan (no blocks modified)
 */
public class TerraformingPlan {
    
    private static final Logger LOGGER = Logger.getLogger(TerraformingPlan.class.getName());
    
    // Maximum blocks to terraform in a single operation
    private static final int MAX_TERRAFORM_BLOCKS = 300;
    private static final int LARGE_STRUCTURE_THRESHOLD = 900; // 30x30
    private static final int MAX_TERRAFORM_BLOCKS_LARGE = 3000;
    private static final int MAX_VERTICAL_CHANGE = 3;
    
    private final World world;
    private final Location origin;
    private final int width;
    private final int depth;
    private final int height;
    private final int[] bounds; // [minX, maxX, minY, maxY, minZ, maxZ]
    
    // Planned operations (not yet committed)
    private final List<BlockOperation> plannedOperations = new ArrayList<>();
    
    // State tracking
    private boolean planned = false;
    private boolean committed = false;
    private boolean planSucceeded = false;
    private String rejectionReason = null;
    
    // Diagnostics
    private int trimCount = 0;
    private int gradeCount = 0;
    private int fillCount = 0;
    
    /**
     * Represents a single block modification operation.
     */
    public static class BlockOperation {
        public final int x;
        public final int y;
        public final int z;
        public final Material originalMaterial;
        public final Material targetMaterial;
        public final OperationType type;
        
        public enum OperationType {
            TRIM,    // Remove vegetation
            GRADE,   // Level terrain
            FILL     // Fill gaps
        }
        
        public BlockOperation(int x, int y, int z, Material original, Material target, OperationType type) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.originalMaterial = original;
            this.targetMaterial = target;
            this.type = type;
        }
        
        @Override
        public String toString() {
            return String.format("%s(%d,%d,%d): %s -> %s", type, x, y, z, originalMaterial, targetMaterial);
        }
    }
    
    /**
     * Create a new terraforming plan for a site.
     */
    private TerraformingPlan(World world, Location origin, int width, int depth, int height) {
        this.world = world;
        this.origin = origin.clone();
        this.width = width;
        this.depth = depth;
        this.height = height;
        
        // Compute AABB bounds
        this.bounds = new int[] {
            origin.getBlockX(),
            origin.getBlockX() + width - 1,
            origin.getBlockY(),
            origin.getBlockY() + height - 1,
            origin.getBlockZ(),
            origin.getBlockZ() + depth - 1
        };
    }
    
    /**
     * Create a terraforming plan for a site with explicit AABB bounds.
     */
    private TerraformingPlan(World world, int[] bounds) {
        this.world = world;
        this.bounds = bounds.clone();
        
        this.width = bounds[1] - bounds[0] + 1;
        this.depth = bounds[5] - bounds[4] + 1;
        this.height = bounds[3] - bounds[2] + 1;
        this.origin = new Location(world, bounds[0], bounds[2], bounds[4]);
    }
    
    /**
     * Create a new terraforming plan for a site.
     * 
     * @param world Target world
     * @param origin Southwest corner of footprint
     * @param width Footprint width (X)
     * @param depth Footprint depth (Z)
     * @param height Structure height (Y)
     * @return New TerraformingPlan instance
     */
    public static TerraformingPlan forSite(World world, Location origin, int width, int depth, int height) {
        return new TerraformingPlan(world, origin, width, depth, height);
    }
    
    /**
     * Create a terraforming plan from explicit AABB bounds.
     * 
     * @param world Target world
     * @param bounds AABB bounds [minX, maxX, minY, maxY, minZ, maxZ]
     * @return New TerraformingPlan instance
     */
    public static TerraformingPlan forBounds(World world, int[] bounds) {
        if (bounds.length != 6) {
            throw new IllegalArgumentException("bounds must have 6 elements: minX, maxX, minY, maxY, minZ, maxZ");
        }
        return new TerraformingPlan(world, bounds);
    }
    
    /**
     * Plan terraforming operations without modifying the world.
     * Analyzes the site and records all necessary operations.
     * 
     * @return true if the plan is valid and can be committed, false if site is unsuitable
     */
    public boolean plan() {
        if (planned) {
            throw new IllegalStateException("Plan already computed; create a new TerraformingPlan instance");
        }
        planned = true;
        
        int footprintArea = width * depth;
        boolean isLargeStructure = footprintArea > LARGE_STRUCTURE_THRESHOLD;
        int maxBlocks = isLargeStructure ? MAX_TERRAFORM_BLOCKS_LARGE : MAX_TERRAFORM_BLOCKS;
        
        LOGGER.fine(String.format("[STRUCT][PLAN] Planning terraforming at %s (%dx%dx%d), footprint=%d, large=%s", 
                formatLocation(origin), width, depth, height, footprintArea, isLargeStructure));
        
        // Step 0: HARD VETO on ANY water/lava in footprint or surrounding area
        int checkMargin = 2;
        for (int x = -checkMargin; x < width + checkMargin; x++) {
            for (int z = -checkMargin; z < depth + checkMargin; z++) {
                int checkX = origin.getBlockX() + x;
                int checkZ = origin.getBlockZ() + z;
                int y = world.getHighestBlockYAt(checkX, checkZ);
                Material surfaceMat = world.getBlockAt(checkX, y, checkZ).getType();
                
                if (surfaceMat == Material.WATER || surfaceMat == Material.LAVA) {
                    rejectionReason = String.format("fluid (%s at %d, %d, %d)", surfaceMat, checkX, y, checkZ);
                    LOGGER.info(String.format("[STRUCT][PLAN] Site rejected: %s", rejectionReason));
                    planSucceeded = false;
                    return false;
                }
            }
        }
        
        // Step 1: Plan vegetation trimming
        planVegetationTrimming();
        
        int targetY = origin.getBlockY();
        
        // For very large structures, skip grading but plan foundation filling
        if (isLargeStructure) {
            LOGGER.fine(String.format("[STRUCT][PLAN] Large structure, skipping grading"));
            planFoundationFilling(targetY, maxBlocks);
        } else {
            // Step 2: Plan light grading
            int gradeResult = planLightGrading(targetY, maxBlocks);
            if (gradeResult < 0) {
                rejectionReason = "grading exceeded limits";
                planSucceeded = false;
                return false;
            }
            
            // Step 3: Plan gap filling
            int fillResult = planGapFilling(targetY, maxBlocks - gradeCount);
            if (fillResult < 0) {
                rejectionReason = "filling exceeded limits";
                planSucceeded = false;
                return false;
            }
        }
        
        int totalPlanned = trimCount + gradeCount + fillCount;
        LOGGER.info(String.format("[STRUCT][PLAN] Plan complete: %d operations (trim=%d, grade=%d, fill=%d)",
                totalPlanned, trimCount, gradeCount, fillCount));
        
        planSucceeded = true;
        return true;
    }
    
    /**
     * Commit the planned terraforming operations to the world.
     * Only call this after placement decision is finalized.
     * 
     * @return true if committed successfully
     */
    public boolean commit() {
        if (!planned) {
            throw new IllegalStateException("Must call plan() before commit()");
        }
        if (committed) {
            throw new IllegalStateException("Plan already committed");
        }
        if (!planSucceeded) {
            throw new IllegalStateException("Cannot commit failed plan");
        }
        
        committed = true;
        
        LOGGER.info(String.format("[STRUCT][COMMIT] Committing %d terraforming operations at %s",
                plannedOperations.size(), formatLocation(origin)));
        
        int applied = 0;
        for (BlockOperation op : plannedOperations) {
            Block block = world.getBlockAt(op.x, op.y, op.z);
            
            // Verify block hasn't changed since planning (concurrent modification detection)
            Material currentMaterial = block.getType();
            if (!currentMaterial.equals(op.originalMaterial)) {
                LOGGER.warning(String.format("[STRUCT][COMMIT] Block at (%d,%d,%d) changed from %s to %s - skipping",
                        op.x, op.y, op.z, op.originalMaterial, currentMaterial));
                continue;
            }
            
            block.setType(op.targetMaterial);
            applied++;
        }
        
        LOGGER.info(String.format("[STRUCT][COMMIT] Applied %d/%d operations",
                applied, plannedOperations.size()));
        
        return true;
    }
    
    /**
     * Get planned operations without committing.
     * Useful for diagnostics and verification.
     */
    public List<BlockOperation> getPlannedOperations() {
        return Collections.unmodifiableList(plannedOperations);
    }
    
    /**
     * Get the AABB bounds for this terraforming plan.
     */
    public int[] getBounds() {
        return bounds.clone();
    }
    
    /**
     * Check if the plan was successful.
     */
    public boolean isSuccessful() {
        return planSucceeded;
    }
    
    /**
     * Get rejection reason if plan failed.
     */
    public String getRejectionReason() {
        return rejectionReason;
    }
    
    /**
     * Get diagnostics summary.
     */
    public String getDiagnosticsSummary() {
        return String.format("TerraformingPlan{bounds=(%d..%d,%d..%d,%d..%d), ops=%d, trim=%d, grade=%d, fill=%d, success=%s, reason=%s}",
                bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5],
                plannedOperations.size(), trimCount, gradeCount, fillCount,
                planSucceeded, rejectionReason);
    }
    
    /**
     * Check if the plan has been committed.
     */
    public boolean isCommitted() {
        return committed;
    }
    
    // ---- Private planning methods ----
    
    private void planVegetationTrimming() {
        int maxTrimHeight = 3; // Only trim 0-2 blocks above ground level
        
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                for (int y = 0; y < maxTrimHeight; y++) {
                    int blockX = origin.getBlockX() + x;
                    int blockY = origin.getBlockY() + y;
                    int blockZ = origin.getBlockZ() + z;
                    
                    Block block = world.getBlockAt(blockX, blockY, blockZ);
                    Material mat = block.getType();
                    
                    if (isTrimmableVegetation(mat)) {
                        plannedOperations.add(new BlockOperation(
                                blockX, blockY, blockZ, mat, Material.AIR, BlockOperation.OperationType.TRIM));
                        trimCount++;
                    }
                }
            }
        }
    }
    
    private int planLightGrading(int targetY, int maxBlocks) {
        int graded = 0;
        
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                int blockX = origin.getBlockX() + x;
                int blockZ = origin.getBlockZ() + z;
                
                int surfaceY = world.getHighestBlockYAt(blockX, blockZ);
                int yDiff = surfaceY - targetY;
                
                // Only fill gaps UPWARD - never dig down
                if (yDiff < 0 && Math.abs(yDiff) <= MAX_VERTICAL_CHANGE) {
                    for (int y = surfaceY + 1; y <= targetY; y++) {
                        Block fillBlock = world.getBlockAt(blockX, y, blockZ);
                        Material mat = fillBlock.getType();
                        
                        if (!mat.isSolid()) {
                            plannedOperations.add(new BlockOperation(
                                    blockX, y, blockZ, mat, Material.DIRT, BlockOperation.OperationType.GRADE));
                            graded++;
                            gradeCount++;
                            
                            if (graded > maxBlocks) {
                                LOGGER.warning(String.format("[STRUCT][PLAN] Grading exceeded limit: %d > %d", 
                                        graded, maxBlocks));
                                return -1;
                            }
                        }
                    }
                }
            }
        }
        
        return graded;
    }
    
    private int planGapFilling(int foundationY, int maxBlocks) {
        int filled = 0;
        int minFillY = foundationY - MAX_VERTICAL_CHANGE;
        
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                int blockX = origin.getBlockX() + x;
                int blockZ = origin.getBlockZ() + z;
                
                int surfaceY = world.getHighestBlockYAt(blockX, blockZ);
                
                // CRITICAL: Always solidify the foundation layer
                Block foundationBlock = world.getBlockAt(blockX, foundationY, blockZ);
                Material foundationMat = foundationBlock.getType();
                
                if (!isGoodFoundationMaterial(foundationMat)) {
                    plannedOperations.add(new BlockOperation(
                            blockX, foundationY, blockZ, foundationMat, Material.DIRT, BlockOperation.OperationType.FILL));
                    filled++;
                    fillCount++;
                    
                    if (filled > maxBlocks) {
                        LOGGER.warning(String.format("[STRUCT][PLAN] Filling exceeded limit: %d > %d", 
                                filled, maxBlocks));
                        return -1;
                    }
                }
                
                // Fill gaps below foundation
                if (surfaceY >= minFillY && surfaceY < foundationY) {
                    for (int y = surfaceY + 1; y < foundationY; y++) {
                        Block block = world.getBlockAt(blockX, y, blockZ);
                        Material mat = block.getType();
                        
                        if (!mat.isSolid() && mat != Material.WATER) {
                            plannedOperations.add(new BlockOperation(
                                    blockX, y, blockZ, mat, Material.DIRT, BlockOperation.OperationType.FILL));
                            filled++;
                            fillCount++;
                            
                            if (filled > maxBlocks) {
                                LOGGER.warning(String.format("[STRUCT][PLAN] Filling exceeded limit: %d > %d", 
                                        filled, maxBlocks));
                                return -1;
                            }
                        }
                    }
                }
            }
        }
        
        return filled;
    }
    
    private void planFoundationFilling(int foundationY, int maxBlocks) {
        // For large structures, only fill foundation gaps
        int filled = 0;
        
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                int blockX = origin.getBlockX() + x;
                int blockZ = origin.getBlockZ() + z;
                
                Block foundationBlock = world.getBlockAt(blockX, foundationY, blockZ);
                Material foundationMat = foundationBlock.getType();
                
                if (!isGoodFoundationMaterial(foundationMat)) {
                    plannedOperations.add(new BlockOperation(
                            blockX, foundationY, blockZ, foundationMat, Material.DIRT, BlockOperation.OperationType.FILL));
                    filled++;
                    fillCount++;
                }
            }
        }
        
        if (filled > maxBlocks) {
            LOGGER.warning(String.format("[STRUCT][PLAN] Large structure foundation filling exceeded limits: %d > %d",
                    filled, maxBlocks));
            // Continue anyway for large structures
        }
    }
    
    private boolean isTrimmableVegetation(Material material) {
        return material == Material.SHORT_GRASS ||
               material == Material.TALL_GRASS ||
               material == Material.FERN ||
               material == Material.LARGE_FERN ||
               material == Material.DEAD_BUSH ||
               material == Material.DANDELION ||
               material == Material.POPPY ||
               material == Material.AZURE_BLUET ||
               material == Material.ALLIUM ||
               material == Material.OXEYE_DAISY ||
               material == Material.CORNFLOWER ||
               material == Material.LILY_OF_THE_VALLEY ||
               material == Material.SUNFLOWER ||
               material == Material.LILAC ||
               material == Material.ROSE_BUSH ||
               material == Material.PEONY ||
               material == Material.SUGAR_CANE ||
               material == Material.VINE ||
               material == Material.WEEPING_VINES ||
               material == Material.TWISTING_VINES ||
               material == Material.KELP ||
               material == Material.SEAGRASS ||
               material == Material.TALL_SEAGRASS ||
               material.name().endsWith("_LOG") ||
               material.name().endsWith("_LEAVES") ||
               material.name().endsWith("_SAPLING") ||
               material == Material.BROWN_MUSHROOM ||
               material == Material.RED_MUSHROOM ||
               material == Material.BROWN_MUSHROOM_BLOCK ||
               material == Material.RED_MUSHROOM_BLOCK ||
               material == Material.MUSHROOM_STEM;
    }
    
    private boolean isGoodFoundationMaterial(Material material) {
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
    }
    
    private String formatLocation(Location loc) {
        return String.format("(%d,%d,%d)", loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }
}
