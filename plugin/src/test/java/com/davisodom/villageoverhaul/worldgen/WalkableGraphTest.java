package com.davisodom.villageoverhaul.worldgen;

import com.davisodom.villageoverhaul.model.VolumeMask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WalkableGraphTest {

    @Mock
    private SurfaceSolver surfaceSolver;

    private WalkableGraph graph;
    private VolumeMask mask;

    @BeforeEach
    void setUp() {
        // Create a mask at (10,10) size 5x5x5
        mask = new VolumeMask.Builder()
                .structureId("test-struct")
                .villageId(java.util.UUID.randomUUID())
                .bounds(10, 15, 60, 65, 10, 15)
                .build();
        
        // Graph with buffer=2
        graph = new WalkableGraph(surfaceSolver, Collections.singletonList(mask), 2);
    }

    @Test
    void testIsObstacle_InsideMask() {
        // Inside original mask
        assertTrue(graph.isObstacle(12, 62, 12));
    }

    @Test
    void testIsObstacle_InsideBuffer() {
        // Outside original mask but inside buffer (buffer=2)
        // Mask X: 10..15 -> Expanded: 8..17
        assertTrue(graph.isObstacle(9, 62, 12));
        assertTrue(graph.isObstacle(16, 62, 12));
    }

    @Test
    void testIsObstacle_OutsideBuffer() {
        // Outside expanded mask
        assertFalse(graph.isObstacle(7, 62, 12));
        assertFalse(graph.isObstacle(18, 62, 12));
    }

    @Test
    void testIsWalkable_ValidSurface() {
        // Setup surface at Y=50
        lenient().when(surfaceSolver.getSurfaceHeight(20, 20)).thenReturn(50);
        
        // Node at Y=51 (G+1) should be walkable
        assertTrue(graph.isWalkable(20, 51, 20));
        
        // Node at Y=50 (G) should be walkable (±1 tolerance)
        assertTrue(graph.isWalkable(20, 50, 20));
        
        // Node at Y=52 (G+2) should be walkable (±1 tolerance)
        assertTrue(graph.isWalkable(20, 52, 20));
    }

    @Test
    void testIsWalkable_InvalidHeight() {
        // Setup surface at Y=50
        lenient().when(surfaceSolver.getSurfaceHeight(20, 20)).thenReturn(50);
        
        // Node at Y=55 is too high
        assertFalse(graph.isWalkable(20, 55, 20));
    }

    @Test
    void testIsWalkable_Obstacle() {
        // Setup surface at Y=62 (inside mask height range)
        lenient().when(surfaceSolver.getSurfaceHeight(12, 12)).thenReturn(62);
        
        // Even if height matches, it's inside an obstacle
        assertFalse(graph.isWalkable(12, 63, 12));
    }

    @Test
    void testGetNeighbors() {
        // Center at (20, 51, 20), surface at 50
        // Neighbor at (21, 51, 20), surface at 50 -> Valid
        // Neighbor at (19, 51, 20), surface at 51 -> Valid (step up)
        // Neighbor at (20, 51, 21), surface at 49 -> Valid (step down)
        // Neighbor at (20, 51, 19), surface at 55 -> Invalid (too steep)
        
        lenient().when(surfaceSolver.nearestWalkable(21, 20, 51)).thenReturn(OptionalInt.of(51));
        lenient().when(surfaceSolver.nearestWalkable(19, 20, 51)).thenReturn(OptionalInt.of(52));
        lenient().when(surfaceSolver.nearestWalkable(20, 21, 51)).thenReturn(OptionalInt.of(50));
        lenient().when(surfaceSolver.nearestWalkable(20, 19, 51)).thenReturn(OptionalInt.of(56)); // Too high
        
        // Mock other neighbors as empty/invalid for simplicity
        lenient().when(surfaceSolver.nearestWalkable(anyInt(), anyInt(), anyInt())).thenReturn(OptionalInt.empty());
        
        // Re-stub specific calls to override the general one if needed (Mockito order matters)
        when(surfaceSolver.nearestWalkable(21, 20, 51)).thenReturn(OptionalInt.of(51));
        when(surfaceSolver.nearestWalkable(19, 20, 51)).thenReturn(OptionalInt.of(52));
        when(surfaceSolver.nearestWalkable(20, 21, 51)).thenReturn(OptionalInt.of(50));
        when(surfaceSolver.nearestWalkable(20, 19, 51)).thenReturn(OptionalInt.of(56));

        List<int[]> neighbors = graph.getNeighbors(20, 51, 20);
        
        // Should find 3 valid neighbors
        // (21, 51, 20) -> dy=0
        // (19, 52, 20) -> dy=1
        // (20, 50, 21) -> dy=-1
        
        // Note: getNeighbors iterates 8 directions.
        // We only mocked 4 specific ones.
        // The others return empty (invalid).
        
        // Wait, my mock setup for "others" might override the specific ones if not careful.
        // Mockito matches arguments.
        // The specific ones match specific coords.
        // The general one matches anyInt().
        // If I define general first, then specific, specific wins? No, last defined wins?
        // Actually, specific matchers have higher priority usually, or order matters.
        // Let's just be explicit.
        
        // Let's check the count.
        // We expect 3 neighbors.
        // (21, 51, 20)
        // (19, 52, 20)
        // (20, 50, 21)
        
        // Wait, (20, 50, 21) is neighbor at (20, 21) in X/Z?
        // My test setup:
        // Center (20, 20)
        // Neighbor (21, 20) -> (21, 51, 20)
        // Neighbor (19, 20) -> (19, 52, 20)
        // Neighbor (20, 21) -> (20, 50, 21)
        // Neighbor (20, 19) -> (20, 56, 19) -> Invalid
        
        // Let's verify the list content
        boolean foundFlat = false;
        boolean foundUp = false;
        boolean foundDown = false;
        
        for (int[] n : neighbors) {
            if (n[0] == 21 && n[1] == 51 && n[2] == 20) foundFlat = true;
            if (n[0] == 19 && n[1] == 52 && n[2] == 20) foundUp = true;
            if (n[0] == 20 && n[1] == 50 && n[2] == 21) foundDown = true;
        }
        
        assertTrue(foundFlat, "Should find flat neighbor");
        assertTrue(foundUp, "Should find step-up neighbor");
        assertTrue(foundDown, "Should find step-down neighbor");
        assertEquals(3, neighbors.size());
    }
}
