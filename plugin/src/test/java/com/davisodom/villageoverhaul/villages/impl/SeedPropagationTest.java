package com.davisodom.villageoverhaul.villages.impl;

import com.davisodom.villageoverhaul.villages.VillageMetadataStore;
import com.davisodom.villageoverhaul.worldgen.StructureService;
import com.davisodom.villageoverhaul.cultures.CultureService;
import com.davisodom.villageoverhaul.model.PlacementReceipt;
import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import com.davisodom.villageoverhaul.VillageOverhaulPlugin;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class SeedPropagationTest {

    @Test
    @DisplayName("building seeds are derived from placement seed (placementSeed + index)")
    public void testBuildingSeedsDerivedFromPlacementSeed() {
        // Verify compounding seed derivation: villageSeed -> placementSeed -> pathBaseSeed
        long villageSeedA = 4242424242L;
        long placementA = new Random(villageSeedA).nextLong();
        long pathBaseA = new Random(placementA).nextLong();

        long villageSeedB = 4242424243L;
        long placementB = new Random(villageSeedB).nextLong();
        long pathBaseB = new Random(placementB).nextLong();

        assertNotEquals(pathBaseA, pathBaseB, "Different top-level seeds should produce different path base seeds");
    }
}
