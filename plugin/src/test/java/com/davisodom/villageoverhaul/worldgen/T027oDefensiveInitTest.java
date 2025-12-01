package com.davisodom.villageoverhaul.worldgen;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

public class T027oDefensiveInitTest {

    @Test
    @DisplayName("TerrainClassifier class loads without initializer errors")
    public void terrainClassifierLoadsSafely() {
        assertDoesNotThrow(() -> Class.forName("com.davisodom.villageoverhaul.worldgen.TerrainClassifier"));
    }

    @Test
    @DisplayName("StructureServiceImpl constructor is resilient to optional plugin classes")
    public void structureServiceInitDoesNotThrow() {
        assertDoesNotThrow(() -> new com.davisodom.villageoverhaul.worldgen.impl.StructureServiceImpl());
    }

    @Test
    @DisplayName("TerraformingPlan trimmable detection works for existing materials and is callable")
    public void terraformingPlanTrimmableDetection() throws Exception {
        World world = mock(World.class);
        Location loc = new Location(world, 0, 60, 0);
        TerraformingPlan p = TerraformingPlan.forSite(world, loc, 5, 5, 4);

        // Use reflection to call private isTrimmableVegetation(Material)
        Method m = TerraformingPlan.class.getDeclaredMethod("isTrimmableVegetation", Material.class);
        m.setAccessible(true);

        // Existing, well-known materials should be recognized
        boolean oakLog = (Boolean) m.invoke(p, Material.OAK_LOG);
        boolean dirt = (Boolean) m.invoke(p, Material.DIRT);

        assertTrue(oakLog, "OAK_LOG should be considered trimmable vegetation");
        assertFalse(dirt, "DIRT should not be considered trimmable vegetation");
    }
}
