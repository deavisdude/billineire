package com.davisodom.villageoverhaul.worldgen;

import com.davisodom.villageoverhaul.worldgen.TerrainClassifier.Classification;
import com.davisodom.villageoverhaul.worldgen.TerrainClassifier.ClassificationResult;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Validates sites for structure placement.
 * Checks foundation solidity, interior clearance, and entrance accessibility.
 * 
 * T057: Configurable thresholds to reduce false-positive rejections.
 * Key fixes:
 * - Vegetation counted as traversable (can be cleared during terraforming)
 * - Solidity threshold lowered to 60% (terraforming handles gaps)
 * - Slope tolerance increased to 0.6 blocks/distance (mild slopes OK)
 * - Detailed rejection reasons for diagnostics
 */
public class SiteValidator {
    
    private static final Logger LOGGER = Logger.getLogger(SiteValidator.class.getName());
    
    // === Configurable Thresholds (T057) ===
    // These can be overridden via constructor for testing or config-driven values
    
    /**
     * Maximum allowed slope for foundation (blocks per horizontal distance).
     * Default: 0.6 (relaxed from 0.25 to allow mild slopes; terraforming can level)
     */
    private double maxFoundationSlope = 0.6;
    
    /**
     * Minimum percentage of solid+vegetation blocks required in foundation.
     * Note: Vegetation is counted as "solid" since it can be cleared.
     * Default: 0.60 (relaxed from 0.85 to allow natural terrain with gaps)
     */
    private double minFoundationSolidity = 0.60;
    
    /**
     * Maximum fraction of steep tiles allowed (0.0-1.0).
     * Default: 0.40 (40% steep tiles allowed; terraforming handles them)
     */
    private double maxSteepFraction = 0.40;
    
    /**
     * Maximum fraction of blocked tiles allowed (0.0-1.0).
     * Note: Blocked = AIR/VOID only; vegetation is separate.
     * Default: 0.30 (30% air gaps allowed; foundation will be built)
     */
    private double maxBlockedFraction = 0.30;

    /**
     * T075: Allow small water patches (<= 3x3x3) to be filled during terraforming.
     */
    private static final int MAX_SMALL_WATER_PATCH_VOLUME = 27;
    private static final int MAX_SMALL_WATER_PATCH_DEPTH = 3;
    
    // Legacy constants for backward compatibility (used if not configured)
    private static final double DEFAULT_MAX_SLOPE = 0.6;
    private static final double DEFAULT_MIN_SOLIDITY = 0.60;
    private static final double DEFAULT_MAX_STEEP = 0.40;
    private static final double DEFAULT_MAX_BLOCKED = 0.30;
    
    /**
     * Default constructor with relaxed thresholds (T057).
     */
    public SiteValidator() {
        this.maxFoundationSlope = DEFAULT_MAX_SLOPE;
        this.minFoundationSolidity = DEFAULT_MIN_SOLIDITY;
        this.maxSteepFraction = DEFAULT_MAX_STEEP;
        this.maxBlockedFraction = DEFAULT_MAX_BLOCKED;
    }
    
    /**
     * Constructor with configurable thresholds for testing and config-driven values.
     * T057: Allows fine-tuning of terrain acceptance criteria.
     * 
     * @param maxSlope Maximum slope (blocks per horizontal distance), e.g., 0.6
     * @param minSolidity Minimum solidity fraction, e.g., 0.60
     * @param maxSteepFraction Maximum fraction of steep tiles, e.g., 0.40
     * @param maxBlockedFraction Maximum fraction of blocked tiles, e.g., 0.30
     */
    public SiteValidator(double maxSlope, double minSolidity, double maxSteepFraction, double maxBlockedFraction) {
        this.maxFoundationSlope = maxSlope;
        this.minFoundationSolidity = minSolidity;
        this.maxSteepFraction = maxSteepFraction;
        this.maxBlockedFraction = maxBlockedFraction;
    }
    
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
     * T057: Improved to count vegetation as "solid" (can be cleared), use configurable thresholds,
     * and provide detailed rejection reasons.
     */
    private boolean validateFoundation(World world, Location origin, int width, int depth, 
                                      ClassificationResult classificationResult) {
        int solidCount = 0;           // Solid blocks (stone, dirt, etc.)
        int vegetationCount = 0;      // Vegetation blocks (can be cleared)
        int totalCount = 0;
        double calculatedSlope = 0.0;
        
        Integer minY = null;
        Integer maxY = null;
        
        List<String> rejectionReasons = new ArrayList<>();
        
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
                
                // T057: Count solid blocks REGARDLESS of terrain classification
                // The classification tracks slope/fluid/blocked issues separately
                // Solidity is about whether there's actual ground to build on
                if (block.getType().isSolid()) {
                    solidCount++;
                    // Track Y variation for slope calculation
                    int y = block.getY();
                    if (minY == null || y < minY) minY = y;
                    if (maxY == null || y > maxY) maxY = y;
                } else if (classification == Classification.VEGETATION) {
                    vegetationCount++;
                    // Vegetation is on solid ground - find the ground below
                    Block belowBlock = world.getBlockAt(worldX, worldY - 1, worldZ);
                    if (belowBlock.getType().isSolid()) {
                        // Count vegetation location as solid since there's solid ground below
                        solidCount++;
                        int y = belowBlock.getY();
                        if (minY == null || y < minY) minY = y;
                        if (maxY == null || y > maxY) maxY = y;
                    }
                }
            }
        }
        
        // Calculate slope
        if (minY != null && maxY != null) {
            int yDiff = maxY - minY;
            int horizontalDist = Math.max(width, depth);
            calculatedSlope = (double) yDiff / horizontalDist;
        }
        
        // T057: Calculate effective solidity including vegetation (can be cleared)
        // effectiveSolid = actual solid + vegetation (on solid ground)
        double effectiveSolidity = totalCount > 0 ? (double) solidCount / totalCount : 0.0;
        
        // T057: Use configurable thresholds
        boolean solidityOk = effectiveSolidity >= minFoundationSolidity;
        boolean slopeOk = calculatedSlope <= maxFoundationSlope;
        
        // T057: Use configurable steep/blocked fractions
        int total = classificationResult.getTotal();
        double steepFraction = total > 0 ? (double) classificationResult.steep / total : 0.0;
        double blockedFraction = total > 0 ? (double) classificationResult.blocked / total : 0.0;
        
        boolean steepOk = steepFraction <= maxSteepFraction;
        boolean blockedOk = blockedFraction <= maxBlockedFraction;
        
        // T075: Allow small water patches (<= 3x3x3) to be filled during terraforming.
        // Lava is still a hard veto.
        boolean fluidOk = classificationResult.fluid == 0;
        String fluidRejectionDetail = null;
        if (!fluidOk) {
            FluidPatchCheckResult patchResult = checkSmallWaterPatches(world, origin, width, depth);
            if (patchResult.allowed) {
                fluidOk = true;
            } else {
                fluidRejectionDetail = patchResult.rejectionReason;
            }
        }
        
        // Build rejection reasons for diagnostics
        if (!solidityOk) {
            rejectionReasons.add(String.format("solidity=%.2f<%.2f", effectiveSolidity, minFoundationSolidity));
        }
        if (!slopeOk) {
            rejectionReasons.add(String.format("slope=%.2f>%.2f", calculatedSlope, maxFoundationSlope));
        }
        if (!steepOk) {
            rejectionReasons.add(String.format("steep=%.2f>%.2f", steepFraction, maxSteepFraction));
        }
        if (!blockedOk) {
            rejectionReasons.add(String.format("blocked=%.2f>%.2f", blockedFraction, maxBlockedFraction));
        }
        if (!fluidOk) {
            if (fluidRejectionDetail != null) {
                rejectionReasons.add(fluidRejectionDetail);
            }
            rejectionReasons.add(String.format("fluid=%d", classificationResult.fluid));
        }
        
        boolean passed = solidityOk && slopeOk && steepOk && blockedOk && fluidOk;
        
        // Store rejection reasons in classification result for diagnostics
        classificationResult.setRejectionReasons(rejectionReasons);
        
        LOGGER.fine(String.format("[STRUCT] Foundation check: solidity=%.2f (min %.2f), slope=%.3f (max %.3f), steep=%.2f (max %.2f), blocked=%.2f (max %.2f), passed=%b, reasons=%s",
                effectiveSolidity, minFoundationSolidity, 
                calculatedSlope, maxFoundationSlope,
                steepFraction, maxSteepFraction,
                blockedFraction, maxBlockedFraction,
                passed, rejectionReasons));
        
        return passed;
    }

    private FluidPatchCheckResult checkSmallWaterPatches(World world, Location origin, int width, int depth) {
        int minX = origin.getBlockX();
        int maxX = origin.getBlockX() + width - 1;
        int minZ = origin.getBlockZ();
        int maxZ = origin.getBlockZ() + depth - 1;
        int maxY = origin.getBlockY() - 1;
        int minY = maxY - (MAX_SMALL_WATER_PATCH_DEPTH - 1);

        Set<String> visited = new HashSet<>();

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = maxY; y >= minY; y--) {
                    Block block = world.getBlockAt(x, y, z);
                    Material mat = block.getType();

                    if (isLavaMaterial(mat)) {
                        return FluidPatchCheckResult.rejected(String.format("fluid (LAVA at %d, %d, %d)", x, y, z));
                    }

                    if (!isWaterMaterial(mat)) {
                        continue;
                    }

                    String key = key(x, y, z);
                    if (visited.contains(key)) {
                        continue;
                    }

                    FluidPatchCheckResult patchResult = floodFillWaterPatch(world, x, y, z, minX, maxX, minY, maxY, minZ, maxZ, visited);
                    if (!patchResult.allowed) {
                        return patchResult;
                    }
                }
            }
        }

        return FluidPatchCheckResult.allowed();
    }

    private FluidPatchCheckResult floodFillWaterPatch(
            World world,
            int startX,
            int startY,
            int startZ,
            int minX,
            int maxX,
            int minY,
            int maxY,
            int minZ,
            int maxZ,
            Set<String> visited) {
        Deque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[]{startX, startY, startZ});
        visited.add(key(startX, startY, startZ));

        int patchSize = 0;
        int[] directions = new int[]{1, 0, 0, -1, 0, 0, 0, 1, 0, 0, -1, 0, 0, 0, 1, 0, 0, -1};

        while (!queue.isEmpty()) {
            int[] pos = queue.poll();
            int x = pos[0];
            int y = pos[1];
            int z = pos[2];

            patchSize++;
            if (patchSize > MAX_SMALL_WATER_PATCH_VOLUME) {
                return FluidPatchCheckResult.rejected(String.format("fluid patch >3x3x3 near %d, %d, %d", startX, startY, startZ));
            }

            for (int i = 0; i < directions.length; i += 3) {
                int nx = x + directions[i];
                int ny = y + directions[i + 1];
                int nz = z + directions[i + 2];

                if (nx < minX || nx > maxX || nz < minZ || nz > maxZ || ny < minY || ny > maxY) {
                    Material outsideMat = world.getBlockAt(nx, ny, nz).getType();
                    if (isLavaMaterial(outsideMat)) {
                        return FluidPatchCheckResult.rejected(String.format("fluid (LAVA at %d, %d, %d)", nx, ny, nz));
                    }
                    if (isWaterMaterial(outsideMat)) {
                        return FluidPatchCheckResult.rejected(String.format("fluid patch >3x3x3 near %d, %d, %d", startX, startY, startZ));
                    }
                    continue;
                }

                String key = key(nx, ny, nz);
                if (visited.contains(key)) {
                    continue;
                }

                Block neighbor = world.getBlockAt(nx, ny, nz);
                Material mat = neighbor.getType();
                if (isLavaMaterial(mat)) {
                    return FluidPatchCheckResult.rejected(String.format("fluid (LAVA at %d, %d, %d)", nx, ny, nz));
                }
                if (isWaterMaterial(mat)) {
                    visited.add(key);
                    queue.add(new int[]{nx, ny, nz});
                }
            }
        }

        return FluidPatchCheckResult.allowed();
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

    private static final class FluidPatchCheckResult {
        private final boolean allowed;
        private final String rejectionReason;

        private FluidPatchCheckResult(boolean allowed, String rejectionReason) {
            this.allowed = allowed;
            this.rejectionReason = rejectionReason;
        }

        private static FluidPatchCheckResult allowed() {
            return new FluidPatchCheckResult(true, null);
        }

        private static FluidPatchCheckResult rejected(String reason) {
            return new FluidPatchCheckResult(false, reason);
        }
    }
    
    /**
     * Result of site validation.
     * T057: Enhanced with detailed rejection reasons for diagnostics.
     */
    public static class ValidationResult {
        public boolean passed = false;
        public boolean foundationOk = false;
        public boolean interiorAirOk = false;
        public boolean entranceOk = false;
        public ClassificationResult classificationResult = null;
        
        /**
         * Get human-readable rejection reasons (if validation failed).
         * T057: Provides detailed diagnostics for harness parsing.
         */
        public List<String> getRejectionReasons() {
            if (classificationResult != null) {
                return classificationResult.getRejectionReasons();
            }
            return new ArrayList<>();
        }
        
        @Override
        public String toString() {
            String classStr = classificationResult != null ? ", classification: " + classificationResult : "";
            String reasonsStr = !getRejectionReasons().isEmpty() ? ", reasons=" + getRejectionReasons() : "";
            return String.format("ValidationResult{passed=%b, foundation=%b, interior=%b, entrance=%b%s%s}",
                    passed, foundationOk, interiorAirOk, entranceOk, classStr, reasonsStr);
        }
    }
}
