package com.davisodom.villageoverhaul.worldgen;

import com.davisodom.villageoverhaul.model.VolumeMask;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.*;

/**
 * R005: Walkable graph and obstacle field.
 * Defines the walkable nodes and connections for pathfinding.
 * Nodes exist only at y = G(x,z) ± 1 (via SurfaceSolver).
 * Obstacles are VolumeMask.expand(buffer=2) and fluids.
 */
public class WalkableGraph {
    private final SurfaceSolver surfaceSolver;
    private final List<VolumeMask> obstacles;
    private final int buffer;

    public WalkableGraph(SurfaceSolver surfaceSolver, List<VolumeMask> volumeMasks, int buffer) {
        this.surfaceSolver = surfaceSolver;
        this.buffer = buffer;
        this.obstacles = new ArrayList<>();
        
        // Pre-expand masks for obstacle checking
        for (VolumeMask mask : volumeMasks) {
            this.obstacles.add(mask.expand(buffer));
        }
    }

    /**
     * Check if a node at (x,y,z) is valid (walkable).
     * 1. Must be at valid surface height (G(x,z) ± 1).
     * 2. Must not be inside any expanded VolumeMask.
     * 3. (Implicit) Must not be fluid (handled by SurfaceSolver/PathService cost).
     */
    public boolean isWalkable(int x, int y, int z) {
        // Check obstacles first (fastest fail)
        if (isObstacle(x, y, z)) {
            return false;
        }

        // Check surface alignment
        // We allow y to be G(x,z) - 1, G(x,z), or G(x,z) + 1
        // Actually, SurfaceSolver.nearestWalkable returns the ideal walking Y.
        // But A* might explore neighbors slightly up/down.
        // The requirement says "Nodes exist only at y = G(x,z) ± 1".
        // This means if we are at y, we check if G(x,z) is close to y.
        
        int g = surfaceSolver.getSurfaceHeight(x, z);
        // G is the solid block. Walkable space is G+1.
        // So valid node Y should be (G+1) ± 1 => G, G+1, G+2?
        // "Nodes exist only at y = G(x,z) ± 1" likely refers to the surface height G.
        // If G is the ground Y, then the node (foot position) is usually G+1.
        // So node Y should be within [G, G+2].
        
        // Let's interpret "y = G(x,z) ± 1" as the node Y relative to the surface Y.
        // If G(x,z) returns the Y of the solid block, then the space above it is G+1.
        // So we expect node Y to be G+1.
        // Allowing ±1 means G, G+1, G+2.
        
        return Math.abs(y - (g + 1)) <= 1;
    }

    /**
     * Check if (x,y,z) is inside any obstacle (expanded VolumeMask).
     */
    public boolean isObstacle(int x, int y, int z) {
        for (VolumeMask mask : obstacles) {
            if (mask.contains(x, y, z)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get valid neighbors for a node.
     * Returns list of {x, y, z} arrays.
     */
    public List<int[]> getNeighbors(int x, int y, int z) {
        List<int[]> neighbors = new ArrayList<>();
        
        // 8 horizontal neighbors
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                
                int nx = x + dx;
                int nz = z + dz;
                
                // For each horizontal neighbor, check vertical range
                // We want to find the valid surface at (nx, nz)
                // Instead of probing all Ys, we ask SurfaceSolver for the ground
                OptionalInt ground = surfaceSolver.nearestWalkable(nx, nz, y);
                
                if (ground.isPresent()) {
                    int ny = ground.getAsInt();
                    // Check vertical connectivity (step height)
                    if (Math.abs(ny - y) <= 1) {
                        // Check if this specific node is obstructed
                        if (!isObstacle(nx, ny, nz)) {
                            neighbors.add(new int[]{nx, ny, nz});
                        }
                    }
                }
            }
        }
        return neighbors;
    }
}
