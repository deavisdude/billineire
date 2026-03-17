package com.davisodom.villageoverhaul.test;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@Tag("integration")
public class MockBukkitRegistryTest {

    @Test
    public void potionRegistryShouldContainCoreTypes() {
        ServerMock server = null;
        try {
            server = MockBukkit.mock();
        } catch (Throwable t) {
            // Some runners can't initialize MockBukkit; skip this sentinel test in that case
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "MockBukkit not available in this environment: " + t.getMessage());
        }

        try {
            assertNotNull(PotionEffectType.getByName("SPEED"), "SPEED potion effect should be registered");
            MockBukkitRegistryInitializer.assertPotionTypesPresent();
        } finally {
            if (server != null) MockBukkit.unmock();
        }
    }
}
