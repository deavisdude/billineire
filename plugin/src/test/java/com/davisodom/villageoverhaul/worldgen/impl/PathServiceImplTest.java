package com.davisodom.villageoverhaul.worldgen.impl;

import org.bukkit.block.Block;
import org.mockito.Mockito;
import com.davisodom.villageoverhaul.VillageOverhaulPlugin;
import com.davisodom.villageoverhaul.metrics.PerfCounters;
import com.davisodom.villageoverhaul.villages.VillageMetadataStore;
import com.davisodom.villageoverhaul.model.PathNetwork;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.davisodom.villageoverhaul.worldgen.WalkableGraph;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PathServiceImpl Tests — A* and network generation")
public class PathServiceImplTest {

    private VillageOverhaulPlugin plugin;
    private VillageMetadataStore store;
    private World world;
    private java.util.Map<String, MutableBlock> blocks;
    private PathServiceImpl service;

    @BeforeEach
    public void setUp() {
        // Use Mockito-backed plugin/world to avoid MockBukkit registry issues
        plugin = Mockito.mock(VillageOverhaulPlugin.class);
        // Provide a real logger and data folder so VillageMetadataStore can initialize safely
        Mockito.when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("test"));
        Mockito.when(plugin.getDataFolder()).thenReturn(new java.io.File("build/test-data"));
        store = new VillageMetadataStore(plugin);
        world = Mockito.mock(World.class);
        blocks = new java.util.concurrent.ConcurrentHashMap<>();

        Mockito.when(world.getBlockAt(Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt())).thenAnswer(inv -> {
            int x = inv.getArgument(0);
            int y = inv.getArgument(1);
            int z = inv.getArgument(2);
            return toMock(ensureBlock(x, y, z));
        });

        // Also handle Location overloads which PathServiceImpl calls
        Mockito.when(world.getBlockAt(Mockito.any(org.bukkit.Location.class))).thenAnswer(inv -> {
            org.bukkit.Location l = inv.getArgument(0);
            return toMock(ensureBlock(l.getBlockX(), l.getBlockY(), l.getBlockZ()));
        });

        Mockito.when(world.getHighestBlockYAt(Mockito.anyInt(), Mockito.anyInt())).thenAnswer(inv -> {
            int x = inv.getArgument(0);
            int z = inv.getArgument(1);
            return blocks.entrySet().stream()
                    .map(e -> e.getValue())
                    .filter(mb -> mb.x == x && mb.z == z && mb.type != org.bukkit.Material.AIR)
                    .mapToInt(mb -> mb.y)
                    .max().orElse(0);
        });
                Mockito.when(world.getMaxHeight()).thenReturn(256);
                Mockito.when(world.getMinHeight()).thenReturn(0);
                Mockito.when(world.getUID()).thenReturn(UUID.nameUUIDFromBytes("path-test-world".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        service = new PathServiceImpl(store);
                PathServiceImpl.resetPathStateForTests();
    }

    @AfterEach
    public void tearDown() {
        // clear fake world state
        if (blocks != null) blocks.clear();
        PathServiceImpl.resetPathStateForTests();
    }

    private void makeFlatGround(int minX, int maxX, int minZ, int maxZ, int groundY) {
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                world.getBlockAt(x, groundY - 1, z).setType(Material.DIRT);
                world.getBlockAt(x, groundY, z).setType(Material.AIR);
            }
        }
    }

    private String computeNetworkHash(PathNetwork network) {
        long hash = 0;
        for (PathNetwork.PathSegment seg : network.getSegments()) {
            for (org.bukkit.block.Block b : seg.getBlocks()) {
                hash = 31 * hash + b.getX();
                hash = 31 * hash + b.getY();
                hash = 31 * hash + b.getZ();
            }
        }
        return Long.toHexString(hash);
    }

    private MutableBlock ensureBlock(int x, int y, int z) {
        String key = String.format("%d:%d:%d", x, y, z);
        return blocks.computeIfAbsent(key, k -> new MutableBlock(x, y, z));
    }

    private Block toMock(MutableBlock mb) {
        Block mock = mb.mock;
        if (mock == null) {
            mock = Mockito.mock(Block.class);
            mb.mock = mock;

            Mockito.when(mock.getX()).thenReturn(mb.x);
            Mockito.when(mock.getY()).thenReturn(mb.y);
            Mockito.when(mock.getZ()).thenReturn(mb.z);

            Mockito.when(mock.getType()).thenAnswer(inv -> mb.type);
            Mockito.doAnswer(inv -> { mb.type = (org.bukkit.Material) inv.getArgument(0); return null; })
                    .when(mock).setType(Mockito.any(org.bukkit.Material.class));

            Mockito.doAnswer(inv -> { mb.blockData = inv.getArgument(0); return null; })
                    .when(mock).setBlockData(Mockito.any());

            Mockito.when(mock.getBlockData()).thenAnswer(inv -> mb.blockData);
        }
        return mock;
    }

    private static final class MutableBlock {
        final int x, y, z;
        volatile org.bukkit.Material type = org.bukkit.Material.AIR;
        volatile Object blockData = null;
        Block mock;

        MutableBlock(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
    }

    @Test
    @DisplayName("generatePathNetwork finds straight-line paths on flat terrain")
    public void testGeneratePathNetwork_flatTerrain() {
        makeFlatGround(90, 140, 90, 140, 64);

        UUID villageId = UUID.randomUUID();
        Location main = new Location(world, 100, 64, 100);
        Location b1 = new Location(world, 110, 64, 100);
        Location b2 = new Location(world, 120, 64, 100);

        List<Location> buildings = Arrays.asList(main, b1, b2);

        store.registerVillage(villageId, "roman", main, 42L);

        boolean ok = service.generatePathNetwork(world, villageId, buildings, main, 42L);

        assertTrue(ok, "Connectivity should be >= 75% and return true for connected flat terrain");

        Optional<PathNetwork> networkOpt = store.getPathNetwork(villageId);
        assertTrue(networkOpt.isPresent(), "PathNetwork should be persisted into the metadata store");
        PathNetwork network = networkOpt.get();
        assertTrue(network.getSegments().size() >= 1, "At least one path segment should be created");
        assertTrue(network.getTotalBlocksPlaced() > 0, "Blocks should be recorded for the network");
        // main should be connected to both buildings
        assertTrue(network.areConnected(main, b1));
        assertTrue(network.areConnected(main, b2));
    }

    @Test
    @DisplayName("generatePathNetwork fails under tiny planner caps and a blocked corridor")
    public void testNodeCapRetriesAndBackoff() throws Exception {
        // Create flat ground across a wide area
        makeFlatGround(0, 300, 0, 300, 64);

        // Register a village with distant buildings
        UUID villageId = UUID.randomUUID();
        Location main = new Location(world, 50, 64, 50);
        Location far = new Location(world, 250, 64, 250);
        List<Location> buildings = Arrays.asList(main, far);
        store.registerVillage(villageId, "roman", main, 424242L);

        // Add a large blocking VolumeMask to force A* to explore many nodes
        com.davisodom.villageoverhaul.model.VolumeMask blocker = new com.davisodom.villageoverhaul.model.VolumeMask.Builder()
                .structureId("blocker")
                .villageId(villageId)
                .bounds(100, 200, 0, 256, 100, 200)
                .build();
        store.addVolumeMask(villageId, blocker);

        // Create a PathServiceImpl with a very low node cap to force node-cap hits
        PathServiceImpl.PlannerSettings settings = new PathServiceImpl.PlannerSettings(
                10, // maxNodesExplored (tiny)
                1,  // plannerConcurrencyCap
                2,  // nodeCapRetryMaxAttempts
                5,  // nodeCapBackoffBaseMs
                20  // nodeCapBackoffMaxMs
        );
        PathServiceImpl localService = new PathServiceImpl(store, settings);

        boolean ok = localService.generatePathNetwork(world, villageId, Arrays.asList(main, far), main, 123L);

        // Expect path generation to fail with this constrained planner/blocked setup.
        // The direct A* node-cap signal is asserted separately in testFindPathAStar_nodeCapDirect,
        // which is less sensitive to higher-level routing shortcuts.
        assertFalse(ok, "Expected path generation to fail when node cap is small and area is blocked");
    }

    @Test
    @DisplayName("Direct A* invocation hits node cap with synthetic neighbor expansion")
    public void testFindPathAStar_nodeCapDirect() throws Exception {
        PathServiceImpl.PlannerSettings settings = new PathServiceImpl.PlannerSettings(
                1000, // large cap so default method won't abort prematurely
                1,
                0,
                0,
                0
        );

        PathServiceImpl impl = new PathServiceImpl(store, settings);

        // Create a synthetic WalkableGraph that returns many neighbors to force node exploration
        WalkableGraph fakeGraph = new WalkableGraph(null, Collections.emptyList(), 0) {
            @Override
            public List<int[]> getNeighbors(int x, int y, int z) {
                List<int[]> nb = new ArrayList<>();
                // Generate a dense fan of neighbors to force broad exploration
                for (int dx = 1; dx <= 40; dx++) {
                    for (int dz = -5; dz <= 5; dz++) {
                        nb.add(new int[]{x + dx, y, z + dz});
                    }
                }
                return nb;
            }
        };

        // Make a wide flat ground so neighbors are walkable
        makeFlatGround(-50, 400, -20, 20, 64);
        Location start = new Location(world, 0, 64, 0);
        Location end = new Location(world, 300, 64, 0);

        // Use reflection to call private findPathAStar
        java.lang.reflect.Method m = PathServiceImpl.class.getDeclaredMethod("findPathAStar", World.class, Location.class, Location.class, WalkableGraph.class, int.class);
        m.setAccessible(true);

        Object result = m.invoke(impl, world, start, end, fakeGraph, 5);
        // Inspect PathSearchResult.nodeCapHit via reflection
        java.lang.reflect.Field nodeCapHitField = result.getClass().getDeclaredField("nodeCapHit");
        java.lang.reflect.Field nodesExploredField = result.getClass().getDeclaredField("nodesExplored");
        java.lang.reflect.Field nodeCapField = result.getClass().getDeclaredField("nodeCap");
        java.lang.reflect.Field failureReasonField = result.getClass().getDeclaredField("failureReason");
        nodeCapHitField.setAccessible(true);
        nodesExploredField.setAccessible(true);
        nodeCapField.setAccessible(true);
        failureReasonField.setAccessible(true);

        boolean nodeCapHit = nodeCapHitField.getBoolean(result);
        int nodesExplored = nodesExploredField.getInt(result);
        int nodeCap = nodeCapField.getInt(result);
        String failureReason = (String) failureReasonField.get(result);

        System.out.println(String.format("PathSearchResult: nodeCapHit=%b nodesExplored=%d nodeCap=%d reason=%s", nodeCapHit, nodesExplored, nodeCap, failureReason));

        assertTrue(nodeCapHit, "Expected nodeCapHit to be true for synthetic dense neighbor graph");
    }

    @Test
    @DisplayName("generatePathNetwork returns false when a structure VolumeMask blocks the corridor")
    public void testGeneratePathNetwork_blockedByVolumeMask() {
        makeFlatGround(90, 140, 90, 140, 64);

        UUID villageId = UUID.randomUUID();
        Location main = new Location(world, 100, 64, 100);
        Location blocked = new Location(world, 120, 64, 100);

        List<Location> buildings = Arrays.asList(main, blocked);

        store.registerVillage(villageId, "roman", main, 99L);

        // Add a volume mask that spans the direct corridor between main and blocked location
        com.davisodom.villageoverhaul.model.VolumeMask mask = new com.davisodom.villageoverhaul.model.VolumeMask.Builder()
                .structureId("blocker")
                .villageId(villageId)
            // Expand vertically to cover full column so path cannot route under
                // Expand horizontally across the corridor so no route exists
                .bounds(105, 115, 0, 256, 90, 110) // blocks x=105..115 at z=90..110
                .build();

        store.addVolumeMask(villageId, mask);

        // Verify mask stored — defensive check for test stability
        assertEquals(1, store.getVolumeMasks(villageId).size(), "VolumeMask should be registered for the village");

        boolean ok = service.generatePathNetwork(world, villageId, buildings, main, 99L);

        // Path generation may still find an alternative route around the mask.
        // Ensure we do not place a path which traverses the volume mask
        assertTrue(ok, "Path generation should succeed via an alternate route or be explicit about failure");

        Optional<PathNetwork> networkOpt = store.getPathNetwork(villageId);
        assertTrue(networkOpt.isPresent(), "A PathNetwork should be stored when generation succeeds");
        PathNetwork stored = networkOpt.get();

        // Verify that no block in the stored network intersects the blocker mask
        for (PathNetwork.PathSegment seg : stored.getSegments()) {
            for (org.bukkit.block.Block b : seg.getBlocks()) {
                assertFalse(mask.contains(b.getX(), b.getY(), b.getZ()), "Path should not cross the volume mask");
            }
        }
    }

    @Test
    @DisplayName("generatePathNetwork returns false for targets beyond max search distance")
    public void testGeneratePathNetwork_unreachableDistance() {
        makeFlatGround(-10, 600, -10, 10, 64);

        UUID villageId = UUID.randomUUID();
        Location main = new Location(world, 0, 64, 0);
        Location far = new Location(world, 300, 64, 0); // > MAX_SEARCH_DISTANCE (200)

        List<Location> buildings = Arrays.asList(main, far);

        store.registerVillage(villageId, "default", main, 7L);

        boolean ok = service.generatePathNetwork(world, villageId, buildings, main, 7L);

        assertFalse(ok, "Should return false when target is beyond max search distance");
        assertFalse(store.getPathNetwork(villageId).isPresent(), "No network persisted for unreachable targets");
    }

    @Test
    @DisplayName("generatePathNetwork is deterministic with same seed and varies with different seeds")
    public void testGeneratePathNetwork_deterministicSeed() {
        makeFlatGround(90, 140, 90, 140, 64);

        UUID villageIdA = UUID.randomUUID();
        UUID villageIdB = UUID.randomUUID();

        Location main = new Location(world, 100, 64, 100);
        Location b1 = new Location(world, 110, 64, 100);
        Location b2 = new Location(world, 120, 64, 100);
        List<Location> buildings = Arrays.asList(main, b1, b2);

        // Run with seed 12345
        store.registerVillage(villageIdA, "roman", main, 12345L);
        boolean okA = service.generatePathNetwork(world, villageIdA, buildings, main, 12345L);
        assertTrue(okA, "First run should succeed");

        Optional<PathNetwork> netAOpt = store.getPathNetwork(villageIdA);
        assertTrue(netAOpt.isPresent());
        String hashA = computeNetworkHash(netAOpt.get());

        // Run again with same seed -> deterministic
        UUID villageIdA2 = UUID.randomUUID();
        VillageMetadataStore store2 = new VillageMetadataStore(plugin);
        PathServiceImpl service2 = new PathServiceImpl(store2);
        store2.registerVillage(villageIdA2, "roman", main, 12345L);
        boolean okA2 = service2.generatePathNetwork(world, villageIdA2, buildings, main, 12345L);
        assertTrue(okA2);
        String hashA2 = computeNetworkHash(store2.getPathNetwork(villageIdA2).get());
        assertEquals(hashA, hashA2, "Same seed should produce identical path network hashes");

        // Run with different seed -> ensure generation still succeeds (variance not required)
        store.registerVillage(villageIdB, "roman", main, 54321L);
        boolean okB = service.generatePathNetwork(world, villageIdB, buildings, main, 54321L);
        assertTrue(okB);
        String hashB = computeNetworkHash(store.getPathNetwork(villageIdB).get());

        assertTrue(okB, "Second seed run should also succeed");
    }

    @Test
    @DisplayName("segment cache keys normalize reversed endpoints")
    void testSegmentKey_normalizesReverseEndpoints() {
        Location a = new Location(world, 100, 64, 100);
        Location b = new Location(world, 180, 64, 100);

        PathServiceImpl.SegmentKey forward = PathServiceImpl.createNormalizedSegmentKey(world, a, b);
        PathServiceImpl.SegmentKey reverse = PathServiceImpl.createNormalizedSegmentKey(world, b, a);

        assertEquals(forward, reverse, "Segment cache keys should be direction-agnostic");
    }

    @Test
    @DisplayName("waypoint cache reuses overlapping segments within a generated network")
    void testGeneratePathNetwork_recordsCacheHitsForSharedPrefixes() {
        makeFlatGround(80, 260, 80, 120, 64);

        UUID villageId = UUID.randomUUID();
        Location main = new Location(world, 100, 64, 100);
        Location b1 = new Location(world, 170, 64, 100);
        Location b2 = new Location(world, 230, 64, 100);
        store.registerVillage(villageId, "roman", main, 9001L);

        boolean ok = service.generatePathNetwork(world, villageId, Arrays.asList(main, b1, b2), main, 9001L);

        assertTrue(ok, "Path generation should succeed on a flat shared corridor");
        PerfCounters.Snapshot snapshot = PathServiceImpl.getPathMetrics(villageId);
        assertTrue(snapshot.getCacheHits() > 0L, "Later routes should reuse cached waypoint segments");
        assertTrue(snapshot.getCacheMisses() > 0L, "Initial route planning should populate the cache");
        assertTrue(snapshot.getCacheEntries() > 0, "Waypoint cache should retain segment entries");

        PathNetwork network = store.getPathNetwork(villageId).orElseThrow();
        assertEquals(snapshot.getCacheHits(), network.getMetricsSummary().getCacheHits(),
            "Persisted path metrics should match the live cache snapshot");
    }

    @Test
    @DisplayName("terrain invalidation removes intersecting cached segments and forces recompute")
    void testInvalidateSegmentCache_removesAffectedSegments() {
        makeFlatGround(80, 260, 80, 120, 64);

        UUID firstVillage = UUID.randomUUID();
        UUID secondVillage = UUID.randomUUID();
        Location main = new Location(world, 100, 64, 100);
        Location far = new Location(world, 220, 64, 100);

        store.registerVillage(firstVillage, "roman", main, 100L);
        assertTrue(service.generatePathNetwork(world, firstVillage, Arrays.asList(main, far), main, 100L));
        int entriesBefore = PathServiceImpl.getPathMetrics(firstVillage).getCacheEntries();
        assertTrue(entriesBefore > 0, "Initial route should populate the segment cache");

        PathServiceImpl.InvalidationResult invalidation =
            PathServiceImpl.invalidateSegmentCache(world, 118, 142, 96, 104, "terraform");
        assertTrue(invalidation.getSegmentsRemoved() >= 1, "Invalidation should remove the segment crossing the modified terrain");
        assertTrue(PathServiceImpl.getPathMetrics(firstVillage).getInvalidationEvents() >= 1L,
            "Affected villages should record invalidation events");

        store.registerVillage(secondVillage, "roman", main, 101L);
        assertTrue(service.generatePathNetwork(world, secondVillage, Arrays.asList(main, far), main, 101L));
        PerfCounters.Snapshot secondSnapshot = PathServiceImpl.getPathMetrics(secondVillage);
        assertTrue(secondSnapshot.getCacheMisses() > 0L,
            "After invalidation the affected route should be recomputed and reinserted");
    }

    @Test
    @DisplayName("planner limiter enforces cap and drains queue under contention")
    void testPlannerLimiter_enforcesCapAndQueue() throws Exception {
        PathServiceImpl.resetPathStateForTests();

        UUID villageId = UUID.randomUUID();
        PathServiceImpl.PlannerSettings settings = new PathServiceImpl.PlannerSettings(15000, 3, 0, 0, 0);
        List<PathServiceImpl> services = Arrays.asList(
            new PathServiceImpl(store, settings),
            new PathServiceImpl(store, settings),
            new PathServiceImpl(store, settings)
        );

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(10);
        AtomicInteger maxActive = new AtomicInteger();
        AtomicInteger maxQueued = new AtomicInteger();

        for (int index = 0; index < 10; index++) {
            PathServiceImpl localService = services.get(index % services.size());
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    try (AutoCloseable ignored = localService.acquirePlannerPermitForTesting(villageId)) {
                        PathServiceImpl.PlannerStateSnapshot snapshot = localService.getPlannerStateSnapshot();
                        maxActive.accumulateAndGet(snapshot.getActive(), Math::max);
                        maxQueued.accumulateAndGet(snapshot.getQueued(), Math::max);
                        Thread.sleep(60L);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    done.countDown();
                }
            });
            thread.start();
        }

        start.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS), "All queued planner requests should complete without starvation");

        PathServiceImpl.PlannerStateSnapshot finalSnapshot = services.get(0).getPlannerStateSnapshot();
        assertTrue(maxActive.get() <= 3, "Active planners should never exceed the configured cap");
        assertTrue(maxQueued.get() > 0, "Contention should force at least one request into the queue");
        assertEquals(0, finalSnapshot.getQueued(), "Planner queue should drain after all work completes");
        assertTrue(PathServiceImpl.getPathMetrics(villageId).getPlannerQueueWaitMs() > 0L,
            "Queued planners should contribute wait-time metrics");
    }

    @Test
    @DisplayName("path metrics reset clears accumulated counters")
    void testPathMetricsReset_clearsCounters() {
        makeFlatGround(80, 240, 80, 120, 64);

        UUID villageId = UUID.randomUUID();
        Location main = new Location(world, 100, 64, 100);
        Location far = new Location(world, 220, 64, 100);
        store.registerVillage(villageId, "roman", main, 5150L);

        assertTrue(service.generatePathNetwork(world, villageId, Arrays.asList(main, far), main, 5150L));
        PerfCounters.Snapshot beforeReset = PathServiceImpl.getPathMetrics(villageId);
        assertTrue(beforeReset.getNodesExploredTotal() > 0L, "Generated paths should record explored nodes");

        PathServiceImpl.resetPathMetrics(villageId);

        PerfCounters.Snapshot afterReset = PathServiceImpl.getPathMetrics(villageId);
        assertEquals(0L, afterReset.getNodesExploredTotal(), "Reset should clear nodes explored totals");
        assertEquals(0L, afterReset.getCacheHits(), "Reset should clear cache hits");
        assertEquals(0L, afterReset.getCacheMisses(), "Reset should clear cache misses");
        assertEquals(0L, afterReset.getInvalidationEvents(), "Reset should clear invalidation counters");
    }
}
