package com.example.villageoverhaul.worldgen;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for generating path networks between village buildings.
 * Uses A* pathfinding on 2D heightmap with terrain-aware costs.
 */
public interface PathService {
    
    /**
     * Generate a path network connecting key buildings in a village.
     * Paths should connect the main building to all other structures.
     * 
     * @param world Target world
     * @param villageId Village UUID for metadata association
     * @param buildingLocations List of building origins to connect
     * @param mainBuildingLocation Main building origin (path hub)
     * @param seed Deterministic seed for path generation
     * @return true if path network generated successfully
     */
    boolean generatePathNetwork(World world, UUID villageId, List<Location> buildingLocations, 
                                Location mainBuildingLocation, long seed);
    
    /**
     * Generate a single path between two locations.
     * Uses A* pathfinding with terrain-aware cost function.
     * 
     * @param world Target world
     * @param start Path start location
     * @param end Path end location
     * @param seed Deterministic seed for path variations
     * @return List of blocks forming the path, empty if no path found
     */
    Optional<List<Block>> generatePath(World world, Location start, Location end, long seed);
    
    /**
     * Place path blocks along a computed route.
     * Uses appropriate blocks (dirt path, cobblestone, etc.) based on culture.
     * Smooths terrain with stairs/slabs for gentle slopes.
     * 
     * @param world Target world
     * @param pathBlocks List of path block locations
     * @param cultureId Culture identifier for path style
     * @return Number of blocks placed
     */
    int placePath(World world, List<Block> pathBlocks, String cultureId);
    
    /**
     * Smooth terrain along path with stairs/slabs for natural elevation changes.
     * 
     * @param world Target world
     * @param pathBlocks Path block locations
     * @return Number of blocks modified for smoothing
     */
    int smoothPath(World world, List<Block> pathBlocks);
    
    /**
     * Calculate terrain-aware cost for A* pathfinding.
     * Higher cost for steep slopes, water, obstacles.
     * 
     * @param world Target world
     * @param from Current location
     * @param to Neighbor location
     * @return Movement cost (higher = less desirable)
     */
    double calculateTerrainCost(World world, Location from, Location to);
    
    /**
     * Get the path network for a specific village.
     * 
     * @param villageId Village UUID
     * @return List of path segments (each segment is a list of blocks)
     */
    List<List<Block>> getVillagePathNetwork(UUID villageId);
    
    /**
     * Check if two buildings are connected by paths.
     * 
     * @param villageId Village UUID
     * @param buildingA First building location
     * @param buildingB Second building location
     * @return true if path exists between buildings
     */
    boolean areConnected(UUID villageId, Location buildingA, Location buildingB);
    
    /**
     * Calculate path connectivity percentage for a village.
     * Success criterion: ≥90% of buildings connected to main building.
     * 
     * @param villageId Village UUID
     * @return Connectivity percentage (0.0-1.0)
     */
    double calculateConnectivity(UUID villageId);
}
