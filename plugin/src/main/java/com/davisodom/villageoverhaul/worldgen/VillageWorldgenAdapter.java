package com.davisodom.villageoverhaul.worldgen;

import com.davisodom.villageoverhaul.VillageOverhaulPlugin;
import com.davisodom.villageoverhaul.villages.VillageService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;

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
            trySeed(Bukkit.getWorlds().get(0));
            return;
        }
        
        // Schedule delayed check for live servers (worlds load before onEnable)
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!Bukkit.getWorlds().isEmpty()) {
                logger.info("Delayed seeding check: found " + Bukkit.getWorlds().size() + " world(s)");
                trySeed(Bukkit.getWorlds().get(0));
            } else {
                logger.warning("No worlds available for village seeding after delayed check");
            }
        }, 20L); // 1 second delay to ensure worlds are fully loaded
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

        // Determine placement: spawn location with a small offset for safety
        Location spawn = world.getSpawnLocation();
        int baseX = spawn.getBlockX() + 16; // one chunk east
        int baseZ = spawn.getBlockZ() + 16; // one chunk south
        int y = world.getHighestBlockYAt(baseX, baseZ);

        logger.info("Village placement calculated: " + baseX + ", " + y + ", " + baseZ);

        // Create a tiny marker pillar (stone + torch) to indicate village center
        safeSet(world, baseX, y, baseZ, Material.STONE);
        safeSet(world, baseX, y + 1, baseZ, Material.STONE);
        safeSet(world, baseX, y + 2, baseZ, Material.TORCH);

        // Register village in service with culture fallback to first available (roman for now)
    String cultureId = plugin.getCultureService().all().stream().findFirst()
        .map(c -> c.getId()).orElse("roman");
        String name = switch (cultureId) {
            case "roman" -> "Roma I";
            default -> "Village I";
        };

        VillageService vs = plugin.getVillageService();
        var village = vs.createVillage(cultureId, name, world.getName(), baseX, y + 1, baseZ);
        logger.info("✓ Seeded test village '" + village.getName() + "' (" + cultureId + ") at "
                + world.getName() + " @ (" + baseX + "," + (y + 1) + "," + baseZ + ")");
        
        // Generate initial projects for the village
        if (plugin.getProjectGenerator() != null) {
            plugin.getProjectGenerator().generateInitialProjects(village);
        }
        
        // Spawn initial custom villagers for the village
        spawnInitialVillagers(village, world, baseX, y + 1, baseZ);
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

