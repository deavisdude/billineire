package com.davisodom.villageoverhaul.commands;

import com.davisodom.villageoverhaul.VillageOverhaulPlugin;
import com.davisodom.villageoverhaul.villages.Village;
import com.davisodom.villageoverhaul.villages.VillageMetadataStore;
import com.davisodom.villageoverhaul.villages.VillagePlacementService;
import com.davisodom.villageoverhaul.villages.impl.VillagePlacementServiceImpl;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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
    private final TickBudgetedGenerationQueue generationQueue;
    private final VillageTerrainSearcher terrainSearcher;
    
    public GenerateCommand(VillageOverhaulPlugin plugin, TickBudgetedGenerationQueue generationQueue) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.generationQueue = generationQueue;
        // T071: Use shared terrain searcher
        this.terrainSearcher = new VillageTerrainSearcher(plugin, plugin.getMetadataStore());
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
            sender.sendMessage(Component.text("Usage: /vo generate <culture> <name> [seed] [--allow-marker]", NamedTextColor.RED));
            sender.sendMessage(Component.text("Example: /vo generate roman 'New Rome' 12345 --allow-marker", NamedTextColor.GRAY));
            return true;
        }
        
        String cultureId = args[0];
        String villageName = args[1];
        
        // Optional seed argument + --allow-marker flag
        Long parsedSeed = null;
        boolean allowMarkerFlag = false;
        for (int i = 2; i < args.length; i++) {
            String extra = args[i];
            if ("--allow-marker".equalsIgnoreCase(extra)) {
                allowMarkerFlag = true;
                continue;
            }
            if (parsedSeed == null) {
                try {
                    parsedSeed = Long.parseLong(extra);
                } catch (NumberFormatException e) {
                    sender.sendMessage(Component.text("Invalid seed: " + extra, NamedTextColor.RED));
                    return true;
                }
            } else {
                sender.sendMessage(Component.text("Unexpected argument: " + extra, NamedTextColor.RED));
                return true;
            }
        }
        final Long seedArg = parsedSeed;

        // Immediate feedback to user
        sender.sendMessage(Component.text("OK Village generation command received", NamedTextColor.GREEN));
        sender.sendMessage(Component.text("  Culture: ", NamedTextColor.GRAY).append(Component.text(cultureId, NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("  Name: ", NamedTextColor.GRAY).append(Component.text(villageName, NamedTextColor.WHITE)));
        if (seedArg != null) {
            sender.sendMessage(Component.text("  Seed: ", NamedTextColor.GRAY).append(Component.text(String.valueOf(seedArg), NamedTextColor.WHITE)));
        }

        final boolean allowMarkerFallback = allowMarkerFlag || plugin.isMarkerFallbackAllowed();
        if (allowMarkerFallback) {
            sender.sendMessage(Component.text("Marker fallback is enabled for this run.", NamedTextColor.GRAY));
        } else {
            sender.sendMessage(Component.text("Marker fallback suppressed. Enable worldgen.allowMarkerFallback or add --allow-marker to place a pillar on failure.", NamedTextColor.GRAY));
        }
        
        // Validate culture exists
        if (!plugin.getCultureService().all().stream().anyMatch(c -> c.getId().equals(cultureId))) {
            sender.sendMessage(Component.text("Unknown culture: " + cultureId, NamedTextColor.RED));
            sender.sendMessage(Component.text("Available cultures: " + 
                String.join(", ", plugin.getCultureService().all().stream()
                    .map(c -> c.getId()).toList()), NamedTextColor.GRAY));
            return true;
        }
        
        // Determine search location
        Location searchOrigin;
        World world;
        
        if (sender instanceof Player player) {
            searchOrigin = player.getLocation();
            world = player.getWorld();
            sender.sendMessage(Component.text("Searching for suitable terrain near your location...", NamedTextColor.GRAY));
        } else {
            // Console command - use world spawn
            world = Bukkit.getWorlds().get(0);
            searchOrigin = world.getSpawnLocation();
            sender.sendMessage(Component.text("Searching for suitable terrain near world spawn...", NamedTextColor.GRAY));
        }
        
        // Use shared metadata store (T012l: singleton for cross-session enforcement)
        VillageMetadataStore metadataStore = plugin.getMetadataStore();
        boolean isFirstVillage = terrainSearcher.isFirstVillage(world);
        int spawnProximityRadius = plugin.getSpawnProximityRadius();
        
        // Adjust search strategy based on whether this is first village
        if (isFirstVillage && spawnProximityRadius > 0) {
            // First village: search near spawn (Constitution v1.5.0, Principle XII)
            searchOrigin = world.getSpawnLocation();
            sender.sendMessage(Component.text("First village: searching within " + spawnProximityRadius + " blocks of spawn...", NamedTextColor.GRAY));
        } else if (!isFirstVillage) {
            // Subsequent villages: find nearest existing village and search near it
            Location nearestVillage = terrainSearcher.findNearestVillageLocation(world, searchOrigin);
            if (nearestVillage != null) {
                searchOrigin = nearestVillage;
                sender.sendMessage(Component.text("Subsequent village: searching near existing village at " + 
                    formatLocation(nearestVillage) + "...", NamedTextColor.GRAY));
            }
        }
        
        // T066: Enqueue generation request instead of executing synchronously
        CommandGenerationRequest request = new CommandGenerationRequest(
                sender, cultureId, villageName, seedArg, searchOrigin);
        
        generationQueue.enqueue(request);
        
        logger.info(String.format("[STRUCT] User-triggered village generation: '%s' (culture=%s, queued)", 
                villageName, cultureId));
        
        return true;
    }
    
    /**
     * Format location for logging.
     */
    private String formatLocation(Location loc) {
        return String.format("(%d, %d, %d)", loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }
}
