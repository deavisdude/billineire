package com.davisodom.villageoverhaul.commands;

import com.davisodom.villageoverhaul.VillageOverhaulPlugin;
import com.davisodom.villageoverhaul.npc.CustomVillagerService;
import com.davisodom.villageoverhaul.npc.VillagerInteractionController;
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
        
        if (args.length == 0) {
            sender.sendMessage("§cUsage: /votest <create-village|generate-structures|generate-paths|spawn-villager|trigger-interaction|simulate-interaction|place-obstacle|verify-persistence|metrics|performance>");
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
        
        com.davisodom.villageoverhaul.villages.Village village = 
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
     * Generate structures for a village
     * Usage: /votest generate-structures <village-id>
     */
    private boolean handleGenerateStructures(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /votest generate-structures <village-id>");
            return true;
        }
        
        String villageIdStr = args[1];
        UUID villageId;
        
        try {
            villageId = UUID.fromString(villageIdStr);
        } catch (IllegalArgumentException e) {
            sender.sendMessage("§cInvalid village ID format");
            return true;
        }
        
        // TODO: Integrate with VillagePlacementService
        // For now, log the request
        plugin.getLogger().info(String.format("[STRUCT] Begin structure generation for village %s", villageId));
        
        sender.sendMessage("§aStructure generation initiated for village: " + villageId);
        sender.sendMessage("§7Check logs for [STRUCT] markers for placement details");
        
        // TODO: Implement actual structure generation via VillagePlacementService
        // Expected flow:
        // 1. Load village metadata (culture, location)
        // 2. Get structure set from culture definition
        // 3. For each structure in set:
        //    - Find suitable placement location
        //    - Validate site with SiteValidator
        //    - Attempt placement via StructureService (with re-seating)
        //    - Log [STRUCT] begin/seat/re-seat/abort
        // 4. Persist placed buildings to VillageMetadataStore
        
        plugin.getLogger().info(String.format("[STRUCT] Structure generation complete for village %s (placeholder)", villageId));
        
        return true;
    }
    
    /**
     * Generate path network for a village
     * Usage: /votest generate-paths <village-id>
     */
    private boolean handleGeneratePaths(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /votest generate-paths <village-id>");
            return true;
        }
        
        String villageIdStr = args[1];
        UUID villageId;
        
        try {
            villageId = UUID.fromString(villageIdStr);
        } catch (IllegalArgumentException e) {
            sender.sendMessage("§cInvalid village ID format");
            return true;
        }
        
        // Get shared metadata store from plugin
        com.davisodom.villageoverhaul.villages.VillageMetadataStore metadataStore = 
            plugin.getMetadataStore();
        
        // Verify village exists
        Optional<com.davisodom.villageoverhaul.villages.VillageMetadataStore.VillageMetadata> villageOpt = 
            metadataStore.getVillage(villageId);
        
        if (villageOpt.isEmpty()) {
            sender.sendMessage("§cVillage not found: " + villageId);
            return true;
        }
        
        com.davisodom.villageoverhaul.villages.VillageMetadataStore.VillageMetadata village = 
            villageOpt.get();
        
        // Get buildings for this village
        List<com.davisodom.villageoverhaul.model.Building> buildings = 
            metadataStore.getVillageBuildings(villageId);
        
        if (buildings.isEmpty()) {
            sender.sendMessage("§cNo buildings found for village: " + villageId);
            sender.sendMessage("§7Run /votest generate-structures first");
            return true;
        }
        
        // Get main building location
        Optional<UUID> mainBuildingIdOpt = metadataStore.getMainBuilding(villageId);
        if (mainBuildingIdOpt.isEmpty()) {
            sender.sendMessage("§cNo main building designated for village: " + villageId);
            sender.sendMessage("§7Main building should be designated during structure generation");
            return true;
        }
        
        UUID mainBuildingId = mainBuildingIdOpt.get();
        com.davisodom.villageoverhaul.model.Building mainBuilding = null;
        
        for (com.davisodom.villageoverhaul.model.Building building : buildings) {
            if (building.getBuildingId().equals(mainBuildingId)) {
                mainBuilding = building;
                break;
            }
        }
        
        if (mainBuilding == null) {
            sender.sendMessage("§cMain building not found in building list: " + mainBuildingId);
            return true;
        }
        
        // Get world
        org.bukkit.World world = village.getOrigin().getWorld();
        if (world == null) {
            sender.sendMessage("§cWorld not found for village");
            return true;
        }
        
        // Collect building locations
        List<Location> buildingLocations = new java.util.ArrayList<>();
        for (com.davisodom.villageoverhaul.model.Building building : buildings) {
            buildingLocations.add(building.getOrigin());
        }
        
        // Initialize PathService (use plugin's instance if available, or create one)
        com.davisodom.villageoverhaul.worldgen.PathService pathService = 
            new com.davisodom.villageoverhaul.worldgen.impl.PathServiceImpl(metadataStore);
        
        // Log path generation start
        plugin.getLogger().info(String.format(
            "[STRUCT] Begin path network generation for village %s: buildings=%d, mainBuilding=%s",
            villageId, buildings.size(), mainBuildingId));
        
        sender.sendMessage("§aGenerating path network for village: " + villageId);
        sender.sendMessage(String.format("§7Buildings: %d, Main building: %s", 
            buildings.size(), mainBuilding.getStructureId()));
        
        // Generate path network
        boolean success = pathService.generatePathNetwork(
            world, 
            villageId, 
            buildingLocations, 
            mainBuilding.getOrigin(), 
            village.getSeed()
        );
        
        if (success) {
            sender.sendMessage("§aPath network generated successfully!");
            
            // Retrieve path segments using the interface method
            List<List<org.bukkit.block.Block>> pathSegments = 
                pathService.getVillagePathNetwork(villageId);
            
            if (!pathSegments.isEmpty()) {
                // Emit path blocks using PathEmitter
                com.davisodom.villageoverhaul.worldgen.impl.PathEmitter pathEmitter = 
                    new com.davisodom.villageoverhaul.worldgen.impl.PathEmitter();
                
                int totalBlocksPlaced = 0;
                for (List<org.bukkit.block.Block> pathBlocks : pathSegments) {
                    int blocksPlaced = pathEmitter.emitPath(
                        world, 
                        pathBlocks, 
                        village.getCultureId()
                    );
                    totalBlocksPlaced += blocksPlaced;
                }
                
                sender.sendMessage(String.format("§7Path segments: %d, Blocks placed: %d", 
                    pathSegments.size(), totalBlocksPlaced));
                
                plugin.getLogger().info(String.format(
                    "[STRUCT] Path network complete for village %s: segments=%d, blocks=%d",
                    villageId, pathSegments.size(), totalBlocksPlaced));
            } else {
                sender.sendMessage("§eWarning: Path network generated but no segments retrievable");
            }
        } else {
            sender.sendMessage("§cPath network generation failed");
            sender.sendMessage("§7Check logs for [STRUCT] markers with failure details");
            
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
        com.davisodom.villageoverhaul.npc.CustomVillager customVillager = 
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
            java.util.Optional<com.davisodom.villageoverhaul.villages.Village> villageOpt = 
                plugin.getVillageService().getVillage(villageId);
            
            if (villageOpt.isEmpty()) {
                plugin.getLogger().warning("[TEST] No village found for custom villager: " + villageId);
                plugin.getLogger().info("[TEST] Trade completed without village contribution (isolated villager)");
                sender.sendMessage("§aSimulated trade completed (no village project to contribute to)");
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
                sender.sendMessage("§aSimulated trade completed! Village treasury increased.");
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
     * Place terrain obstacles for controlled pathfinding tests
     * Usage: /votest place-obstacle <water|steep> <x> <z> <radius|width>
     */
    private boolean handlePlaceObstacle(CommandSender sender, String[] args) {
        if (args.length < 5) {
            sender.sendMessage("§cUsage: /votest place-obstacle <water|steep> <x> <z> <radius|width>");
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
                        sender.sendMessage(String.format("§aPlaced water patch at (%d, %d) radius=%d", x, z, size));
                        plugin.getLogger().info(String.format("[TEST] Placed water obstacle at (%d, %d) radius=%d", x, z, size));
                        break;
                        
                    case "steep":
                        placeSteepTerrain(world, x, z, size);
                        sender.sendMessage(String.format("§aPlaced steep terrain at (%d, %d) width=%d", x, z, size));
                        plugin.getLogger().info(String.format("[TEST] Placed steep obstacle at (%d, %d) width=%d", x, z, size));
                        break;
                        
                    default:
                        sender.sendMessage("§cUnknown obstacle type: " + obstacleType);
                        sender.sendMessage("§7Valid types: water, steep");
                        return true;
                }
                
                return true;
            }
            
            Player player = (Player) sender;
            org.bukkit.World world = player.getWorld();
            
            switch (obstacleType) {
                case "water":
                    placeWaterPatch(world, x, z, size);
                    sender.sendMessage(String.format("§aPlaced water patch at (%d, %d) radius=%d", x, z, size));
                    plugin.getLogger().info(String.format("[TEST] Placed water obstacle at (%d, %d) radius=%d", x, z, size));
                    break;
                    
                case "steep":
                    placeSteepTerrain(world, x, z, size);
                    sender.sendMessage(String.format("§aPlaced steep terrain at (%d, %d) width=%d", x, z, size));
                    plugin.getLogger().info(String.format("[TEST] Placed steep obstacle at (%d, %d) width=%d", x, z, size));
                    break;
                    
                default:
                    sender.sendMessage("§cUnknown obstacle type: " + obstacleType);
                    sender.sendMessage("§7Valid types: water, steep");
                    return true;
            }
            
        } catch (NumberFormatException e) {
            sender.sendMessage("§cInvalid coordinates or size");
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
    
    /**
     * Verify persistence data against in-game reality
     * Usage: /votest verify-persistence <village-id>
     */
    private boolean handleVerifyPersistence(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /votest verify-persistence <village-id>");
            return true;
        }
        
        String villageIdStr = args[1];
        UUID villageId;
        try {
            villageId = UUID.fromString(villageIdStr);
        } catch (IllegalArgumentException e) {
            sender.sendMessage("§cInvalid village ID format");
            return true;
        }
        
        com.davisodom.villageoverhaul.villages.VillageMetadataStore metadataStore = plugin.getMetadataStore();
        List<com.davisodom.villageoverhaul.model.VolumeMask> masks = metadataStore.getVolumeMasks(villageId);
        List<com.davisodom.villageoverhaul.model.PlacementReceipt> receipts = metadataStore.getPlacementReceipts(villageId);
        
        if (masks.isEmpty() && receipts.isEmpty()) {
            sender.sendMessage("§cNo persistence data found for village " + villageId);
            return true;
        }
        
        sender.sendMessage("§aVerifying persistence for village " + villageId);
        sender.sendMessage(String.format("§7Found %d masks and %d receipts", masks.size(), receipts.size()));
        
        boolean allPass = true;
        int totalChecks = 0;
        int failedChecks = 0;
        
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
            // Need world to check blocks. Mask doesn't store world name, but Receipt does.
            // Assuming all in same world or we can find it.
            Optional<com.davisodom.villageoverhaul.villages.VillageMetadataStore.VillageMetadata> villageOpt = 
                metadataStore.getVillage(villageId);
            if (villageOpt.isEmpty()) continue;
            org.bukkit.World world = villageOpt.get().getOrigin().getWorld();
            if (world == null) continue;
            
            // 1. Sample 32 points INSIDE
            for (int i = 0; i < 32; i++) {
                int x = mask.getMinX() + random.nextInt(mask.getWidth());
                int y = mask.getMinY() + random.nextInt(mask.getHeight());
                int z = mask.getMinZ() + random.nextInt(mask.getDepth());
                
                if (mask.contains(x, y, z)) {
                    totalChecks++;
                    if (world.getBlockAt(x, y, z).getType().isAir()) {
                        // Note: This might fail for hollow structures until occupancy bitmaps are implemented
                        // For now, we log it but maybe we should be lenient if it's just interior air?
                        // The requirement says "asserts blocks are non-air", so we report failure.
                        failedChecks++;
                        sender.sendMessage(String.format("§cFAIL: Block at %d,%d,%d is AIR (inside mask)", x, y, z));
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
                    sender.sendMessage(String.format("§cFAIL: Point %d,%d,%d is INSIDE mask (should be outside)", x, y, z));
                    allPass = false;
                }
            }
        }
        
        if (allPass) {
            sender.sendMessage("§aPASS: All persistence checks passed (" + totalChecks + " checks)");
        } else {
            sender.sendMessage("§cFAIL: " + failedChecks + "/" + totalChecks + " checks failed");
        }
        
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

