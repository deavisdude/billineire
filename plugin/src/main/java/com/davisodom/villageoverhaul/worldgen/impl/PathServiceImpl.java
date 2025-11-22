package com.davisodom.villageoverhaul.worldgen.impl;

import com.davisodom.villageoverhaul.model.PathNetwork;
import com.davisodom.villageoverhaul.model.VolumeMask;
import com.davisodom.villageoverhaul.villages.VillageMetadataStore;
import com.davisodom.villageoverhaul.worldgen.PathService;
import com.davisodom.villageoverhaul.worldgen.SurfaceSolver;
import com.davisodom.villageoverhaul.worldgen.WalkableGraph;
import java.util.OptionalInt;
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
    private static final int MAX_NODES_EXPLORED = 10000;
    
    // Terrain cost multipliers
    private static final double FLAT_COST = 1.0;
    private static final double SLOPE_COST = 1.5;
    private static final double WATER_COST = Double.POSITIVE_INFINITY;
    private static final double OBSTACLE_COST = Double.POSITIVE_INFINITY;
    
    // Path network cache (villageId -> PathNetwork)
    private final Map<UUID, PathNetwork> pathNetworks = new HashMap<>();
    
    // R005: VillageMetadataStore for accessing VolumeMasks
    private final VillageMetadataStore metadataStore;
    
    // Current village context for pathfinding
    private UUID currentVillageContext = null;

    public PathServiceImpl(VillageMetadataStore metadataStore) {
        this.metadataStore = metadataStore;
    }
    
    @Override
    public boolean generatePathNetwork(World world, UUID villageId, List<Location> buildingLocations,
                                       Location mainBuildingLocation, long seed) {
        if (buildingLocations.isEmpty()) {
            return false;
        }
        
        // Set village context for building footprint avoidance (T021b)
        currentVillageContext = villageId;
        
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
            }
        }
        
        // Clear village context after path generation
        currentVillageContext = null;
        
        if (successfulPaths == 0) {
            return false;
        }
        
        PathNetwork network = networkBuilder.build();
        pathNetworks.put(villageId, network);
        
        double connectivity = network.calculateConnectivity(buildingLocations, mainBuildingLocation);
        LOGGER.info(String.format("[PATH] network: village=%s paths=%d/%d blocks=%d connectivity=%.0f%%",
                villageId, successfulPaths, buildingLocations.size() - 1, network.getTotalBlocksPlaced(), connectivity * 100));
        
        return connectivity >= 0.75;
    }
    
    /**
     * R005: Create a WalkableGraph for the current village context.
     * Uses SurfaceSolver and VolumeMasks to define valid nodes and obstacles.
     */
    private WalkableGraph createWalkableGraph(World world, UUID villageId) {
        if (metadataStore == null) {
            return null; // Should not happen if properly initialized
        }
        
        List<VolumeMask> masks = metadataStore.getVolumeMasks(villageId);
        SurfaceSolver solver = new SurfaceSolver(world, masks);
        
        // Buffer = 2 blocks around structures
        return new WalkableGraph(solver, masks, 2);
    }

    @Override
    public Optional<List<Block>> generatePath(World world, Location start, Location end, long seed) {
        double distance = start.distance(end);
        if (distance > MAX_SEARCH_DISTANCE || distance < 3) {
            return Optional.empty();
        }
        
        // R007: Snap start/end to walkable surface if village context exists
        Location snappedStart = start;
        Location snappedEnd = end;
        
        WalkableGraph graph = null;
        if (currentVillageContext != null && metadataStore != null) {
            List<VolumeMask> masks = metadataStore.getVolumeMasks(currentVillageContext);
            SurfaceSolver solver = new SurfaceSolver(world, masks);
            graph = new WalkableGraph(solver, masks, 2); // Buffer=2
            
            // Snap start
            OptionalInt startY = solver.nearestWalkable(start.getBlockX(), start.getBlockZ(), start.getBlockY());
            if (startY.isPresent()) {
                snappedStart = new Location(world, start.getBlockX(), startY.getAsInt(), start.getBlockZ());
            }
            
            // Snap end
            OptionalInt endY = solver.nearestWalkable(end.getBlockX(), end.getBlockZ(), end.getBlockY());
            if (endY.isPresent()) {
                snappedEnd = new Location(world, end.getBlockX(), endY.getAsInt(), end.getBlockZ());
            }
        }
        
        List<PathNode> path = findPathAStar(world, snappedStart, snappedEnd, graph);
        
        if (path == null || path.isEmpty()) {
            return Optional.empty();
        }
        
        List<Block> pathBlocks = new ArrayList<>();
        for (PathNode node : path) {
            pathBlocks.add(world.getBlockAt(node.x, node.y, node.z));
        }
        
        return Optional.of(pathBlocks);
    }
    
    /**
     * A* pathfinding on 2D heightmap.
     * Returns list of path nodes from start to end, or null if no path found.
     */
    private List<PathNode> findPathAStar(World world, Location start, Location end) {
        return findPathAStar(world, start, end, null);
    }
    
    /**
     * A* pathfinding on 2D heightmap with optional village context for building avoidance.
     * Returns list of path nodes from start to end, or null if no path found.
     */
    private List<PathNode> findPathAStar(World world, Location start, Location end, WalkableGraph graph) {
        PriorityQueue<PathNode> openSet = new PriorityQueue<>(Comparator.comparingDouble(n -> n.fScore));
        Set<String> closedSet = new HashSet<>();
        Map<String, PathNode> allNodes = new HashMap<>();
        
        int startX = start.getBlockX();
        int startY = start.getBlockY();
        int startZ = start.getBlockZ();
        
        int endX = end.getBlockX();
        int endY = end.getBlockY();
        int endZ = end.getBlockZ();
        
        PathNode startNode = new PathNode(startX, startY, startZ);
        startNode.gScore = 0;
        startNode.fScore = heuristic(startX, startZ, endX, endZ);
        
        openSet.add(startNode);
        allNodes.put(startNode.key(), startNode);
        
        int nodesExplored = 0;
        int obstaclesEncountered = 0;
        int buildingTilesAvoided = 0; // T021b: count building footprint obstacles
        double maxTerrainCostSeen = 0.0;
        
        while (!openSet.isEmpty() && nodesExplored < MAX_NODES_EXPLORED) {
            PathNode current = openSet.poll();
            nodesExplored++;
            
            if (Math.abs(current.x - endX) <= 2 && Math.abs(current.z - endZ) <= 2) {
                List<PathNode> path = reconstructPath(current);
                String pathHash = computePathHash(path);
                LOGGER.info(String.format("[PATH] A* success: nodes=%d avoided=%d hash=%s",
                        nodesExplored, buildingTilesAvoided, pathHash));
                return path;
            }
            
            closedSet.add(current.key());
            
            // Get neighbors
            List<int[]> neighbors;
            if (graph != null) {
                neighbors = graph.getNeighbors(current.x, current.y, current.z);
            } else {
                // R009: Require WalkableGraph for pathfinding
                LOGGER.warning("[PATH] A* FAILED: No WalkableGraph available (legacy fallback removed)");
                return null;
            }
            
            for (int[] n : neighbors) {
                int neighborX = n[0];
                int neighborY = n[1];
                int neighborZ = n[2];
                
                String neighborKey = neighborX + "," + neighborY + "," + neighborZ;
                if (closedSet.contains(neighborKey)) {
                    continue;
                }
                
                // Calculate movement cost
                Location fromLoc = new Location(world, current.x, current.y, current.z);
                Location toLoc = new Location(world, neighborX, neighborY, neighborZ);
                double movementCost = calculateTerrainCost(world, fromLoc, toLoc);
                
                maxTerrainCostSeen = Math.max(maxTerrainCostSeen, movementCost);
                
                // Skip if terrain is impassable
                if (movementCost >= OBSTACLE_COST) {
                    obstaclesEncountered++;
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
        
        LOGGER.warning(String.format("[PATH] A* failed: explored=%d/%d",
                nodesExplored, MAX_NODES_EXPLORED));
        return null;
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
        
        // Check for water or lava at destination (path walks on top of blocks, so check the block itself)
        Block blockAt = world.getBlockAt(to);
        Block blockBelow = world.getBlockAt(to.getBlockX(), to.getBlockY() - 1, to.getBlockZ());
        
        Material typeAt = blockAt.getType();
        Material typeBelow = blockBelow.getType();
        
        // Water penalty applies if walking through water OR walking on top of water surface
        boolean hasWater = (typeAt == Material.WATER || typeAt == Material.LAVA || 
                           typeBelow == Material.WATER || typeBelow == Material.LAVA);
        if (hasWater) {
            return WATER_COST;
        }
        
        // Base cost
        if (yDiff == 0) {
            return FLAT_COST;
        } else if (yDiff == 1) {
            return SLOPE_COST;
        } else {
            return OBSTACLE_COST; // Too steep
        }
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
            Block ground = block.getRelative(BlockFace.DOWN);
            block.setType(pathMaterial);
            blocksPlaced++;
            
            if (!ground.getType().isSolid()) {
                ground.setType(Material.DIRT);
            }
        }
        
        blocksPlaced += smoothPath(world, pathBlocks);
        
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
    
    @Override
    public void registerBuildingFootprint(UUID villageId, int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        // R009: Legacy footprint registration removed. VolumeMasks are now used.
    }
    
    /**
     * Compute a hash of the path for determinism testing.
     */
    private String computePathHash(List<PathNode> path) {
        long hash = 0;
        for (PathNode node : path) {
            hash = 31 * hash + node.x;
            hash = 31 * hash + node.y;
            hash = 31 * hash + node.z;
        }
        return Long.toHexString(hash);
    }
    
    /**
     * Simple node class for A* pathfinding.
     */
    private static class PathNode {
        final int x, y, z;
        double gScore = Double.POSITIVE_INFINITY;
        double fScore = Double.POSITIVE_INFINITY;
        PathNode parent;
        
        PathNode(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
        
        String key() {
            return x + "," + y + "," + z;
        }
    }
}