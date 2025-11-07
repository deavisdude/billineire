package com.davisodom.villageoverhaul.worldgen.impl;

import com.davisodom.villageoverhaul.model.PathNetwork;
import com.davisodom.villageoverhaul.worldgen.PathService;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;

import java.util.*;
import java.util.logging.Logger;

/**
 * Implementation of PathService using A* pathfinding on 2D heightmap.
 * 
 * Features:
 * - Terrain-aware cost function (penalizes steep slopes, water, obstacles)
 * - Minimal path smoothing with stairs/slabs for gentle elevation changes
 * - Deterministic path generation from seeds
 * - Culture-specific path materials (Roman cobblestone roads, etc.)
 * 
 * Performance:
 * - 2D search space (X,Z only, Y determined by heightmap)
 * - Configurable search limits to prevent runaway pathfinding
 * - Path caching per village
 */
public class PathServiceImpl implements PathService {
    
    private static final Logger LOGGER = Logger.getLogger(PathServiceImpl.class.getName());
    
    // Maximum pathfinding search distance (blocks)
    private static final int MAX_SEARCH_DISTANCE = 200;
    
    // Maximum nodes to explore in A* search
    private static final int MAX_NODES_EXPLORED = 5000;
    
    // Terrain cost multipliers
    private static final double FLAT_COST = 1.0;
    private static final double SLOPE_COST_MULTIPLIER = 2.0; // Per block of elevation change
    private static final double WATER_COST = 10.0;
    private static final double OBSTACLE_COST = 20.0;
    private static final double MAX_ACCEPTABLE_SLOPE = 3.0; // blocks per horizontal distance
    
    // Path network cache (villageId -> PathNetwork)
    private final Map<UUID, PathNetwork> pathNetworks = new HashMap<>();
    
    @Override
    public boolean generatePathNetwork(World world, UUID villageId, List<Location> buildingLocations,
                                       Location mainBuildingLocation, long seed) {
        LOGGER.info(String.format("[STRUCT] Begin path network generation: village=%s, buildings=%d, seed=%d",
                villageId, buildingLocations.size(), seed));
        
        if (buildingLocations.isEmpty()) {
            LOGGER.warning("[STRUCT] No buildings to connect");
            return false;
        }
        
        PathNetwork.Builder networkBuilder = new PathNetwork.Builder()
                .villageId(villageId)
                .generatedTimestamp(System.currentTimeMillis());
        
        // Connect main building to all other buildings
        int successfulPaths = 0;
        for (int i = 0; i < buildingLocations.size(); i++) {
            Location building = buildingLocations.get(i);
            
            // Skip main building
            if (building.distance(mainBuildingLocation) < 5) {
                continue;
            }
            
            // Generate path with building-specific seed
            long pathSeed = seed + i;
            Optional<List<Block>> pathBlocks = generatePath(world, mainBuildingLocation, building, pathSeed);
            
            if (pathBlocks.isPresent() && !pathBlocks.get().isEmpty()) {
                PathNetwork.PathSegment segment = new PathNetwork.PathSegment(
                        mainBuildingLocation, building, pathBlocks.get());
                networkBuilder.addSegment(segment);
                successfulPaths++;
                
                LOGGER.fine(String.format("[STRUCT] Path generated: from main to building %d, blocks=%d",
                        i, pathBlocks.get().size()));
            } else {
                LOGGER.warning(String.format("[STRUCT] Failed to generate path from main to building %d", i));
            }
        }
        
        if (successfulPaths == 0) {
            LOGGER.warning(String.format("[STRUCT] Path network generation failed: no paths created for village %s", villageId));
            return false;
        }
        
        // Cache the network
        PathNetwork network = networkBuilder.build();
        pathNetworks.put(villageId, network);
        
        double connectivity = network.calculateConnectivity(buildingLocations, mainBuildingLocation);
        LOGGER.info(String.format("[STRUCT] Path network complete: village=%s, paths=%d, blocks=%d, connectivity=%.1f%%",
                villageId, successfulPaths, network.getTotalBlocksPlaced(), connectivity * 100));
        
        return connectivity >= 0.9; // Success criterion: ≥90% connectivity
    }
    
    @Override
    public Optional<List<Block>> generatePath(World world, Location start, Location end, long seed) {
        // Validate distance
        double distance = start.distance(end);
        if (distance > MAX_SEARCH_DISTANCE) {
            LOGGER.warning(String.format("[STRUCT] Path distance too far: %.1f blocks (max %d)",
                    distance, MAX_SEARCH_DISTANCE));
            return Optional.empty();
        }
        
        if (distance < 3) {
            LOGGER.fine("[STRUCT] Path too short, skipping");
            return Optional.empty();
        }
        
        // Run A* pathfinding on 2D heightmap
        List<PathNode> path = findPathAStar(world, start, end);
        
        if (path == null || path.isEmpty()) {
            LOGGER.fine(String.format("[STRUCT] No path found from (%d,%d,%d) to (%d,%d,%d)",
                    start.getBlockX(), start.getBlockY(), start.getBlockZ(),
                    end.getBlockX(), end.getBlockY(), end.getBlockZ()));
            return Optional.empty();
        }
        
        // Convert path nodes to blocks
        List<Block> pathBlocks = new ArrayList<>();
        for (PathNode node : path) {
            pathBlocks.add(world.getBlockAt(node.x, node.y, node.z));
        }
        
        LOGGER.fine(String.format("[STRUCT] Path found: distance=%.1f, blocks=%d", distance, pathBlocks.size()));
        
        return Optional.of(pathBlocks);
    }
    
    /**
     * A* pathfinding on 2D heightmap.
     * Returns list of path nodes from start to end, or null if no path found.
     */
    private List<PathNode> findPathAStar(World world, Location start, Location end) {
        PriorityQueue<PathNode> openSet = new PriorityQueue<>(Comparator.comparingDouble(n -> n.fScore));
        Set<String> closedSet = new HashSet<>();
        Map<String, PathNode> allNodes = new HashMap<>();
        
        int startX = start.getBlockX();
        int startZ = start.getBlockZ();
        int startY = world.getHighestBlockYAt(startX, startZ);
        
        int endX = end.getBlockX();
        int endZ = end.getBlockZ();
        // endY stored for potential future use in 3D pathfinding
        // int endY = world.getHighestBlockYAt(endX, endZ);
        
        PathNode startNode = new PathNode(startX, startY, startZ);
        startNode.gScore = 0;
        startNode.fScore = heuristic(startX, startZ, endX, endZ);
        
        openSet.add(startNode);
        allNodes.put(startNode.key(), startNode);
        
        int nodesExplored = 0;
        
        while (!openSet.isEmpty() && nodesExplored < MAX_NODES_EXPLORED) {
            PathNode current = openSet.poll();
            nodesExplored++;
            
            // Check if we reached the goal
            if (Math.abs(current.x - endX) <= 2 && Math.abs(current.z - endZ) <= 2) {
                return reconstructPath(current);
            }
            
            closedSet.add(current.key());
            
            // Explore neighbors (8 directions)
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    
                    int neighborX = current.x + dx;
                    int neighborZ = current.z + dz;
                    
                    // Get height at neighbor position
                    int neighborY = world.getHighestBlockYAt(neighborX, neighborZ);
                    
                    String neighborKey = neighborX + "," + neighborZ;
                    if (closedSet.contains(neighborKey)) {
                        continue;
                    }
                    
                    // Calculate movement cost
                    Location fromLoc = new Location(world, current.x, current.y, current.z);
                    Location toLoc = new Location(world, neighborX, neighborY, neighborZ);
                    double movementCost = calculateTerrainCost(world, fromLoc, toLoc);
                    
                    // Skip if terrain is impassable
                    if (movementCost >= OBSTACLE_COST) {
                        continue;
                    }
                    
                    double tentativeGScore = current.gScore + movementCost;
                    
                    PathNode neighbor = allNodes.get(neighborKey);
                    if (neighbor == null) {
                        neighbor = new PathNode(neighborX, neighborY, neighborZ);
                        neighbor.gScore = Double.POSITIVE_INFINITY;
                        allNodes.put(neighborKey, neighbor);
                    }
                    
                    if (tentativeGScore < neighbor.gScore) {
                        neighbor.parent = current;
                        neighbor.gScore = tentativeGScore;
                        neighbor.fScore = tentativeGScore + heuristic(neighborX, neighborZ, endX, endZ);
                        
                        openSet.remove(neighbor); // Re-add with updated priority
                        openSet.add(neighbor);
                    }
                }
            }
        }
        
        LOGGER.fine(String.format("[STRUCT] A* search exhausted: explored=%d nodes", nodesExplored));
        return null; // No path found
    }
    
    /**
     * Heuristic function for A* (Manhattan distance).
     */
    private double heuristic(int x1, int z1, int x2, int z2) {
        return Math.abs(x1 - x2) + Math.abs(z1 - z2);
    }
    
    /**
     * Reconstruct path from end node by following parent pointers.
     */
    private List<PathNode> reconstructPath(PathNode endNode) {
        List<PathNode> path = new ArrayList<>();
        PathNode current = endNode;
        
        while (current != null) {
            path.add(current);
            current = current.parent;
        }
        
        Collections.reverse(path);
        return path;
    }
    
    @Override
    public double calculateTerrainCost(World world, Location from, Location to) {
        int yDiff = Math.abs(to.getBlockY() - from.getBlockY());
        double horizontalDist = Math.sqrt(
                Math.pow(to.getX() - from.getX(), 2) + 
                Math.pow(to.getZ() - from.getZ(), 2));
        
        // Base cost
        double cost = FLAT_COST;
        
        // Add slope cost
        if (yDiff > 0) {
            double slope = yDiff / horizontalDist;
            if (slope > MAX_ACCEPTABLE_SLOPE) {
                return OBSTACLE_COST; // Too steep
            }
            cost += yDiff * SLOPE_COST_MULTIPLIER;
        }
        
        // Check for water or lava
        Block block = world.getBlockAt(to);
        Material type = block.getType();
        
        if (type == Material.WATER || type == Material.LAVA) {
            cost += WATER_COST;
        }
        
        // Check for solid obstacles
        if (type.isSolid() && type != Material.GRASS_BLOCK && type != Material.DIRT && 
            type != Material.STONE && type != Material.SAND) {
            return OBSTACLE_COST;
        }
        
        return cost;
    }
    
    @Override
    public int placePath(World world, List<Block> pathBlocks, String cultureId) {
        if (pathBlocks.isEmpty()) {
            return 0;
        }
        
        // Determine path material based on culture
        Material pathMaterial = getPathMaterial(cultureId);
        
        int blocksPlaced = 0;
        for (Block block : pathBlocks) {
            // Place path on top of ground
            Block ground = block.getRelative(BlockFace.DOWN);
            
            // Set path block
            block.setType(pathMaterial);
            blocksPlaced++;
            
            // Ensure solid foundation
            if (!ground.getType().isSolid()) {
                ground.setType(Material.DIRT);
            }
        }
        
        // Smooth path after placement
        blocksPlaced += smoothPath(world, pathBlocks);
        
        LOGGER.fine(String.format("[STRUCT] Path placed: culture=%s, blocks=%d, material=%s",
                cultureId, blocksPlaced, pathMaterial));
        
        return blocksPlaced;
    }
    
    /**
     * Get culture-appropriate path material.
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
    
    @Override
    public int smoothPath(World world, List<Block> pathBlocks) {
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
                if (tryPlaceStairs(current, prev, next)) {
                    blocksSmoothed++;
                }
            }
            // Add slabs for half-block smoothing
            else if (Math.abs(yDiffPrev) == 0 && Math.abs(yDiffNext) == 0) {
                if (tryPlaceSlab(current)) {
                    blocksSmoothed++;
                }
            }
        }
        
        return blocksSmoothed;
    }
    
    /**
     * Try to place stairs at location based on elevation change direction.
     */
    private boolean tryPlaceStairs(Block current, Block prev, Block next) {
        // Determine stair direction based on elevation change
        BlockFace facing = getStairDirection(current, prev, next);
        if (facing == null) {
            return false;
        }
        
        // Use cobblestone stairs for Roman paths, stone brick stairs otherwise
        Material stairMaterial = Material.COBBLESTONE_STAIRS;
        if (current.getType() == Material.DIRT_PATH) {
            stairMaterial = Material.STONE_BRICK_STAIRS;
        }
        
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
     */
    private boolean tryPlaceSlab(Block current) {
        // Use cobblestone slab for Roman paths
        Material slabMaterial = Material.COBBLESTONE_SLAB;
        if (current.getType() == Material.DIRT_PATH) {
            slabMaterial = Material.STONE_BRICK_SLAB;
        }
        
        current.setType(slabMaterial);
        
        if (current.getBlockData() instanceof Slab) {
            Slab slab = (Slab) current.getBlockData();
            slab.setType(Slab.Type.BOTTOM);
            current.setBlockData(slab);
            return true;
        }
        
        return false;
    }
    
    @Override
    public List<List<Block>> getVillagePathNetwork(UUID villageId) {
        PathNetwork network = pathNetworks.get(villageId);
        if (network == null) {
            return Collections.emptyList();
        }
        
        List<List<Block>> paths = new ArrayList<>();
        for (PathNetwork.PathSegment segment : network.getSegments()) {
            paths.add(segment.getBlocks());
        }
        
        return paths;
    }
    
    @Override
    public boolean areConnected(UUID villageId, Location buildingA, Location buildingB) {
        PathNetwork network = pathNetworks.get(villageId);
        if (network == null) {
            return false;
        }
        
        return network.areConnected(buildingA, buildingB);
    }
    
    @Override
    public double calculateConnectivity(UUID villageId) {
        PathNetwork network = pathNetworks.get(villageId);
        if (network == null) {
            return 0.0;
        }
        
        // Note: This is a simplified calculation
        // Full implementation would require building locations and main building reference
        return network.getSegments().isEmpty() ? 0.0 : 1.0;
    }
    
    /**
     * Path node for A* algorithm.
     */
    private static class PathNode {
        final int x;
        final int y;
        final int z;
        PathNode parent;
        double gScore = Double.POSITIVE_INFINITY;
        double fScore = Double.POSITIVE_INFINITY;
        
        PathNode(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
        
        String key() {
            return x + "," + z;
        }
    }
}
