package com.davisodom.villageoverhaul.worldgen;

import com.davisodom.villageoverhaul.VillageOverhaulPlugin;
import com.davisodom.villageoverhaul.villages.VillageMetadataStore;
import com.davisodom.villageoverhaul.villages.VillageService;
import com.davisodom.villageoverhaul.villages.impl.VillagePlacementServiceImpl;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Minimal worldgen adapter that seeds a deterministic test village near spawn.
 *
 * Goals for Phase 2.6 (bootstrap):
 * - Ensure at least one culture-tagged village exists in-world for US1 testing
 * - Deterministic placement near world spawn
 * - Very small footprint (a marker pillar) to avoid griefing worlds during development
 * - Register created village in VillageService
 */
public class VillageWorldgenAdapter implements Listener {

    private final VillageOverhaulPlugin plugin;
    private final Logger logger;
    private final AtomicBoolean seeded = new AtomicBoolean(false);

    public VillageWorldgenAdapter(VillageOverhaulPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    /**
     * Hook world load to seed a test village once.
     * Note: WorldLoadEvent fires before onEnable, so this may not catch the initial world.
     * We also schedule a delayed check.
     */
    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        logger.info("WorldLoadEvent fired for: " + event.getWorld().getName());
        trySeed(event.getWorld());
    }

    /**
     * Can be called from onEnable to eagerly seed when a default world is already present (MockBukkit/CI).
     * For live servers, schedules a delayed check since worlds load before plugin enable.
     */
    public void seedIfPossible() {
        // Immediate attempt (works in tests where world is added before plugin load)
        if (!Bukkit.getWorlds().isEmpty()) {
            scheduleAsyncSeed(Bukkit.getWorlds().get(0));
            return;
        }
        
        // Schedule delayed check for live servers (worlds load before onEnable)
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!Bukkit.getWorlds().isEmpty()) {
                logger.info("Delayed seeding check: found " + Bukkit.getWorlds().size() + " world(s)");
                scheduleAsyncSeed(Bukkit.getWorlds().get(0));
            } else {
                logger.warning("No worlds available for village seeding after delayed check");
            }
        }, 20L); // 1 second delay to ensure worlds are fully loaded
    }
    
    /**
     * Schedule village seeding to run asynchronously so it doesn't block server startup.
     */
    private void scheduleAsyncSeed(World world) {
        if (world == null) {
            logger.warning("scheduleAsyncSeed called with null world");
            return;
        }
        
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                trySeed(world);
            } catch (Exception e) {
                logger.severe("Error during async village seeding: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    private void trySeed(World world) {
        if (world == null) {
            logger.warning("trySeed called with null world");
            return;
        }
        if (!seeded.compareAndSet(false, true)) {
            logger.fine("Village already seeded, skipping");
            return; // only once per server boot
        }

        logger.info("Attempting to seed village in world: " + world.getName());

        // Search for suitable terrain starting from spawn (this is slow, but now async!)
        Location spawn = world.getSpawnLocation();
        Location suitableLocation = findSuitableVillageLocation(world, spawn, 512); // Search up to 512 blocks
        
        if (suitableLocation == null) {
            logger.warning("Could not find suitable terrain for village placement, using spawn location as fallback");
            suitableLocation = spawn.clone().add(16, 0, 16);
        }
        
        int baseX = suitableLocation.getBlockX();
        int baseZ = suitableLocation.getBlockZ();
        int y = world.getHighestBlockYAt(baseX, baseZ);

        logger.info("Village placement selected: " + baseX + ", " + y + ", " + baseZ);

        // Register village in service with culture fallback to first available (roman for now)
        String cultureId = plugin.getCultureService().all().stream().findFirst()
                .map(c -> c.getId()).orElse("roman");
        String name = switch (cultureId) {
            case "roman" -> "Roma I";
            default -> "Village I";
        };

        VillageService vs = plugin.getVillageService();
        var village = vs.createVillage(cultureId, name, world.getName(), baseX, y + 1, baseZ);
        
        // Village creation and structure placement must happen on main thread
        // We're already async from terrain search, so schedule sync for block operations
        final UUID villageId = village.getId();
        final String villageName = village.getName();
        final int finalY = y;
        
        Bukkit.getScheduler().runTask(plugin, () -> {
            // Use VillagePlacementService to generate structures
            VillageMetadataStore metadataStore = new VillageMetadataStore(plugin);
            VillagePlacementServiceImpl placementService = new VillagePlacementServiceImpl(plugin, metadataStore);
            
            // Register village in metadata store
            metadataStore.registerVillage(villageId, cultureId, 
                    new Location(world, baseX, finalY + 1, baseZ), System.currentTimeMillis());
            
            // Generate village structures using placement service
            Location villageOrigin = new Location(world, baseX, finalY, baseZ);
            long seed = world.getSeed() + villageId.getMostSignificantBits();
            
            logger.info("[STRUCT] Generating structures for village '" + villageName + "' (ID: " + villageId + ")");
            Optional<UUID> placedVillageId = placementService.placeVillage(world, villageOrigin, cultureId, seed);
            
            if (placedVillageId.isPresent()) {
                logger.info("✓ Seeded village '" + villageName + "' (" + cultureId + ") with structures at "
                        + world.getName() + " @ (" + baseX + "," + (finalY + 1) + "," + baseZ + ")");
            } else {
                logger.warning("✗ Failed to place structures for village '" + villageName + "', placing marker pillar");
                // Fallback: Create a tiny marker pillar (stone + torch) to indicate village center
                safeSet(world, baseX, finalY, baseZ, Material.STONE);
                safeSet(world, baseX, finalY + 1, baseZ, Material.STONE);
                safeSet(world, baseX, finalY + 2, baseZ, Material.TORCH);
            }
            
            // Generate initial projects for the village
            if (plugin.getProjectGenerator() != null) {
                plugin.getProjectGenerator().generateInitialProjects(village);
            }
            
            // Spawn initial custom villagers for the village
            spawnInitialVillagers(village, world, baseX, finalY + 1, baseZ);
        });
    }
    
    /**
     * Search for suitable flat terrain for village placement.
     * 
     * @param world Target world
     * @param start Starting search location (typically spawn)
     * @param maxRadius Maximum search radius in blocks
     * @return Suitable location or null if none found
     */
    private Location findSuitableVillageLocation(World world, Location start, int maxRadius) {
        logger.info("Searching for suitable village terrain within " + maxRadius + " blocks of spawn...");
        
        int startX = start.getBlockX();
        int startZ = start.getBlockZ();
        int checkRadius = 24; // Check 24 block radius for flatness (reduced from 32)
        int sampleInterval = 24; // Check every 24 blocks in spiral (increased from 16 for speed)
        
        // Spiral search pattern - increased max radius for more opportunities
        for (int radius = 16; radius <= Math.min(maxRadius, 768); radius += sampleInterval) {
            // Check 8 points around the circle at this radius
            for (int i = 0; i < 8; i++) {
                double angle = (i / 8.0) * 2 * Math.PI;
                int x = startX + (int)(radius * Math.cos(angle));
                int z = startZ + (int)(radius * Math.sin(angle));
                
                // Check if this location is suitable
                if (isTerrainSuitable(world, x, z, checkRadius)) {
                    int y = world.getHighestBlockYAt(x, z);
                    logger.info("Found suitable terrain at distance " + radius + " blocks: (" + x + ", " + y + ", " + z + ")");
                    return new Location(world, x, y, z);
                }
            }
        }
        
        return null;
    }
    
    /**
     * Check if terrain at location is suitable for village placement.
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
        
        // Sample terrain in a grid pattern (increased from 8 to 12 for speed)
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
        
        // Criteria for suitable terrain:
        // - Y variation <= 15 blocks (relaxed for more placement opportunities)
        // - Less than 30% water coverage
        // - Not too high or too low (between Y 50 and Y 120)
        boolean flatEnough = yVariation <= 15;
        boolean notTooWatery = waterPercent < 0.3;
        boolean goodHeight = minY >= 50 && maxY <= 120;
        
        // Skip ice check for speed - water check is sufficient
        return flatEnough && notTooWatery && goodHeight;
    }
    
    /**
     * Spawn initial custom villagers for a new village
     * Spawns 2-3 villagers based on culture professions
     */
    private void spawnInitialVillagers(com.davisodom.villageoverhaul.villages.Village village, 
                                       World world, int centerX, int centerY, int centerZ) {
        var npcService = plugin.getCustomVillagerService();
        var appearanceAdapter = plugin.getVillagerAppearanceAdapter();
        
        if (npcService == null || appearanceAdapter == null) {
            logger.warning("NPC services not initialized, skipping villager spawns");
            return;
        }
        
        String cultureId = village.getCultureId();
        
        // Spawn villagers at offset positions around village center
        int[][] spawnOffsets = {
            {3, 0, 2},   // East side
            {-2, 0, 3},  // West side
            {0, 0, -3}   // North side
        };
        
        String[] professions = {"merchant", "blacksmith", "elder"};
        
        for (int i = 0; i < Math.min(spawnOffsets.length, professions.length); i++) {
            int[] offset = spawnOffsets[i];
            String profession = professions[i];
            String definitionId = cultureId + "_" + profession;
            
            Location spawnLoc = new Location(
                world,
                centerX + offset[0] + 0.5,
                centerY + offset[1],
                centerZ + offset[2] + 0.5
            );
            
            var customVillager = npcService.spawnVillager(
                definitionId,
                cultureId,
                profession,
                village.getId(),
                spawnLoc
            );
            
            if (customVillager != null) {
                // Apply appearance
                org.bukkit.entity.Entity entity = plugin.getServer().getEntity(customVillager.getEntityId());
                if (entity != null) {
                    appearanceAdapter.applyAppearance(entity, definitionId);
                }
                
                logger.info("  ✓ Spawned " + definitionId + " at village " + village.getName());
            }
        }
    }

    private void safeSet(World world, int x, int y, int z, Material material) {
        try {
            world.getBlockAt(x, y, z).setType(material, false);
        } catch (Throwable t) {
            // In MockBukkit or when worlds aren't fully ready, ignore block placement failures
        }
    }
}

