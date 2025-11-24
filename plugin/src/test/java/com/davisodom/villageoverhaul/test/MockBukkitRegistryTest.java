package com.davisodom.villageoverhaul.test;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

public class MockBukkitRegistryTest {

    @Test
    public void potionRegistryShouldContainCoreTypes() {
        ServerMock server = MockBukkit.mock();
        try {
            assertNotNull(PotionEffectType.getByName("SPEED"), "SPEED potion effect should be registered");
            MockBukkitRegistryInitializer.assertPotionTypesPresent();
        } finally {
            MockBukkit.unmock();
        }
    }
}
