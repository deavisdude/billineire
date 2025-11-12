package com.davisodom.villageoverhaul.commands;

import com.davisodom.villageoverhaul.VillageOverhaulPlugin;
import com.davisodom.villageoverhaul.villages.Village;
import com.davisodom.villageoverhaul.villages.VillageMetadataStore;
import com.davisodom.villageoverhaul.villages.VillagePlacementService;
import com.davisodom.villageoverhaul.villages.impl.VillagePlacementServiceImpl;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * User-facing command to generate villages.
 * 
 * Usage: /vo generate <culture> <name> [seed]
 * 
 * This command:
 * 1. Finds suitable terrain near the player (or specified location)
 * 2. Creates a village in the VillageService with the given culture and name
 * 3. Uses VillagePlacementService to place structures
 * 4. Logs [STRUCT] summary of placement results
 * 5. (Future) When US2 is complete, also invokes path network generation
 */
public class GenerateCommand {
    
    private final VillageOverhaulPlugin plugin;
    private final Logger logger;
    
    public GenerateCommand(VillageOverhaulPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }
    
    /**
     * Handle /vo generate <culture> <name> [seed]
     * 
     * @param sender Command sender
     * @param args Command arguments (culture, name, seed)
     * @return true if command executed successfully
     */
    public boolean execute(CommandSender sender, String[] args) {
        // Parse arguments
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /vo generate <culture> <name> [seed]");
            sender.sendMessage("§7Example: /vo generate roman 'New Rome' 12345");
            return true;
        }
        
        String cultureId = args[0];
        String villageName = args[1];
        
        // Optional seed argument (make final for lambda capture)
        final Long seedArg;
        if (args.length >= 3) {
            try {
                seedArg = Long.parseLong(args[2]);
            } catch (NumberFormatException e) {
                sender.sendMessage("§cInvalid seed: " + args[2]);
                return true;
            }
        } else {
            seedArg = null;
        }
        
    // Immediate feedback to user
    sender.sendMessage("§aOK Village generation command received");
        sender.sendMessage("§7  Culture: §f" + cultureId);
        sender.sendMessage("§7  Name: §f" + villageName);
        if (seedArg != null) {
            sender.sendMessage("§7  Seed: §f" + seedArg);
        }
        
        // Validate culture exists
        if (!plugin.getCultureService().all().stream().anyMatch(c -> c.getId().equals(cultureId))) {
            sender.sendMessage("§cUnknown culture: " + cultureId);
            sender.sendMessage("§7Available cultures: " + 
                String.join(", ", plugin.getCultureService().all().stream()
                    .map(c -> c.getId()).toList()));
            return true;
        }
        
        // Determine search location
        Location searchOrigin;
        World world;
        
        if (sender instanceof Player player) {
            searchOrigin = player.getLocation();
            world = player.getWorld();
            sender.sendMessage("§7Searching for suitable terrain near your location...");
        } else {
            // Console command - use world spawn
            world = Bukkit.getWorlds().get(0);
            searchOrigin = world.getSpawnLocation();
            sender.sendMessage("§7Searching for suitable terrain near world spawn...");
        }
        
        // Use shared metadata store (T012l: singleton for cross-session enforcement)
        VillageMetadataStore metadataStore = plugin.getMetadataStore();
        boolean isFirstVillage = isFirstVillage(world, metadataStore);
        int spawnProximityRadius = plugin.getSpawnProximityRadius();
        
        // Adjust search strategy based on whether this is first village
        if (isFirstVillage && spawnProximityRadius > 0) {
            // First village: search near spawn (Constitution v1.5.0, Principle XII)
            searchOrigin = world.getSpawnLocation();
            sender.sendMessage("§7First village: searching within " + spawnProximityRadius + " blocks of spawn...");
        } else if (!isFirstVillage) {
            // Subsequent villages: find nearest existing village and search near it
            Location nearestVillage = findNearestVillageLocation(world, searchOrigin, metadataStore);
            if (nearestVillage != null) {
                searchOrigin = nearestVillage;
                sender.sendMessage("§7Subsequent village: searching near existing village at " + 
                    formatLocation(nearestVillage) + "...");
            }
        }
        
        // Search for suitable terrain (async to avoid blocking)
        final Location finalSearchOrigin = searchOrigin;
        final World finalWorld = world;
        final int minVillageSpacing = plugin.getMinVillageSpacing();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Location suitableLocation = findSuitableVillageLocation(finalWorld, finalSearchOrigin, 
                    isFirstVillage ? spawnProximityRadius : 512, metadataStore, minVillageSpacing);
            
            if (suitableLocation == null) {
                sender.sendMessage("§cNo suitable terrain found. Try a different location.");
                return;
            }
            
            int baseX = suitableLocation.getBlockX();
            int baseZ = suitableLocation.getBlockZ();
            int baseY = world.getHighestBlockYAt(baseX, baseZ);
            
            sender.sendMessage("§aFound suitable terrain at (" + baseX + ", " + baseY + ", " + baseZ + ")");
            
            // Calculate seed (use provided seed or generate from world + location)
            final long villageSeed = seedArg != null ? seedArg : 
                world.getSeed() ^ (((long)baseX << 32) | (baseZ & 0xFFFFFFFFL));
            
            // Return to main thread for village creation and structure placement
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    // Create village in VillageService
                    Village village = plugin.getVillageService().createVillage(
                        cultureId, 
                        villageName, 
                        world.getName(), 
                        baseX, 
                        baseY + 1, 
                        baseZ
                    );
                    
                    UUID villageId = village.getId();
                    
                    // Set up placement service with shared metadata store (T012l)
                    VillagePlacementService placementService = new VillagePlacementServiceImpl(
                        plugin, metadataStore, plugin.getCultureService());
                    
                    // Log start
                    // Note: Village registration now happens INSIDE placeVillage() after spacing validation
                    logger.info("[STRUCT] User-triggered village generation: '" + villageName + "' (culture=" + 
                        cultureId + ", seed=" + villageSeed + ")");
                    sender.sendMessage("§7Generating village '" + villageName + "' (ID: " + villageId + ")...");
                    
                    // Place structures
                    Location villageOrigin = new Location(world, baseX, baseY, baseZ);
                    Optional<UUID> placedVillageId = placementService.placeVillage(
                        world, villageOrigin, cultureId, villageSeed);
                    
                    // Report results
                    if (placedVillageId.isPresent()) {
                        int buildingCount = metadataStore.getVillageBuildings(villageId).size();
                        
                        sender.sendMessage("§aOK Village '" + villageName + "' generated successfully!");
                        sender.sendMessage("§7  Culture: " + cultureId);
                        sender.sendMessage("§7  Location: " + baseX + ", " + baseY + ", " + baseZ);
                        sender.sendMessage("§7  Buildings: " + buildingCount);
                        sender.sendMessage("§7  Seed: " + villageSeed);
                        
                        logger.info("[STRUCT] Successfully generated village '" + villageName + "' with " + 
                            buildingCount + " buildings");
                        
                        // TODO: When US2 is complete, invoke path network generation here
                        // For now, report that paths are not yet available
                        sender.sendMessage("§7  Paths: Not yet available (US2 in progress)");
                        
                    } else {
                        sender.sendMessage("§cX Failed to place structures for village '" + villageName + "'");
                        sender.sendMessage("§7Check server logs for details.");
                        
                        // Place marker pillar as fallback
                        world.getBlockAt(baseX, baseY, baseZ).setType(Material.STONE, false);
                        world.getBlockAt(baseX, baseY + 1, baseZ).setType(Material.STONE, false);
                        world.getBlockAt(baseX, baseY + 2, baseZ).setType(Material.TORCH, false);
                        
                        sender.sendMessage("§7Placed marker pillar at village center.");
                        
                        logger.warning("[STRUCT] Failed to place structures for village '" + villageName + "' " +
                            "(ID: " + villageId + "), placed marker pillar");
                    }
                    
                } catch (Exception e) {
                    sender.sendMessage("§cError generating village: " + e.getMessage());
                    logger.severe("[STRUCT] Error during village generation: " + e.getMessage());
                    e.printStackTrace();
                }
            });
        });
        
        return true;
    }
    
    /**
     * Search for suitable flat terrain for village placement.
     * Adapted from VillageWorldgenAdapter with similar criteria.
     * For subsequent villages, starts search beyond minVillageSpacing radius.
     * 
     * @param world Target world
     * @param start Starting search location
     * @param maxRadius Maximum search radius in blocks
     * @param metadataStore Metadata store to check existing villages
     * @param minVillageSpacing Minimum spacing requirement
     * @return Suitable location or null if none found
     */
    private Location findSuitableVillageLocation(World world, Location start, int maxRadius, 
            VillageMetadataStore metadataStore, int minVillageSpacing) {
        logger.info("[STRUCT] Searching for suitable terrain within " + maxRadius + " blocks...");
        
        int startX = start.getBlockX();
        int startZ = start.getBlockZ();
        int checkRadius = 24; // Check 24 block radius for flatness
        int sampleInterval = 24; // Check every 24 blocks in spiral
        
        // For subsequent villages, start search beyond minVillageSpacing
        boolean isFirstVillage = isFirstVillage(world, metadataStore);
        int startRadius = isFirstVillage ? 16 : (minVillageSpacing + 32);
        
        // Spiral search pattern
        for (int radius = startRadius; radius <= Math.min(maxRadius, 512); radius += sampleInterval) {
            // Check 8 points around the circle at this radius
            for (int i = 0; i < 8; i++) {
                double angle = (i / 8.0) * 2 * Math.PI;
                int x = startX + (int)(radius * Math.cos(angle));
                int z = startZ + (int)(radius * Math.sin(angle));
                
                // Check if this location is suitable for terrain
                if (!isTerrainSuitable(world, x, z, checkRadius)) {
                    continue;
                }
                
                int y = world.getHighestBlockYAt(x, z);
                Location candidate = new Location(world, x, y, z);
                
                // Check inter-village spacing
                if (!checkInterVillageSpacing(candidate, metadataStore, minVillageSpacing)) {
                    continue;
                }
                
                logger.info("[STRUCT] Found suitable terrain at distance " + radius + " blocks: " +
                    "(" + x + ", " + y + ", " + z + ")");
                return candidate;
            }
        }
        
        logger.warning("[STRUCT] No suitable terrain found within " + maxRadius + " blocks");
        return null;
    }
    
    /**
     * Check if terrain at location is suitable for village placement.
     * 
     * Criteria:
     * - Y variation <= 15 blocks (relatively flat)
     * - Less than 30% water coverage
     * - Height between Y 50 and Y 120 (avoid too deep or too high)
     * 
     * @param world Target world
     * @param centerX Center X coordinate
     * @param centerZ Center Z coordinate
     * @param checkRadius Radius to check around center
     * @return true if terrain is suitable
     */
    private boolean isTerrainSuitable(World world, int centerX, int centerZ, int checkRadius) {
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        int waterBlocks = 0;
        int totalChecks = 0;
        
        // Sample terrain in a grid pattern (every 12 blocks for speed)
        for (int x = -checkRadius; x <= checkRadius; x += 12) {
            for (int z = -checkRadius; z <= checkRadius; z += 12) {
                int checkX = centerX + x;
                int checkZ = centerZ + z;
                int y = world.getHighestBlockYAt(checkX, checkZ);
                
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
                totalChecks++;
                
                // Check if surface is water
                Material surface = world.getBlockAt(checkX, y, checkZ).getType();
                if (surface == Material.WATER) {
                    waterBlocks++;
                }
            }
        }
        
        int yVariation = maxY - minY;
        double waterPercent = (double) waterBlocks / totalChecks;
        
        // Apply criteria
        boolean flatEnough = yVariation <= 15;
        boolean notTooWatery = waterPercent < 0.3;
        boolean goodHeight = minY >= 50 && maxY <= 120;
        
        
        return flatEnough && notTooWatery && goodHeight;
    }
    
    /**
     * Check if this is the first village in the world.
     * 
     * @param world Target world
     * @param metadataStore Metadata store to check
     * @return true if no villages exist in this world yet
     */
    private boolean isFirstVillage(World world, VillageMetadataStore metadataStore) {
        for (VillageMetadataStore.VillageMetadata village : metadataStore.getAllVillages()) {
            if (village.getOrigin().getWorld().equals(world)) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Find the nearest existing village location.
     * Used for nearest-neighbor bias (Constitution v1.5.0, Principle XII).
     * 
     * @param world Target world
     * @param searchOrigin Current search origin
     * @param metadataStore Metadata store with village data
     * @return Location of nearest village, or null if no villages exist
     */
    private Location findNearestVillageLocation(World world, Location searchOrigin, VillageMetadataStore metadataStore) {
        Location nearest = null;
        int minDistance = Integer.MAX_VALUE;
        
        for (VillageMetadataStore.VillageMetadata village : metadataStore.getAllVillages()) {
            if (!village.getOrigin().getWorld().equals(world)) {
                continue;
            }
            
            Location villageOrigin = village.getOrigin();
            int dx = Math.abs(searchOrigin.getBlockX() - villageOrigin.getBlockX());
            int dz = Math.abs(searchOrigin.getBlockZ() - villageOrigin.getBlockZ());
            int distance = dx + dz; // Manhattan distance
            
            if (distance < minDistance) {
                minDistance = distance;
                nearest = villageOrigin;
            }
        }
        
        return nearest;
    }
    
    /**
     * Check if proposed village location violates minimum inter-village spacing.
     * Used in GenerateCommand pre-check (before placeVillage() is called).
     * 
     * @param proposedOrigin Proposed village origin
     * @param metadataStore Metadata store with village data
     * @param minVillageSpacing Minimum spacing requirement (border-to-border)
     * @return true if spacing is acceptable, false if violated
     */
    private boolean checkInterVillageSpacing(Location proposedOrigin, VillageMetadataStore metadataStore, int minVillageSpacing) {
        World world = proposedOrigin.getWorld();
        
        // Create temporary border for proposed location (initial size before any buildings)
        VillageMetadataStore.VillageBorder proposedBorder = new VillageMetadataStore.VillageBorder(
            proposedOrigin.getBlockX(), proposedOrigin.getBlockX(),
            proposedOrigin.getBlockZ(), proposedOrigin.getBlockZ());
        
        // Check against all existing villages in same world
        for (VillageMetadataStore.VillageMetadata existingVillage : metadataStore.getAllVillages()) {
            if (!existingVillage.getOrigin().getWorld().equals(world)) {
                continue;
            }
            
            VillageMetadataStore.VillageBorder existingBorder = existingVillage.getBorder();
            int distance = proposedBorder.getDistanceTo(existingBorder);
            
            if (distance < minVillageSpacing) {
                logger.fine(String.format("[STRUCT] Rejecting site at %s: distance %d to village %s violates minVillageSpacing=%d",
                    formatLocation(proposedOrigin), distance, existingVillage.getVillageId(), minVillageSpacing));
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * Format location for logging.
     */
    private String formatLocation(Location loc) {
        return String.format("(%d, %d, %d)", loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }
}
