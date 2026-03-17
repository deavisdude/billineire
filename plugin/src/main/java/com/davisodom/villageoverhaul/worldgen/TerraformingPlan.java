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
 * T057: Relaxed terraforming limits to reduce false placement failures
 * 
 * Usage:
 * 1. Create plan with TerraformingPlan.forSite(world, origin, dimensions)
 * 2. Call plan() to compute operations (returns false if site is unsuitable)
 * 3. Call commit() only after placement decision is finalized
 * 4. If placement is abandoned, simply discard the plan (no blocks modified)
 */
public class TerraformingPlan {
    
    private static final Logger LOGGER = Logger.getLogger(TerraformingPlan.class.getName());
    
    // T057: Maximum blocks to terraform - increased from 300 to 500 to reduce false rejections
    // Playtest logs showed structures being rejected for exceeding limits by 1-2 blocks
    private static final int MAX_TERRAFORM_BLOCKS = 500;
    private static final int LARGE_STRUCTURE_THRESHOLD = 400; // 20x20 (lowered from 30x30)
    private static final int MAX_TERRAFORM_BLOCKS_LARGE = 5000; // increased from 3000
    private static final int MAX_VERTICAL_CHANGE = 4; // increased from 3
    private static final int MAX_SMALL_WATER_PATCH_VOLUME = 27; // <= 3x3x3
    private static final int MAX_SMALL_WATER_PATCH_DEPTH = 3;
    private static final int WATER_PATCH_MARGIN = 1;
    private static final double MAX_PRECOMMIT_MISMATCH_RATIO = 0.10;
    private static final int MIN_PRECOMMIT_MISMATCH_ABORT = 10;
    private static final double MAX_SKIP_RATIO_ABORT = 0.15;
    private static final int MIN_SKIPPED_FOR_ABORT = 10;
    
    // T057: Tolerance buffer for limit checks (allows small overages)
    // This prevents rejection for being just 1-2 blocks over limit
    private static final double LIMIT_TOLERANCE = 1.10; // 10% overage allowed
    
    private final World world;
    private final Location origin;
    private final int width;
    private final int depth;
    private final int height;
    private final int[] bounds; // [minX, maxX, minY, maxY, minZ, maxZ]
    
    // Planned operations (not yet committed)
    private final List<BlockOperation> plannedOperations = new ArrayList<>();
    private final Map<String, BlockOperation> plannedOperationsByKey = new HashMap<>();
    private static final Map<Long, Object> CHUNK_LOCKS = new java.util.concurrent.ConcurrentHashMap<>();
    
    // T058: Applied operations tracking for rollback support
    private final List<AppliedOperation> appliedOperations = new ArrayList<>();
    
    // State tracking
    private boolean planned = false;
    private boolean committed = false;
    private boolean planSucceeded = false;
    private boolean rolledBack = false;
    private boolean planFailed = false;
    private String rejectionReason = null;
    
    // T058: Commit diagnostics
    private int appliedOpsCount = 0;
    private int skippedOpsCount = 0;
    
    // Diagnostics
    private int trimCount = 0;
    private int gradeCount = 0;
    private int fillCount = 0;
    private final Set<String> canopyColumns = new HashSet<>();

    private static final class SurfaceColumn {
        private final int surfaceY;
        private final Material surfaceMaterial;

        private SurfaceColumn(int surfaceY, Material surfaceMaterial) {
            this.surfaceY = surfaceY;
            this.surfaceMaterial = surfaceMaterial;
        }
    }
    
    /**
     * T058: Represents an operation that was actually applied to the world.
     * Stores the actual original material at commit time (may differ from planned if world changed).
     */
    public static class AppliedOperation {
        public final int x;
        public final int y;
        public final int z;
        public final Material actualOriginalMaterial;
        public final Material appliedMaterial;
        
        public AppliedOperation(int x, int y, int z, Material actualOriginal, Material applied) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.actualOriginalMaterial = actualOriginal;
            this.appliedMaterial = applied;
        }
        
        @Override
        public String toString() {
            return String.format("Applied(%d,%d,%d): %s -> %s", x, y, z, actualOriginalMaterial, appliedMaterial);
        }
    }
    
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
        canopyColumns.clear();
        
        int footprintArea = width * depth;
        boolean isLargeStructure = footprintArea > LARGE_STRUCTURE_THRESHOLD;
        int maxBlocks = isLargeStructure ? MAX_TERRAFORM_BLOCKS_LARGE : MAX_TERRAFORM_BLOCKS;
        
        LOGGER.fine(String.format("[STRUCT][PLAN] Planning terraforming at %s (%dx%dx%d), footprint=%d, large=%s", 
                formatLocation(origin), width, depth, height, footprintArea, isLargeStructure));
        
        // Step 0: T075 - allow small water patches (<= 3x3x3) to be filled; lava remains a hard veto
        boolean waterOk = planSmallWaterPatches(maxBlocks);
        if (!waterOk) {
            LOGGER.info(String.format("[STRUCT][PLAN] Site rejected: %s", rejectionReason));
            planSucceeded = false;
            return false;
        }
        if (planFailed) {
            planSucceeded = false;
            return false;
        }
        
        // Step 1: Plan vegetation trimming
        planVegetationTrimming();
        if (planFailed) {
            planSucceeded = false;
            return false;
        }
        
        int targetY = origin.getBlockY();
        
        // For very large structures, skip grading but plan foundation filling
        if (isLargeStructure) {
            LOGGER.fine(String.format("[STRUCT][PLAN] Large structure, skipping grading"));
            planFoundationFilling(targetY, maxBlocks);
        } else {
            // T057: Give each phase its own budget instead of sharing one pool
            // This prevents grading from starving the filling phase
            // Each phase gets the full budget since they're different operation types
            int gradeBudget = maxBlocks;
            int fillBudget = maxBlocks;
            
            // Step 2: Plan light grading
            int gradeResult = planLightGrading(targetY, gradeBudget);
            if (gradeResult < 0) {
                rejectionReason = "grading exceeded limits";
                planSucceeded = false;
                return false;
            }
            if (planFailed) {
                planSucceeded = false;
                return false;
            }
            
            // Step 3: Plan gap filling (independent budget)
            int fillResult = planGapFilling(targetY, fillBudget);
            if (fillResult < 0) {
                rejectionReason = "filling exceeded limits";
                planSucceeded = false;
                return false;
            }
            if (planFailed) {
                planSucceeded = false;
                return false;
            }
        }

        normalizeTopLayerMaterials(origin.getBlockY());
        
        int totalPlanned = trimCount + gradeCount + fillCount;
        LOGGER.info(String.format("[STRUCT][PLAN] Plan complete: %d operations (trim=%d, grade=%d, fill=%d, skippedCanopyColumns=%d)",
            totalPlanned, trimCount, gradeCount, fillCount, canopyColumns.size()));
        
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
        if (plannedOperations.isEmpty()) {
            LOGGER.info(String.format("[STRUCT][COMMIT] No terraforming operations to commit at %s",
                    formatLocation(origin)));
            committed = true;
            return true;
        }

        PreCommitCheck preCommitCheck = preCommitVerify();
        if (preCommitCheck.shouldAbort) {
            rejectionReason = "precommit_mismatch";
            LOGGER.warning(String.format("[STRUCT][COMMIT] Pre-commit verification failed: mismatches=%d total=%d ratio=%.2f",
                    preCommitCheck.mismatches, preCommitCheck.total, preCommitCheck.mismatchRatio));
            return false;
        }

        committed = true;

        LOGGER.info(String.format("[STRUCT][COMMIT] Committing %d terraforming operations at %s",
                plannedOperations.size(), formatLocation(origin)));
        
        // T058: Clear any previous applied operations and reset counters
        appliedOperations.clear();
        appliedOpsCount = 0;
        skippedOpsCount = 0;
        
        Map<Long, List<BlockOperation>> opsByChunk = groupOperationsByChunk();
        boolean abortCommit = false;

        for (Map.Entry<Long, List<BlockOperation>> entry : opsByChunk.entrySet()) {
            Object lock = CHUNK_LOCKS.computeIfAbsent(entry.getKey(), key -> new Object());
            synchronized (lock) {
                for (BlockOperation op : entry.getValue()) {
                    if (applyOperation(op)) {
                        appliedOpsCount++;
                    } else {
                        skippedOpsCount++;
                    }

                    if (shouldAbortForSkips(skippedOpsCount, plannedOperations.size())) {
                        abortCommit = true;
                        break;
                    }
                }
            }
            if (abortCommit) {
                break;
            }
        }

        if (abortCommit) {
            LOGGER.warning(String.format("[STRUCT][COMMIT] Skip ratio exceeded - aborting and rolling back. skipped=%d total=%d",
                    skippedOpsCount, plannedOperations.size()));
            rollback();
            return false;
        }
        
        // T058: Emit TERRAFORM-COMMIT diagnostic line with applied/skipped counts
        double skippedRatio = plannedOperations.isEmpty() ? 0.0
            : (double) skippedOpsCount / (double) plannedOperations.size();
        LOGGER.info(String.format("[TERRAFORM-COMMIT] bounds=(%d..%d,%d..%d,%d..%d) appliedOps=%d skippedOps=%d opsTotal=%d skippedRatio=%.2f",
            bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5],
            appliedOpsCount, skippedOpsCount, plannedOperations.size(), skippedRatio));
        
        return true;
    }
    
    /**
     * T058: Rollback committed terraforming operations.
     * Restores blocks to their state before commit() was called.
     * Only works if commit() was called and rollback() hasn't been called yet.
     * 
     * @return true if rollback was successful
     */
    public boolean rollback() {
        if (!committed) {
            throw new IllegalStateException("Cannot rollback uncommitted plan");
        }
        if (rolledBack) {
            throw new IllegalStateException("Plan already rolled back");
        }
        
        LOGGER.info(String.format("[STRUCT][ROLLBACK] Rolling back %d applied operations at %s",
                appliedOperations.size(), formatLocation(origin)));
        
        int reverted = 0;
        int skipped = 0;
        
        // Rollback in reverse order to handle any potential dependencies
        for (int i = appliedOperations.size() - 1; i >= 0; i--) {
            AppliedOperation applied = appliedOperations.get(i);
            Block block = world.getBlockAt(applied.x, applied.y, applied.z);
            
            // Verify block is still what we set it to (another modification may have occurred)
            Material currentMaterial = block.getType();
            if (!currentMaterial.equals(applied.appliedMaterial)) {
                LOGGER.warning(String.format("[STRUCT][ROLLBACK] Block at (%d,%d,%d) changed from %s to %s since commit - skipping revert",
                        applied.x, applied.y, applied.z, applied.appliedMaterial, currentMaterial));
                skipped++;
                continue;
            }
            
            // Restore to the actual original material at commit time
            block.setType(applied.actualOriginalMaterial);
            reverted++;
        }
        
        rolledBack = true;
        
        LOGGER.info(String.format("[TERRAFORM-ROLLBACK] bounds=(%d..%d,%d..%d,%d..%d) revertedOps=%d skippedOps=%d totalApplied=%d",
                bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5],
                reverted, skipped, appliedOperations.size()));
        
        return reverted > 0 || appliedOperations.isEmpty();
    }
    
    /**
     * T058: Check if this plan has been rolled back.
     */
    public boolean isRolledBack() {
        return rolledBack;
    }
    
    /**
     * T058: Get the number of operations that were actually applied during commit.
     */
    public int getAppliedOpsCount() {
        return appliedOpsCount;
    }
    
    /**
     * T058: Get the number of operations that were skipped during commit.
     */
    public int getSkippedOpsCount() {
        return skippedOpsCount;
    }
    
    /**
     * T058: Get the list of operations that were applied during commit.
     * Useful for diagnostics and potential partial rollbacks.
     */
    public List<AppliedOperation> getAppliedOperations() {
        return Collections.unmodifiableList(appliedOperations);
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
     * T058: Now includes applied/skipped counts and rollback status.
     */
    public String getDiagnosticsSummary() {
        double skippedRatio = plannedOperations.isEmpty() ? 0.0
            : (double) skippedOpsCount / (double) plannedOperations.size();
        return String.format("TerraformingPlan{bounds=(%d..%d,%d..%d,%d..%d), ops=%d, trim=%d, grade=%d, fill=%d, skippedCanopyColumns=%d, applied=%d, skipped=%d, skippedRatio=%.2f, success=%s, committed=%s, rolledBack=%s, reason=%s}",
                bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5],
                plannedOperations.size(), trimCount, gradeCount, fillCount, canopyColumns.size(),
            appliedOpsCount, skippedOpsCount, skippedRatio,
                planSucceeded, committed, rolledBack, rejectionReason);
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
                        if (addPlannedOperation(new BlockOperation(
                                blockX, blockY, blockZ, mat, Material.AIR, BlockOperation.OperationType.TRIM))) {
                            trimCount++;
                        }
                        if (planFailed) {
                            return;
                        }
                    }
                }
            }
        }
    }
    
    private int planLightGrading(int targetY, int maxBlocks) {
        int graded = 0;
        // T057: Apply tolerance to limit - allow small overages
        int hardLimit = (int) Math.ceil(maxBlocks * LIMIT_TOLERANCE);
        
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                int blockX = origin.getBlockX() + x;
                int blockZ = origin.getBlockZ() + z;

                SurfaceColumn surface = resolveSurfaceColumn(blockX, blockZ, world.getHighestBlockYAt(blockX, blockZ));
                int surfaceY = surface.surfaceY;
                int yDiff = surfaceY - targetY;
                
                // Only fill gaps UPWARD - never dig down
                if (yDiff < 0 && Math.abs(yDiff) <= MAX_VERTICAL_CHANGE) {
                    // T065: Determine appropriate fill material based on surface context
                    Material surfaceMat = surface.surfaceMaterial;
                    
                    for (int y = surfaceY + 1; y <= targetY; y++) {
                        Block fillBlock = world.getBlockAt(blockX, y, blockZ);
                        Material mat = fillBlock.getType();
                        
                        if (!mat.isSolid()) {
                            if (isTrimmableVegetation(mat)) {
                                continue;
                            }
                            // T065: For the top block, use surface-appropriate material to prevent dirt scars
                            Material fillMaterial = determineFillMaterial(surfaceMat, y, targetY);
                            if (addPlannedOperation(new BlockOperation(
                                    blockX, y, blockZ, mat, fillMaterial, BlockOperation.OperationType.GRADE))) {
                                graded++;
                                gradeCount++;
                            }
                            if (planFailed) {
                                return -1;
                            }
                            
                            // T057: Use hard limit with tolerance instead of exact limit
                            if (graded > hardLimit) {
                                LOGGER.warning(String.format("[STRUCT][PLAN] Grading exceeded hard limit: %d > %d (soft=%d)", 
                                        graded, hardLimit, maxBlocks));
                                return -1;
                            }
                        }
                    }
                }
            }
        }
        
        // Log if we exceeded soft limit but stayed within tolerance
        if (graded > maxBlocks) {
            LOGGER.fine(String.format("[STRUCT][PLAN] Grading exceeded soft limit but within tolerance: %d > %d (hard=%d)",
                    graded, maxBlocks, hardLimit));
        }
        
        return graded;
    }
    
    private int planGapFilling(int foundationY, int maxBlocks) {
        int filled = 0;
        int minFillY = foundationY - MAX_VERTICAL_CHANGE;
        // T057: Apply tolerance to limit - allow small overages
        int hardLimit = (int) Math.ceil(maxBlocks * LIMIT_TOLERANCE);
        
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                int blockX = origin.getBlockX() + x;
                int blockZ = origin.getBlockZ() + z;

                SurfaceColumn surface = resolveSurfaceColumn(blockX, blockZ, foundationY - 1);
                int surfaceY = surface.surfaceY;
                
                // T057e: DO NOT fill at foundationY - the structure will be placed there by WorldEdit
                // Only fill gaps BELOW the structure's foundation level (from surfaceY+1 to foundationY-1)
                // This prevents terraforming from placing blocks that WorldEdit will immediately overwrite
                
                // Fill gaps below foundation (from ground surface up to one block below structure)
                if (surfaceY >= minFillY && surfaceY < foundationY - 1) {
                    // T065: Determine appropriate fill material based on surface context
                    Material surfaceMat = surface.surfaceMaterial;
                    
                    for (int y = surfaceY + 1; y < foundationY; y++) {
                        Block block = world.getBlockAt(blockX, y, blockZ);
                        Material mat = block.getType();
                        
                        if (!mat.isSolid() && mat != Material.WATER) {
                            // T065: For the top block, use surface-appropriate material to prevent dirt scars
                            Material fillMaterial = determineFillMaterial(surfaceMat, y, foundationY - 1);
                            if (addPlannedOperation(new BlockOperation(
                                    blockX, y, blockZ, mat, fillMaterial, BlockOperation.OperationType.FILL))) {
                                filled++;
                                fillCount++;
                            }
                            if (planFailed) {
                                return -1;
                            }
                            
                            // T057: Use hard limit with tolerance instead of exact limit
                            if (filled > hardLimit) {
                                LOGGER.warning(String.format("[STRUCT][PLAN] Filling exceeded hard limit: %d > %d (soft=%d)", 
                                        filled, hardLimit, maxBlocks));
                                return -1;
                            }
                        }
                    }
                }
            }
        }
        
        // Log if we exceeded soft limit but stayed within tolerance
        if (filled > maxBlocks) {
            LOGGER.fine(String.format("[STRUCT][PLAN] Filling exceeded soft limit but within tolerance: %d > %d (hard=%d)",
                    filled, maxBlocks, hardLimit));
        }
        
        return filled;
    }
    
    private void planFoundationFilling(int foundationY, int maxBlocks) {
        // T057e: For large structures, fill gaps below foundation but NOT at foundation level
        // The structure will place its own blocks at foundationY via WorldEdit
        int filled = 0;
        int minFillY = foundationY - MAX_VERTICAL_CHANGE;
        
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                int blockX = origin.getBlockX() + x;
                int blockZ = origin.getBlockZ() + z;

                SurfaceColumn surface = resolveSurfaceColumn(blockX, blockZ, foundationY - 1);
                int surfaceY = surface.surfaceY;
                
                // Only fill gaps below the foundation (not at foundation level)
                if (surfaceY >= minFillY && surfaceY < foundationY - 1) {
                    // T065: Determine appropriate fill material based on surface context
                    Material surfaceMat = surface.surfaceMaterial;
                    
                    for (int y = surfaceY + 1; y < foundationY; y++) {
                        Block block = world.getBlockAt(blockX, y, blockZ);
                        Material mat = block.getType();
                        
                        if (!mat.isSolid() && mat != Material.WATER) {
                            // T065: For the top block, use surface-appropriate material to prevent dirt scars
                            Material fillMaterial = determineFillMaterial(surfaceMat, y, foundationY - 1);
                            if (addPlannedOperation(new BlockOperation(
                                    blockX, y, blockZ, mat, fillMaterial, BlockOperation.OperationType.FILL))) {
                                filled++;
                                fillCount++;
                            }
                            if (planFailed) {
                                return;
                            }
                        }
                    }
                }
            }
        }
        
        if (filled > maxBlocks) {
            LOGGER.warning(String.format("[STRUCT][PLAN] Large structure foundation filling exceeded limits: %d > %d",
                    filled, maxBlocks));
            // Continue anyway for large structures
        }
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
    private Material determineFillMaterial(Material surfaceMaterial, int fillY, int topY) {
        // If this is NOT the top layer, always use DIRT (underground)
        if (fillY < topY) {
            return Material.DIRT;
        }
        
        if ("GRASS".equals(surfaceMaterial.name())) {
            return Material.GRASS_BLOCK;
        }
        if (isSurfaceMaterial(surfaceMaterial)) {
            return surfaceMaterial;
        }
        
        // Default to DIRT for all other cases
        return Material.DIRT;
    }
    
    private boolean isTrimmableVegetation(Material material) {
        // Some runtime environments (MockBukkit or test harnesses) may not expose newer
        // Material constants like SHORT_GRASS. Avoid direct enum reference to prevent
        // NoSuchFieldError in such environments — check by name instead.
        if ("SHORT_GRASS".equals(material.name())) return true;

        return material == Material.TALL_GRASS ||
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

    private boolean planSmallWaterPatches(int maxBlocks) {
        int minX = origin.getBlockX() - WATER_PATCH_MARGIN;
        int maxX = origin.getBlockX() + width - 1 + WATER_PATCH_MARGIN;
        int minZ = origin.getBlockZ() - WATER_PATCH_MARGIN;
        int maxZ = origin.getBlockZ() + depth - 1 + WATER_PATCH_MARGIN;
        int hardLimit = (int) Math.ceil(maxBlocks * LIMIT_TOLERANCE);

        Material dominantSurface = determineDominantSurfaceMaterial(
                origin.getBlockX(), origin.getBlockX() + width - 1,
                origin.getBlockZ(), origin.getBlockZ() + depth - 1);

        Set<String> visited = new HashSet<>();
        int plannedFill = 0;

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                int surfaceY = world.getHighestBlockYAt(x, z);
                Material surfaceMat = world.getBlockAt(x, surfaceY, z).getType();

                if (isLavaMaterial(surfaceMat)) {
                    rejectionReason = String.format("fluid (LAVA at %d, %d, %d)", x, surfaceY, z);
                    return false;
                }

                if (!isWaterMaterial(surfaceMat)) {
                    continue;
                }

                String key = key(x, surfaceY, z);
                if (visited.contains(key)) {
                    continue;
                }

                List<int[]> patchBlocks = new ArrayList<>();
                boolean patchOk = floodFillWaterPatch(
                        x, surfaceY, z,
                        minX, maxX,
                        minZ, maxZ,
                        surfaceY - (MAX_SMALL_WATER_PATCH_DEPTH - 1), surfaceY,
                        visited, patchBlocks);

                if (!patchOk) {
                    rejectionReason = String.format("fluid patch >3x3x3 near %d, %d, %d", x, surfaceY, z);
                    return false;
                }

                for (int[] pos : patchBlocks) {
                    int px = pos[0];
                    int py = pos[1];
                    int pz = pos[2];

                    if (px < origin.getBlockX() || px > origin.getBlockX() + width - 1) continue;
                    if (pz < origin.getBlockZ() || pz > origin.getBlockZ() + depth - 1) continue;

                    Block block = world.getBlockAt(px, py, pz);
                    Material mat = block.getType();
                    if (!isWaterMaterial(mat)) {
                        continue;
                    }

                    int columnSurfaceY = world.getHighestBlockYAt(px, pz);
                    Material fillMaterial = determineFillMaterial(dominantSurface, py, columnSurfaceY);
                    if (addPlannedOperation(new BlockOperation(
                            px, py, pz, mat, fillMaterial, BlockOperation.OperationType.FILL))) {
                        plannedFill++;
                        fillCount++;
                    }
                    if (planFailed) {
                        rejectionReason = "duplicate operation conflict";
                        return false;
                    }

                    if (plannedFill > hardLimit) {
                        rejectionReason = "water fill exceeded limits";
                        return false;
                    }
                }
            }
        }

        if (plannedFill > 0) {
            LOGGER.info(String.format("[STRUCT][PLAN] Planned small water fill: %d blocks", plannedFill));
        }

        return true;
    }

    private boolean floodFillWaterPatch(
            int startX,
            int startY,
            int startZ,
            int minX,
            int maxX,
            int minZ,
            int maxZ,
            int minY,
            int maxY,
            Set<String> visited,
            List<int[]> patchBlocks) {
        Deque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[]{startX, startY, startZ});
        visited.add(key(startX, startY, startZ));

        int[] directions = new int[]{1, 0, 0, -1, 0, 0, 0, 1, 0, 0, -1, 0, 0, 0, 1, 0, 0, -1};

        while (!queue.isEmpty()) {
            int[] pos = queue.poll();
            int x = pos[0];
            int y = pos[1];
            int z = pos[2];

            patchBlocks.add(pos);
            if (patchBlocks.size() > MAX_SMALL_WATER_PATCH_VOLUME) {
                return false;
            }

            for (int i = 0; i < directions.length; i += 3) {
                int nx = x + directions[i];
                int ny = y + directions[i + 1];
                int nz = z + directions[i + 2];

                if (nx < minX || nx > maxX || nz < minZ || nz > maxZ || ny < minY || ny > maxY) {
                    Material outsideMat = world.getBlockAt(nx, ny, nz).getType();
                    if (isLavaMaterial(outsideMat)) {
                        return false;
                    }
                    if (isWaterMaterial(outsideMat)) {
                        return false;
                    }
                    continue;
                }

                String key = key(nx, ny, nz);
                if (visited.contains(key)) {
                    continue;
                }

                Material mat = world.getBlockAt(nx, ny, nz).getType();
                if (isLavaMaterial(mat)) {
                    return false;
                }
                if (isWaterMaterial(mat)) {
                    visited.add(key);
                    queue.add(new int[]{nx, ny, nz});
                }
            }
        }

        return true;
    }

    private Material determineDominantSurfaceMaterial(int minX, int maxX, int minZ, int maxZ) {
        Map<Material, Integer> counts = new HashMap<>();

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                SurfaceColumn surface = resolveSurfaceColumn(x, z, world.getHighestBlockYAt(x, z));
                Material surfaceMat = surface.surfaceMaterial;
                if (surfaceMat.isSolid()) {
                    counts.merge(surfaceMat, 1, Integer::sum);
                }
            }
        }

        Material dominant = Material.DIRT;
        int best = 0;
        for (Map.Entry<Material, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > best) {
                best = entry.getValue();
                dominant = entry.getKey();
            }
        }

        return dominant;
    }

    private boolean isWaterMaterial(Material material) {
        return material == Material.WATER || "BUBBLE_COLUMN".equals(material.name());
    }

    private boolean isLavaMaterial(Material material) {
        return material == Material.LAVA;
    }

    private String key(int x, int y, int z) {
        return x + ":" + y + ":" + z;
    }

    private int findSurfaceYFromTarget(int blockX, int blockZ, int startY) {
        SurfaceColumn surface = resolveSurfaceColumn(blockX, blockZ, startY);
        return surface.surfaceY;
    }

    private SurfaceColumn resolveSurfaceColumn(int blockX, int blockZ, int startY) {
        int minY = world.getMinHeight();
        int highestY = Math.max(startY, world.getHighestBlockYAt(blockX, blockZ));
        Material fallback = Material.DIRT;

        for (int y = highestY; y >= minY; y--) {
            Material mat = world.getBlockAt(blockX, y, blockZ).getType();
            if (mat.isAir()) {
                continue;
            }
            if (isCanopyMaterial(mat)) {
                canopyColumns.add(blockX + ":" + blockZ);
                continue;
            }
            if (!isWaterMaterial(mat) && !isLavaMaterial(mat)) {
                return new SurfaceColumn(y, mat);
            }
            fallback = mat;
        }

        return new SurfaceColumn(startY, fallback);
    }

    private boolean isSurfaceMaterial(Material material) {
        return material == Material.GRASS_BLOCK
            || "GRASS".equals(material.name())
                || material == Material.DIRT
                || material == Material.PODZOL
                || material == Material.MYCELIUM
                || material == Material.SAND
                || material == Material.RED_SAND
                || material == Material.GRAVEL
                || material == Material.COARSE_DIRT
                || material == Material.ROOTED_DIRT
                || material == Material.STONE
                || material == Material.ANDESITE
                || material == Material.DIORITE
                || material == Material.GRANITE
                || material == Material.CLAY
                || material == Material.TERRACOTTA
                || material == Material.MUD
                || material == Material.MOSS_BLOCK
                || material == Material.SANDSTONE
                || material == Material.RED_SANDSTONE;
    }

    private void normalizeTopLayerMaterials(int targetY) {
        for (BlockOperation op : new ArrayList<>(plannedOperations)) {
            if (op.type != BlockOperation.OperationType.GRADE || op.y != targetY) {
                continue;
            }

            Material surfaceMat = findSurfaceMaterialForColumn(op.x, op.z, targetY - 1);
            Material normalized = determineFillMaterial(surfaceMat, op.y, targetY);
            if (op.targetMaterial == normalized) {
                continue;
            }

            String opKey = key(op.x, op.y, op.z);
            BlockOperation replacement = new BlockOperation(op.x, op.y, op.z, op.originalMaterial, normalized, op.type);
            replacePlannedOperation(opKey, op, replacement);
        }
    }

    private Material findSurfaceMaterialForColumn(int blockX, int blockZ, int startY) {
        int minY = world.getMinHeight();
        Material fallback = Material.DIRT;
        for (int y = startY; y >= minY; y--) {
            Material mat = world.getBlockAt(blockX, y, blockZ).getType();
            if (isCanopyMaterial(mat)) {
                canopyColumns.add(blockX + ":" + blockZ);
                continue;
            }
            if (isSurfaceMaterial(mat)) {
                return mat;
            }
            if (!mat.isAir() && !isWaterMaterial(mat) && !isLavaMaterial(mat)) {
                fallback = mat;
            }
        }
        return fallback;
    }

    private boolean isCanopyMaterial(Material material) {
        String name = material.name();
        return name.endsWith("_LEAVES") || name.endsWith("_LOG") || name.endsWith("_STEM");
    }


    private boolean addPlannedOperation(BlockOperation operation) {
        if (planFailed) {
            return false;
        }

        String opKey = key(operation.x, operation.y, operation.z);
        BlockOperation existing = plannedOperationsByKey.get(opKey);
        if (existing == null) {
            plannedOperations.add(operation);
            plannedOperationsByKey.put(opKey, operation);
            return true;
        }

        if (existing.targetMaterial == operation.targetMaterial && existing.type == operation.type) {
            return false;
        }

        if (existing.type == BlockOperation.OperationType.TRIM
                && operation.type != BlockOperation.OperationType.TRIM) {
            decrementOperationCount(existing.type);
            replacePlannedOperation(opKey, existing, operation);
            return true;
        }

        if (existing.type != BlockOperation.OperationType.TRIM
                && operation.type == BlockOperation.OperationType.TRIM) {
            return false;
        }

        if (existing.targetMaterial == operation.targetMaterial) {
            LOGGER.fine(String.format("[STRUCT][PLAN] Duplicate operation merged at (%d,%d,%d) target=%s",
                    operation.x, operation.y, operation.z, operation.targetMaterial));
            return false;
        }

        // T059/T065: When two GRADE/FILL operations target the same block with different materials,
        // keep the first one (grading takes precedence over gap filling). This avoids false failures
        // when both methods legitimately try to fill the same gap with different surface logic.
        // The first operation (planLightGrading) has the correct top-layer awareness.
        if (existing.type != BlockOperation.OperationType.TRIM
                && operation.type != BlockOperation.OperationType.TRIM) {
            LOGGER.fine(String.format("[STRUCT][PLAN] Duplicate GRADE/FILL at (%d,%d,%d): keeping %s, ignoring %s",
                    operation.x, operation.y, operation.z, existing.targetMaterial, operation.targetMaterial));
            return false;
        }

        planFailed = true;
        rejectionReason = String.format("duplicate operation conflict at (%d,%d,%d): %s -> %s vs %s",
                operation.x, operation.y, operation.z,
                existing.originalMaterial, existing.targetMaterial, operation.targetMaterial);
        LOGGER.warning(String.format("[STRUCT][PLAN] %s", rejectionReason));
        return false;
    }

    private void replacePlannedOperation(String opKey, BlockOperation existing, BlockOperation replacement) {
        int existingIndex = plannedOperations.indexOf(existing);
        if (existingIndex >= 0) {
            plannedOperations.set(existingIndex, replacement);
        } else {
            plannedOperations.add(replacement);
        }
        plannedOperationsByKey.put(opKey, replacement);
    }

    private void decrementOperationCount(BlockOperation.OperationType type) {
        switch (type) {
            case TRIM:
                trimCount = Math.max(0, trimCount - 1);
                break;
            case GRADE:
                gradeCount = Math.max(0, gradeCount - 1);
                break;
            case FILL:
                fillCount = Math.max(0, fillCount - 1);
                break;
            default:
                break;
        }
    }

    private PreCommitCheck preCommitVerify() {
        int mismatches = 0;
        int total = plannedOperations.size();

        for (BlockOperation op : plannedOperations) {
            Block block = world.getBlockAt(op.x, op.y, op.z);
            Material current = block.getType();
            if (current.equals(op.originalMaterial)) {
                continue;
            }
            if (current.equals(op.targetMaterial)) {
                continue;
            }
            if (shouldApplyDespiteMismatch(op, current)) {
                continue;
            }
            mismatches++;
        }

        double mismatchRatio = total == 0 ? 0.0 : (double) mismatches / (double) total;
        boolean shouldAbort = mismatches >= MIN_PRECOMMIT_MISMATCH_ABORT
                && mismatchRatio >= MAX_PRECOMMIT_MISMATCH_RATIO;

        if (mismatches > 0) {
            LOGGER.info(String.format("[STRUCT][COMMIT] Pre-commit verification: mismatches=%d total=%d ratio=%.2f",
                    mismatches, total, mismatchRatio));
        }

        return new PreCommitCheck(mismatches, total, mismatchRatio, shouldAbort);
    }

    private Map<Long, List<BlockOperation>> groupOperationsByChunk() {
        Map<Long, List<BlockOperation>> grouped = new HashMap<>();
        for (BlockOperation op : plannedOperations) {
            long chunkKey = chunkKey(op.x, op.z);
            grouped.computeIfAbsent(chunkKey, key -> new ArrayList<>()).add(op);
        }
        return grouped;
    }

    private boolean applyOperation(BlockOperation op) {
        Block block = world.getBlockAt(op.x, op.y, op.z);

        // T058: Capture actual current material at commit time (for rollback)
        Material currentMaterial = block.getType();

        // Retry read in case of transient change
        if (!currentMaterial.equals(op.originalMaterial)) {
            currentMaterial = block.getType();
        }

        if (currentMaterial.equals(op.originalMaterial)) {
            appliedOperations.add(new AppliedOperation(op.x, op.y, op.z, currentMaterial, op.targetMaterial));
            block.setType(op.targetMaterial);
            return true;
        }

        if (currentMaterial.equals(op.targetMaterial)) {
            appliedOperations.add(new AppliedOperation(op.x, op.y, op.z, currentMaterial, op.targetMaterial));
            return true;
        }

        if (shouldApplyDespiteMismatch(op, currentMaterial)) {
            appliedOperations.add(new AppliedOperation(op.x, op.y, op.z, currentMaterial, op.targetMaterial));
            block.setType(op.targetMaterial);
            return true;
        }

        LOGGER.warning(String.format("[STRUCT][COMMIT] Block at (%d,%d,%d) changed from %s to %s - skipping",
                op.x, op.y, op.z, op.originalMaterial, currentMaterial));
        return false;
    }

    private boolean shouldApplyDespiteMismatch(BlockOperation op, Material currentMaterial) {
        if (currentMaterial.equals(op.targetMaterial)) {
            return true;
        }

        switch (op.type) {
            case TRIM:
                return isTrimmableVegetation(currentMaterial);
            case GRADE:
            case FILL:
                return !currentMaterial.isSolid() && !isLavaMaterial(currentMaterial);
            default:
                return false;
        }
    }

    private boolean shouldAbortForSkips(int skipped, int total) {
        if (total == 0) {
            return false;
        }
        double ratio = (double) skipped / (double) total;
        return skipped >= MIN_SKIPPED_FOR_ABORT && ratio >= MAX_SKIP_RATIO_ABORT;
    }

    private long chunkKey(int blockX, int blockZ) {
        int chunkX = blockX >> 4;
        int chunkZ = blockZ >> 4;
        return (((long) chunkX) << 32) ^ (chunkZ & 0xffffffffL);
    }

    private static class PreCommitCheck {
        private final int mismatches;
        private final int total;
        private final double mismatchRatio;
        private final boolean shouldAbort;

        private PreCommitCheck(int mismatches, int total, double mismatchRatio, boolean shouldAbort) {
            this.mismatches = mismatches;
            this.total = total;
            this.mismatchRatio = mismatchRatio;
            this.shouldAbort = shouldAbort;
        }
    }
}
