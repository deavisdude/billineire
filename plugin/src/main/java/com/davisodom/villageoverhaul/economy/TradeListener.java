package com.davisodom.villageoverhaul.economy;

import com.davisodom.villageoverhaul.VillageOverhaulPlugin;
import com.davisodom.villageoverhaul.projects.Project;
import com.davisodom.villageoverhaul.projects.ProjectService;
import com.davisodom.villageoverhaul.villages.Village;
import com.davisodom.villageoverhaul.villages.VillageService;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.MerchantRecipe;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Listens for villager trades and routes proceeds to village projects.
 * 
 * Flow:
 * 1. Player completes a trade with a villager
 * 2. Trade value is calculated (for now, fixed amount per trade)
 * 3. Proceeds are credited to the player's wallet
 * 4. A portion is automatically contributed to the village's active project
 * 
 * This implements the core US1 mechanic: "Player trades directly contribute to
 * village building and expansion goals."
 */
public class TradeListener implements Listener {
    
    private final VillageOverhaulPlugin plugin;
    private final Logger logger;
    private final WalletService walletService;
    private final ProjectService projectService;
    private final VillageService villageService;
    
    // Configuration (will be loaded from config later)
    private static final long BASE_TRADE_VALUE_MILLZ = 100L; // 1 Billz per trade
    private static final double PROJECT_CONTRIBUTION_RATE = 0.20; // 20% of trade value goes to village
    
    public TradeListener(VillageOverhaulPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.walletService = plugin.getWalletService();
        this.projectService = plugin.getProjectService();
        this.villageService = plugin.getVillageService();
    }
    
    /**
     * Handle villager trade interactions
     * 
     * Note: Paper/Spigot doesn't have a dedicated "trade completed" event,
     * so we hook into the interaction and infer trades. This is a minimal
     * implementation for US1 testing.
     * 
     * Production version would:
     * - Track actual items exchanged
     * - Calculate value based on market rates
     * - Handle trade validation server-side
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVillagerInteract(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Villager)) {
            return;
        }
        
        Player player = event.getPlayer();
        Villager villager = (Villager) event.getRightClicked();
        
        // For US1 MVP, we'll simulate trade completion on interaction
        // This is a placeholder until we implement proper trade validation
        handleSimulatedTrade(player, villager);
    }
    
    /**
     * Simulate a trade for testing US1 mechanics
     * 
     * In production, this would be replaced with actual trade validation
     * that checks inventory changes and applies server-side rules.
     */
    private void handleSimulatedTrade(Player player, Villager villager) {
        UUID playerId = player.getUniqueId();
        
        // Find the nearest village to this villager
        Optional<Village> nearestVillage = findNearestVillage(villager);
        if (nearestVillage.isEmpty()) {
            logger.fine("No village found near villager at " + villager.getLocation());
            return;
        }
        
        Village village = nearestVillage.get();
        
        // Calculate trade proceeds
        long tradeValueMillz = BASE_TRADE_VALUE_MILLZ;
        long playerEarnings = (long) (tradeValueMillz * (1.0 - PROJECT_CONTRIBUTION_RATE));
        long projectContribution = tradeValueMillz - playerEarnings;
        
        // Credit player wallet
        boolean credited = walletService.credit(playerId, playerEarnings);
        if (!credited) {
            logger.warning("Failed to credit player wallet: " + playerId);
            return;
        }
        
        logger.info(String.format("Trade completed: player=%s earned=%d millz",
                player.getName(), playerEarnings));
        
        // Contribute to village project
        List<Project> activeProjects = projectService.getActiveVillageProjects(village.getId());
        if (activeProjects.isEmpty()) {
            logger.fine("No active projects in village: " + village.getName());
            // Store in village treasury for future projects
            village.addWealth(projectContribution);
            logger.info(String.format("Added %d millz to village treasury (no active projects)",
                    projectContribution));
            return;
        }
        
        // Contribute to the first active project
        Project project = activeProjects.get(0);
        Optional<Project.ContributionResult> result = projectService.contribute(
                project.getId(), playerId, projectContribution);
        
        if (result.isPresent()) {
            Project.ContributionResult cr = result.get();
            
                if (cr.isCompleted()) {
                // Project just completed!
                player.sendMessage("§a§lOK Village project completed: " + project.getBuildingRef());
                player.sendMessage("§7The village thanks you for your contributions!");
                
                // Trigger upgrade
                logger.info("Project completed, triggering upgrade: " + project.getId());
                plugin.getUpgradeExecutor().executeUpgrade(project, village);
            } else {
                // Show progress
                int percent = project.getCompletionPercent();
                player.sendMessage(String.format("§6Village project: %s §7(%d%% complete)",
                        project.getBuildingRef(), percent));
            }
            
            // Handle overflow (contributed more than needed)
            if (cr.getOverflow() > 0) {
                village.addWealth(cr.getOverflow());
                logger.fine(String.format("Overflow %d millz added to village treasury",
                        cr.getOverflow()));
            }
        }
    }
    
    /**
     * Find the nearest village to a villager NPC
     * 
     * Simple distance check for US1 MVP. Production version would use
     * proper village boundaries and spatial indexing.
     */
    private Optional<Village> findNearestVillage(Villager villager) {
        String worldName = villager.getWorld().getName();
        int vx = villager.getLocation().getBlockX();
        int vz = villager.getLocation().getBlockZ();
        
        return villageService.getAllVillages().stream()
                .filter(v -> v.getWorldName().equals(worldName))
                .min((v1, v2) -> {
                    int d1 = distanceSquared(vx, vz, v1.getX(), v1.getZ());
                    int d2 = distanceSquared(vx, vz, v2.getX(), v2.getZ());
                    return Integer.compare(d1, d2);
                });
    }
    
    private int distanceSquared(int x1, int z1, int x2, int z2) {
        int dx = x2 - x1;
        int dz = z2 - z1;
        return dx * dx + dz * dz;
    }
}

