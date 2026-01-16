package com.davisodom.villageoverhaul.commands;

import com.davisodom.villageoverhaul.VillageOverhaulPlugin;
import com.davisodom.villageoverhaul.villages.VillageMetadataStore;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * Unit tests for VillageTerrainSearcher.
 * 
 * T071: Tests verifying that terrain search properly enforces minVillageSpacing
 * and prevents villages from being placed too close together.
 */
class VillageTerrainSearcherTest {
    
    @Mock
    private VillageOverhaulPlugin plugin;
    
    @Mock
    private VillageMetadataStore metadataStore;
    
    @Mock
    private World world;
    
    private VillageTerrainSearcher searcher;
    
    private static final int MIN_VILLAGE_SPACING = 200;
    
    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        searcher = new VillageTerrainSearcher(plugin, metadataStore);
        
        // Mock world name
        when(world.getName()).thenReturn("test_world");

        // Mock world height bounds for SurfaceSolver
        when(world.getMaxHeight()).thenReturn(320);
        when(world.getMinHeight()).thenReturn(0);
        
        // Mock heightmap - flat terrain at Y=64
        when(world.getHighestBlockYAt(anyInt(), anyInt())).thenReturn(64);
        
        // Mock block type - grass surface (suitable terrain)
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(invocation -> {
            org.bukkit.block.Block block = mock(org.bukkit.block.Block.class);
            when(block.getType()).thenReturn(Material.GRASS_BLOCK);
            return block;
        });
    }
    
    @Test
    void testIsFirstVillage_NoExistingVillages_ReturnsTrue() {
        // Given: No existing villages
        when(metadataStore.getAllVillages()).thenReturn(new ArrayList<>());
        
        // When: Check if first village
        boolean isFirst = searcher.isFirstVillage(world);
        
        // Then: Should return true
        assertTrue(isFirst, "Should be first village when no villages exist");
    }
    
    @Test
    void testIsFirstVillage_ExistingVillageInSameWorld_ReturnsFalse() {
        // Given: One existing village in the same world
        List<VillageMetadataStore.VillageMetadata> villages = new ArrayList<>();
        Location existingLocation = new Location(world, 0, 64, 0);
        VillageMetadataStore.VillageMetadata existingVillage = createMockVillage(existingLocation);
        villages.add(existingVillage);
        
        when(metadataStore.getAllVillages()).thenReturn(villages);
        
        // When: Check if first village
        boolean isFirst = searcher.isFirstVillage(world);
        
        // Then: Should return false
        assertFalse(isFirst, "Should not be first village when villages exist in same world");
    }
    
    @Test
    void testIsFirstVillage_ExistingVillageInDifferentWorld_ReturnsTrue() {
        // Given: One existing village in a different world
        World otherWorld = mock(World.class);
        when(otherWorld.getName()).thenReturn("other_world");
        
        List<VillageMetadataStore.VillageMetadata> villages = new ArrayList<>();
        Location existingLocation = new Location(otherWorld, 0, 64, 0);
        VillageMetadataStore.VillageMetadata existingVillage = createMockVillage(existingLocation);
        villages.add(existingVillage);
        
        when(metadataStore.getAllVillages()).thenReturn(villages);
        
        // When: Check if first village
        boolean isFirst = searcher.isFirstVillage(world);
        
        // Then: Should return true (first in THIS world)
        assertTrue(isFirst, "Should be first village in this world even if villages exist in other worlds");
    }
    
    @Test
    void testFindNearestVillageLocation_NoVillages_ReturnsNull() {
        // Given: No existing villages
        when(metadataStore.getAllVillages()).thenReturn(new ArrayList<>());
        Location searchOrigin = new Location(world, 500, 64, 500);
        
        // When: Find nearest village
        Location nearest = searcher.findNearestVillageLocation(world, searchOrigin);
        
        // Then: Should return null
        assertNull(nearest, "Should return null when no villages exist");
    }
    
    @Test
    void testFindNearestVillageLocation_MultipleVillages_ReturnsNearest() {
        // Given: Multiple existing villages
        List<VillageMetadataStore.VillageMetadata> villages = new ArrayList<>();
        
        Location village1 = new Location(world, 100, 64, 100);
        Location village2 = new Location(world, 500, 64, 500);
        Location village3 = new Location(world, 1000, 64, 1000);
        
        villages.add(createMockVillage(village1));
        villages.add(createMockVillage(village2));
        villages.add(createMockVillage(village3));
        
        when(metadataStore.getAllVillages()).thenReturn(villages);
        
        Location searchOrigin = new Location(world, 450, 64, 450);
        
        // When: Find nearest village
        Location nearest = searcher.findNearestVillageLocation(world, searchOrigin);
        
        // Then: Should return village2 (closest to search origin)
        assertNotNull(nearest, "Should find nearest village");
        assertEquals(500, nearest.getBlockX(), "Should return village at (500, 64, 500)");
        assertEquals(500, nearest.getBlockZ(), "Should return village at (500, 64, 500)");
    }
    
    @Test
    void testFindSuitableVillageLocation_TooCloseToExisting_ReturnsNull() {
        // Given: Existing village at (0, 64, 0)
        List<VillageMetadataStore.VillageMetadata> villages = new ArrayList<>();
        Location existingLocation = new Location(world, 0, 64, 0);
        VillageMetadataStore.VillageMetadata existingVillage = createMockVillage(existingLocation);
        villages.add(existingVillage);
        
        when(metadataStore.getAllVillages()).thenReturn(villages);
        
        // When: Search for location starting very close to existing village (within minVillageSpacing)
        Location searchOrigin = new Location(world, 50, 64, 50); // Too close (71 blocks)
        Location result = searcher.findSuitableVillageLocation(world, searchOrigin, 256, MIN_VILLAGE_SPACING);
        
        // Then: Should return null or a location far enough away
        if (result != null) {
            // If it found a location, it should be far enough away
            int dx = Math.abs(result.getBlockX() - existingLocation.getBlockX());
            int dz = Math.abs(result.getBlockZ() - existingLocation.getBlockZ());
            int distance = (int) Math.sqrt(dx * dx + dz * dz);
            assertTrue(distance >= MIN_VILLAGE_SPACING, 
                    "Found location should be at least " + MIN_VILLAGE_SPACING + " blocks away, but was " + distance);
        }
        // Otherwise null is acceptable (no suitable location found)
    }
    
    @Test
    void testFindSuitableVillageLocation_FarFromExisting_FindsLocation() {
        // Given: Existing village far away at (0, 64, 0)
        List<VillageMetadataStore.VillageMetadata> villages = new ArrayList<>();
        Location existingLocation = new Location(world, 0, 64, 0);
        VillageMetadataStore.VillageMetadata existingVillage = createMockVillage(existingLocation);
        villages.add(existingVillage);
        
        when(metadataStore.getAllVillages()).thenReturn(villages);
        
        // When: Search for location far from existing village
        Location searchOrigin = new Location(world, 1000, 64, 1000); // Far away
        Location result = searcher.findSuitableVillageLocation(world, searchOrigin, 256, MIN_VILLAGE_SPACING);
        
        // Then: Should find a suitable location
        assertNotNull(result, "Should find suitable location when far from existing villages");
        
        // Verify it respects minimum spacing
        int dx = Math.abs(result.getBlockX() - existingLocation.getBlockX());
        int dz = Math.abs(result.getBlockZ() - existingLocation.getBlockZ());
        int distance = (int) Math.sqrt(dx * dx + dz * dz);
        assertTrue(distance >= MIN_VILLAGE_SPACING, 
                "Found location should be at least " + MIN_VILLAGE_SPACING + " blocks away from existing village");
    }
    
    /**
     * Helper method to create a mock VillageMetadata with proper border.
     */
    private VillageMetadataStore.VillageMetadata createMockVillage(Location location) {
        UUID villageId = UUID.randomUUID();
        String cultureId = "test_culture";
        long seed = System.currentTimeMillis();
        
        // Create border (10x10 around origin for testing)
        VillageMetadataStore.VillageBorder border = new VillageMetadataStore.VillageBorder(
                location.getBlockX() - 5,
                location.getBlockX() + 5,
                location.getBlockZ() - 5,
                location.getBlockZ() + 5
        );
        
        VillageMetadataStore.VillageMetadata metadata = mock(VillageMetadataStore.VillageMetadata.class);
        when(metadata.getVillageId()).thenReturn(villageId);
        when(metadata.getCultureId()).thenReturn(cultureId);
        when(metadata.getOrigin()).thenReturn(location);
        when(metadata.getSeed()).thenReturn(seed);
        when(metadata.getBorder()).thenReturn(border);
        
        return metadata;
    }
}
