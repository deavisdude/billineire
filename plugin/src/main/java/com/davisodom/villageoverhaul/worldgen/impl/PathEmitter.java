package com.davisodom.villageoverhaul.worldgen.impl;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;

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
 * - Ensure solid foundation below path blocks
 * - Avoid excessive block spam or unnatural boardwalks
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
     * @param world The world to place blocks in
     * @param pathBlocks Ordered list of blocks forming the path (at terrain height)
     * @param cultureId Culture identifier for material selection (e.g., "roman")
     * @return Number of blocks placed (including foundation fixes)
     */
    public int emitPath(World world, List<Block> pathBlocks, String cultureId) {
        if (pathBlocks.isEmpty()) {
            return 0;
        }
        
        // Determine path material based on culture
        Material pathMaterial = getPathMaterial(cultureId);
        
        int blocksPlaced = 0;
        for (Block pathBlock : pathBlocks) {
            // Get the actual ground level at this X,Z position
            int groundY = world.getHighestBlockYAt(pathBlock.getX(), pathBlock.getZ());
            
            // REPLACE the surface block with path material (at groundY, not groundY+1)
            Block pathSurface = world.getBlockAt(pathBlock.getX(), groundY, pathBlock.getZ());
            pathSurface.setType(pathMaterial);
            blocksPlaced++;
            
            // Ensure solid foundation below path
            Block foundation = world.getBlockAt(pathBlock.getX(), groundY - 1, pathBlock.getZ());
            if (!foundation.getType().isSolid()) {
                foundation.setType(Material.DIRT);
                blocksPlaced++;
            }
            
            // Ensure air above path (2 blocks for player clearance)
            Block airAbove1 = world.getBlockAt(pathBlock.getX(), groundY + 1, pathBlock.getZ());
            Block airAbove2 = world.getBlockAt(pathBlock.getX(), groundY + 2, pathBlock.getZ());
            if (!airAbove1.getType().isAir()) {
                airAbove1.setType(Material.AIR);
            }
            if (!airAbove2.getType().isAir()) {
                airAbove2.setType(Material.AIR);
            }
        }
        
        LOGGER.fine(String.format("[STRUCT] Path emitted: culture=%s, blocks=%d, material=%s",
                cultureId, blocksPlaced, pathMaterial));
        
        return blocksPlaced;
    }
    
    /**
     * Apply minimal smoothing to path with stairs and slabs.
     * 
     * Smoothing rules:
     * - Single-block elevation changes → stairs
     * - Flat sections → occasional slabs for variation
     * - Avoid over-smoothing to maintain natural appearance
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
     * @return Total number of blocks placed and smoothed
     */
    public int emitPathWithSmoothing(World world, List<Block> pathBlocks, String cultureId) {
        int placed = emitPath(world, pathBlocks, cultureId);
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
