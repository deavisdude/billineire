package com.davisodom.villageoverhaul.worldgen;

import com.davisodom.villageoverhaul.model.VolumeMask;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.Material;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * R004: Ground-truth surface solver.
 * Builds a deterministic surface function G(x,z) by scanning down from world max height,
 * ignoring any blocks whose (x,y,z) fall inside any VolumeMask.
 */
public class SurfaceSolver {
    private final World world;
    private final List<VolumeMask> volumeMasks;
    
    // Cache for surface height G(x,z)
    // Key: (long)x << 32 | (long)z & 0xFFFFFFFFL
    // Value: Integer Y
    private final Map<Long, Integer> surfaceCache = new ConcurrentHashMap<>();
    
    // Spatial index for VolumeMasks to speed up lookups
    // Key: Chunk key (long)chunkX << 32 | (long)chunkZ & 0xFFFFFFFFL
    // Value: List of masks that overlap this chunk
    private final Map<Long, List<VolumeMask>> maskChunkIndex = new ConcurrentHashMap<>();

    public SurfaceSolver(World world, List<VolumeMask> volumeMasks) {
        this.world = world;
        this.volumeMasks = new ArrayList<>(volumeMasks);
        buildMaskIndex();
    }

    private void buildMaskIndex() {
        for (VolumeMask mask : volumeMasks) {
            int minCx = mask.getMinX() >> 4;
            int maxCx = mask.getMaxX() >> 4;
            int minCz = mask.getMinZ() >> 4;
            int maxCz = mask.getMaxZ() >> 4;

            for (int cx = minCx; cx <= maxCx; cx++) {
                for (int cz = minCz; cz <= maxCz; cz++) {
                    long key = getChunkKey(cx, cz);
                    maskChunkIndex.computeIfAbsent(key, k -> new ArrayList<>()).add(mask);
                }
            }
        }
    }

    /**
     * Find the nearest walkable Y level at (x,z) near yHint.
     * Returns y within {G(x,z)-1..G(x,z)+1} if valid and not inside any VolumeMask.
     * 
     * @param x world X
     * @param z world Z
     * @param yHint hint Y (currently unused, but kept for API compatibility)
     * @return OptionalInt containing the Y level, or empty if invalid/inside mask
     */
    public OptionalInt nearestWalkable(int x, int z, int yHint) {
        int g = getSurfaceHeight(x, z);
        
        // G(x,z) is the solid surface block. Walkable is usually G+1.
        // Validate candidate positions within {G-1..G+1} and never return a Y inside any VolumeMask.
        int[] candidates = new int[] { g + 1, g, g - 1 };
        int maxHeight = world.getMaxHeight();
        int minHeight = world.getMinHeight();
        if (maxHeight <= minHeight) {
            maxHeight = 320;
            minHeight = -64;
        }
        if (maxHeight <= minHeight) {
            maxHeight = 320;
            minHeight = -64;
        }

        for (int candidateY : candidates) {
            if (candidateY < minHeight || candidateY > maxHeight) {
                continue;
            }
            if (isInAnyMask(x, candidateY, z) || isInAnyMask(x, candidateY - 1, z)) {
                continue;
            }
            if (isWalkableAt(x, candidateY, z)) {
                return OptionalInt.of(candidateY);
            }
        }

        return OptionalInt.empty();
    }

    /**
     * Check whether a rotated footprint and its immediate sampling margin are ready
     * without triggering synchronous chunk loads.
     */
    public static boolean isFootprintReady(World world, int[] bounds) {
        if (bounds == null || bounds.length < 6) {
            return false;
        }

        int minX = bounds[0] - 1;
        int maxX = bounds[1] + 1;
        int minZ = bounds[4] - 1;
        int maxZ = bounds[5] + 1;

        int minChunkX = minX >> 4;
        int maxChunkX = maxX >> 4;
        int minChunkZ = minZ >> 4;
        int maxChunkZ = maxZ >> 4;

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (!world.isChunkLoaded(chunkX, chunkZ)) {
                    return false;
                }
            }
        }

        return true;
    }
    
    /**
     * Calculate G(x,z): the Y level of the highest solid block that is NOT inside any VolumeMask.
     * Scans down from world max height.
     */
    public int getSurfaceHeight(int x, int z) {
        long key = getCoordinateKey(x, z);
        if (surfaceCache.containsKey(key)) {
            return surfaceCache.get(key);
        }
        
        // Start scan from a reasonable height
        // We can use world.getHighestBlockYAt(x,z) as a starting point, 
        // but we must be careful if that block is inside a mask.
        // If the highest block is inside a mask, we might need to go higher?
        // No, if the highest block is inside a mask, the ground is below it.
        // So starting at highest block is fine.
        // Wait, what if there is a floating structure above the highest natural block?
        // Then getHighestBlockYAt will return the structure's top.
        // We scan down from there.
        
        int startY = world.getHighestBlockYAt(x, z) + 1;
        // Cache world bounds locally to avoid repeated mocked calls and expensive stack-walks
        int maxHeight = world.getMaxHeight();
        int minHeight = world.getMinHeight();

        // Clamp to world bounds
        startY = Math.min(startY, maxHeight);

        for (int y = startY; y >= minHeight; y--) {
            // 1. Check if (x,y,z) is inside any mask
            if (isInAnyMask(x, y, z)) {
                continue; // Ignore structure blocks
            }
            
            // 2. Check if block is solid
            // We use getType() which is generally fast enough
            // Optimization: could use ChunkSnapshot if we had access to it
            Block block = world.getBlockAt(x, y, z);
            Material type = block.getType();
            
            // Skip vegetation (we want true ground, not tree tops)
            // Skip frozen water (ice sits on water, foundation check would fail)
            if (type.isSolid() && !isVegetation(type) && !isFrozenWater(type)) {
                surfaceCache.put(key, y);
                return y;
            }
        }
        
        // Fallback to min height if nothing found
        return world.getMinHeight();
    }

    private boolean isWalkableAt(int x, int y, int z) {
        if (y <= world.getMinHeight()) {
            return false;
        }

        Block standing = world.getBlockAt(x, y, z);
        if (standing == null) {
            return false;
        }
        Material standingType = standing.getType();
        if (standingType.isSolid() || isVegetation(standingType) || isFrozenWater(standingType)
                || standingType == Material.WATER || standingType == Material.LAVA) {
            return false;
        }

        Block ground = world.getBlockAt(x, y - 1, z);
        if (ground == null) {
            return false;
        }
        Material groundType = ground.getType();
        if (!groundType.isSolid()) {
            return false;
        }
        if (isVegetation(groundType) || isFrozenWater(groundType)
                || groundType == Material.WATER || groundType == Material.LAVA) {
            return false;
        }

        return true;
    }
    
    private boolean isVegetation(Material type) {
        // Basic vegetation check to find "true" ground
        // This matches logic in PathServiceImpl.findGroundLevel
        String name = type.name();
         return name.contains("LEAVES") ||
             name.endsWith("_LOG") ||
             name.endsWith("_STEM") ||
             (name.contains("GRASS") && type != Material.GRASS_BLOCK) ||
             name.contains("FERN") ||
             type == Material.VINE ||
             type == Material.SUNFLOWER ||
             type == Material.LILAC ||
             type == Material.ROSE_BUSH ||
             type == Material.PEONY;
    }
    
    /**
     * Check if a material is a frozen water surface (ice variant).
     * These blocks should be skipped when finding ground for structure placement
     * because they're floating on water and the foundation check will fail.
     */
    private boolean isFrozenWater(Material type) {
        // Skip all ice variants - they sit on top of water
        // Building on ice would fail foundation check (water below)
        return type == Material.ICE ||
               type == Material.PACKED_ICE ||
               type == Material.BLUE_ICE ||
               type == Material.FROSTED_ICE;
    }
    
    private boolean isInAnyMask(int x, int y, int z) {
        long chunkKey = getChunkKey(x >> 4, z >> 4);
        List<VolumeMask> masks = maskChunkIndex.get(chunkKey);
        
        if (masks == null) return false;
        
        for (VolumeMask mask : masks) {
            if (mask.contains(x, y, z)) {
                return true;
            }
        }
        return false;
    }
    
    private static long getChunkKey(int cx, int cz) {
        return (long) cx & 0xFFFFFFFFL | ((long) cz & 0xFFFFFFFFL) << 32;
    }
    
    private static long getCoordinateKey(int x, int z) {
        return (long) x & 0xFFFFFFFFL | ((long) z & 0xFFFFFFFFL) << 32;
    }
}
