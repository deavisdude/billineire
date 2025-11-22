package com.davisodom.villageoverhaul.worldgen.impl;

import com.davisodom.villageoverhaul.model.VolumeMask;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * PathEmitter handles physical placement of path blocks with culture-specific materials
 * and minimal smoothing (stairs/slabs) for natural terrain integration.
 * 
 * Responsibilities:
 * - Place path blocks with appropriate materials based on culture
 * - Apply minimal smoothing with stairs for single-block elevation changes
 * - Add slabs for subtle half-block transitions
 * - Ensure solid foundation below path blocks (R008: check support, don't force it)
 * - Avoid excessive block spam or unnatural boardwalks
 * - R008: Check against VolumeMasks to prevent placement inside structures
 * 
 * Design: Extracted from PathServiceImpl to separate path generation (A*) from
 * physical block placement concerns, enabling independent testing and reuse.
 */
public class PathEmitter {
    
    private static final Logger LOGGER = Logger.getLogger(PathEmitter.class.getName());
    
    /**
     * Place path blocks in the world with culture-specific materials.
     * Paths REPLACE the top surface block and are surrounded by terrain on sides.
     * 
     * R008: Place path blocks only when the block below is solid natural ground 
     * and the target is not inside any VolumeMask.
     * 
     * @param world The world to place blocks in
     * @param pathBlocks Ordered list of blocks forming the path (at terrain height)
     * @param cultureId Culture identifier for material selection (e.g., "roman")
     * @param masks List of VolumeMasks to avoid
     * @return Number of blocks placed
     */
    public int emitPath(World world, List<Block> pathBlocks, String cultureId, List<VolumeMask> masks) {
        if (pathBlocks.isEmpty()) {
            return 0;
        }
        
        // Determine path material based on culture
        Material pathMaterial = getPathMaterial(cultureId);
        
        int blocksPlaced = 0;
        List<Block> successfullyPlacedBlocks = new ArrayList<>();

        for (Block pathBlock : pathBlocks) {
            int x = pathBlock.getX();
            int z = pathBlock.getZ();
            // Get the actual ground level at this X,Z position
            int groundY = world.getHighestBlockYAt(x, z);
            
            // Check if target is inside any VolumeMask
            if (isInsideAnyMask(masks, x, groundY, z)) {
                continue;
            }

            // Check support: block below must be solid natural ground
            Block foundation = world.getBlockAt(x, groundY - 1, z);
            if (!foundation.getType().isSolid()) {
                // R008: Never place when support is missing
                continue;
            }
            
            // REPLACE the surface block with path material (at groundY)
            Block pathSurface = world.getBlockAt(x, groundY, z);
            pathSurface.setType(pathMaterial);
            blocksPlaced++;
            successfullyPlacedBlocks.add(pathSurface);
            
            // Ensure air above path (2 blocks for player clearance)
            // Only clear if not inside a mask (though we checked target, check above too?)
            // Assuming masks cover structures, we shouldn't be under them if we are not inside them?
            // But let's be safe and check masks for air blocks too if needed, or just assume safe.
            // R008 says "target is not inside any VolumeMask".
            
            Block airAbove1 = world.getBlockAt(x, groundY + 1, z);
            if (!isInsideAnyMask(masks, x, groundY + 1, z) && !airAbove1.getType().isAir()) {
                airAbove1.setType(Material.AIR);
            }
            
            Block airAbove2 = world.getBlockAt(x, groundY + 2, z);
            if (!isInsideAnyMask(masks, x, groundY + 2, z) && !airAbove2.getType().isAir()) {
                airAbove2.setType(Material.AIR);
            }
        }
        
        // R008: Apply simple widening after emission
        blocksPlaced += widenPath(world, successfullyPlacedBlocks, pathMaterial, masks);
        
        LOGGER.fine(String.format("[STRUCT] Path emitted: culture=%s, blocks=%d, material=%s",
                cultureId, blocksPlaced, pathMaterial));
        
        return blocksPlaced;
    }
    
    /**
     * Check if a point is inside any VolumeMask.
     */
    private boolean isInsideAnyMask(List<VolumeMask> masks, int x, int y, int z) {
        if (masks == null || masks.isEmpty()) {
            return false;
        }
        for (VolumeMask mask : masks) {
            if (mask.contains(x, y, z)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Apply simple widening to the path.
     * Adds adjacent blocks if supported and not obstructed.
     */
    private int widenPath(World world, List<Block> pathBlocks, Material pathMaterial, List<VolumeMask> masks) {
        int widened = 0;
        // Simple widening: for each path block, try to place path material on random adjacent blocks
        // or just make it 2-wide in some places?
        // "Apply simple widening after emission" - let's try to make it slightly irregular but wider.
        // Iterate and add neighbors with some probability? Or just deterministic widening?
        // Let's do deterministic 1-block widening for now to ensure walkability, 
        // but maybe skip some to look natural?
        // R008 doesn't specify "natural", just "simple widening".
        // Let's add neighbors if they are at same Y and supported.
        
        List<Block> toWiden = new ArrayList<>();
        
        for (Block block : pathBlocks) {
            // Check 4 neighbors
            int[][] neighbors = {{1,0}, {-1,0}, {0,1}, {0,-1}};
            for (int[] offset : neighbors) {
                int nx = block.getX() + offset[0];
                int nz = block.getZ() + offset[1];
                int y = block.getY();
                
                if (isInsideAnyMask(masks, nx, y, nz)) continue;
                
                Block neighbor = world.getBlockAt(nx, y, nz);
                Block neighborBelow = world.getBlockAt(nx, y - 1, nz);
                
                // Only widen if neighbor is not already path, is solid/replaceable, and has support
                if (neighbor.getType() != pathMaterial && 
                    !neighbor.getType().isSolid() && // Replace non-solid (air/grass)
                    neighborBelow.getType().isSolid()) {
                    
                    // Avoid widening into masks
                    if (!isInsideAnyMask(masks, nx, y, nz)) {
                         toWiden.add(neighbor);
                    }
                }
            }
        }
        
        // Apply widening
        for (Block block : toWiden) {
            // Double check to avoid overwriting if multiple sources claimed it
            if (block.getType() != pathMaterial) {
                block.setType(pathMaterial);
                widened++;
                
                // Clear air above widened blocks too
                Block above1 = block.getRelative(BlockFace.UP);
                if (!isInsideAnyMask(masks, above1.getX(), above1.getY(), above1.getZ()) && !above1.getType().isAir()) {
                    above1.setType(Material.AIR);
                }
            }
        }
        
        return widened;
    }
    
    /**
     * Apply minimal smoothing to path with stairs and slabs.
     * 
     * Smoothing rules:
     * - Single-block elevation changes → stairs
     * - Flat sections → occasional slabs for variation
     * - Avoid over-smoothing to maintain natural appearance
     * - R008: Never place slabs/stairs when support is missing
     * 
     * @param world The world containing the path
     * @param pathBlocks Ordered list of blocks forming the path
     * @param cultureId Culture identifier for material selection
     * @return Number of blocks smoothed
     */
    public int smoothPath(World world, List<Block> pathBlocks, String cultureId) {
        if (pathBlocks.size() < 2) {
            return 0;
        }
        
        int blocksSmoothed = 0;
        
        for (int i = 1; i < pathBlocks.size() - 1; i++) {
            Block current = pathBlocks.get(i);
            Block prev = pathBlocks.get(i - 1);
            Block next = pathBlocks.get(i + 1);
            
            // R008: Check support before smoothing
            Block below = current.getRelative(BlockFace.DOWN);
            if (!below.getType().isSolid()) {
                continue;
            }
            
            int yDiffPrev = current.getY() - prev.getY();
            int yDiffNext = next.getY() - current.getY();
            
            // Add stairs for single-block elevation changes
            if (Math.abs(yDiffPrev) == 1 || Math.abs(yDiffNext) == 1) {
                if (tryPlaceStairs(current, prev, next, cultureId)) {
                    blocksSmoothed++;
                }
            }
            // Add slabs for half-block smoothing on flat sections
            else if (Math.abs(yDiffPrev) == 0 && Math.abs(yDiffNext) == 0) {
                // Only place slabs occasionally (every 5th block) to avoid block spam
                if (i % 5 == 0) {
                    if (tryPlaceSlab(current, cultureId)) {
                        blocksSmoothed++;
                    }
                }
            }
        }
        
        LOGGER.fine(String.format("[STRUCT] Path smoothed: culture=%s, blocks=%d", 
                cultureId, blocksSmoothed));
        
        return blocksSmoothed;
    }
    
    /**
     * Emit path with integrated smoothing in a single pass.
     * 
     * @param world The world to place blocks in
     * @param pathBlocks Ordered list of blocks forming the path
     * @param cultureId Culture identifier for material selection
     * @param masks List of VolumeMasks to avoid
     * @return Total number of blocks placed and smoothed
     */
    public int emitPathWithSmoothing(World world, List<Block> pathBlocks, String cultureId, List<VolumeMask> masks) {
        int placed = emitPath(world, pathBlocks, cultureId, masks);
        int smoothed = smoothPath(world, pathBlocks, cultureId);
        
        LOGGER.fine(String.format("[STRUCT] Path complete: culture=%s, placed=%d, smoothed=%d",
                cultureId, placed, smoothed));
        
        return placed + smoothed;
    }
    
    /**
     * Get culture-appropriate path material.
     * 
     * Material selection rules:
     * - Roman culture → cobblestone roads
     * - Default → dirt paths
     * 
     * @param cultureId Culture identifier (null-safe)
     * @return Block material for the path
     */
    private Material getPathMaterial(String cultureId) {
        if (cultureId == null) {
            return Material.DIRT_PATH;
        }
        
        // Roman culture uses cobblestone roads
        if (cultureId.toLowerCase().contains("roman")) {
            return Material.COBBLESTONE;
        }
        
        // Default to dirt path
        return Material.DIRT_PATH;
    }
    
    /**
     * Try to place stairs at location based on elevation change direction.
     * 
     * @param current The block to place stairs at
     * @param prev Previous block in path
     * @param next Next block in path
     * @param cultureId Culture identifier for material selection
     * @return true if stairs were placed successfully
     */
    private boolean tryPlaceStairs(Block current, Block prev, Block next, String cultureId) {
        // Determine stair direction based on elevation change
        BlockFace facing = getStairDirection(current, prev, next);
        if (facing == null) {
            return false;
        }
        
        // Select stair material based on culture
        Material stairMaterial = getStairMaterial(current.getType(), cultureId);
        current.setType(stairMaterial);
        
        // Set stair direction
        if (current.getBlockData() instanceof Stairs) {
            Stairs stairs = (Stairs) current.getBlockData();
            stairs.setFacing(facing);
            current.setBlockData(stairs);
            return true;
        }
        
        return false;
    }
    
    /**
     * Determine stair facing direction based on neighboring blocks.
     * 
     * @param current Current block position
     * @param prev Previous block in path
     * @param next Next block in path
     * @return BlockFace direction for stairs, or null if indeterminate
     */
    private BlockFace getStairDirection(Block current, Block prev, Block next) {
        int dx = next.getX() - current.getX();
        int dz = next.getZ() - current.getZ();
        
        if (dx > 0) return BlockFace.EAST;
        if (dx < 0) return BlockFace.WEST;
        if (dz > 0) return BlockFace.SOUTH;
        if (dz < 0) return BlockFace.NORTH;
        
        return null;
    }
    
    /**
     * Try to place slab at location for subtle elevation variation.
     * 
     * @param current The block to place slab at
     * @param cultureId Culture identifier for material selection
     * @return true if slab was placed successfully
     */
    private boolean tryPlaceSlab(Block current, String cultureId) {
        // Select slab material based on culture
        Material slabMaterial = getSlabMaterial(current.getType(), cultureId);
        current.setType(slabMaterial);
        
        if (current.getBlockData() instanceof Slab) {
            Slab slab = (Slab) current.getBlockData();
            slab.setType(Slab.Type.BOTTOM);
            current.setBlockData(slab);
            return true;
        }
        
        return false;
    }
    
    /**
     * Get culture-appropriate stair material.
     * 
     * @param currentMaterial Current path block material
     * @param cultureId Culture identifier
     * @return Stair material matching the culture
     */
    private Material getStairMaterial(Material currentMaterial, String cultureId) {
        // Roman culture uses cobblestone stairs
        if (cultureId != null && cultureId.toLowerCase().contains("roman")) {
            return Material.COBBLESTONE_STAIRS;
        }
        
        // Match existing path material if possible
        if (currentMaterial == Material.COBBLESTONE) {
            return Material.COBBLESTONE_STAIRS;
        }
        
        // Default to stone brick stairs for dirt paths
        return Material.STONE_BRICK_STAIRS;
    }
    
    /**
     * Get culture-appropriate slab material.
     * 
     * @param currentMaterial Current path block material
     * @param cultureId Culture identifier
     * @return Slab material matching the culture
     */
    private Material getSlabMaterial(Material currentMaterial, String cultureId) {
        // Roman culture uses cobblestone slabs
        if (cultureId != null && cultureId.toLowerCase().contains("roman")) {
            return Material.COBBLESTONE_SLAB;
        }
        
        // Match existing path material if possible
        if (currentMaterial == Material.COBBLESTONE) {
            return Material.COBBLESTONE_SLAB;
        }
        
        // Default to stone brick slabs for dirt paths
        return Material.STONE_BRICK_SLAB;
    }
}
