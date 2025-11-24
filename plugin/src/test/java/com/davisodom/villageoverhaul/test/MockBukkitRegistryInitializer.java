package com.davisodom.villageoverhaul.test;

import org.bukkit.potion.PotionEffectType;

/**
 * Minimal test helper for ensuring Bukkit registries are available during MockBukkit tests.
 */
public final class MockBukkitRegistryInitializer {
    private MockBukkitRegistryInitializer() { }

    public static void assertPotionTypesPresent() {
            // Some runner environments register different subsets of potion effect names.
            // Require that at least one common effect is present and that the registry is non-empty.
            if (PotionEffectType.values() == null || PotionEffectType.values().length == 0) {
                throw new IllegalStateException("PotionEffectType registry appears empty");
            }

            String[] common = new String[]{"SPEED", "REGENERATION", "INCREASE_DAMAGE", "ABSORPTION"};
            boolean found = false;
            for (String name : common) {
                if (PotionEffectType.getByName(name) != null) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new IllegalStateException("No common potion effect types present in registry");
            }
    }
}
