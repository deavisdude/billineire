package com.davisodom.villageoverhaul.worldgen;

import com.davisodom.villageoverhaul.model.VolumeMask;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SurfaceSolverTest {

    @Mock
    private World world;

    private SurfaceSolver surfaceSolver;
    private VolumeMask mask;

    @BeforeEach
    void setUp() {
        // Create a mask at (0,0) size 10x10x10, from Y=60 to Y=70
        mask = new VolumeMask.Builder()
                .structureId("test-struct")
                .villageId(java.util.UUID.randomUUID())
                .bounds(0, 10, 60, 70, 0, 10)
                .build();

        lenient().when(world.getMaxHeight()).thenReturn(320);
        lenient().when(world.getMinHeight()).thenReturn(-64);
    }

    @Test
    void testNearestWalkable_OutsideMask() {
        // Setup world: ground at Y=50
        setupColumn(20, 20, 50, Material.GRASS_BLOCK);
        
        surfaceSolver = new SurfaceSolver(world, Collections.singletonList(mask));
        
        OptionalInt result = surfaceSolver.nearestWalkable(20, 20, 60);
        
        assertTrue(result.isPresent());
        assertEquals(51, result.getAsInt()); // Ground + 1
    }

    @Test
    void testNearestWalkable_InsideMask_GroundBelow() {
        // Setup world: ground at Y=50
        // Mask is at Y=60..70
        // We are querying at (5,5) which is inside the mask's X/Z bounds
        setupColumn(5, 5, 50, Material.GRASS_BLOCK);
        
        surfaceSolver = new SurfaceSolver(world, Collections.singletonList(mask));
        
        // The solver should see through the mask and find the ground at 50
        // And return 51 (which is NOT inside the mask)
        OptionalInt result = surfaceSolver.nearestWalkable(5, 5, 60);
        
        assertTrue(result.isPresent());
        assertEquals(51, result.getAsInt());
    }

    @Test
    void testNearestWalkable_InsideMask_GroundInsideMask() {
        // Setup world: ground at Y=65
        // Mask is at Y=60..70
        // Ground is inside the mask!
        // This means the structure is embedded in the ground or the ground is inside the structure.
        // The solver should ignore blocks inside the mask.
        // So it will ignore the ground at 65.
        // It will keep scanning down.
        // Let's say there is another ground at Y=40 (cave floor?)
        
        // We need to mock the column carefully.
        // getHighestBlockYAt might return 65.
        lenient().when(world.getHighestBlockYAt(5, 5)).thenReturn(65);
        
        // Mock blocks
        mockBlock(5, 65, 5, Material.STONE); // Inside mask
        // Blocks between 64 and 41 are AIR
        for (int y = 64; y > 40; y--) {
            mockBlock(5, y, 5, Material.AIR);
        }
        mockBlock(5, 40, 5, Material.STONE); // Solid ground below
        
        surfaceSolver = new SurfaceSolver(world, Collections.singletonList(mask));
        
        OptionalInt result = surfaceSolver.nearestWalkable(5, 5, 70);
        
        assertTrue(result.isPresent());
        assertEquals(41, result.getAsInt()); // Ground at 40 + 1
    }
    
    @Test
    void testNearestWalkable_ResultInsideMask() {
        // Setup world: ground at Y=59
        // Mask is at Y=60..70
        // Ground is at 59 (outside mask).
        // Walkable is at 60 (inside mask).
        // Solver should return empty.
        
        setupColumn(5, 5, 59, Material.STONE);
        
        surfaceSolver = new SurfaceSolver(world, Collections.singletonList(mask));
        
        OptionalInt result = surfaceSolver.nearestWalkable(5, 5, 70);
        
        assertFalse(result.isPresent());
    }

    private void setupColumn(int x, int z, int groundY, Material groundType) {
        lenient().when(world.getHighestBlockYAt(x, z)).thenReturn(groundY);
        
        // Mock the ground block
        mockBlock(x, groundY, z, groundType);
        
        // Mock air above
        // We only need to mock a few blocks above for the test
        // Start from groundY + 1 up to a reasonable height (e.g. mask top + 5)
        for (int y = groundY + 1; y <= 75; y++) {
            mockBlock(x, y, z, Material.AIR);
        }
    }
    
    private void mockBlock(int x, int y, int z, Material type) {
        Block block = mock(Block.class);
        lenient().when(block.getType()).thenReturn(type);
        lenient().when(world.getBlockAt(x, y, z)).thenReturn(block);
    }
}
