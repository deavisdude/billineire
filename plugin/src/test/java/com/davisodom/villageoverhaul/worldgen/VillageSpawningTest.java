package com.davisodom.villageoverhaul.worldgen;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import com.davisodom.villageoverhaul.VillageOverhaulPlugin;
import com.davisodom.villageoverhaul.villages.Village;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collection;

import static org.junit.jupiter.api.Assertions.*;

class VillageSpawningTest {

    private ServerMock server;
    private VillageOverhaulPlugin plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(VillageOverhaulPlugin.class);
        // Ensure a default world exists for seeding
        if (server.getWorlds().isEmpty()) {
            server.addSimpleWorld("world");
        }
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("Seeds at least one test village with location")
    void testVillageSeeded() {
    // Force seeding now that a world exists
        plugin.getWorldgenAdapter().seedIfPossible();

        Collection<Village> villages = plugin.getVillageService().getAllVillages();
        assertFalse(villages.isEmpty(), "Expected at least one seeded village");
        Village v = villages.iterator().next();
        assertNotNull(v.getCultureId());
        assertNotNull(v.getWorldName());
        assertTrue(v.getWorldName().length() > 0);
    }
}

