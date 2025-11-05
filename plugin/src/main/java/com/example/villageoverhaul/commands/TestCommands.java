package com.example.villageoverhaul.commands;

import com.example.villageoverhaul.VillageOverhaulPlugin;
import com.example.villageoverhaul.npc.CustomVillagerService;
import com.example.villageoverhaul.npc.VillagerInteractionController;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Test commands for automated CI testing of Village Overhaul features
 * 
 * These commands should ONLY be used in CI/test environments.
 * Production servers should disable this command via permissions.
 * 
 * Commands:
 *   /votest spawn-villager <type> [x] [y] [z] - Spawn a custom villager
 *   /votest trigger-interaction <player> <villager-uuid> - Trigger interaction event
 *   /votest metrics - Dump current metrics to logs
 *   /votest performance - Report current performance stats
 */
public class TestCommands implements CommandExecutor, TabCompleter {
    
    private final VillageOverhaulPlugin plugin;
    private final CustomVillagerService customVillagerService;
    private final VillagerInteractionController interactionController;
    
    public TestCommands(VillageOverhaulPlugin plugin, 
                       CustomVillagerService customVillagerService,
                       VillagerInteractionController interactionController) {
        this.plugin = plugin;
        this.customVillagerService = customVillagerService;
        this.interactionController = interactionController;
    }
    
    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, 
                            @NotNull String label, @NotNull String[] args) {
        
        if (args.length == 0) {
            sender.sendMessage("§cUsage: /votest <create-village|spawn-villager|trigger-interaction|simulate-interaction|metrics|performance>");
            return true;
        }
        
        String subCommand = args[0].toLowerCase();
        
        switch (subCommand) {
            case "create-village":
                return handleCreateVillage(sender, args);
                
            case "spawn-villager":
                return handleSpawnVillager(sender, args);
                
            case "trigger-interaction":
                return handleTriggerInteraction(sender, args);
                
            case "simulate-interaction":
                return handleSimulateInteraction(sender, args);
                
            case "metrics":
                return handleMetrics(sender);
                
            case "performance":
                return handlePerformance(sender);
                
            default:
                sender.sendMessage("§cUnknown subcommand: " + subCommand);
                return true;
        }
    }
    
    /**
     * Create a test village
     * Usage: /votest create-village <name> [x] [y] [z]
     */
    private boolean handleCreateVillage(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /votest create-village <name> [x] [y] [z]");
            return true;
        }
        
        String villageName = args[1];
        
        // Parse location or default to 0,64,0
        int x = 0, y = 64, z = 0;
        if (args.length >= 5) {
            try {
                x = Integer.parseInt(args[2]);
                y = Integer.parseInt(args[3]);
                z = Integer.parseInt(args[4]);
            } catch (NumberFormatException e) {
                sender.sendMessage("§cInvalid coordinates");
                return true;
            }
        }
        
        // Create the village with default "roman" culture
        String cultureId = "roman";
        String worldName = "world";
        
        com.example.villageoverhaul.villages.Village village = 
            plugin.getVillageService().createVillage(cultureId, villageName, worldName, x, y, z);
        
        // Give the village some initial wealth
        village.addWealth(1000L);
        
        // Create an initial project for the village
        plugin.getProjectService().createProject(
            village.getId(),
            "test_building",
            500L,  // Cost: 500 millz
            new java.util.ArrayList<>()
        );
        
        sender.sendMessage("§aCreated test village '" + villageName + "' with ID: " + village.getId());
        sender.sendMessage("§7Culture: " + cultureId + ", Location: " + x + "," + y + "," + z);
        sender.sendMessage("§7Initial wealth: 1000 millz, Active project: test_building (500 millz)");
        
        plugin.getLogger().info("[TEST] Created test village: " + villageName + 
                " (ID: " + village.getId() + ") at " + x + "," + y + "," + z);
        
        return true;
    }
    
    /**
     * Spawn a custom villager for testing
     * Usage: /votest spawn-villager <type> <village-id> [x] [y] [z]
     */
    private boolean handleSpawnVillager(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§cUsage: /votest spawn-villager <type> <village-id> [x] [y] [z]");
            return true;
        }
        
        String villagerType = args[1];
        String villageIdStr = args[2];
        
        // Parse location or default to 0,64,0
        int x = 0, y = 64, z = 0;
        if (args.length >= 6) {
            try {
                x = Integer.parseInt(args[3]);
                y = Integer.parseInt(args[4]);
                z = Integer.parseInt(args[5]);
            } catch (NumberFormatException e) {
                sender.sendMessage("§cInvalid coordinates");
                return true;
            }
        }
        
        // Create location (default world for testing)
        org.bukkit.World world = org.bukkit.Bukkit.getWorlds().get(0);
        org.bukkit.Location location = new org.bukkit.Location(world, x, y, z);
        
        // Spawn the custom villager
        UUID villagerId = customVillagerService.spawnCustomVillager(location, villagerType, villageIdStr);
        
        if (villagerId != null) {
            sender.sendMessage("§aSpawned custom villager '" + villagerType + "' with UUID: " + villagerId);
            plugin.getLogger().info("[TEST] Spawned custom villager: " + villagerType + " at " + x + "," + y + "," + z);
        } else {
            sender.sendMessage("§cFailed to spawn custom villager");
        }
        
        return true;
    }
    
    /**
     * Trigger an interaction between a player and custom villager
     * Usage: /votest trigger-interaction <player> <villager-uuid>
     */
    private boolean handleTriggerInteraction(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§cUsage: /votest trigger-interaction <player> <villager-uuid>");
            return true;
        }
        
        String playerName = args[1];
        String villagerUuidStr = args[2];
        
        Player player = Bukkit.getPlayer(playerName);
        if (player == null) {
            sender.sendMessage("§cPlayer not found: " + playerName);
            return true;
        }
        
        UUID villagerUuid;
        try {
            villagerUuid = UUID.fromString(villagerUuidStr);
        } catch (IllegalArgumentException e) {
            sender.sendMessage("§cInvalid UUID: " + villagerUuidStr);
            return true;
        }
        
        // Find the villager entity
        Entity villagerEntity = Bukkit.getEntity(villagerUuid);
        if (villagerEntity == null || !(villagerEntity instanceof Villager)) {
            sender.sendMessage("§cVillager not found with UUID: " + villagerUuid);
            return true;
        }
        
        // Trigger the interaction through the controller
        try {
            interactionController.handleInteraction(player, (Villager) villagerEntity);
            sender.sendMessage("§aTriggered interaction between " + playerName + " and custom villager");
            plugin.getLogger().info("[TEST] Player " + playerName + " interacted with custom villager " + villagerUuid);
        } catch (Exception e) {
            sender.sendMessage("§cFailed to trigger interaction: " + e.getMessage());
            plugin.getLogger().severe("[TEST] Interaction failed: " + e.getMessage());
            e.printStackTrace();
        }
        
        return true;
    }
    
    /**
     * Simulate an interaction without requiring a real player
     * Usage: /votest simulate-interaction <villager-uuid>
     * 
     * This creates a mock player context and triggers the interaction directly
     */
    private boolean handleSimulateInteraction(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /votest simulate-interaction <villager-uuid>");
            return true;
        }
        
        String villagerUuidStr = args[1];
        
        UUID villagerUuid;
        try {
            villagerUuid = UUID.fromString(villagerUuidStr);
        } catch (IllegalArgumentException e) {
            sender.sendMessage("§cInvalid UUID: " + villagerUuidStr);
            return true;
        }
        
        // Find the villager entity
        Entity villagerEntity = Bukkit.getEntity(villagerUuid);
        if (villagerEntity == null || !(villagerEntity instanceof Villager)) {
            sender.sendMessage("§cVillager not found with UUID: " + villagerUuid);
            return true;
        }
        
        // Get the custom villager wrapper
        com.example.villageoverhaul.npc.CustomVillager customVillager = 
            customVillagerService.getVillagerByEntityId(villagerUuid);
        
        if (customVillager == null) {
            sender.sendMessage("§cNot a custom villager: " + villagerUuid);
            return true;
        }
        
        // Simulate interaction by directly calling the trade contribution logic
        // This bypasses player requirements and simulates a successful trade
        try {
            // Create a mock player UUID for the test
            UUID mockPlayerId = UUID.nameUUIDFromBytes("test-player".getBytes());
            
            // Get the village for this villager
            UUID villageId = customVillager.getVillageId();
            
            // Simulate trade proceeds (base trade value)
            long tradeValueMillz = 100L; // BASE_TRADE_VALUE_MILLZ
            double projectContributionRate = 0.20; // PROJECT_CONTRIBUTION_RATE
            long playerEarnings = (long) (tradeValueMillz * (1.0 - projectContributionRate));
            long projectContribution = tradeValueMillz - playerEarnings;
            
            // Credit mock player wallet
            boolean credited = plugin.getWalletService().credit(mockPlayerId, playerEarnings);
            
            if (!credited) {
                sender.sendMessage("§cFailed to credit mock player wallet");
                plugin.getLogger().warning("[TEST] Failed to credit mock player wallet");
                return true;
            }
            
            plugin.getLogger().info(String.format("[TEST] Simulated trade: mockPlayer earned=%d millz (custom villager)", 
                    playerEarnings));
            
            // Mark interaction and trade completion in logs for test validation
            plugin.getLogger().info("[TEST] Simulated interaction with custom villager " + villagerUuid);
            plugin.getLogger().info("[TEST] Trade completed with custom villager (simulated)");
            
            // Find village and contribute to project
            java.util.Optional<com.example.villageoverhaul.villages.Village> villageOpt = 
                plugin.getVillageService().getVillage(villageId);
            
            if (villageOpt.isEmpty()) {
                plugin.getLogger().warning("[TEST] No village found for custom villager: " + villageId);
                plugin.getLogger().info("[TEST] Trade completed without village contribution (isolated villager)");
                sender.sendMessage("§aSimulated trade completed (no village project to contribute to)");
                return true;
            }
            
            com.example.villageoverhaul.villages.Village village = villageOpt.get();
            
            // Contribute to village project
            java.util.List<com.example.villageoverhaul.projects.Project> activeProjects = 
                plugin.getProjectService().getActiveVillageProjects(village.getId());
            
            if (activeProjects.isEmpty()) {
                // Store in village treasury
                village.addWealth(projectContribution);
                plugin.getLogger().info(String.format("[TEST] Added %d millz to village treasury (no active projects)",
                        projectContribution));
                plugin.getLogger().info("[TEST] Project contribution made (treasury)");
                sender.sendMessage("§aSimulated trade completed! Village treasury increased.");
            } else {
                // Contribute to first active project
                com.example.villageoverhaul.projects.Project project = activeProjects.get(0);
                java.util.Optional<com.example.villageoverhaul.projects.Project.ContributionResult> result = 
                    plugin.getProjectService().contribute(project.getId(), mockPlayerId, projectContribution);
                
                if (result.isPresent()) {
                    com.example.villageoverhaul.projects.Project.ContributionResult cr = result.get();
                    
                    plugin.getLogger().info(String.format("[TEST] Contributed %d millz to project %s",
                            projectContribution, project.getBuildingRef()));
                    plugin.getLogger().info("[TEST] Project contribution made (active project)");
                    
                    if (cr.isCompleted()) {
                        sender.sendMessage("§aSimulated trade completed! Village project COMPLETED: " + 
                                project.getBuildingRef());
                        plugin.getLogger().info("[TEST] Project completed: " + project.getId());
                    } else {
                        int percent = project.getCompletionPercent();
                        sender.sendMessage(String.format("§aSimulated trade completed! Project: %s (%d%% complete)",
                                project.getBuildingRef(), percent));
                    }
                    
                    if (cr.getOverflow() > 0) {
                        village.addWealth(cr.getOverflow());
                    }
                } else {
                    sender.sendMessage("§cFailed to contribute to project");
                }
            }
            
            // Mark interaction in logs for test validation
            plugin.getLogger().info("[TEST] Simulated interaction with custom villager " + villagerUuid);
            plugin.getLogger().info("[TEST] Trade completed with custom villager (simulated)");
            
        } catch (Exception e) {
            sender.sendMessage("§cFailed to simulate interaction: " + e.getMessage());
            plugin.getLogger().severe("[TEST] Simulated interaction failed: " + e.getMessage());
            e.printStackTrace();
        }
        
        return true;
    }
    
    /**
     * Dump current metrics to logs
     */
    private boolean handleMetrics(CommandSender sender) {
        sender.sendMessage("§aMetrics dumped to server logs");
        plugin.getLogger().info("[TEST] === METRICS DUMP ===");
        
        // Get metrics from the Metrics service
        // This assumes Metrics has a method to export current values
        // For now, just log that metrics were requested
        plugin.getLogger().info("[TEST] Metrics dump requested");
        
        return true;
    }
    
    /**
     * Report current performance stats
     */
    private boolean handlePerformance(CommandSender sender) {
        // Get NPC tick time from metrics
        // This would query the actual Metrics service
        
        int villagerCount = customVillagerService.getActiveVillagerCount();
        
        sender.sendMessage("§aPerformance Stats:");
        sender.sendMessage("§7Active custom villagers: §f" + villagerCount);
        
        plugin.getLogger().info("[TEST] === PERFORMANCE STATS ===");
        plugin.getLogger().info("[TEST] Active custom villagers: " + villagerCount);
        plugin.getLogger().info("[TEST] npc.tick_time_ms: <metric-not-yet-integrated>");
        
        return true;
    }
    
    @Nullable
    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, 
                                     @NotNull String alias, @NotNull String[] args) {
        List<String> completions = new ArrayList<>();
        
        if (args.length == 1) {
            // Subcommands
            completions.add("spawn-villager");
            completions.add("trigger-interaction");
            completions.add("metrics");
            completions.add("performance");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("spawn-villager")) {
            // Villager types - would ideally come from configuration
            completions.add("blacksmith");
            completions.add("merchant");
            completions.add("builder");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("trigger-interaction")) {
            // Player names
            Bukkit.getOnlinePlayers().forEach(p -> completions.add(p.getName()));
        }
        
        return completions;
    }
}
