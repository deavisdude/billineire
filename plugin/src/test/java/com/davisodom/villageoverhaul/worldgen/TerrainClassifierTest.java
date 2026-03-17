package com.davisodom.villageoverhaul.worldgen;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;

public class TerrainClassifierTest {

    @Test
    @DisplayName("classify single-block materials returns expected categories")
    public void testClassifySingleBlockMaterials() {
        Block water = Mockito.mock(Block.class);
        Mockito.when(water.getType()).thenReturn(Material.WATER);

        Block lava = Mockito.mock(Block.class);
        Mockito.when(lava.getType()).thenReturn(Material.LAVA);

        Block air = Mockito.mock(Block.class);
        Mockito.when(air.getType()).thenReturn(Material.AIR);

        Block leaves = Mockito.mock(Block.class);
        Mockito.when(leaves.getType()).thenReturn(Material.OAK_LEAVES);

        Block dirt = Mockito.mock(Block.class);
        Mockito.when(dirt.getType()).thenReturn(Material.DIRT);

        assertEquals(TerrainClassifier.Classification.FLUID, TerrainClassifier.classify(water));
        assertEquals(TerrainClassifier.Classification.FLUID, TerrainClassifier.classify(lava));
        assertEquals(TerrainClassifier.Classification.BLOCKED, TerrainClassifier.classify(air));
        assertEquals(TerrainClassifier.Classification.VEGETATION, TerrainClassifier.classify(leaves));
        assertEquals(TerrainClassifier.Classification.ACCEPTABLE, TerrainClassifier.classify(dirt));
    }

    @Test
    @DisplayName("classify(world,x,y,z) detects steep slope when heights vary beyond threshold")
    public void testClassifyWithSlopeDetectsSteep() {
        World world = Mockito.mock(World.class);

        // center position
        int cx = 100, cy = 60, cz = 100;

        // Default for any unspecified block queries: dirt
        Block any = Mockito.mock(Block.class);
        Mockito.when(any.getType()).thenReturn(Material.DIRT);
        Mockito.when(world.getBlockAt(Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt())).thenReturn(any);

        // Ensure the center block is dirt (acceptable material)
        Block center = Mockito.mock(Block.class);
        Mockito.when(center.getType()).thenReturn(Material.DIRT);
        Mockito.when(world.getBlockAt(cx, cy, cz)).thenReturn(center);

        // Make highest block Y vary across the 3x3 grid to exceed MAX_SLOPE_DELTA (10)
        // Use values spanning 60..74 (delta = 14)
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int x = cx + dx;
                int z = cz + dz;
                int val = (dx == 0 && dz == 0) ? 60 : 74; // neighbors high
                Mockito.when(world.getHighestBlockYAt(x, z)).thenReturn(val);
                // For simplicity, have getBlockAt return dirt at those highest Y values
                Block pb = Mockito.mock(Block.class);
                Mockito.when(pb.getType()).thenReturn(Material.DIRT);
                Mockito.when(world.getBlockAt(x, val, z)).thenReturn(pb);
                // Also ensure the block below is simulated as solid dirt
                Block below = Mockito.mock(Block.class);
                Mockito.when(below.getType()).thenReturn(Material.DIRT);
                Mockito.when(world.getBlockAt(x, val - 1, z)).thenReturn(below);
            }
        }

        TerrainClassifier.Classification c = TerrainClassifier.classify(world, cx, cy, cz);
        assertEquals(TerrainClassifier.Classification.STEEP, c, "Expected STEEP when neighbor height delta exceeds threshold");
    }

    @Test
    @DisplayName("ClassificationResult acceptance rules honor fluid veto and tolerance")
    public void testClassificationResultToleranceRules() {
        TerrainClassifier.ClassificationResult r = new TerrainClassifier.ClassificationResult();

        // Any fluid should reject regardless of other counts
        r.fluid = 1;
        r.acceptable = 8;
        r.steep = 1;
        r.blocked = 0;
        r.vegetation = 0;
        assertFalse(r.isAcceptableWithTolerance(), "Fluid tiles should veto acceptance");

        // Reset and test rejection tolerance: allow up to 70% steep/blocked
        r = new TerrainClassifier.ClassificationResult();
        r.acceptable = 3;
        r.steep = 2;
        r.blocked = 1;
        r.vegetation = 4;
        // total=10, non-fluid rejected = 3 -> 30% -> acceptable
        assertTrue(r.isAcceptableWithTolerance(), "Rejection rate 30% should be acceptable");

        r = new TerrainClassifier.ClassificationResult();
        r.acceptable = 1;
        r.steep = 6;
        r.blocked = 3;
        r.vegetation = 0;
        // total=10, non-fluid rejected=9 -> 90% -> not acceptable
        assertFalse(r.isAcceptableWithTolerance(), "Rejection rate 90% should fail tolerance");
    }
}
