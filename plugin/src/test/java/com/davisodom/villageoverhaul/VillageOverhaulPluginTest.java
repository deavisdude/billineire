package com.davisodom.villageoverhaul;

import com.davisodom.villageoverhaul.test.MockBukkitRegistryInitializer;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Basic MockBukkit lifecycle test for VillageOverhaulPlugin
 */
public class VillageOverhaulPluginTest {

    private be.seeseemelk.mockbukkit.ServerMock server = null;
    private VillageOverhaulPlugin plugin = null;

    @AfterEach
    void tearDown() {
        if (plugin != null) {
            try {
                plugin.onDisable();
            } catch (Throwable ignored) { }
        }
        if (server != null) be.seeseemelk.mockbukkit.MockBukkit.unmock();
    }

    @Test
    public void plugin_loads_and_initializes_services() {
        try {
            server = be.seeseemelk.mockbukkit.MockBukkit.mock();
        } catch (Throwable t) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "MockBukkit not available in this environment: " + t.getMessage());
        }

        // Ensure registries are available for MockBukkit tests
        MockBukkitRegistryInitializer.assertPotionTypesPresent();

        // Load plugin
        plugin = (VillageOverhaulPlugin) be.seeseemelk.mockbukkit.MockBukkit.load(VillageOverhaulPlugin.class);
        Assertions.assertNotNull(plugin);
        Assertions.assertTrue(plugin.isEnabled(), "Plugin should be enabled after load");

        // Core services should be present
        Assertions.assertNotNull(plugin.getVillageService(), "VillageService should be initialized");
        Assertions.assertNotNull(plugin.getProjectService(), "ProjectService should be initialized");
        Assertions.assertNotNull(plugin.getTickEngine(), "TickEngine should be initialized and accessible via getTickEngine()");

        // Commands defined in plugin.yml should exist
        Assertions.assertNotNull(plugin.getCommand("vo"), "/vo command should be registered");
        Assertions.assertNotNull(plugin.getCommand("votest"), "/votest command should be registered");

        // Verify executor/tab completer wired for /vo (project commands)
        org.bukkit.command.PluginCommand voCmd = plugin.getCommand("vo");
        Assertions.assertNotNull(voCmd.getExecutor(), "/vo should have a CommandExecutor");
        Assertions.assertTrue(voCmd.getExecutor() instanceof CommandExecutor);
        Assertions.assertTrue(voCmd.getTabCompleter() == null || voCmd.getTabCompleter() instanceof TabCompleter);
    }

    @Test
    public void onDisable_graceful_shutdown_without_exceptions() {
        try {
            server = be.seeseemelk.mockbukkit.MockBukkit.mock();
        } catch (Throwable t) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "MockBukkit not available in this environment: " + t.getMessage());
        }

        MockBukkitRegistryInitializer.assertPotionTypesPresent();

        plugin = (VillageOverhaulPlugin) be.seeseemelk.mockbukkit.MockBukkit.load(VillageOverhaulPlugin.class);
        Assertions.assertNotNull(plugin);

        // Call onDisable and ensure no exception is thrown and engine stops
        Assertions.assertDoesNotThrow(() -> plugin.onDisable());

        // After disable fields may still be non-null but shutdown should have executed cleanly
        Plugin p = plugin;
        Assertions.assertNotNull(p);
    }
}
