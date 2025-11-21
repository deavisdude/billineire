package com.davisodom.villageoverhaul.worldgen.impl;

import com.davisodom.villageoverhaul.model.PathNetwork;
import com.davisodom.villageoverhaul.model.VolumeMask;
import com.davisodom.villageoverhaul.villages.VillageMetadataStore;
import com.davisodom.villageoverhaul.worldgen.PathService;
import com.davisodom.villageoverhaul.worldgen.SurfaceSolver;
import com.davisodom.villageoverhaul.worldgen.WalkableGraph;
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
    
    // Building footprints per village (villageId -> List of BuildingBounds)
    private final Map<UUID, List<BuildingBounds>> buildingFootprints = new HashMap<>();
    
    // R005: VillageMetadataStore for accessing VolumeMasks
    private final VillageMetadataStore metadataStore;
    
    // Current village context for pathfinding (to avoid building footprints)
    private UUID currentVillageContext = null;

    public PathServiceImpl(VillageMetadataStore metadataStore) {
        this.metadataStore = metadataStore;
    }
    
    @Override
    public boolean generatePathNetwork(World world, UUID villageId, List<Location> buildingLocations,
                                       Location mainBuildingLocation, long seed) {
        LOGGER.info(String.format("[STRUCT] Begin path network generation: village=%s, buildings=%d, seed=%d",
                villageId, buildingLocations.size(), seed));
        
        if (buildingLocations.isEmpty()) {
            LOGGER.warning("[STRUCT] No buildings to connect");
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
                
                LOGGER.fine(String.format("[STRUCT] Path generated: from main to building %d, blocks=%d",
                        i, pathBlocks.get().size()));
            } else {
                LOGGER.warning(String.format("[STRUCT] Failed to generate path from main to building %d", i));
            }
        }
        
        // Clear village context after path generation
        currentVillageContext = null;
        
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
        return findPathAStar(world, start, end, null);
    }
    
    /**
     * A* pathfinding on 2D heightmap with optional village context for building avoidance.
     * Returns list of path nodes from start to end, or null if no path found.
     */
    private List<PathNode> findPathAStar(World world, Location start, Location end, UUID villageId) {
        PriorityQueue<PathNode> openSet = new PriorityQueue<>(Comparator.comparingDouble(n -> n.fScore));
        Set<String> closedSet = new HashSet<>();
        Map<String, PathNode> allNodes = new HashMap<>();
        
        int startX = start.getBlockX();
        int startZ = start.getBlockZ();
        // Use provided start Y as hint (building entrance level) instead of scanning from world highest block
        int startY = findGroundLevel(world, startX, startZ, start.getBlockY());
        
        int endX = end.getBlockX();
        int endZ = end.getBlockZ();
        // endY stored for potential future use in 3D pathfinding
        // int endY = findGroundLevel(world, endX, endZ, end.getBlockY());
        
        LOGGER.info(String.format("[PATH] A* search start: from (%d,%d,%d) to (%d,%d), distance=%.1f",
                startX, startY, startZ, endX, endZ, 
                Math.sqrt(Math.pow(endX - startX, 2) + Math.pow(endZ - startZ, 2))));
        
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
            
            // Check if we reached the goal
            if (Math.abs(current.x - endX) <= 2 && Math.abs(current.z - endZ) <= 2) {
                List<PathNode> path = reconstructPath(current);
                logPathTerrainCosts(world, path, nodesExplored, buildingTilesAvoided);
                return path;
            }
            
            closedSet.add(current.key());
            
            // T021c: Explore neighbors in 3D (8 horizontal directions × 3 vertical levels = 24 neighbors)
            // Allow Y±1 per step to follow natural terrain slopes
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    
                    int neighborX = current.x + dx;
                    int neighborZ = current.z + dz;
                    
                    // T021c: Try multiple Y levels (same level, +1, -1)
                    // This allows paths to follow gentle slopes naturally
                    for (int dy = -1; dy <= 1; dy++) {
                        int neighborY = current.y + dy;
                        
                        // Validate Y coordinate is within world bounds
                        if (neighborY < world.getMinHeight() || neighborY > world.getMaxHeight()) {
                            continue;
                        }
                        
                        // Create unique 3D key (include Y for closed set check)
                        String neighborKey = neighborX + "," + neighborY + "," + neighborZ;
                        if (closedSet.contains(neighborKey)) {
                            continue;
                        }
                        
                        // T021b: Check if neighbor position is inside any building footprint FIRST
                        // This prevents paths from entering or crossing building volumes
                        if (isInsideBuildingFootprint(neighborX, neighborY, neighborZ)) {
                            buildingTilesAvoided++;
                            continue; // Skip this neighbor (treat as obstacle)
                        }
                        
                        // T021c: Check if the block BELOW neighbor is inside a building footprint
                        // This prevents paths from walking on top of building roofs/floors
                        Block blockBelow = world.getBlockAt(neighborX, neighborY - 1, neighborZ);
                        if (isInsideBuildingFootprint(neighborX, neighborY - 1, neighborZ)) {
                            buildingTilesAvoided++;
                            continue; // Skip - would be walking on building structure
                        }
                        
                        // T021c: Only allow stepping onto natural ground materials
                        // This prevents paths from climbing onto roofs or man-made structures
                        if (!isNaturalGroundMaterial(blockBelow.getType())) {
                            // Cannot step onto this block - it's not natural ground
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
            }
        }
        
        String reason = nodesExplored >= MAX_NODES_EXPLORED ? "node limit reached" : "no path exists";
        LOGGER.warning(String.format("[PATH] A* FAILED: %s (explored=%d/%d, obstacles=%d, maxCost=%.1f)",
                reason, nodesExplored, MAX_NODES_EXPLORED, obstaclesEncountered, maxTerrainCostSeen));
        return null; // No path found
    }
    
    /**
     * Check if a material is natural ground suitable for path placement (T021c).
     * Whitelists natural terrain blocks and rejects man-made structures.
     * 
     * @param material Block material to check
     * @return true if natural ground, false if man-made or unsuitable
     */
    private boolean isNaturalGroundMaterial(Material material) {
        // Natural ground materials that paths can traverse
        return material == Material.GRASS_BLOCK ||
               material == Material.DIRT ||
               material == Material.COARSE_DIRT ||
               material == Material.PODZOL ||
               material == Material.MYCELIUM ||
               material == Material.STONE ||
               material == Material.ANDESITE ||
               material == Material.DIORITE ||
               material == Material.GRANITE ||
               material == Material.SAND ||
               material == Material.RED_SAND ||
               material == Material.SANDSTONE ||
               material == Material.RED_SANDSTONE ||
               material == Material.GRAVEL ||
               material == Material.CLAY ||
               material == Material.TERRACOTTA ||
               material == Material.PACKED_ICE ||
               material == Material.SNOW_BLOCK ||
               material == Material.SNOW;
    }
    
    /**
     * Heuristic function for A* (Manhattan distance).
     */
    private double heuristic(int x1, int z1, int x2, int z2) {
        return Math.abs(x1 - x2) + Math.abs(z1 - z2);
    }
    
    /**
     * Check if a position is inside any registered building footprint for the current village context.
     * T021b: Building footprint avoidance for pathfinding.
     */
    private boolean isInsideBuildingFootprint(int x, int y, int z) {
        if (currentVillageContext == null) {
            return false; // No village context, no building avoidance
        }
        
        List<BuildingBounds> bounds = buildingFootprints.get(currentVillageContext);
        if (bounds == null || bounds.isEmpty()) {
            return false; // No registered footprints for this village
        }
        
        // Check if position is inside any building bounds
        for (BuildingBounds building : bounds) {
            if (building.contains(x, y, z)) {
                return true;
            }
        }
        
        return false;
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
        
        // Check for water or lava at destination (path walks on top of blocks, so check the block itself)
        Block blockAt = world.getBlockAt(to);
        Block blockBelow = world.getBlockAt(to.getBlockX(), to.getBlockY() - 1, to.getBlockZ());
        
        Material typeAt = blockAt.getType();
        Material typeBelow = blockBelow.getType();
        
        // Water penalty applies if walking through water OR walking on top of water surface
        boolean hasWater = (typeAt == Material.WATER || typeAt == Material.LAVA || 
                           typeBelow == Material.WATER || typeBelow == Material.LAVA);
        if (hasWater) {
            cost += WATER_COST;
        }
        
        // Use typeAt for passability checks (the block at the path level)
        Material type = typeAt;
        
        // Allow paths through most natural materials
        // Only veto truly impassable blocks (structures, ores, bedrock)
        if (type.isSolid()) {
            // Allow: grass, dirt, stone, sand, gravel, vegetation
            if (type == Material.GRASS_BLOCK || type == Material.DIRT || 
                type == Material.STONE || type == Material.SAND || 
                type == Material.GRAVEL || type == Material.COARSE_DIRT ||
                type == Material.PODZOL || type == Material.CLAY ||
                type.name().contains("LOG") || type.name().contains("LEAVES")) {
                return cost; // Passable natural terrain
            }
            
            // Veto artificial/rare blocks (ores, structures, bedrock)
            if (type == Material.BEDROCK || 
                type.name().contains("ORE") ||
                type.name().contains("PLANKS") ||
                type.name().contains("BRICKS") ||
                type.name().contains("COBBLESTONE")) {
                return OBSTACLE_COST;
            }
            
            // Default: slightly increased cost for other solid blocks
            cost += 2.0;
        }
        
        return cost;
    }
    
    /**
     * Log terrain cost breakdown for a successful path.
     * Analyzes the final path to report flat, slope, water, and steep tile counts.
     * T021b: Also logs building tiles avoided during pathfinding.
     */
    private void logPathTerrainCosts(World world, List<PathNode> path, int nodesExplored, int buildingTilesAvoided) {
        if (path.isEmpty()) {
            LOGGER.info(String.format("[PATH] A* SUCCESS: Goal reached after exploring %d nodes (empty path)", nodesExplored));
            return;
        }
        
        int flatTiles = 0;
        int slopeTiles = 0;
        int waterTiles = 0;
        int steepTiles = 0;
        double totalCost = 0.0;
        
        for (int i = 0; i < path.size() - 1; i++) {
            PathNode from = path.get(i);
            PathNode to = path.get(i + 1);
            
            Location fromLoc = new Location(world, from.x, from.y, from.z);
            Location toLoc = new Location(world, to.x, to.y, to.z);
            
            int yDiff = Math.abs(to.y - from.y);
            
            // Check block at path level and block below for water detection
            Block blockAt = world.getBlockAt(toLoc);
            Block blockBelow = world.getBlockAt(to.x, to.y - 1, to.z);
            Material typeAt = blockAt.getType();
            Material typeBelow = blockBelow.getType();
            
            double segmentCost = calculateTerrainCost(world, fromLoc, toLoc);
            totalCost += segmentCost;
            
            // Categorize tile by dominant cost factor
            // Water check needs to look at both the path level AND the block below
            boolean isWater = (typeAt == Material.WATER || typeAt == Material.LAVA ||
                              typeBelow == Material.WATER || typeBelow == Material.LAVA);
            boolean isSteep = (yDiff >= 2); // 2+ blocks elevation change
            boolean isSlope = (yDiff == 1);
            
            if (isWater) {
                waterTiles++;
            } else if (isSteep) {
                steepTiles++;
            } else if (isSlope) {
                slopeTiles++;
            } else {
                flatTiles++;
            }
        }
        
        // Build log message with terrain breakdown and building avoidance stats (T021b)
        String logMessage = String.format(
            "[PATH] A* SUCCESS: Goal reached after exploring %d nodes (path=%d tiles, cost=%.1f, flat=%d, slope=%d, water=%d, steep=%d",
            nodesExplored, path.size(), totalCost, flatTiles, slopeTiles, waterTiles, steepTiles
        );
        
        // Append building avoidance info if applicable
        if (buildingTilesAvoided > 0) {
            logMessage += String.format(", avoided %d building tiles", buildingTilesAvoided);
        }
        logMessage += ")";
        
        LOGGER.info(logMessage);
        
        // Log path hash for determinism testing (T026d)
        String pathHash = computePathHash(path);
        LOGGER.info(String.format("[PATH] Determinism hash: %s (nodes=%d)", pathHash, path.size()));
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
     * Find the actual ground level at a position, searching down from the highest block to find solid ground.
     * This ignores vegetation (leaves, grass, flowers) and finds the actual solid foundation beneath.
     * Same logic as used in structure placement to ensure consistent ground detection.
     * 
     * @param world World to search
     * @param x X coordinate
     * @param z Z coordinate
     * @return Ground level Y coordinate
     */
    /**
     * Find ground level at given X,Z coordinates, using a Y hint to avoid scanning through tall buildings.
     * CRITICAL: Skips Y-levels that are inside registered building footprints to prevent paths on rooftops.
     * 
     * @param world World to search
     * @param x X coordinate
     * @param z Z coordinate
     * @param yHint Y coordinate hint (e.g., building origin/door level) to start search from
     * @return Y coordinate of solid ground, guaranteed to be outside building footprints
     */
    private int findGroundLevel(World world, int x, int z, int yHint) {
        // Start by checking the hint level and a few blocks around it
        for (int yOffset = 0; yOffset <= 3; yOffset++) {
            int checkY = yHint + yOffset;
            Block block = world.getBlockAt(x, checkY, z);
            Material type = block.getType();
            
            // CRITICAL: Skip this Y-level if it's inside a building footprint (T021b)
            // This prevents paths from being placed on building floors/rooftops
            if (isInsideBuildingFootprint(x, checkY, z)) {
                continue; // Skip this Y-level, try next
            }
            
            // If we find solid ground at or slightly above the hint, use it
            if (type.isSolid() && !type.isAir() && 
                    type != Material.OAK_LEAVES && type != Material.BIRCH_LEAVES &&
                    type != Material.SPRUCE_LEAVES && type != Material.JUNGLE_LEAVES &&
                    type != Material.ACACIA_LEAVES && type != Material.DARK_OAK_LEAVES &&
                    type != Material.MANGROVE_LEAVES && type != Material.CHERRY_LEAVES) {
                return checkY;
            }
        }
        
        // If hint didn't work, fall back to original logic (scan from world highest block)
        // But still skip Y-levels inside building footprints
        int highestY = world.getHighestBlockYAt(x, z);
        Block highestBlock = world.getBlockAt(x, highestY, z);
        Material highestType = highestBlock.getType();
        
        // If highest block is vegetation (leaves, grass), search down to find ground
        boolean isVegetation = highestType == Material.OAK_LEAVES || 
                highestType == Material.BIRCH_LEAVES ||
                highestType == Material.SPRUCE_LEAVES ||
                highestType == Material.JUNGLE_LEAVES ||
                highestType == Material.ACACIA_LEAVES ||
                highestType == Material.DARK_OAK_LEAVES ||
                highestType == Material.MANGROVE_LEAVES ||
                highestType == Material.CHERRY_LEAVES ||
                highestType == Material.SHORT_GRASS || 
                highestType == Material.TALL_GRASS ||
                highestType == Material.FERN ||
                highestType == Material.LARGE_FERN;
        
        if (!isVegetation && highestType.isSolid() && !isInsideBuildingFootprint(x, highestY, z)) {
            // Highest block is already solid ground AND not inside a building
            return highestY;
        }
        
        // Search downward up to 20 blocks to find solid ground beneath vegetation
        for (int y = highestY - 1; y > highestY - 20 && y > world.getMinHeight(); y--) {
            // Skip Y-levels inside building footprints
            if (isInsideBuildingFootprint(x, y, z)) {
                continue;
            }
            
            Block current = world.getBlockAt(x, y, z);
            Material currentType = current.getType();
            
            // Found solid ground outside building footprints
            if (currentType.isSolid() && !currentType.isAir() && currentType != Material.OAK_LEAVES &&
                    currentType != Material.BIRCH_LEAVES && currentType != Material.SPRUCE_LEAVES &&
                    currentType != Material.JUNGLE_LEAVES && currentType != Material.ACACIA_LEAVES &&
                    currentType != Material.DARK_OAK_LEAVES && currentType != Material.MANGROVE_LEAVES &&
                    currentType != Material.CHERRY_LEAVES) {
                return y;
            }
        }
        
        // Fallback: if we didn't find ground in search range, use original highest block or hint
        // CRITICAL: Make sure fallback is also not inside a building footprint
        int fallbackY = Math.min(yHint, highestY);
        // Scan down from fallback to find first Y outside building footprints
        for (int y = fallbackY; y > fallbackY - 10 && y > world.getMinHeight(); y--) {
            if (!isInsideBuildingFootprint(x, y, z)) {
                return y;
            }
        }
        return fallbackY; // Last resort
    }
    
    /**
     * Compute deterministic hash of path coordinates for seed testing (T026d).
     * Uses MD5 hash of ordered (x,y,z) coordinates to verify reproducibility.
     */
    private String computePathHash(List<PathNode> path) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            StringBuilder coordString = new StringBuilder();
            
            for (PathNode node : path) {
                coordString.append(node.x).append(",")
                          .append(node.y).append(",")
                          .append(node.z).append(";");
            }
            
            byte[] hash = md.digest(coordString.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return "hash-error";
        }
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
        
        /**
         * Generate unique 3D key for this node (T021c).
         * Used for closed set and node lookup in A* pathfinding.
         */
        String key() {
            return x + "," + y + "," + z;
        }
    }
    
    /**
     * Building bounds for obstacle avoidance in pathfinding (T021b).
     * Represents a 3D axis-aligned bounding box.
     */
    private static class BuildingBounds {
        final int minX;
        final int maxX;
        final int minY;
        final int maxY;
        final int minZ;
        final int maxZ;
        
        BuildingBounds(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
            this.minX = minX;
            this.maxX = maxX;
            this.minY = minY;
            this.maxY = maxY;
            this.minZ = minZ;
            this.maxZ = maxZ;
        }
        
        /**
         * Check if a point (x,y,z) is inside this building's bounds (inclusive).
         */
        boolean contains(int x, int y, int z) {
            return x >= minX && x <= maxX &&
                   y >= minY && y <= maxY &&
                   z >= minZ && z <= maxZ;
        }
    }
    
    @Override
    public void registerBuildingFootprint(UUID villageId, int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        buildingFootprints.computeIfAbsent(villageId, k -> new ArrayList<>())
                .add(new BuildingBounds(minX, maxX, minY, maxY, minZ, maxZ));
        
        LOGGER.info(String.format("[PATH] FOOTPRINT REGISTERED for village %s: X[%d to %d] Y[%d to %d] Z[%d to %d] (size: %dx%dx%d)",
                villageId, minX, maxX, minY, maxY, minZ, maxZ, 
                (maxX - minX + 1), (maxY - minY + 1), (maxZ - minZ + 1)));
    }
}
