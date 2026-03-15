package com.davisodom.villageoverhaul.commands;

import com.davisodom.villageoverhaul.VillageOverhaulPlugin;
import com.davisodom.villageoverhaul.npc.CustomVillagerService;
import com.davisodom.villageoverhaul.npc.VillagerInteractionController;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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
import java.util.Optional;
import java.util.Random;
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
 *   /votest place-obstacle <water|steep> <x> <z> <radius|width> - Place terrain obstacles for pathfinding tests
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

        if (!sender.hasPermission("villageoverhaul.test")) {
            CommandFeedback.error(sender, "You do not have permission to use /votest.");
            plugin.getLogger().warning("[TEST] Unauthorized /votest access attempt by " + sender.getName());
            return true;
        }

        if (args.length == 0) {
            CommandFeedback.error(sender, "Usage: /votest <create-village|generate-structures|generate-paths|spawn-villager|trigger-interaction|simulate-interaction|place-obstacle|verify-persistence|metrics|performance>");
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "create-village":
                return handleCreateVillage(sender, args);
                
            case "generate-structures":
                return handleGenerateStructures(sender, args);
                
            case "generate-paths":
                return handleGeneratePaths(sender, args);

            case "fixed-layout":
                return handleFixedLayout(sender, args);
                
            case "spawn-villager":
                return handleSpawnVillager(sender, args);
                
            case "trigger-interaction":
                return handleTriggerInteraction(sender, args);
                
            case "simulate-interaction":
                return handleSimulateInteraction(sender, args);
                
            case "place-obstacle":
                return handlePlaceObstacle(sender, args);
                
            case "verify-persistence":
                return handleVerifyPersistence(sender, args);
                
            case "write-placement-counters":
                if (args.length < 2) {
                    CommandFeedback.error(sender, "Usage: /votest write-placement-counters <villageId>");
                    return true;
                }

                try {
                    UUID target = UUID.fromString(args[1]);
                    com.davisodom.villageoverhaul.villages.VillageMetadataStore store = plugin.getMetadataStore();
                    // write existing counters or zero if missing
                        com.davisodom.villageoverhaul.villages.VillageMetadataStore.PlacementRejectionCounters counters =
                            store.getPlacementRejectionCounters(target).orElse(new com.davisodom.villageoverhaul.villages.VillageMetadataStore.PlacementRejectionCounters(0,0,0,0,0,0,0,0,0,0));
                    store.recordPlacementRejectionCounters(target, counters);
                    CommandFeedback.info(sender, "Placement counters written for village: " + target);
                } catch (Exception ex) {
                    CommandFeedback.error(sender, "Failed to write counters: " + ex.getMessage());
                }
                return true;
                
            case "metrics":
                return handleMetrics(sender);
                
            case "performance":
                return handlePerformance(sender);
                
            default:
                CommandFeedback.error(sender, "Unknown subcommand: " + subCommand);
                return true;
        }
    }
    
    /**
     * Create a test village
     * Usage: /votest create-village <name> [x] [y] [z]
     */
    private boolean handleCreateVillage(CommandSender sender, String[] args) {
        if (args.length < 2) {
            CommandFeedback.error(sender, "Usage: /votest create-village <name> [x] [y] [z]");
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
                CommandFeedback.error(sender, "Invalid coordinates");
                return true;
            }
        }
        
        // Create the village with default "roman" culture
        String cultureId = "roman";
        String worldName = "world";
        
        com.davisodom.villageoverhaul.villages.Village village = 
            plugin.getVillageService().createVillage(cultureId, villageName, worldName, x, y, z);
        plugin.getMetadataStore().setVillageName(village.getId(), villageName);
        
        // Give the village some initial wealth
        village.addWealth(1000L);
        
        // Create an initial project for the village
        plugin.getProjectService().createProject(
            village.getId(),
            "test_building",
            500L,  // Cost: 500 millz
            new java.util.ArrayList<>()
        );
        
        CommandFeedback.info(sender, "Created test village '" + villageName + "' with ID: " + village.getId());
        CommandFeedback.detail(sender, "Culture: " + cultureId + ", Location: " + x + "," + y + "," + z);
        CommandFeedback.detail(sender, "Initial wealth: 1000 millz, Active project: test_building (500 millz)");
        
        plugin.getLogger().info("[TEST] Created test village: " + villageName + 
                " (ID: " + village.getId() + ") at " + x + "," + y + "," + z);
        
        return true;
    }
    
    /**
     * Generate structures for a village
     * Usage: /votest generate-structures <village-id>
     */
    private boolean handleGenerateStructures(CommandSender sender, String[] args) {
        if (args.length < 2) {
            CommandFeedback.error(sender, "Usage: /votest generate-structures <village-id>");
            return true;
        }
        
        String villageIdStr = args[1];
        UUID villageId;
        
        try {
            villageId = UUID.fromString(villageIdStr);
        } catch (IllegalArgumentException e) {
            CommandFeedback.error(sender, "Invalid village ID format");
            return true;
        }
        
        // Load village from service
        com.davisodom.villageoverhaul.villages.VillageService villageService = plugin.getVillageService();
        Optional<com.davisodom.villageoverhaul.villages.Village> villageOpt = villageService.getVillage(villageId);
        
        if (!villageOpt.isPresent()) {
            CommandFeedback.error(sender, "Village not found: " + villageId);
            return true;
        }
        
        com.davisodom.villageoverhaul.villages.Village village = villageOpt.get();
        
        // Get world
        org.bukkit.World world = plugin.getServer().getWorld(village.getWorldName());
        if (world == null) {
            CommandFeedback.error(sender, "World not found: " + village.getWorldName());
            return true;
        }
        
        // Construct origin location from village coordinates
        Location origin = new Location(world, village.getX(), village.getY(), village.getZ());
        
        // Create generation request
        CommandGenerationRequest request = new CommandGenerationRequest(
            sender,
            village.getCultureId(),
            village.getName(),
            null, // No seed override for regeneration
            origin,
            village.getId()
        );
        
        // Enqueue request for tick-budgeted processing
        plugin.getGenerationQueue().enqueue(request);
        
        CommandFeedback.info(sender, "Structure generation enqueued for village: " + village.getName());
        CommandFeedback.detail(sender, "Fill-in mode: existing village detected; missing structures will be attempted.");
        CommandFeedback.detail(sender, "Max village bounds radius: " + plugin.getMaxBoundsRadiusBlocks() + " blocks");
        CommandFeedback.detail(sender, "Generation will occur over multiple ticks - watch for [GEN-PROGRESS] logs");
        
        return true;
    }
    
    /**
     * Generate path network for a village
     * Usage: /votest generate-paths <village-id>
     */
    private boolean handleGeneratePaths(CommandSender sender, String[] args) {
        if (args.length < 2) {
            CommandFeedback.error(sender, "Usage: /votest generate-paths <village-id>");
            return true;
        }
        
        String villageIdStr = args[1];
        UUID villageId;
        
        try {
            villageId = UUID.fromString(villageIdStr);
        } catch (IllegalArgumentException e) {
            CommandFeedback.error(sender, "Invalid village ID format");
            return true;
        }
        
        // Get shared metadata store from plugin
        com.davisodom.villageoverhaul.villages.VillageMetadataStore metadataStore = 
            plugin.getMetadataStore();
        
        // Verify village exists
        Optional<com.davisodom.villageoverhaul.villages.VillageMetadataStore.VillageMetadata> villageOpt = 
            metadataStore.getVillage(villageId);
        
        if (villageOpt.isEmpty()) {
            CommandFeedback.error(sender, "Village not found: " + villageId);
            return true;
        }
        
        com.davisodom.villageoverhaul.villages.VillageMetadataStore.VillageMetadata village = 
            villageOpt.get();
        
        // Get buildings for this village
        List<com.davisodom.villageoverhaul.model.Building> buildings = 
            metadataStore.getVillageBuildings(villageId);
        
        if (buildings.isEmpty()) {
            CommandFeedback.error(sender, "No buildings found for village: " + villageId);
            CommandFeedback.detail(sender, "Run /votest generate-structures first");
            return true;
        }
        
        // Get or choose main building
        Optional<UUID> mainBuildingIdOpt = metadataStore.getMainBuilding(villageId);
        UUID mainBuildingId = null;
        com.davisodom.villageoverhaul.model.Building mainBuilding = null;

        if (mainBuildingIdOpt.isPresent()) {
            mainBuildingId = mainBuildingIdOpt.get();
            for (com.davisodom.villageoverhaul.model.Building building : buildings) {
                if (building.getBuildingId().equals(mainBuildingId)) {
                    mainBuilding = building;
                    break;
                }
            }
        }

        // Fallback: if no main building designated, use first building as main (keep test-friendly behavior)
        if (mainBuilding == null && !buildings.isEmpty()) {
            mainBuilding = buildings.get(0);
            mainBuildingId = mainBuilding.getBuildingId();
            // Persist designation so subsequent calls see it
            metadataStore.setMainBuilding(villageId, mainBuildingId);
            plugin.getLogger().info(String.format("[STRUCT] Auto-designated main building %s for village %s (test fallback)", mainBuildingId, villageId));
            CommandFeedback.warn(sender, "No main building previously designated - using first building as main for path generation");
        }

        if (mainBuilding == null) {
            CommandFeedback.error(sender, "No main building available for village: " + villageId);
            CommandFeedback.detail(sender, "Ensure structures exist for this village before path generation");
            return true;
        }
        
        // Get world from village origin
        org.bukkit.World world = village.getOrigin().getWorld();
        if (world == null) {
            CommandFeedback.error(sender, "World not found for village");
            return true;
        }
        
        // Collect building locations
        List<Location> buildingLocations = new java.util.ArrayList<>();
        for (com.davisodom.villageoverhaul.model.Building building : buildings) {
            buildingLocations.add(building.getOrigin());
        }
        
        // Initialize PathService (use plugin's instance if available, or create one)
        com.davisodom.villageoverhaul.worldgen.impl.PathServiceImpl.PlannerSettings plannerSettings =
            new com.davisodom.villageoverhaul.worldgen.impl.PathServiceImpl.PlannerSettings(
                plugin.getPathMaxNodesExplored(),
                plugin.getPathPlannerConcurrencyCap(),
                plugin.getPathNodeCapRetryMaxAttempts(),
                plugin.getPathNodeCapBackoffBaseMs(),
                plugin.getPathNodeCapBackoffMaxMs()
            );
        com.davisodom.villageoverhaul.worldgen.PathService pathService = 
            new com.davisodom.villageoverhaul.worldgen.impl.PathServiceImpl(metadataStore, plannerSettings);
        
        // Log path generation start
        plugin.getLogger().info(String.format(
            "[STRUCT] Begin path network generation for village %s: buildings=%d, mainBuilding=%s",
            villageId, buildings.size(), mainBuildingId));
        
        CommandFeedback.info(sender, "Generating path network for village: " + villageId);
        CommandFeedback.detail(sender, String.format("Buildings: %d, Main building: %s",
            buildings.size(), mainBuilding.getStructureId()));
        
        // Generate path network
        boolean success = pathService.generatePathNetwork(
            world, 
            villageId, 
            buildingLocations, 
            mainBuilding.getOrigin(), 
            village.getSeed()
        );

        List<List<org.bukkit.block.Block>> pathSegments = 
            pathService.getVillagePathNetwork(villageId);

        if (!pathSegments.isEmpty()) {
            if (success) {
                CommandFeedback.info(sender, "Path network generated successfully!");
            } else {
                CommandFeedback.warn(sender, "Path network generated partially; emitting successful segments anyway.");
            }

            // Emit path blocks using PathEmitter
            com.davisodom.villageoverhaul.worldgen.impl.PathEmitter pathEmitter = 
                new com.davisodom.villageoverhaul.worldgen.impl.PathEmitter();
            
            // R008: Get volume masks for path placement checks
            List<com.davisodom.villageoverhaul.model.VolumeMask> masks = metadataStore.getVolumeMasks(villageId);
            
            int totalBlocksPlaced = 0;
            for (List<org.bukkit.block.Block> pathBlocks : pathSegments) {
                int blocksPlaced = pathEmitter.emitPath(
                    world, 
                    pathBlocks, 
                    village.getCultureId(),
                    masks
                );
                totalBlocksPlaced += blocksPlaced;
            }
            
            CommandFeedback.detail(sender, String.format("Path segments: %d, Blocks placed: %d",
                pathSegments.size(), totalBlocksPlaced));
            
            plugin.getLogger().info(String.format(
                "[STRUCT] Path network %s for village %s: segments=%d, blocks=%d",
                success ? "complete" : "partial", villageId, pathSegments.size(), totalBlocksPlaced));
        } else {
            CommandFeedback.error(sender, "Path network generation failed");
            CommandFeedback.detail(sender, "Check logs for [STRUCT] markers with failure details");
            
            plugin.getLogger().warning(String.format(
                "[STRUCT] Path network generation failed for village %s", villageId));
        }
        
        return true;
    }
    
    /**
     * Spawn a custom villager for testing
     * Usage: /votest spawn-villager <type> <village-id> [x] [y] [z]
     */
    private boolean handleSpawnVillager(CommandSender sender, String[] args) {
        if (args.length < 3) {
            CommandFeedback.error(sender, "Usage: /votest spawn-villager <type> <village-id> [x] [y] [z]");
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
                CommandFeedback.error(sender, "Invalid coordinates");
                return true;
            }
        }
        
        // Create location (default world for testing)
        org.bukkit.World world = org.bukkit.Bukkit.getWorlds().get(0);
        org.bukkit.Location location = new org.bukkit.Location(world, x, y, z);
        
        // Spawn the custom villager
        UUID villagerId = customVillagerService.spawnCustomVillager(location, villagerType, villageIdStr);
        
        if (villagerId != null) {
            CommandFeedback.info(sender, "Spawned custom villager '" + villagerType + "' with UUID: " + villagerId);
            plugin.getLogger().info("[TEST] Spawned custom villager: " + villagerType + " at " + x + "," + y + "," + z);
        } else {
            CommandFeedback.error(sender, "Failed to spawn custom villager");
        }
        
        return true;
    }
    
    /**
     * Trigger an interaction between a player and custom villager
     * Usage: /votest trigger-interaction <player> <villager-uuid>
     */
    private boolean handleTriggerInteraction(CommandSender sender, String[] args) {
        if (args.length < 3) {
            CommandFeedback.error(sender, "Usage: /votest trigger-interaction <player> <villager-uuid>");
            return true;
        }
        
        String playerName = args[1];
        String villagerUuidStr = args[2];
        
        Player player = Bukkit.getPlayer(playerName);
        if (player == null) {
            CommandFeedback.error(sender, "Player not found: " + playerName);
            return true;
        }
        
        UUID villagerUuid;
        try {
            villagerUuid = UUID.fromString(villagerUuidStr);
        } catch (IllegalArgumentException e) {
            CommandFeedback.error(sender, "Invalid UUID: " + villagerUuidStr);
            return true;
        }
        
        // Find the villager entity
        Entity villagerEntity = Bukkit.getEntity(villagerUuid);
        if (villagerEntity == null || !(villagerEntity instanceof Villager)) {
            CommandFeedback.error(sender, "Villager not found with UUID: " + villagerUuid);
            return true;
        }
        
        // Trigger the interaction through the controller
        try {
            interactionController.handleInteraction(player, (Villager) villagerEntity);
            CommandFeedback.info(sender, "Triggered interaction between " + playerName + " and custom villager");
            plugin.getLogger().info("[TEST] Player " + playerName + " interacted with custom villager " + villagerUuid);
        } catch (Exception e) {
            CommandFeedback.error(sender, "Failed to trigger interaction: " + e.getMessage());
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
            CommandFeedback.error(sender, "Usage: /votest simulate-interaction <villager-uuid>");
            return true;
        }
        
        String villagerUuidStr = args[1];
        
        UUID villagerUuid;
        try {
            villagerUuid = UUID.fromString(villagerUuidStr);
        } catch (IllegalArgumentException e) {
            CommandFeedback.error(sender, "Invalid UUID: " + villagerUuidStr);
            return true;
        }
        
        // Find the villager entity
        Entity villagerEntity = Bukkit.getEntity(villagerUuid);
        if (villagerEntity == null || !(villagerEntity instanceof Villager)) {
            CommandFeedback.error(sender, "Villager not found with UUID: " + villagerUuid);
            return true;
        }
        
        // Get the custom villager wrapper
        com.davisodom.villageoverhaul.npc.CustomVillager customVillager = 
            customVillagerService.getVillagerByEntityId(villagerUuid);
        
        if (customVillager == null) {
            CommandFeedback.error(sender, "Not a custom villager: " + villagerUuid);
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
                CommandFeedback.error(sender, "Failed to credit mock player wallet");
                plugin.getLogger().warning("[TEST] Failed to credit mock player wallet");
                return true;
            }
            
            plugin.getLogger().info(String.format("[TEST] Simulated trade: mockPlayer earned=%d millz (custom villager)", 
                    playerEarnings));
            
            // Mark interaction and trade completion in logs for test validation
            plugin.getLogger().info("[TEST] Simulated interaction with custom villager " + villagerUuid);
            plugin.getLogger().info("[TEST] Trade completed with custom villager (simulated)");
            
            // Find village and contribute to project
            java.util.Optional<com.davisodom.villageoverhaul.villages.Village> villageOpt = 
                plugin.getVillageService().getVillage(villageId);
            
            if (villageOpt.isEmpty()) {
                plugin.getLogger().warning("[TEST] No village found for custom villager: " + villageId);
                plugin.getLogger().info("[TEST] Trade completed without village contribution (isolated villager)");
                CommandFeedback.info(sender, "Simulated trade completed (no village project to contribute to)");
                return true;
            }
            
            com.davisodom.villageoverhaul.villages.Village village = villageOpt.get();
            
            // Contribute to village project
            java.util.List<com.davisodom.villageoverhaul.projects.Project> activeProjects = 
                plugin.getProjectService().getActiveVillageProjects(village.getId());
            
            if (activeProjects.isEmpty()) {
                // Store in village treasury
                village.addWealth(projectContribution);
                plugin.getLogger().info(String.format("[TEST] Added %d millz to village treasury (no active projects)",
                        projectContribution));
                plugin.getLogger().info("[TEST] Project contribution made (treasury)");
                CommandFeedback.info(sender, "Simulated trade completed! Village treasury increased.");
            } else {
                // Contribute to first active project
                com.davisodom.villageoverhaul.projects.Project project = activeProjects.get(0);
                java.util.Optional<com.davisodom.villageoverhaul.projects.Project.ContributionResult> result = 
                    plugin.getProjectService().contribute(project.getId(), mockPlayerId, projectContribution);
                
                if (result.isPresent()) {
                    com.davisodom.villageoverhaul.projects.Project.ContributionResult cr = result.get();
                    
                    plugin.getLogger().info(String.format("[TEST] Contributed %d millz to project %s",
                            projectContribution, project.getBuildingRef()));
                    plugin.getLogger().info("[TEST] Project contribution made (active project)");
                    
                    if (cr.isCompleted()) {
                        CommandFeedback.info(sender, "Simulated trade completed! Village project COMPLETED: " +
                                project.getBuildingRef());
                        plugin.getLogger().info("[TEST] Project completed: " + project.getId());
                    } else {
                        int percent = project.getCompletionPercent();
                        CommandFeedback.info(sender, String.format("Simulated trade completed! Project: %s (%d%% complete)",
                                project.getBuildingRef(), percent));
                    }
                    
                    if (cr.getOverflow() > 0) {
                        village.addWealth(cr.getOverflow());
                    }
                } else {
                    CommandFeedback.error(sender, "Failed to contribute to project");
                }
            }
            
            // Mark interaction in logs for test validation
            plugin.getLogger().info("[TEST] Simulated interaction with custom villager " + villagerUuid);
            plugin.getLogger().info("[TEST] Trade completed with custom villager (simulated)");
            
        } catch (Exception e) {
            CommandFeedback.error(sender, "Failed to simulate interaction: " + e.getMessage());
            plugin.getLogger().severe("[TEST] Simulated interaction failed: " + e.getMessage());
            e.printStackTrace();
        }
        
        return true;
    }
    
    /**
     * Place terrain obstacles for controlled pathfinding tests
     * Usage: /votest place-obstacle <water|steep> <x> <z> <radius|width>
     */
    private boolean handlePlaceObstacle(CommandSender sender, String[] args) {
        if (args.length < 5) {
            CommandFeedback.error(sender, "Usage: /votest place-obstacle <water|steep> <x> <z> <radius|width>");
            return true;
        }
        
        String obstacleType = args[1].toLowerCase();
        
        try {
            int x = Integer.parseInt(args[2]);
            int z = Integer.parseInt(args[3]);
            int size = Integer.parseInt(args[4]);
            
            if (!(sender instanceof Player)) {
                // For RCON/console, use first loaded world
                org.bukkit.World world = Bukkit.getWorlds().get(0);
                
                switch (obstacleType) {
                    case "water":
                        placeWaterPatch(world, x, z, size);
                        CommandFeedback.info(sender, String.format("Placed water patch at (%d, %d) radius=%d", x, z, size));
                        plugin.getLogger().info(String.format("[TEST] Placed water obstacle at (%d, %d) radius=%d", x, z, size));
                        break;
                        
                    case "steep":
                        placeSteepTerrain(world, x, z, size);
                        CommandFeedback.info(sender, String.format("Placed steep terrain at (%d, %d) width=%d", x, z, size));
                        plugin.getLogger().info(String.format("[TEST] Placed steep obstacle at (%d, %d) width=%d", x, z, size));
                        break;
                        
                    default:
                        CommandFeedback.error(sender, "Unknown obstacle type: " + obstacleType);
                        CommandFeedback.detail(sender, "Valid types: water, steep");
                        return true;
                }
                
                return true;
            }
            
            Player player = (Player) sender;
            org.bukkit.World world = player.getWorld();
            
            switch (obstacleType) {
                case "water":
                    placeWaterPatch(world, x, z, size);
                    CommandFeedback.info(sender, String.format("Placed water patch at (%d, %d) radius=%d", x, z, size));
                    plugin.getLogger().info(String.format("[TEST] Placed water obstacle at (%d, %d) radius=%d", x, z, size));
                    break;
                    
                case "steep":
                    placeSteepTerrain(world, x, z, size);
                    CommandFeedback.info(sender, String.format("Placed steep terrain at (%d, %d) width=%d", x, z, size));
                    plugin.getLogger().info(String.format("[TEST] Placed steep obstacle at (%d, %d) width=%d", x, z, size));
                    break;
                    
                default:
                    CommandFeedback.error(sender, "Unknown obstacle type: " + obstacleType);
                    CommandFeedback.detail(sender, "Valid types: water, steep");
                    return true;
            }
            
        } catch (NumberFormatException e) {
            CommandFeedback.error(sender, "Invalid coordinates or size");
            return true;
        }
        
        return true;
    }
    
    /**
     * Place a water patch at specified coordinates
     * Creates a circular water patch with given radius
     */
    private void placeWaterPatch(org.bukkit.World world, int centerX, int centerZ, int radius) {
        org.bukkit.Material waterMaterial = org.bukkit.Material.WATER;
        
        // Place water in a circular pattern
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                // Check if within circular radius
                double distance = Math.sqrt(dx * dx + dz * dz);
                if (distance <= radius) {
                    int x = centerX + dx;
                    int z = centerZ + dz;
                    
                    // Find surface Y coordinate
                    int y = world.getHighestBlockYAt(x, z);
                    
                    // Place water at surface level
                    Location waterLoc = new Location(world, x, y, z);
                    world.getBlockAt(waterLoc).setType(waterMaterial);
                    
                    // Also place one block below to ensure it's a full water source
                    Location belowLoc = new Location(world, x, y - 1, z);
                    if (world.getBlockAt(belowLoc).getType() == org.bukkit.Material.AIR) {
                        world.getBlockAt(belowLoc).setType(waterMaterial);
                    }
                }
            }
        }
    }
    
    /**
     * Place steep terrain elevation change at specified coordinates
     * Creates a wall of stone blocks to simulate elevation change
     */
    private void placeSteepTerrain(org.bukkit.World world, int centerX, int centerZ, int width) {
        org.bukkit.Material stoneMaterial = org.bukkit.Material.STONE;
        int height = 4; // Create 4-block high wall for steep obstacle
        
        // Place stone wall perpendicular to Z-axis
        for (int dx = -width/2; dx <= width/2; dx++) {
            int x = centerX + dx;
            int z = centerZ;
            
            // Find surface Y coordinate
            int baseY = world.getHighestBlockYAt(x, z);
            
            // Build wall upward
            for (int dy = 0; dy < height; dy++) {
                Location blockLoc = new Location(world, x, baseY + dy, z);
                world.getBlockAt(blockLoc).setType(stoneMaterial);
            }
        }
    }
    
    /**
     * Dump current metrics to logs
     */
    private boolean handleMetrics(CommandSender sender) {
        CommandFeedback.info(sender, "Metrics dumped to server logs");
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
        
        CommandFeedback.info(sender, "Performance Stats:");
        CommandFeedback.send(sender, CommandFeedback.detailLine("Active custom villagers: ", villagerCount));
        
        plugin.getLogger().info("[TEST] === PERFORMANCE STATS ===");
        plugin.getLogger().info("[TEST] Active custom villagers: " + villagerCount);
        plugin.getLogger().info("[TEST] npc.tick_time_ms: <metric-not-yet-integrated>");
        
        return true;
    }
    
    /**
     * Verify persistence data against in-game reality
     * Usage: /votest verify-persistence <village-id>
     */
    private boolean handleVerifyPersistence(CommandSender sender, String[] args) {
        if (args.length < 2) {
            CommandFeedback.error(sender, "Usage: /votest verify-persistence <village-id>");
            return true;
        }
        
        String villageIdStr = args[1];
        UUID villageId;
        try {
            villageId = UUID.fromString(villageIdStr);
        } catch (IllegalArgumentException e) {
            CommandFeedback.error(sender, "Invalid village ID format");
            return true;
        }
        
        com.davisodom.villageoverhaul.villages.VillageMetadataStore metadataStore = plugin.getMetadataStore();
        List<com.davisodom.villageoverhaul.model.VolumeMask> masks = metadataStore.getVolumeMasks(villageId);
        List<com.davisodom.villageoverhaul.model.PlacementReceipt> receipts = metadataStore.getPlacementReceipts(villageId);
        
        if (masks.isEmpty() && receipts.isEmpty()) {
            CommandFeedback.error(sender, "No persistence data found for village " + villageId);
            return true;
        }
        
        CommandFeedback.info(sender, "Verifying persistence for village " + villageId);
        CommandFeedback.detail(sender, String.format("Found %d masks and %d receipts", masks.size(), receipts.size()));
        
        boolean allPass = true;
        int totalChecks = 0;
        int failedChecks = 0;
        
        // R011c: Categorized failure tracking
        int cornerFailures = 0;
        int perimeterFailures = 0;
        int undersideFailures = 0;
        int outsideMaskFailures = 0;
        int pathMaskFailures = 0;
        int structuresChecked = 0;
        
        // Visuals: Particles at corners and entrances
        for (com.davisodom.villageoverhaul.model.PlacementReceipt receipt : receipts) {
            org.bukkit.World world = Bukkit.getWorld(receipt.getWorldName());
            if (world == null) continue;
            
            // Draw corners
            spawnParticle(world, receipt.getMinX(), receipt.getMinY(), receipt.getMinZ());
            spawnParticle(world, receipt.getMaxX(), receipt.getMinY(), receipt.getMinZ());
            spawnParticle(world, receipt.getMaxX(), receipt.getMinY(), receipt.getMaxZ());
            spawnParticle(world, receipt.getMinX(), receipt.getMinY(), receipt.getMaxZ());
            spawnParticle(world, receipt.getMinX(), receipt.getMaxY(), receipt.getMinZ());
            spawnParticle(world, receipt.getMaxX(), receipt.getMaxY(), receipt.getMinZ());
            spawnParticle(world, receipt.getMaxX(), receipt.getMaxY(), receipt.getMaxZ());
            spawnParticle(world, receipt.getMinX(), receipt.getMaxY(), receipt.getMaxZ());
            
            // Draw entrance (different color/particle if possible, or just same)
            world.spawnParticle(org.bukkit.Particle.VILLAGER_HAPPY, 
                receipt.getEntranceX() + 0.5, receipt.getEntranceY() + 0.5, receipt.getEntranceZ() + 0.5, 10);
        }
        
        // Logic checks: VolumeMasks
        Random random = new Random();
        for (com.davisodom.villageoverhaul.model.VolumeMask mask : masks) {
            structuresChecked++;
            int structureCornerFailures = 0;
            int structurePerimeterFailures = 0;
            int structureUndersideFailures = 0;
            int structureOutsideFailures = 0;
            
            // Need world to check blocks. Mask doesn't store world name, but Receipt does.
            // Assuming all in same world or we can find it.
            Optional<com.davisodom.villageoverhaul.villages.VillageMetadataStore.VillageMetadata> villageOpt = 
                metadataStore.getVillage(villageId);
            if (villageOpt.isEmpty()) continue;
            org.bukkit.World world = villageOpt.get().getOrigin().getWorld();
            if (world == null) continue;
            
            // 1. Sample foundation and perimeter points (not interior, which may have air for rooms/hallways)
            // Check foundation corners (y=minY) - these should be mostly solid
            // TOLERANCE: Allow 1 AIR corner (out of 4) for rotated structures at terrain edges
            int[][] foundationCorners = {
                {mask.getMinX(), mask.getMinY(), mask.getMinZ()},
                {mask.getMaxX(), mask.getMinY(), mask.getMinZ()},
                {mask.getMaxX(), mask.getMinY(), mask.getMaxZ()},
                {mask.getMinX(), mask.getMinY(), mask.getMaxZ()}
            };
            
            int airCorners = 0;
            for (int[] corner : foundationCorners) {
                totalChecks++;
                org.bukkit.block.Block block = world.getBlockAt(corner[0], corner[1], corner[2]);
                if (block.getType().isAir()) {
                    airCorners++;
                    if (airCorners > 1) {
                        // Multiple AIR corners = critical failure
                        failedChecks++;
                        cornerFailures++;
                        structureCornerFailures++;
                        allPass = false;
                    }
                }
            }
            
            // Check perimeter foundation points (edges along y=minY)
            for (int i = 0; i < 8; i++) {
                int x, z;
                if (i < 2) {
                    // Min X edge
                    x = mask.getMinX();
                    z = randomRange(random, mask.getMinZ(), mask.getMaxZ());
                } else if (i < 4) {
                    // Max X edge
                    x = mask.getMaxX();
                    z = randomRange(random, mask.getMinZ(), mask.getMaxZ());
                } else if (i < 6) {
                    // Min Z edge
                    z = mask.getMinZ();
                    x = randomRange(random, mask.getMinX(), mask.getMaxX());
                } else {
                    // Max Z edge
                    z = mask.getMaxZ();
                    x = randomRange(random, mask.getMinX(), mask.getMaxX());
                }
                
                totalChecks++;
                org.bukkit.block.Block block = world.getBlockAt(x, mask.getMinY(), z);
                if (block.getType().isAir()) {
                    failedChecks++;
                    perimeterFailures++;
                    structurePerimeterFailures++;
                    allPass = false;
                }
            }

            if (mask.getMaxX() - mask.getMinX() >= 2 && mask.getMaxZ() - mask.getMinZ() >= 2) {
                java.util.LinkedHashSet<String> undersidePoints = new java.util.LinkedHashSet<>();
                int centerX = (mask.getMinX() + mask.getMaxX()) / 2;
                int centerZ = (mask.getMinZ() + mask.getMaxZ()) / 2;
                undersidePoints.add(centerX + ":" + centerZ);
                undersidePoints.add((mask.getMinX() + 1) + ":" + (mask.getMinZ() + 1));
                undersidePoints.add((mask.getMaxX() - 1) + ":" + (mask.getMinZ() + 1));
                undersidePoints.add((mask.getMinX() + 1) + ":" + (mask.getMaxZ() - 1));
                undersidePoints.add((mask.getMaxX() - 1) + ":" + (mask.getMaxZ() - 1));

                for (String point : undersidePoints) {
                    String[] coords = point.split(":");
                    int x = Integer.parseInt(coords[0]);
                    int z = Integer.parseInt(coords[1]);
                    totalChecks++;
                    org.bukkit.block.Block below = world.getBlockAt(x, mask.getMinY() - 1, z);
                    org.bukkit.Material belowType = below.getType();
                    if (belowType.isAir() || belowType == org.bukkit.Material.WATER || belowType == org.bukkit.Material.LAVA) {
                        failedChecks++;
                        undersideFailures++;
                        structureUndersideFailures++;
                        allPass = false;
                    }
                }
            }
            
            // 2. Sample 32 points JUST OUTSIDE
            for (int i = 0; i < 32; i++) {
                // Pick a face, then a point on that face + 1
                int face = random.nextInt(6);
                int x = 0, y = 0, z = 0;
                switch (face) {
                    case 0: x = mask.getMinX() - 1; y = randomRange(random, mask.getMinY(), mask.getMaxY()); z = randomRange(random, mask.getMinZ(), mask.getMaxZ()); break; // -X
                    case 1: x = mask.getMaxX() + 1; y = randomRange(random, mask.getMinY(), mask.getMaxY()); z = randomRange(random, mask.getMinZ(), mask.getMaxZ()); break; // +X
                    case 2: x = randomRange(random, mask.getMinX(), mask.getMaxX()); y = mask.getMinY() - 1; z = randomRange(random, mask.getMinZ(), mask.getMaxZ()); break; // -Y
                    case 3: x = randomRange(random, mask.getMinX(), mask.getMaxX()); y = mask.getMaxY() + 1; z = randomRange(random, mask.getMinZ(), mask.getMaxZ()); break; // +Y
                    case 4: x = randomRange(random, mask.getMinX(), mask.getMaxX()); y = randomRange(random, mask.getMinY(), mask.getMaxY()); z = mask.getMinZ() - 1; break; // -Z
                    case 5: x = randomRange(random, mask.getMinX(), mask.getMaxX()); y = randomRange(random, mask.getMinY(), mask.getMaxY()); z = mask.getMaxZ() + 1; break; // +Z
                }
                
                totalChecks++;
                if (mask.contains(x, y, z)) {
                    failedChecks++;
                    outsideMaskFailures++;
                    structureOutsideFailures++;
                    allPass = false;
                }
            }
            
            // R011c: Per-structure summary line
            String structureStatus;
            if (structureCornerFailures > 1) {
                structureStatus = "FAIL";
            } else if (structureCornerFailures == 1 || structurePerimeterFailures > 0 || structureUndersideFailures > 0 || structureOutsideFailures > 0) {
                structureStatus = structureCornerFailures == 1 ? "WARN" : "FAIL";
            } else {
                structureStatus = "PASS";
            }

            NamedTextColor structureStatusColor = switch (structureStatus) {
                case "PASS" -> NamedTextColor.GREEN;
                case "WARN" -> NamedTextColor.YELLOW;
                default -> NamedTextColor.RED;
            };
            CommandFeedback.send(sender, Component.text("Structure " + mask.getStructureId() + ": ", NamedTextColor.GRAY)
                .append(Component.text(structureStatus, structureStatusColor))
                .append(Component.text(String.format(" (corners=%d, perimeter=%d, underside=%d, outside=%d)",
                    structureCornerFailures, structurePerimeterFailures, structureUndersideFailures, structureOutsideFailures),
                    NamedTextColor.GRAY)));
        }
        
        // 3. Check paths against masks (R010)
        Optional<com.davisodom.villageoverhaul.model.PathNetwork> networkOpt = metadataStore.getPathNetwork(villageId);
        if (networkOpt.isPresent()) {
            com.davisodom.villageoverhaul.model.PathNetwork network = networkOpt.get();
            for (com.davisodom.villageoverhaul.model.PathNetwork.PathSegment segment : network.getSegments()) {
                for (org.bukkit.block.Block block : segment.getBlocks()) {
                    totalChecks++;
                    for (com.davisodom.villageoverhaul.model.VolumeMask mask : masks) {
                        if (mask.contains(block.getX(), block.getY(), block.getZ())) {
                            failedChecks++;
                            pathMaskFailures++;
                            allPass = false;
                            // Break inner loop (masks) for this block to avoid double counting
                            break; 
                        }
                    }
                }
            }
        }
        
        // R011c: Concise summary with categorized failures
        if (allPass) {
            CommandFeedback.info(sender, String.format("PASS: All persistence checks passed (%d checks, %d structures)",
                totalChecks, structuresChecked));
        } else {
            CommandFeedback.error(sender, String.format("FAIL: %d/%d checks failed (corner=%d, perimeter=%d, underside=%d, outside-mask=%d, path=%d)",
                failedChecks, totalChecks, cornerFailures, perimeterFailures, undersideFailures, outsideMaskFailures, pathMaskFailures));
            if (cornerFailures > 0) {
                CommandFeedback.detail(sender, "Benign edge case: Single AIR corners (1/4) show as WARN, not FAIL");
            }
        }
        
        return true;
    }

    /**
     * Create a deterministic fixed layout test village and place synthetic receipts.
     * Usage: /votest fixed-layout <seed> [count]
     */
    private boolean handleFixedLayout(CommandSender sender, String[] args) {
        if (args.length < 2) {
            CommandFeedback.error(sender, "Usage: /votest fixed-layout <seed> [count]");
            return true;
        }

        long seed;
        try {
            seed = Long.parseLong(args[1]);
        } catch (NumberFormatException e) {
            CommandFeedback.error(sender, "Invalid seed: must be a number");
            return true;
        }

        int count = 3;
        if (args.length >= 3) {
            try { count = Integer.parseInt(args[2]); } catch (NumberFormatException ignored) { }
            if (count < 1) count = 1;
        }

        org.bukkit.World world = Bukkit.getWorlds().get(0);
        if (world == null) {
            CommandFeedback.error(sender, "No world available");
            return true;
        }

        // Derive deterministic base coordinates from seed so different seeds yield different layouts
        int seedInt = (int)(seed & 0x7fffffff);
        int baseX = (seedInt % 200) - 100;
        int baseZ = ((seedInt / 200) % 200) - 100;
        // T026d: Use fixed Y coordinate for deterministic testing (ignore terrain height)
        int baseY = 64;
        int fixedWidth = 7;
        int fixedDepth = 7;
        int fixedHeight = 6;
        int fixedSpacing = 24;
        int entranceOffset = -5;
        int corridorHalfWidth = 1;

        // T026d: Pre-load chunks to ensure terrain is generated before block placement
        // This prevents race conditions where terrain generation interferes with fixed-layout placement
        int minLayoutX = baseX - 2;
        int maxLayoutX = baseX + (Math.max(0, count - 1) * fixedSpacing) + fixedWidth + 2;
        int minLayoutZ = baseZ + entranceOffset - corridorHalfWidth - 2;
        int maxLayoutZ = baseZ + fixedDepth + 2;
        int debugProbeX = baseX + fixedWidth / 2;
        int debugProbeZ = baseZ + entranceOffset;
        int preloadMargin = 16;
        for (int chunkX = (minLayoutX - preloadMargin) >> 4; chunkX <= (maxLayoutX + preloadMargin) >> 4; chunkX++) {
            for (int chunkZ = (minLayoutZ - preloadMargin) >> 4; chunkZ <= (maxLayoutZ + preloadMargin) >> 4; chunkZ++) {
                world.getChunkAt(chunkX, chunkZ);
            }
        }

        // Ensure a flat walkable base so pathfinding has consistent support.
        int baseGroundY = baseY - 1;
        int subGroundY = baseGroundY - 1;
        int minWorldY = world.getMinHeight();
        int maxWorldY = world.getMaxHeight();
        for (int x = minLayoutX; x <= maxLayoutX; x++) {
            for (int z = minLayoutZ; z <= maxLayoutZ; z++) {
                for (int clearY = minWorldY; clearY <= maxWorldY; clearY++) {
                    world.getBlockAt(x, clearY, z).setType(org.bukkit.Material.AIR);
                }
                if (subGroundY >= minWorldY) {
                    world.getBlockAt(x, subGroundY, z).setType(org.bukkit.Material.DIRT);
                }
                world.getBlockAt(x, baseGroundY, z).setType(org.bukkit.Material.DIRT);
                world.getBlockAt(x, baseGroundY + 1, z).setType(org.bukkit.Material.AIR);
            }
        }


        String villageName = "fixed-" + Long.toString(seed);
        // Use deterministic village UUID derived from seed so fixed-layout runs are repeatable
        java.util.UUID villageId = java.util.UUID.nameUUIDFromBytes(("fixed-layout-village-" + seed).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        // Load village into service using deterministic ID (avoids random UUIDs)
        plugin.getVillageService().loadVillage(villageId, "roman", villageName, 1000L, world, baseX, baseY, baseZ);
        plugin.getMetadataStore().setVillageName(villageId, villageName);
        java.util.Optional<com.davisodom.villageoverhaul.villages.Village> villageOpt = plugin.getVillageService().getVillage(villageId);
        if (villageOpt.isEmpty()) {
            CommandFeedback.error(sender, "Failed to create deterministic village");
            plugin.getLogger().warning("[STRUCT][TEST] Failed to load deterministic village id=" + villageId);
            return true;
        }
        com.davisodom.villageoverhaul.villages.Village village = villageOpt.get();

        com.davisodom.villageoverhaul.villages.VillageMetadataStore metadataStore = plugin.getMetadataStore();
        metadataStore.registerVillage(village.getId(), "roman", new org.bukkit.Location(world, baseX, baseY, baseZ), seed);
        // Ensure placement rejection counters artifact exists for fixed-layout test villages
        try {
            metadataStore.recordPlacementRejectionCounters(village.getId(), new com.davisodom.villageoverhaul.villages.VillageMetadataStore.PlacementRejectionCounters(0,0,0,0,0,0,0,0,0,0));
        } catch (Exception ex) {
            plugin.getLogger().warning("[STRUCT][DIAG] Failed to create placement counters artifact for fixed-layout village: " + ex.getMessage());
        }

        java.util.List<org.bukkit.Location> buildingLocations = new java.util.ArrayList<>();

        for (int i = 0; i < count; i++) {
            int width = fixedWidth;
            int depth = fixedDepth;
            int height = fixedHeight;
            int spacing = fixedSpacing;
            int x = baseX + i * spacing;
            int z = baseZ;
            // T026d: Use fixed baseY for all buildings (ignore terrain)
            int y = baseY;



            // Deterministic building id derived from seed+index so repeated runs reproduce the same ids
            UUID buildingId = UUID.nameUUIDFromBytes(("fixed-layout-" + seed + "-" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String structureId = "fixed_house_" + i;

            // Use entrance-aligned origin for building locations so path generation
            // targets a walkable point outside the persisted footprint (entrance)
            // Place entrance further out than the expanded volume mask buffer (buffer=2)
            // so the walkable node lies outside obstacles (use z - 5)

            org.bukkit.Location origin = new org.bukkit.Location(world, x + width / 2, y, z - 5);

            com.davisodom.villageoverhaul.model.Building building =
                new com.davisodom.villageoverhaul.model.Building.Builder()
                    .buildingId(buildingId)
                    .villageId(village.getId())
                    .structureId(structureId)
                    .origin(origin)
                    .dimensions(width, height, depth)
                    .isMainBuilding(i == 0)
                    .build();

            metadataStore.addBuilding(village.getId(), building);
            if (i == 0) metadataStore.setMainBuilding(village.getId(), buildingId);

            int minX = x;
            int maxX = x + width - 1;
            int minZ = z;
            int maxZ = z + depth - 1;
            int minY = y;
            int maxY = y + height - 1;

            com.davisodom.villageoverhaul.model.PlacementReceipt.CornerSample[] corners =
                new com.davisodom.villageoverhaul.model.PlacementReceipt.CornerSample[4];
            corners[0] = new com.davisodom.villageoverhaul.model.PlacementReceipt.CornerSample(minX, minY, minZ, org.bukkit.Material.STONE);
            corners[1] = new com.davisodom.villageoverhaul.model.PlacementReceipt.CornerSample(maxX, minY, minZ, org.bukkit.Material.STONE);
            corners[2] = new com.davisodom.villageoverhaul.model.PlacementReceipt.CornerSample(maxX, minY, maxZ, org.bukkit.Material.STONE);
            corners[3] = new com.davisodom.villageoverhaul.model.PlacementReceipt.CornerSample(minX, minY, maxZ, org.bukkit.Material.STONE);

            com.davisodom.villageoverhaul.model.PlacementReceipt receipt =
                new com.davisodom.villageoverhaul.model.PlacementReceipt.Builder()
                    .structureId(structureId)
                    .villageId(village.getId())
                    .world(world)
                    .origin(x, y, z)
                    .rotation(0)
                    .bounds(minX, maxX, minY, maxY, minZ, maxZ)
                    .dimensions(width, height, depth)
                    .entrance(x + width / 2, y, z - 5)

                    .foundationCorners(corners)
                    .build();

            metadataStore.addPlacementReceipt(village.getId(), receipt);

            com.davisodom.villageoverhaul.model.VolumeMask mask = com.davisodom.villageoverhaul.model.VolumeMask.fromReceipt(receipt);
            metadataStore.addVolumeMask(village.getId(), mask);

            // T026d: Create in-world representation for fixed-layout test mode
            // Unconditionally place blocks at exact coordinates (ignore existing terrain)
            try {
                // First, clear all blocks in and above the receipt AABB to ensure deterministic placement
                int minClearY = world.getMinHeight();
                int maxClearY = world.getMaxHeight();
                for (int bx = receipt.getMinX(); bx <= receipt.getMaxX(); bx++) {
                    for (int bz = receipt.getMinZ(); bz <= receipt.getMaxZ(); bz++) {
                        for (int by = minClearY; by <= maxClearY; by++) {
                            world.getBlockAt(bx, by, bz).setType(org.bukkit.Material.AIR);
                        }
                    }
                }

                
                // Now place the structure blocks unconditionally
                for (int bx = receipt.getMinX(); bx <= receipt.getMaxX(); bx++) {
                    for (int bz = receipt.getMinZ(); bz <= receipt.getMaxZ(); bz++) {
                        for (int by = receipt.getMinY(); by <= receipt.getMaxY(); by++) {
                            world.getBlockAt(bx, by, bz).setType(org.bukkit.Material.STONE);
                        }
                    }
                }

                // Ensure entrance is clear
                org.bukkit.block.Block entranceBlock = world.getBlockAt(receipt.getEntranceX(), receipt.getEntranceY(), receipt.getEntranceZ());
                if (entranceBlock != null) entranceBlock.setType(org.bukkit.Material.AIR);
            } catch (Exception e) {
                plugin.getLogger().warning("[STRUCT][TEST] Failed to place fixed-layout blocks in world: " + e.getMessage());
            }

            buildingLocations.add(origin);
        }

        // Create a simple corridor connecting entrances so pathing has a clear walkable surface
        // T026d: Unconditionally place corridor blocks at fixed Y (ignore terrain)
        if (!buildingLocations.isEmpty()) {
            int minX = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int corridorZ = baseZ - 5; // matches entrance Z

            for (org.bukkit.Location loc : buildingLocations) {
                int ex = loc.getBlockX();
                minX = Math.min(minX, ex);
                maxX = Math.max(maxX, ex);
            }

            int groundY = baseY - 1;
            int corridorSubGroundY = groundY - 1;
            int minClearY = world.getMinHeight();
            int maxClearY = world.getMaxHeight();
            for (int cx = minX - 2; cx <= maxX + 2; cx++) {
                for (int cz = corridorZ - 1; cz <= corridorZ + 1; cz++) {
                    try {
                        for (int clearY = minClearY; clearY <= maxClearY; clearY++) {
                            world.getBlockAt(cx, clearY, cz).setType(org.bukkit.Material.AIR);
                        }

                        if (corridorSubGroundY >= minClearY) {
                            world.getBlockAt(cx, corridorSubGroundY, cz).setType(org.bukkit.Material.DIRT);
                        }
                        world.getBlockAt(cx, groundY, cz).setType(org.bukkit.Material.DIRT);
                        world.getBlockAt(cx, groundY + 1, cz).setType(org.bukkit.Material.AIR);
                    } catch (Exception e) {
                        plugin.getLogger().warning("[STRUCT][TEST] Corridor placement failed at " + cx + "," + cz + ": " + e.getMessage());
                    }
                }
            }
        }


        int debugHighestY;
        org.bukkit.Material debugSurfaceType;
        try {
            debugHighestY = world.getHighestBlockYAt(debugProbeX, debugProbeZ);
            debugSurfaceType = world.getBlockAt(debugProbeX, debugHighestY, debugProbeZ).getType();
        } catch (Exception e) {
            debugHighestY = baseY - 1;
            debugSurfaceType = org.bukkit.Material.AIR;
        }

        CommandFeedback.info(sender, String.format("Created fixed-layout village '%s' id=%s buildings=%d seed=%d", villageName, village.getId(), count, seed));
        plugin.getLogger().info(String.format("[STRUCT][TEST] Fixed layout village=%s buildings=%d seed=%d", village.getId(), count, seed));
        int receiptCount = metadataStore.getPlacementReceipts(village.getId()).size();
        plugin.getLogger().info(String.format("[STRUCT][TEST] Fixed layout receipts=%d village=%s", receiptCount, village.getId()));
        plugin.getLogger().info(String.format("[STRUCT] village: id=%s buildings=%d", village.getId(), receiptCount));
        plugin.getLogger().info(String.format("[STRUCT][TEST] Fixed layout probe=(%d,%d) highestY=%d type=%s baseY=%d",
            debugProbeX, debugProbeZ, debugHighestY, debugSurfaceType, baseY));


        // Do not auto-run path generation here; allow harness to request path generation explicitly
        return true;
    }
    
    private int randomRange(Random random, int min, int max) {
        return min + random.nextInt(max - min + 1);
    }
    
    private void spawnParticle(org.bukkit.World world, int x, int y, int z) {
        world.spawnParticle(org.bukkit.Particle.FLAME, x + 0.5, y + 0.5, z + 0.5, 1, 0, 0, 0, 0);
    }

    @Nullable
    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, 
                                     @NotNull String alias, @NotNull String[] args) {
        List<String> completions = new ArrayList<>();
        
        if (args.length == 1) {
            // Subcommands
            completions.add("create-village");
            completions.add("generate-structures");
            completions.add("generate-paths");
            completions.add("fixed-layout");
            completions.add("spawn-villager");
            completions.add("trigger-interaction");
            completions.add("simulate-interaction");
            completions.add("place-obstacle");
            completions.add("verify-persistence");
            completions.add("metrics");
            completions.add("performance");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("place-obstacle")) {
            // Obstacle types
            completions.add("water");
            completions.add("steep");
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

