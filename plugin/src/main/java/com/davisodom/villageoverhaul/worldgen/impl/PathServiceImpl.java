package com.davisodom.villageoverhaul.worldgen.impl;

import com.davisodom.villageoverhaul.metrics.PerfCounters;
import com.davisodom.villageoverhaul.model.PathNetwork;
import com.davisodom.villageoverhaul.model.VolumeMask;
import com.davisodom.villageoverhaul.villages.VillageMetadataStore;
import com.davisodom.villageoverhaul.worldgen.PathService;
import com.davisodom.villageoverhaul.worldgen.SurfaceSolver;
import com.davisodom.villageoverhaul.worldgen.TerrainClassifier;
import com.davisodom.villageoverhaul.worldgen.WalkableGraph;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Bukkit;
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
    private static final int DEFAULT_MAX_NODES_EXPLORED = 15000;
    private static final int WAYPOINT_SEGMENT_THRESHOLD = 40;
    private static final int WAYPOINT_INTERVAL_BLOCKS = 20;
    private static final int CACHE_BUCKET_SIZE = 16;

    // Path planner defaults
    private static final int DEFAULT_PLANNER_CONCURRENCY_CAP = 3;
    private static final int DEFAULT_NODE_CAP_RETRY_MAX_ATTEMPTS = 2;
    private static final int DEFAULT_NODE_CAP_BACKOFF_BASE_MS = 50;
    private static final int DEFAULT_NODE_CAP_BACKOFF_MAX_MS = 200;
    
    // Terrain cost multipliers
    private static final double FLAT_COST = 1.0;
    private static final double SLOPE_COST = 1.5;
    private static final double WATER_COST = Double.POSITIVE_INFINITY;
    private static final double OBSTACLE_COST = Double.POSITIVE_INFINITY;
    
    // Path network cache (villageId -> PathNetwork)
    private final Map<UUID, PathNetwork> pathNetworks = new HashMap<>();
    private final PathEmitter pathEmitter = new PathEmitter();
    private static final Map<UUID, PerfCounters> VILLAGE_COUNTERS = new ConcurrentHashMap<>();
    private static final Map<UUID, Map<SegmentKey, CachedSegment>> SEGMENT_CACHE = new ConcurrentHashMap<>();
    private static final Map<UUID, Map<String, Set<SegmentKey>>> SEGMENT_INDEX = new ConcurrentHashMap<>();
    private static final Map<Integer, PlannerLimiter> SHARED_LIMITERS = new ConcurrentHashMap<>();

    private static final Set<Material> PATH_SURFACE_WHITELIST = new HashSet<>();
    static {
        addMaterials(PATH_SURFACE_WHITELIST,
                "GRASS_BLOCK",
                "DIRT",
                "COARSE_DIRT",
                "ROOTED_DIRT",
                "STONE",
                "COBBLESTONE",
                "ANDESITE",
                "DIORITE",
                "GRANITE",
                "SAND",
                "RED_SAND",
                "GRAVEL",
                "SNOW",
                "SNOW_BLOCK",
                "DIRT_PATH");
    }
    
    // R005: VillageMetadataStore for accessing VolumeMasks
    private final VillageMetadataStore metadataStore;
    
    // Current village context for pathfinding
    private final ThreadLocal<UUID> currentVillageContext = new ThreadLocal<>();

    private final int maxNodesExplored;
    private final int plannerConcurrencyCap;
    private final int nodeCapRetryMaxAttempts;
    private final int nodeCapBackoffBaseMs;
    private final int nodeCapBackoffMaxMs;
    private final PlannerLimiter plannerLimiter;

    public PathServiceImpl(VillageMetadataStore metadataStore) {
        this(metadataStore, PlannerSettings.defaults());
    }

    public PathServiceImpl(VillageMetadataStore metadataStore, PlannerSettings plannerSettings) {
        this.metadataStore = metadataStore;
        PlannerSettings settings = plannerSettings != null ? plannerSettings : PlannerSettings.defaults();
        this.maxNodesExplored = Math.max(1000, settings.maxNodesExplored);
        this.plannerConcurrencyCap = Math.max(1, settings.plannerConcurrencyCap);
        this.nodeCapRetryMaxAttempts = Math.max(0, settings.nodeCapRetryMaxAttempts);
        this.nodeCapBackoffBaseMs = Math.max(0, settings.nodeCapBackoffBaseMs);
        this.nodeCapBackoffMaxMs = Math.max(this.nodeCapBackoffBaseMs, settings.nodeCapBackoffMaxMs);
        this.plannerLimiter = SHARED_LIMITERS.computeIfAbsent(this.plannerConcurrencyCap, PlannerLimiter::new);
    }
    
    @Override
    public boolean generatePathNetwork(World world, UUID villageId, List<Location> buildingLocations,
                                       Location mainBuildingLocation, long seed) {
        if (buildingLocations.isEmpty()) {
            return false;
        }
        
        // Set village context for building footprint avoidance (T021b)
        currentVillageContext.set(villageId);
        PerfCounters counters = countersForVillage(villageId);
        
        PathNetwork.Builder networkBuilder = new PathNetwork.Builder()
                .villageId(villageId)
                .generatedTimestamp(System.currentTimeMillis());
        
        // Connect main building to all other buildings
        int attemptedPairs = 0;
        int successfulPaths = 0;
        for (int i = 0; i < buildingLocations.size(); i++) {
            Location building = buildingLocations.get(i);
            
            // Skip main building
            if (building.distance(mainBuildingLocation) < 5) {
                continue;
            }
            attemptedPairs++;
            
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
        currentVillageContext.remove();

        int coveragePercent = attemptedPairs > 0
            ? (int) Math.round(((double) successfulPaths / (double) attemptedPairs) * 100.0)
            : 0;
        LOGGER.info(String.format("PATH-COVERAGE village=%s attempted=%d spawnedPairs=%d coverage=%d%%",
            villageId, attemptedPairs, successfulPaths, coveragePercent));
        
        if (successfulPaths == 0) {
            return false;
        }
        
        PerfCounters.Snapshot metricsSummary = counters.snapshot(countCacheEntries(world.getUID()));
        networkBuilder.metricsSummary(metricsSummary);
        PathNetwork network = networkBuilder.build();
        pathNetworks.put(villageId, network);
        // Persist network to metadata store so harness/tests can inspect deterministic results
        if (metadataStore != null) {
            metadataStore.setPathNetwork(villageId, network);
        }

        LOGGER.info(String.format("[PATH] cache: hits=%d, misses=%d, entries=%d village=%s",
            metricsSummary.getCacheHits(), metricsSummary.getCacheMisses(), metricsSummary.getCacheEntries(), villageId));
        
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
        ResolvedPathContext context = resolvePathContext(world, start, end);
        if (context == null) {
            return Optional.empty();
        }

        double distance = context.start.distance(context.end);
        if (distance > MAX_SEARCH_DISTANCE || distance < 3) {
            return Optional.empty();
        }

        UUID villageId = currentVillageContext.get();
        if (villageId != null) {
            return generatePathWithCaching(world, villageId, context, seed);
        }

        PathSearchResult pathResult = findPathAStarWithRetries(world, context.start, context.end, context.graph, seed, null);
        if (!pathResult.isSuccess()) {
            return Optional.empty();
        }
        return Optional.of(blocksFromPath(world, pathResult.path));
    }

    private Optional<List<Block>> generatePathWithCaching(World world, UUID villageId,
                                                          ResolvedPathContext context, long seed) {
        List<Location> waypoints = buildWaypointSequence(world, context, villageId);
        PerfCounters counters = countersForVillage(villageId);
        List<Block> combined = new ArrayList<>();

        for (int index = 0; index < waypoints.size() - 1; index++) {
            Location segmentStart = waypoints.get(index);
            Location segmentEnd = waypoints.get(index + 1);
            SegmentResolution resolution = resolveOrCreateSegment(world, villageId, segmentStart, segmentEnd,
                context.graph, seed + index, counters);
            if (resolution == null || resolution.blocks.isEmpty()) {
                return Optional.empty();
            }

            if (!combined.isEmpty() && !resolution.blocks.isEmpty()) {
                resolution.blocks.remove(0);
            }
            combined.addAll(resolution.blocks);
        }

        return combined.isEmpty() ? Optional.empty() : Optional.of(combined);
    }

    private SegmentResolution resolveOrCreateSegment(World world, UUID villageId, Location start, Location end,
                                                     WalkableGraph graph, long seed, PerfCounters counters) {
        UUID worldId = world.getUID();
        SegmentKey key = SegmentKey.normalized(worldId, start, end);
        CachedSegment cached = cacheForWorld(worldId).get(key);
        if (cached != null) {
            cached.registerVillage(villageId);
            counters.recordCacheHit();
            return new SegmentResolution(cached.materialize(world, key.isReversed(start, end)), true);
        }

        counters.recordCacheMiss();
        PathSearchResult pathResult = findPathAStarWithRetries(world, start, end, graph, seed, villageId);
        counters.recordPathSearch(pathResult.nodesExplored);
        if (!pathResult.isSuccess()) {
            return null;
        }

        List<PathCoordinate> storedCoordinates = new ArrayList<>();
        for (PathNode node : pathResult.path) {
            storedCoordinates.add(new PathCoordinate(node.x, node.y, node.z));
        }
        if (key.isReversed(start, end)) {
            Collections.reverse(storedCoordinates);
        }

        CachedSegment created = new CachedSegment(key, storedCoordinates);
        created.registerVillage(villageId);
        cacheForWorld(worldId).put(key, created);
        indexSegment(created);
        return new SegmentResolution(created.materialize(world, key.isReversed(start, end)), false);
    }

    private ResolvedPathContext resolvePathContext(World world, Location start, Location end) {
        Location snappedStart = start;
        Location snappedEnd = end;

        WalkableGraph graph = null;
        UUID villageContext = currentVillageContext.get();
        SurfaceSolver solver = null;
        if (villageContext != null && metadataStore != null) {
            List<VolumeMask> masks = metadataStore.getVolumeMasks(villageContext);
            solver = new SurfaceSolver(world, masks);
            graph = new WalkableGraph(solver, masks, 2);

            Optional<Location> resolvedStart = resolveEndpointOutsideMasks(world, solver, graph, start, 32);
            Optional<Location> resolvedEnd = resolveEndpointOutsideMasks(world, solver, graph, end, 32);
            if (resolvedStart.isEmpty() || resolvedEnd.isEmpty()) {
                LOGGER.warning(String.format("[PATH] Unable to resolve walkable endpoints for path: start=%s end=%s",
                    formatLocation(start), formatLocation(end)));
                return null;
            }
            snappedStart = resolvedStart.get();
            snappedEnd = resolvedEnd.get();
        }

        return new ResolvedPathContext(snappedStart, snappedEnd, graph, solver);
    }

    private List<Location> buildWaypointSequence(World world, ResolvedPathContext context, UUID villageId) {
        List<Location> waypoints = new ArrayList<>();
        waypoints.add(context.start);

        double distance = context.start.distance(context.end);
        if (distance > WAYPOINT_SEGMENT_THRESHOLD && context.solver != null && context.graph != null) {
            double deltaX = context.end.getX() - context.start.getX();
            double deltaY = context.end.getY() - context.start.getY();
            double deltaZ = context.end.getZ() - context.start.getZ();

            for (double travelled = WAYPOINT_INTERVAL_BLOCKS; travelled < distance; travelled += WAYPOINT_INTERVAL_BLOCKS) {
                double t = travelled / distance;
                Location raw = new Location(world,
                    Math.round(context.start.getX() + (deltaX * t)),
                    Math.round(context.start.getY() + (deltaY * t)),
                    Math.round(context.start.getZ() + (deltaZ * t)));
                Optional<Location> resolved = resolveEndpointOutsideMasks(world, context.solver, context.graph, raw, 12);
                if (resolved.isPresent() && !sameBlock(waypoints.get(waypoints.size() - 1), resolved.get())
                        && !sameBlock(context.end, resolved.get())) {
                    waypoints.add(resolved.get());
                }
            }
        }

        if (!sameBlock(waypoints.get(waypoints.size() - 1), context.end)) {
            waypoints.add(context.end);
        }
        return waypoints;
    }

    private boolean sameBlock(Location a, Location b) {
        return a.getBlockX() == b.getBlockX() && a.getBlockY() == b.getBlockY() && a.getBlockZ() == b.getBlockZ();
    }

    private List<Block> blocksFromPath(World world, List<PathNode> path) {
        List<Block> pathBlocks = new ArrayList<>();
        for (PathNode node : path) {
            pathBlocks.add(world.getBlockAt(node.x, node.y, node.z));
        }
        return pathBlocks;
    }

    private Optional<Location> resolveEndpointOutsideMasks(World world, SurfaceSolver solver, WalkableGraph graph,
                                                          Location target, int maxRadius) {
        OptionalInt directY = solver.nearestWalkable(target.getBlockX(), target.getBlockZ(), target.getBlockY());
        if (directY.isPresent() && isNavigableEndpoint(world, graph, target.getBlockX(), directY.getAsInt(), target.getBlockZ())) {
            return Optional.of(new Location(world, target.getBlockX(), directY.getAsInt(), target.getBlockZ()));
        }

        for (int radius = 1; radius <= maxRadius; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) != radius && Math.abs(dz) != radius) {
                        continue;
                    }
                    int x = target.getBlockX() + dx;
                    int z = target.getBlockZ() + dz;
                    OptionalInt y = solver.nearestWalkable(x, z, target.getBlockY());
                    if (y.isPresent() && isNavigableEndpoint(world, graph, x, y.getAsInt(), z)) {
                        return Optional.of(new Location(world, x, y.getAsInt(), z));
                    }
                }
            }
        }

        return Optional.empty();
    }

    private boolean isNavigableEndpoint(World world, WalkableGraph graph, int x, int y, int z) {
        if (graph.isObstacle(x, y, z)) {
            return false;
        }
        List<int[]> neighbors = graph.getNeighbors(x, y, z);
        if (neighbors.isEmpty()) {
            return false;
        }

        Location here = new Location(world, x, y, z);
        for (int[] neighbor : neighbors) {
            Location there = new Location(world, neighbor[0], neighbor[1], neighbor[2]);
            if (calculateTerrainCost(world, here, there) < OBSTACLE_COST) {
                return true;
            }
        }
        return false;
    }

    private String formatLocation(Location location) {
        if (location == null) {
            return "(null)";
        }
        return String.format("(%d,%d,%d)", location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }
    
    /**
     * A* pathfinding on 2D heightmap.
     * Returns list of path nodes from start to end, or null if no path found.
     */
    private List<PathNode> findPathAStar(World world, Location start, Location end) {
        return findPathAStar(world, start, end, null, maxNodesExplored).path;
    }
    
    /**
     * A* pathfinding on 2D heightmap with optional village context for building avoidance.
     * Returns list of path nodes from start to end, or null if no path found.
     */
    private PathSearchResult findPathAStarWithRetries(World world, Location start, Location end,
                                                      WalkableGraph graph, long seed, UUID villageId) {
        int attempt = 0;
        int maxAttempts = Math.max(0, nodeCapRetryMaxAttempts);

        while (true) {
            int nodeCap = computeNodeCapForAttempt(attempt);
            try (PlannerPermit permit = plannerLimiter.acquire(villageId)) {
                PathSearchResult result = findPathAStar(world, start, end, graph, nodeCap);
                if (result.isSuccess()) {
                    return result;
                }

                if (!result.nodeCapHit || attempt >= maxAttempts) {
                    if (result.nodeCapHit) {
                        LOGGER.warning(String.format(
                            "[PATH] A* node cap exhausted: explored=%d cap=%d attempts=%d/%d start=%s end=%s",
                            result.nodesExplored, nodeCap, attempt + 1, maxAttempts + 1,
                            formatLocation(start), formatLocation(end)));
                    }
                    return result;
                }

                int backoffMs = computeBackoffMs(attempt);
                LOGGER.warning(String.format(
                    "[PATH] A* node cap hit: explored=%d cap=%d attempt=%d/%d backoffMs=%d start=%s end=%s",
                    result.nodesExplored, nodeCap, attempt + 1, maxAttempts + 1, backoffMs,
                    formatLocation(start), formatLocation(end)));
                applyBackoff(backoffMs);
            }

            attempt++;
        }
    }

    /**
     * A* pathfinding on 2D heightmap with optional village context for building avoidance.
     * Returns search result containing path or failure reason.
     */
    private PathSearchResult findPathAStar(World world, Location start, Location end, WalkableGraph graph,
                                           int nodeCap) {
        // T026d: Deterministic tie-breaking for equal fScore values
        PriorityQueue<PathNode> openSet = new PriorityQueue<>(
            Comparator.comparingDouble((PathNode n) -> n.fScore)
                      .thenComparingInt(n -> n.x)
                      .thenComparingInt(n -> n.z)
                      .thenComparingInt(n -> n.y)
        );
        Set<String> closedSet = new HashSet<>();
        Map<String, PathNode> allNodes = new HashMap<>();
        
        int startX = start.getBlockX();
        int startY = start.getBlockY();
        int startZ = start.getBlockZ();
        
        int endX = end.getBlockX();
        int endZ = end.getBlockZ();
        
        PathNode startNode = new PathNode(startX, startY, startZ);
        startNode.gScore = 0;
        startNode.fScore = heuristic(startX, startZ, endX, endZ);
        
        openSet.add(startNode);
        allNodes.put(startNode.key(), startNode);
        
        int nodesExplored = 0;
        int buildingTilesAvoided = 0; // T021b: count building footprint obstacles
        double maxTerrainCostSeen = 0.0;
        
        while (!openSet.isEmpty() && nodesExplored < nodeCap) {
            PathNode current = openSet.poll();
            nodesExplored++;
            
            if (Math.abs(current.x - endX) <= 2 && Math.abs(current.z - endZ) <= 2) {
                List<PathNode> path = reconstructPath(current);
                String pathHash = computePathHash(path);
                LOGGER.info(String.format("[PATH] A* success: nodes=%d avoided=%d hash=%s",
                    nodesExplored, buildingTilesAvoided, pathHash));
                // Also emit deterministic hash in canonical format for harness parsing
                LOGGER.info(String.format("[PATH] Determinism hash: %s (nodes=%d)", pathHash, path.size()));
                return PathSearchResult.success(path, nodesExplored, nodeCap);
            }
            
            closedSet.add(current.key());
            
            // Get neighbors
            List<int[]> neighbors;
            if (graph != null) {
                neighbors = graph.getNeighbors(current.x, current.y, current.z);
            } else {
                // R009: Require WalkableGraph for pathfinding
                LOGGER.warning("[PATH] A* FAILED: No WalkableGraph available (legacy fallback removed)");
                return PathSearchResult.failure(nodesExplored, nodeCap, false, "no_walkable_graph");
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

        if (!openSet.isEmpty() && nodesExplored >= nodeCap) {
            return PathSearchResult.failure(nodesExplored, nodeCap, true, "node_cap");
        }

        LOGGER.warning(String.format("[PATH] A* failed: explored=%d/%d reason=no_path",
            nodesExplored, nodeCap));
        return PathSearchResult.failure(nodesExplored, nodeCap, false, "no_path");
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

    private int computeNodeCapForAttempt(int attempt) {
        if (attempt <= 0) {
            return maxNodesExplored;
        }
        int growth = Math.max(1, maxNodesExplored / 2);
        long candidate = (long) maxNodesExplored + (long) growth * attempt;
        return (int) Math.min(Integer.MAX_VALUE, candidate);
    }

    private int computeBackoffMs(int attempt) {
        if (nodeCapBackoffBaseMs <= 0) {
            return 0;
        }
        int backoff = nodeCapBackoffBaseMs * (attempt + 1);
        return Math.min(backoff, nodeCapBackoffMaxMs);
    }

    private void applyBackoff(int backoffMs) {
        if (backoffMs <= 0) {
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            LOGGER.info(String.format("[PATH] Backoff skipped on main thread (requestedMs=%d)", backoffMs));
            return;
        }
        try {
            Thread.sleep(backoffMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    @Override
    public double calculateTerrainCost(World world, Location from, Location to) {
        int yDiff = Math.abs(to.getBlockY() - from.getBlockY());
        
        // Check for water or lava at destination (path walks on top of blocks, so check the block itself)
        Block blockAt = world.getBlockAt(to);
        Block blockBelow = world.getBlockAt(to.getBlockX(), to.getBlockY() - 1, to.getBlockZ());
        
        Material typeAt = blockAt.getType();
        Material typeBelow = blockBelow.getType();

        if (isVegetation(blockAt) || isVegetation(blockBelow)) {
            return OBSTACLE_COST;
        }

        if (!isWhitelistedSurface(typeBelow)) {
            return OBSTACLE_COST;
        }
        
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

    private static void addMaterials(Set<Material> target, String... names) {
        for (String name : names) {
            Material material = Material.matchMaterial(name);
            if (material != null) {
                target.add(material);
            }
        }
    }

    private boolean isVegetation(Block block) {
        return TerrainClassifier.classify(block) == TerrainClassifier.Classification.VEGETATION;
    }

    private boolean isWhitelistedSurface(Material material) {
        return PATH_SURFACE_WHITELIST.contains(material);
    }
    

    
    @Override
    public int placePath(World world, List<Block> pathBlocks, String cultureId) {
        return pathEmitter.emitPathWithSmoothing(world, pathBlocks, cultureId, Collections.emptyList());
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
        return pathEmitter.smoothPath(world, pathBlocks, null);
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

    public static PerfCounters.Snapshot getPathMetrics(UUID villageId) {
        PerfCounters counters = VILLAGE_COUNTERS.get(villageId);
        return counters != null ? counters.snapshot(getTotalCacheEntries()) : PerfCounters.Snapshot.empty();
    }

    public static void resetPathMetrics() {
        VILLAGE_COUNTERS.values().forEach(PerfCounters::reset);
    }

    public static void resetPathMetrics(UUID villageId) {
        PerfCounters counters = VILLAGE_COUNTERS.get(villageId);
        if (counters != null) {
            counters.reset();
        }
    }

    public static void resetPathState() {
        resetPathMetrics();
        SEGMENT_CACHE.clear();
        SEGMENT_INDEX.clear();
        SHARED_LIMITERS.clear();
    }

    static void resetPathStateForTests() {
        resetPathState();
    }

    public static InvalidationResult invalidateSegmentCache(World world, int minX, int maxX, int minZ, int maxZ,
                                                            String reason) {
        UUID worldId = world.getUID();
        Map<SegmentKey, CachedSegment> cache = cacheForWorld(worldId);
        if (cache.isEmpty()) {
            return new InvalidationResult(0, reason, minX, maxX, minZ, maxZ);
        }

        Set<SegmentKey> candidateKeys = new HashSet<>();
        Map<String, Set<SegmentKey>> index = indexForWorld(worldId);
        int minBucketX = Math.floorDiv(minX, CACHE_BUCKET_SIZE);
        int maxBucketX = Math.floorDiv(maxX, CACHE_BUCKET_SIZE);
        int minBucketZ = Math.floorDiv(minZ, CACHE_BUCKET_SIZE);
        int maxBucketZ = Math.floorDiv(maxZ, CACHE_BUCKET_SIZE);
        for (int bucketX = minBucketX; bucketX <= maxBucketX; bucketX++) {
            for (int bucketZ = minBucketZ; bucketZ <= maxBucketZ; bucketZ++) {
                candidateKeys.addAll(index.getOrDefault(bucketKey(bucketX, bucketZ), Collections.emptySet()));
            }
        }

        int removed = 0;
        for (SegmentKey key : candidateKeys) {
            CachedSegment cached = cache.get(key);
            if (cached == null || !cached.intersects(minX, maxX, minZ, maxZ)) {
                continue;
            }
            cache.remove(key);
            unindexSegment(cached);
            removed++;
            for (UUID villageId : cached.getVillages()) {
                countersForVillage(villageId).recordInvalidationEvent();
            }
        }

        if (removed > 0) {
            LOGGER.info(String.format("[PATH] cache invalidated: segments=%d, reason=%s, bounds=(%d..%d,%d..%d)",
                removed, reason, minX, maxX, minZ, maxZ));
        }

        return new InvalidationResult(removed, reason, minX, maxX, minZ, maxZ);
    }

    AutoCloseable acquirePlannerPermitForTesting(UUID villageId) {
        return plannerLimiter.acquire(villageId);
    }

    PlannerStateSnapshot getPlannerStateSnapshot() {
        return plannerLimiter.snapshot();
    }

    static SegmentKey createNormalizedSegmentKey(World world, Location start, Location end) {
        return SegmentKey.normalized(world.getUID(), start, end);
    }

    private static PerfCounters countersForVillage(UUID villageId) {
        return VILLAGE_COUNTERS.computeIfAbsent(villageId, ignored -> new PerfCounters());
    }

    private static Map<SegmentKey, CachedSegment> cacheForWorld(UUID worldId) {
        return SEGMENT_CACHE.computeIfAbsent(worldId, ignored -> new ConcurrentHashMap<>());
    }

    private static Map<String, Set<SegmentKey>> indexForWorld(UUID worldId) {
        return SEGMENT_INDEX.computeIfAbsent(worldId, ignored -> new ConcurrentHashMap<>());
    }

    private static void indexSegment(CachedSegment segment) {
        Map<String, Set<SegmentKey>> index = indexForWorld(segment.key.worldId);
        for (String bucket : segment.bucketKeys()) {
            index.computeIfAbsent(bucket, ignored -> ConcurrentHashMap.newKeySet()).add(segment.key);
        }
    }

    private static void unindexSegment(CachedSegment segment) {
        Map<String, Set<SegmentKey>> index = indexForWorld(segment.key.worldId);
        for (String bucket : segment.bucketKeys()) {
            Set<SegmentKey> keys = index.get(bucket);
            if (keys == null) {
                continue;
            }
            keys.remove(segment.key);
            if (keys.isEmpty()) {
                index.remove(bucket);
            }
        }
    }

    private static int countCacheEntries(UUID worldId) {
        return cacheForWorld(worldId).size();
    }

    private static int getTotalCacheEntries() {
        int total = 0;
        for (Map<SegmentKey, CachedSegment> cache : SEGMENT_CACHE.values()) {
            total += cache.size();
        }
        return total;
    }

    private static String bucketKey(int bucketX, int bucketZ) {
        return bucketX + ":" + bucketZ;
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

    static final class PlannerStateSnapshot {
        private final int active;
        private final int queued;
        private final int cap;

        private PlannerStateSnapshot(int active, int queued, int cap) {
            this.active = active;
            this.queued = queued;
            this.cap = cap;
        }

        int getActive() {
            return active;
        }

        int getQueued() {
            return queued;
        }

        int getCap() {
            return cap;
        }
    }

    public static final class InvalidationResult {
        private final int segmentsRemoved;

        private InvalidationResult(int segmentsRemoved, String reason, int minX, int maxX, int minZ, int maxZ) {
            this.segmentsRemoved = segmentsRemoved;
        }

        public int getSegmentsRemoved() {
            return segmentsRemoved;
        }
    }

    static final class SegmentKey {
        private final UUID worldId;
        private final int startX;
        private final int startZ;
        private final int endX;
        private final int endZ;

        private SegmentKey(UUID worldId, int startX, int startZ, int endX, int endZ) {
            this.worldId = worldId;
            this.startX = startX;
            this.startZ = startZ;
            this.endX = endX;
            this.endZ = endZ;
        }

        static SegmentKey normalized(UUID worldId, Location start, Location end) {
            int ax = start.getBlockX();
            int az = start.getBlockZ();
            int bx = end.getBlockX();
            int bz = end.getBlockZ();
            boolean reverse = ax > bx || (ax == bx && az > bz);
            return reverse ? new SegmentKey(worldId, bx, bz, ax, az) : new SegmentKey(worldId, ax, az, bx, bz);
        }

        boolean isReversed(Location start, Location end) {
            return start.getBlockX() != startX || start.getBlockZ() != startZ
                || end.getBlockX() != endX || end.getBlockZ() != endZ;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof SegmentKey)) {
                return false;
            }
            SegmentKey that = (SegmentKey) other;
            return startX == that.startX && startZ == that.startZ && endX == that.endX
                && endZ == that.endZ && Objects.equals(worldId, that.worldId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(worldId, startX, startZ, endX, endZ);
        }
    }

    private static final class CachedSegment {
        private final SegmentKey key;
        private final List<PathCoordinate> coordinates;
        private final int minX;
        private final int maxX;
        private final int minZ;
        private final int maxZ;
        private final Set<UUID> villages = ConcurrentHashMap.newKeySet();

        private CachedSegment(SegmentKey key, List<PathCoordinate> coordinates) {
            this.key = key;
            this.coordinates = Collections.unmodifiableList(new ArrayList<>(coordinates));
            int localMinX = Integer.MAX_VALUE;
            int localMaxX = Integer.MIN_VALUE;
            int localMinZ = Integer.MAX_VALUE;
            int localMaxZ = Integer.MIN_VALUE;
            for (PathCoordinate coordinate : coordinates) {
                localMinX = Math.min(localMinX, coordinate.x);
                localMaxX = Math.max(localMaxX, coordinate.x);
                localMinZ = Math.min(localMinZ, coordinate.z);
                localMaxZ = Math.max(localMaxZ, coordinate.z);
            }
            this.minX = localMinX;
            this.maxX = localMaxX;
            this.minZ = localMinZ;
            this.maxZ = localMaxZ;
        }

        void registerVillage(UUID villageId) {
            villages.add(villageId);
        }

        Set<UUID> getVillages() {
            return villages;
        }

        boolean intersects(int otherMinX, int otherMaxX, int otherMinZ, int otherMaxZ) {
            return minX <= otherMaxX && maxX >= otherMinX && minZ <= otherMaxZ && maxZ >= otherMinZ;
        }

        List<String> bucketKeys() {
            List<String> keys = new ArrayList<>();
            int minBucketX = Math.floorDiv(minX, CACHE_BUCKET_SIZE);
            int maxBucketX = Math.floorDiv(maxX, CACHE_BUCKET_SIZE);
            int minBucketZ = Math.floorDiv(minZ, CACHE_BUCKET_SIZE);
            int maxBucketZ = Math.floorDiv(maxZ, CACHE_BUCKET_SIZE);
            for (int bucketX = minBucketX; bucketX <= maxBucketX; bucketX++) {
                for (int bucketZ = minBucketZ; bucketZ <= maxBucketZ; bucketZ++) {
                    keys.add(bucketKey(bucketX, bucketZ));
                }
            }
            return keys;
        }

        List<Block> materialize(World world, boolean reverse) {
            List<Block> blocks = new ArrayList<>();
            List<PathCoordinate> source = reverse ? new ArrayList<>(coordinates) : coordinates;
            if (reverse) {
                Collections.reverse(source);
            }
            for (PathCoordinate coordinate : source) {
                blocks.add(world.getBlockAt(coordinate.x, coordinate.y, coordinate.z));
            }
            return blocks;
        }
    }

    private static final class PathCoordinate {
        private final int x;
        private final int y;
        private final int z;

        private PathCoordinate(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private static final class SegmentResolution {
        private final List<Block> blocks;

        private SegmentResolution(List<Block> blocks, boolean cacheHit) {
            this.blocks = blocks;
        }
    }

    private static final class ResolvedPathContext {
        private final Location start;
        private final Location end;
        private final WalkableGraph graph;
        private final SurfaceSolver solver;

        private ResolvedPathContext(Location start, Location end, WalkableGraph graph, SurfaceSolver solver) {
            this.start = start;
            this.end = end;
            this.graph = graph;
            this.solver = solver;
        }
    }

    public static final class PlannerSettings {
        private final int maxNodesExplored;
        private final int plannerConcurrencyCap;
        private final int nodeCapRetryMaxAttempts;
        private final int nodeCapBackoffBaseMs;
        private final int nodeCapBackoffMaxMs;

        public PlannerSettings(int maxNodesExplored, int plannerConcurrencyCap, int nodeCapRetryMaxAttempts,
                               int nodeCapBackoffBaseMs, int nodeCapBackoffMaxMs) {
            this.maxNodesExplored = maxNodesExplored;
            this.plannerConcurrencyCap = plannerConcurrencyCap;
            this.nodeCapRetryMaxAttempts = nodeCapRetryMaxAttempts;
            this.nodeCapBackoffBaseMs = nodeCapBackoffBaseMs;
            this.nodeCapBackoffMaxMs = nodeCapBackoffMaxMs;
        }

        public static PlannerSettings defaults() {
            return new PlannerSettings(
                DEFAULT_MAX_NODES_EXPLORED,
                DEFAULT_PLANNER_CONCURRENCY_CAP,
                DEFAULT_NODE_CAP_RETRY_MAX_ATTEMPTS,
                DEFAULT_NODE_CAP_BACKOFF_BASE_MS,
                DEFAULT_NODE_CAP_BACKOFF_MAX_MS
            );
        }
    }

    private static final class PlannerLimiter {
        private final Semaphore semaphore;
        private final int cap;
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger queued = new AtomicInteger();

        private PlannerLimiter(int cap) {
            this.cap = cap;
            this.semaphore = new Semaphore(cap, true);
        }

        private PlannerPermit acquire(UUID villageId) {
            boolean acquired = semaphore.tryAcquire();
            if (!acquired) {
                int queuedNow = queued.incrementAndGet();
                LOGGER.info(String.format("[PATH] planner queued: active=%d queued=%d cap=%d",
                    active.get(), queuedNow, cap));
                long waitStarted = System.nanoTime();
                try {
                    semaphore.acquire();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return new PlannerPermit(this, false);
                } finally {
                    queued.decrementAndGet();
                    if (villageId != null) {
                        long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - waitStarted);
                        countersForVillage(villageId).recordPlannerQueueWait(waitedMs);
                    }
                }
            }

            int activeNow = active.incrementAndGet();
            LOGGER.info(String.format("[PATH] planners: active=%d queued=%d cap=%d",
                activeNow, queued.get(), cap));
            return new PlannerPermit(this, true);
        }

        private PlannerStateSnapshot snapshot() {
            return new PlannerStateSnapshot(active.get(), queued.get(), cap);
        }

        private void release() {
            int activeNow = active.decrementAndGet();
            semaphore.release();
            LOGGER.info(String.format("[PATH] planners: active=%d queued=%d cap=%d",
                activeNow, queued.get(), cap));
        }
    }

    private static final class PlannerPermit implements AutoCloseable {
        private final PlannerLimiter limiter;
        private final boolean acquired;

        private PlannerPermit(PlannerLimiter limiter, boolean acquired) {
            this.limiter = limiter;
            this.acquired = acquired;
        }

        @Override
        public void close() {
            if (acquired) {
                limiter.release();
            }
        }
    }

    private static final class PathSearchResult {
        private final List<PathNode> path;
        private final int nodesExplored;
        private final int nodeCap;
        private final boolean nodeCapHit;
        private final String failureReason;

        private PathSearchResult(List<PathNode> path, int nodesExplored, int nodeCap,
                                 boolean nodeCapHit, String failureReason) {
            this.path = path;
            this.nodesExplored = nodesExplored;
            this.nodeCap = nodeCap;
            this.nodeCapHit = nodeCapHit;
            this.failureReason = failureReason;
        }

        private static PathSearchResult success(List<PathNode> path, int nodesExplored, int nodeCap) {
            return new PathSearchResult(path, nodesExplored, nodeCap, false, null);
        }

        private static PathSearchResult failure(int nodesExplored, int nodeCap, boolean nodeCapHit,
                                                String failureReason) {
            return new PathSearchResult(Collections.emptyList(), nodesExplored, nodeCap, nodeCapHit, failureReason);
        }

        private boolean isSuccess() {
            return path != null && !path.isEmpty() && failureReason == null;
        }
    }
}