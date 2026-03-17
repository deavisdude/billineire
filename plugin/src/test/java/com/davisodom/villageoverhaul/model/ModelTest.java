package com.davisodom.villageoverhaul.model;

import com.davisodom.villageoverhaul.projects.Project;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bukkit.Location;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class ModelTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void building_builder_and_json_roundtrip() throws Exception {
        UUID bId = UUID.randomUUID();
        UUID vId = UUID.randomUUID();

        Location origin = new Location(null, 10, 64, -2);

        Building b = new Building.Builder()
                .buildingId(bId)
                .villageId(vId)
                .structureId("roman_house")
                .origin(origin)
                .dimensions(5, 6, 7)
                .placedTimestamp(12345L)
                .isMainBuilding(true)
                .build();

        assertEquals(bId, b.getBuildingId());
        assertEquals(vId, b.getVillageId());
        assertEquals("roman_house", b.getStructureId());
        assertArrayEquals(new int[]{5,6,7}, b.getDimensions());
        assertTrue(b.isMainBuilding());

        // JSON round-trip via a generic map approach (models don't expose direct JSON APIs)
        Map<String,Object> obj = new HashMap<>();
        obj.put("buildingId", b.getBuildingId().toString());
        obj.put("villageId", b.getVillageId().toString());
        obj.put("structureId", b.getStructureId());
        obj.put("origin", Map.of("x", b.getOrigin().getBlockX(), "y", b.getOrigin().getBlockY(), "z", b.getOrigin().getBlockZ()));
        obj.put("dimensions", b.getDimensions());
        obj.put("placedTimestamp", b.getPlacedTimestamp());
        obj.put("isMainBuilding", b.isMainBuilding());

        String json = mapper.writeValueAsString(obj);

        @SuppressWarnings("unchecked")
        Map<String,Object> parsed = mapper.readValue(json, Map.class);

        // Reconstruct from parsed values
        @SuppressWarnings("unchecked")
        Map<String, Object> originMap = (Map<String, Object>) parsed.get("origin");
        int originX = ((Number) originMap.get("x")).intValue();
        int originY = ((Number) originMap.get("y")).intValue();
        int originZ = ((Number) originMap.get("z")).intValue();

        @SuppressWarnings("unchecked")
        List<Number> dims = (List<Number>) parsed.get("dimensions");
        int[] dimsInt = dims.stream().mapToInt(Number::intValue).toArray();

        Building reconstructed = new Building.Builder()
            .buildingId(UUID.fromString((String) parsed.get("buildingId")))
            .villageId(UUID.fromString((String) parsed.get("villageId")))
            .structureId((String) parsed.get("structureId"))
            .origin(new Location(null, originX, originY, originZ))
            .dimensions(dimsInt)
            .placedTimestamp(((Number)parsed.get("placedTimestamp")).longValue())
            .isMainBuilding((Boolean) parsed.get("isMainBuilding"))
            .build();

        assertEquals(b.getBuildingId(), reconstructed.getBuildingId());
        assertEquals(b.getVillageId(), reconstructed.getVillageId());
        assertEquals(b.getStructureId(), reconstructed.getStructureId());
        assertArrayEquals(b.getDimensions(), reconstructed.getDimensions());
        assertEquals(b.isMainBuilding(), reconstructed.isMainBuilding());
    }

    // helpers removed

    @Test
    public void placementQueue_transitions_and_progress() {
        UUID queueId = UUID.randomUUID();
        UUID buildingId = UUID.randomUUID();

        PlacementQueue.Entry e1 = new PlacementQueue.Entry(0,64,0, Material.STONE, null, 0, 0, 0);
        PlacementQueue.Entry e2 = new PlacementQueue.Entry(1,64,0, Material.STONE, null, 0, 1, 1);

        PlacementQueue pq = PlacementQueue.builder()
                .queueId(queueId)
                .buildingId(buildingId)
                .addEntry(e1)
                .addEntry(e2)
                .batchSize(1)
                .status(PlacementQueue.Status.PREPARING)
                .build();

        assertFalse(pq.isReadyForCommit());

        PlacementQueue ready = pq.withStatus(PlacementQueue.Status.READY, System.currentTimeMillis());
        assertTrue(ready.isReadyForCommit());

        // commit one batch
        PlacementQueue advanced = ready.withAdvancedIndex(1, System.currentTimeMillis());
        assertEquals(1, advanced.getBlocksPlaced());
        assertFalse(advanced.isFinished());

        // finish remaining
        PlacementQueue finished = advanced.withAdvancedIndex(2, System.currentTimeMillis());
        assertTrue(finished.isFinished());
        assertEquals(PlacementQueue.Status.COMPLETE, finished.getStatus());

        // abort path
        PlacementQueue aborted = pq.asAborted("error", System.currentTimeMillis());
        assertTrue(aborted.isFinished());
        assertEquals(PlacementQueue.Status.ABORTED, aborted.getStatus());
        assertEquals("error", aborted.getAbortReason());
    }

    @Test
    public void pathNetwork_connectivity_and_block_counting() {
        UUID villageId = UUID.randomUUID();
        be.seeseemelk.mockbukkit.ServerMock server = null;
        try {
            server = be.seeseemelk.mockbukkit.MockBukkit.mock();
            org.bukkit.World world = server.addSimpleWorld("model-test-world");

            Location a = new Location(world, 0, 64, 0);
            Location b = new Location(world, 0, 64, 10);

        PathNetwork.PathSegment seg = new PathNetwork.PathSegment(a, b, Collections.emptyList());

        PathNetwork pn = new PathNetwork.Builder()
                .villageId(villageId)
                .addSegment(seg)
                .build();

        assertTrue(pn.areConnected(a, b));
        List<Location> buildings = List.of(a, b);
        double connectivity = pn.calculateConnectivity(buildings, a);
        assertEquals(1.0, connectivity);
            assertEquals(0, pn.getTotalBlocksPlaced());
        } finally {
            if (server != null) be.seeseemelk.mockbukkit.MockBukkit.unmock();
        }
    }

    @Test
    public void project_validation_and_contributions() {
        UUID villageId = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class, () -> new Project(villageId, "", 1000, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Project(null, "house", 1000, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Project(villageId, "house", 0, List.of()));

        Project p = new Project(villageId, "blacksmith_tier_2", 1000, List.of("trade_slots:+2"));

        assertEquals(0, p.getProgressMillz());
        assertEquals(0, p.getCompletionPercent());

        // activate and contribute
        assertTrue(p.activate());
        assertFalse(p.activate());

        UUID player = UUID.randomUUID();
        Project.ContributionResult res = p.contribute(player, 300);
        assertEquals(300, res.getAccepted());
        assertFalse(res.isCompleted());
        assertEquals(30, p.getCompletionPercent());

        // overpay
        Project.ContributionResult r2 = p.contribute(player, 1000);
        assertTrue(r2.isCompleted());
        assertTrue(p.isFullyFunded());
        assertTrue(p.getProgressMillz() >= p.getCostMillz());
    }

    @Test
    public void building_validation_and_equality() {
        UUID bId = UUID.randomUUID();
        UUID vId = UUID.randomUUID();
        Location origin = new Location(null, 1,2,3);

        // missing dimensions -> constructor will NPE via requireNonNull
        assertThrows(NullPointerException.class, () -> new Building.Builder()
                .buildingId(bId)
                .villageId(vId)
                .structureId("f")
                .origin(origin)
                .placedTimestamp(1L)
                .build());

        Building a = new Building.Builder().buildingId(bId).villageId(vId).structureId("f").origin(origin).dimensions(1,2,3).placedTimestamp(1L).build();
        Building b = new Building.Builder().buildingId(bId).villageId(vId).structureId("g").origin(origin).dimensions(1,2,3).placedTimestamp(2L).build();

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertTrue(a.toString().contains("Building{id="));
    }

    @Test
    public void placementQueue_batching_and_validation_branches() {
        UUID queueId = UUID.randomUUID();
        UUID buildingId = UUID.randomUUID();

        // empty queue should be finished and percent 1.0
        PlacementQueue empty = PlacementQueue.builder().queueId(queueId).buildingId(buildingId).entries(new ArrayList<>()).batchSize(5).status(PlacementQueue.Status.READY).build();
        assertTrue(empty.isFinished() == false); // READY but no entries -> not finished by status alone
        assertEquals(1.0, empty.getPercentComplete());
        assertTrue(empty.getNextBatch().isEmpty());

        // invalid batch size should throw when building
        assertThrows(IllegalArgumentException.class, () -> PlacementQueue.builder().queueId(queueId).buildingId(buildingId).addEntry(new PlacementQueue.Entry(0,0,0, Material.STONE, null, 0,0,0)).batchSize(0).build());

        // coverage: getProgress when index in middle
        PlacementQueue.Entry e1 = new PlacementQueue.Entry(0,64,0, Material.DIRT, null, 0, 0, 0);
        PlacementQueue.Entry e2 = new PlacementQueue.Entry(1,64,0, Material.DIRT, null, 1, 0, 1);
        PlacementQueue pq = PlacementQueue.builder().queueId(queueId).buildingId(buildingId).addEntry(e1).addEntry(e2).batchSize(1).currentIndex(1).status(PlacementQueue.Status.COMMITTING).build();
        PlacementQueue.ConstructionProgress p = pq.getProgress();
        assertEquals(1, p.currentLayer());
        assertTrue(p.percentComplete() > 0 && p.percentComplete() < 1);
    }

    @Test
    public void pathNetwork_various_connectivity_branches() {
        be.seeseemelk.mockbukkit.ServerMock server = null;
        try {
            server = be.seeseemelk.mockbukkit.MockBukkit.mock();
            org.bukkit.World world = server.addSimpleWorld("model-test-world-2");

            Location a = new Location(world, 0, 64, 0);
            Location b = new Location(world, 0, 64, 10);
            Location c = new Location(world, 100, 64, 100);

            // segment uses block list which adds to total blocks count
            org.bukkit.block.Block block = world.getBlockAt(1,64,1);
            PathNetwork.PathSegment s1 = new PathNetwork.PathSegment(a, b, List.of(block));
            PathNetwork.PathSegment s2 = new PathNetwork.PathSegment(b, c, Collections.emptyList());

            PathNetwork pn = new PathNetwork.Builder().villageId(UUID.randomUUID()).addSegment(s1).addSegment(s2).build();

            // a->b directly connected
            assertTrue(pn.areConnected(a,b));
            // a->c indirectly via segment chain: calculateConnectivity only checks direct connectivity to mainBuilding so c will be not counted unless directly connected
            List<Location> locations = List.of(a, b, c);
            double conn = pn.calculateConnectivity(locations, a);
            assertTrue(conn >= 0 && conn <= 1);
            assertEquals(1, pn.getTotalBlocksPlaced());

            // toString branch
            assertTrue(pn.toString().contains("PathNetwork{village="));
        } finally {
            if (server != null) be.seeseemelk.mockbukkit.MockBukkit.unmock();
        }
    }
}
