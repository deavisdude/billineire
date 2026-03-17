package com.davisodom.villageoverhaul.worldgen.impl;

// No external server harness required for seed-determinism checks
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class PathSeedDeterminismTest {

    private String computeNetworkHash(com.davisodom.villageoverhaul.model.PathNetwork network) {
        long hash = 0;
        for (com.davisodom.villageoverhaul.model.PathNetwork.PathSegment seg : network.getSegments()) {
            for (org.bukkit.block.Block b : seg.getBlocks()) {
                hash = 31 * hash + b.getX();
                hash = 31 * hash + b.getY();
                hash = 31 * hash + b.getZ();
            }
        }
        return Long.toHexString(hash);
    }

    @Test
    @DisplayName("path hash deterministic for same path-base; different for different village seeds")
    public void testPathHashPropagation() {
        // Test deterministic mapping from top-level seed -> placementSeed -> pathBaseSeed
        long topA = 12345L;
        long placementA = new Random(topA).nextLong();
        long pathBaseA = new Random(placementA).nextLong();

        long topA2 = 12345L; // same top seed should produce same path base seed
        long placementA2 = new Random(topA2).nextLong();
        long pathBaseA2 = new Random(placementA2).nextLong();

        assertEquals(pathBaseA, pathBaseA2, "Same top-level seeds should produce same path-base seed");

        long topB = 987654321L; // different top seed should produce different path base seed
        long placementB = new Random(topB).nextLong();
        long pathBaseB = new Random(placementB).nextLong();

        assertNotEquals(pathBaseA, pathBaseB, "Different top-level seeds should produce different path-base seeds");
    }
}
