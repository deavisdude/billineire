package com.davisodom.villageoverhaul.commands;

import com.davisodom.villageoverhaul.VillageOverhaulPlugin;
import com.davisodom.villageoverhaul.villages.VillageMetadataStore;
import com.davisodom.villageoverhaul.worldgen.SurfaceSolver;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.logging.Logger;

/**
 * Helper class for terrain search logic used by both GenerateCommand and TickBudgetedGenerationQueue.
 * 
 * T071: Extracted from GenerateCommand to avoid circular dependencies.
 * This allows both the command handler and the async queue processor to use the same
 * validated terrain search logic.
 */
public class VillageTerrainSearcher {
    
    private static final Logger LOGGER = Logger.getLogger(VillageTerrainSearcher.class.getName());
    
    private final VillageOverhaulPlugin plugin;
    private final VillageMetadataStore metadataStore;
    
    public VillageTerrainSearcher(VillageOverhaulPlugin plugin, VillageMetadataStore metadataStore) {
        this.plugin = plugin;
        this.metadataStore = metadataStore;
    }
    
    /**
     * Search for suitable flat terrain for village placement.
     * Adapted from VillageWorldgenAdapter with similar criteria.
     * For subsequent villages, starts search beyond minVillageSpacing radius.
     * 
     * @param world Target world
     * @param start Starting search location
     * @param maxRadius Maximum search radius in blocks
     * @param minVillageSpacing Minimum spacing requirement
     * @return Suitable location or null if none found
     */
    public Location findSuitableVillageLocation(World world, Location start, int maxRadius, int minVillageSpacing) {
        LOGGER.info("[STRUCT] Searching for suitable terrain within " + maxRadius + " blocks...");
        
        int startX = start.getBlockX();
        int startZ = start.getBlockZ();
        int checkRadius = 24; // Check 24 block radius for flatness
        int sampleInterval = 24; // Check every 24 blocks in spiral
        
        // For subsequent villages, start search beyond minVillageSpacing
        boolean isFirstVillage = isFirstVillage(world);
        int startRadius = isFirstVillage ? 16 : (minVillageSpacing + 32);
        
        SurfaceSolver surfaceSolver = new SurfaceSolver(world, java.util.Collections.emptyList());

        // Spiral search pattern
        for (int radius = startRadius; radius <= Math.min(maxRadius, 512); radius += sampleInterval) {
            // Check 8 points around the circle at this radius
            for (int i = 0; i < 8; i++) {
                double angle = (i / 8.0) * 2 * Math.PI;
                int x = startX + (int)(radius * Math.cos(angle));
                int z = startZ + (int)(radius * Math.sin(angle));
                
                // Check if this location is suitable for terrain
                if (!isTerrainSuitable(world, x, z, checkRadius, surfaceSolver)) {
                    continue;
                }
                
                int y = surfaceSolver.getSurfaceHeight(x, z);
                Location candidate = new Location(world, x, y, z);
                
                // Check inter-village spacing
                if (!checkInterVillageSpacing(candidate, minVillageSpacing)) {
                    continue;
                }
                
                LOGGER.info("[STRUCT] Found suitable terrain at distance " + radius + " blocks: " +
                    "(" + x + ", " + y + ", " + z + ")");
                return candidate;
            }
        }
        
        LOGGER.warning("[STRUCT] No suitable terrain found within " + maxRadius + " blocks");
        return null;
    }
    
    /**
     * Check if terrain at location is suitable for village placement.
     * 
     * Criteria:
     * - Y variation <= 15 blocks (relatively flat)
     * - Less than 30% water coverage
     * - Height between Y 50 and Y 120 (avoid too deep or too high)
     * 
     * @param world Target world
     * @param centerX Center X coordinate
     * @param centerZ Center Z coordinate
     * @param checkRadius Radius to check around center
     * @return true if terrain is suitable
     */
    private boolean isTerrainSuitable(World world, int centerX, int centerZ, int checkRadius,
                                      SurfaceSolver surfaceSolver) {
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        int waterBlocks = 0;
        int totalChecks = 0;
        
        // Sample terrain in a grid pattern (every 12 blocks for speed)
        for (int x = -checkRadius; x <= checkRadius; x += 12) {
            for (int z = -checkRadius; z <= checkRadius; z += 12) {
                int checkX = centerX + x;
                int checkZ = centerZ + z;
                int y = surfaceSolver.getSurfaceHeight(checkX, checkZ);
                
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
                totalChecks++;
                
                // Check if surface above ground is water or frozen water (ice on top of water)
                if (isWaterOrFrozenWaterSurface(world, checkX, y, checkZ)) {
                    waterBlocks++;
                }
            }
        }
        
        int yVariation = maxY - minY;
        double waterPercent = (double) waterBlocks / totalChecks;
        
        // Apply criteria
        boolean flatEnough = yVariation <= 15;
        boolean notTooWatery = waterPercent < 0.3;
        boolean goodHeight = minY >= 50 && maxY <= 120;
        
        // T057d: Dense water proximity check to prevent selecting water-adjacent sites
        if (flatEnough && notTooWatery && goodHeight) {
            if (hasWaterInProximity(world, centerX, centerZ, 25, surfaceSolver)) {
                return false;
            }
        }
        
        return flatEnough && notTooWatery && goodHeight;
    }
    
    /**
     * T057d: Check for water blocks within proximity of center.
     * Uses a denser sampling pattern to catch water that sparse checks miss.
     * This prevents selecting sites where TerraformingPlan will veto due to nearby water.
     * 
     * @param world Target world
     * @param centerX Center X coordinate
     * @param centerZ Center Z coordinate
     * @param radius Radius to check (should cover largest structure footprint + margin)
     * @return true if water is found within proximity
     */
    private boolean hasWaterInProximity(World world, int centerX, int centerZ, int radius,
                                        SurfaceSolver surfaceSolver) {
        // Check in a cross pattern first (fast rejection)
        for (int d = -radius; d <= radius; d += 4) {
            // Check along X axis
            int y1 = surfaceSolver.getSurfaceHeight(centerX + d, centerZ);
            if (isWaterOrFrozenWaterSurface(world, centerX + d, y1, centerZ)) {
                return true;
            }
            // Check along Z axis
            int y2 = surfaceSolver.getSurfaceHeight(centerX, centerZ + d);
            if (isWaterOrFrozenWaterSurface(world, centerX, y2, centerZ + d)) {
                return true;
            }
        }
        
        // Check diagonals
        for (int d = -radius; d <= radius; d += 6) {
            int y1 = surfaceSolver.getSurfaceHeight(centerX + d, centerZ + d);
            if (isWaterOrFrozenWaterSurface(world, centerX + d, y1, centerZ + d)) {
                return true;
            }
            int y2 = surfaceSolver.getSurfaceHeight(centerX + d, centerZ - d);
            if (isWaterOrFrozenWaterSurface(world, centerX + d, y2, centerZ - d)) {
                return true;
            }
        }
        
        // Check perimeter of structure area (where TerraformingPlan margin check happens)
        int structureRadius = 20; // Covers 18-block structure + 2-block margin
        for (int x = -structureRadius; x <= structureRadius; x += 3) {
            // Top edge
            int y1 = surfaceSolver.getSurfaceHeight(centerX + x, centerZ - structureRadius);
            if (isWaterOrFrozenWaterSurface(world, centerX + x, y1, centerZ - structureRadius)) {
                return true;
            }
            // Bottom edge
            int y2 = surfaceSolver.getSurfaceHeight(centerX + x, centerZ + structureRadius);
            if (isWaterOrFrozenWaterSurface(world, centerX + x, y2, centerZ + structureRadius)) {
                return true;
            }
        }
        for (int z = -structureRadius; z <= structureRadius; z += 3) {
            // Left edge
            int y1 = surfaceSolver.getSurfaceHeight(centerX - structureRadius, centerZ + z);
            if (isWaterOrFrozenWaterSurface(world, centerX - structureRadius, y1, centerZ + z)) {
                return true;
            }
            // Right edge
            int y2 = surfaceSolver.getSurfaceHeight(centerX + structureRadius, centerZ + z);
            if (isWaterOrFrozenWaterSurface(world, centerX + structureRadius, y2, centerZ + z)) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Check if a material is water or a frozen water surface (ice variants).
     * Frozen water appears as ice on top of water - buildings placed here would
     * end up underwater because SurfaceSolver skips ice to find the actual ground.
     * 
     * @param type Material to check
     * @return true if water or any ice variant
     */
    private boolean isWaterOrFrozenWater(Material type) {
        return type == Material.WATER ||
               type == Material.ICE ||
               type == Material.PACKED_ICE ||
               type == Material.BLUE_ICE ||
               type == Material.FROSTED_ICE;
    }

    private boolean isWaterOrFrozenWaterSurface(World world, int x, int groundY, int z) {
        Material surface = world.getBlockAt(x, groundY + 1, z).getType();
        return isWaterOrFrozenWater(surface);
    }
    
    /**
     * Check if this is the first village in the world.
     * 
     * @param world Target world
     * @return true if no villages exist in this world yet
     */
    public boolean isFirstVillage(World world) {
        for (VillageMetadataStore.VillageMetadata village : metadataStore.getAllVillages()) {
            if (village.getOrigin().getWorld().equals(world)) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Find the nearest existing village location.
     * Used for nearest-neighbor bias (Constitution v1.5.0, Principle XII).
     * 
     * @param world Target world
     * @param searchOrigin Current search origin
     * @return Location of nearest village, or null if no villages exist
     */
    public Location findNearestVillageLocation(World world, Location searchOrigin) {
        Location nearest = null;
        int minDistance = Integer.MAX_VALUE;
        
        for (VillageMetadataStore.VillageMetadata village : metadataStore.getAllVillages()) {
            if (!village.getOrigin().getWorld().equals(world)) {
                continue;
            }
            
            Location villageOrigin = village.getOrigin();
            int dx = Math.abs(searchOrigin.getBlockX() - villageOrigin.getBlockX());
            int dz = Math.abs(searchOrigin.getBlockZ() - villageOrigin.getBlockZ());
            int distance = dx + dz; // Manhattan distance
            
            if (distance < minDistance) {
                minDistance = distance;
                nearest = villageOrigin;
            }
        }
        
        return nearest;
    }
    
    /**
     * Check if proposed village location violates minimum inter-village spacing.
     * Used in GenerateCommand pre-check (before placeVillage() is called).
     * 
     * @param proposedOrigin Proposed village origin
     * @param minVillageSpacing Minimum spacing requirement (border-to-border)
     * @return true if spacing is acceptable, false if violated
     */
    private boolean checkInterVillageSpacing(Location proposedOrigin, int minVillageSpacing) {
        World world = proposedOrigin.getWorld();
        
        // Create temporary border for proposed location (initial size before any buildings)
        VillageMetadataStore.VillageBorder proposedBorder = new VillageMetadataStore.VillageBorder(
            proposedOrigin.getBlockX(), proposedOrigin.getBlockX(),
            proposedOrigin.getBlockZ(), proposedOrigin.getBlockZ());
        
        // Check against all existing villages in same world
        for (VillageMetadataStore.VillageMetadata existingVillage : metadataStore.getAllVillages()) {
            if (!existingVillage.getOrigin().getWorld().equals(world)) {
                continue;
            }
            
            VillageMetadataStore.VillageBorder existingBorder = existingVillage.getBorder();
            int distance = proposedBorder.getDistanceTo(existingBorder);
            
            if (distance < minVillageSpacing) {
                LOGGER.fine(String.format("[STRUCT] Rejecting site at %s: distance %d to village %s violates minVillageSpacing=%d",
                    formatLocation(proposedOrigin), distance, existingVillage.getVillageId(), minVillageSpacing));
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * Format location for logging.
     */
    private String formatLocation(Location loc) {
        return String.format("(%d, %d, %d)", loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }
}
