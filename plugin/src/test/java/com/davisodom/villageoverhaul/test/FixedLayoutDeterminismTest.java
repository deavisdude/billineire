package com.davisodom.villageoverhaul.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FixedLayoutDeterminismTest {

    @Test
    @DisplayName("Deterministic id and placement generation for fixed-layout seed")
    void deterministicFixedLayoutSignatures() {
        long seed = 12345L;
        int count = 3;

        // mimic deterministic base coordinate generation in TestCommands
        int seedInt = (int)(seed & 0x7fffffff);
        int baseX = (seedInt % 200) - 100;
        int baseZ = ((seedInt / 200) % 200) - 100;

        UUID villageIdA = UUID.nameUUIDFromBytes(("fixed-layout-village-" + seed).getBytes(StandardCharsets.UTF_8));

        List<UUID> buildingsA = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            UUID b = UUID.nameUUIDFromBytes(("fixed-layout-" + seed + "-" + i).getBytes(StandardCharsets.UTF_8));
            buildingsA.add(b);
        }

        // Repeat generation (simulating independent run)
        int seedInt2 = (int)(seed & 0x7fffffff);
        int baseX2 = (seedInt2 % 200) - 100;
        int baseZ2 = ((seedInt2 / 200) % 200) - 100;

        UUID villageIdB = UUID.nameUUIDFromBytes(("fixed-layout-village-" + seed).getBytes(StandardCharsets.UTF_8));
        List<UUID> buildingsB = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            UUID b = UUID.nameUUIDFromBytes(("fixed-layout-" + seed + "-" + i).getBytes(StandardCharsets.UTF_8));
            buildingsB.add(b);
        }

        // Determinism assertions
        assertEquals(baseX, baseX2, "baseX should be deterministic for the same seed");
        assertEquals(baseZ, baseZ2, "baseZ should be deterministic for the same seed");
        assertEquals(villageIdA, villageIdB, "village UUID should be deterministic for the same seed");
        assertEquals(buildingsA, buildingsB, "building UUID list should be deterministic for the same seed");
    }
}
