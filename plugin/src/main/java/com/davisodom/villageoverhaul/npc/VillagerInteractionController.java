package com.davisodom.villageoverhaul.npc;

import com.davisodom.villageoverhaul.VillageOverhaulPlugin;
import com.davisodom.villageoverhaul.economy.WalletService;
import com.davisodom.villageoverhaul.obs.Metrics;
import com.davisodom.villageoverhaul.projects.Project;
import com.davisodom.villageoverhaul.projects.ProjectService;
import com.davisodom.villageoverhaul.villages.Village;
import com.davisodom.villageoverhaul.villages.VillageService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Controller for Custom Villager interactions
 * 
 * Intercepts PlayerInteractEntityEvent for Custom Villagers, cancels vanilla
 * trading UI, and routes to custom interaction flows (chat/actionbar + 
 * inventory GUI fallback).
 * 
 * Handles:
 * - Vanilla UI suppression
 * - Rate limiting (≤2 opens/sec per player)
 * - Server-side validation
 * - Trade simulation and project contributions (US1 integration)
 * - Graceful chunk unload/despawn handling
 * - Interaction metrics (npc.interactions_per_sec, npc.interaction_denied_rate)
 * 
 * Constitution compliance:
 * - Principle I: Cross-edition compatible (no client mods)
 * - Principle II: Server-authoritative interaction validation
 * - Principle VII: Observability with interaction metrics
 * - Principle IX: Rate limits, input validation
 */
public class VillagerInteractionController implements Listener {
    
    private final VillageOverhaulPlugin plugin;
    private final Logger logger;
    private final CustomVillagerService villagerService;
    private final Metrics metrics;
    private final WalletService walletService;
    private final ProjectService projectService;
    private final VillageService villageService;
    
    // Rate limiting: player UUID -> last interaction timestamp
    private final Map<UUID, Long> playerInteractionTimestamps;
    private static final long RATE_LIMIT_MS = 500; // Max 2/sec
    
    // Trade configuration (will be loaded from config later)
    private static final long BASE_TRADE_VALUE_MILLZ = 100L; // 1 Billz per trade
    private static final double PROJECT_CONTRIBUTION_RATE = 0.20; // 20% of trade value goes to village
    
    public VillagerInteractionController(Logger logger, CustomVillagerService villagerService, Metrics metrics) {
        this(null, logger, villagerService, metrics);
    }
    
    public VillagerInteractionController(VillageOverhaulPlugin plugin, Logger logger, 
                                        CustomVillagerService villagerService, Metrics metrics) {
        this.plugin = plugin;
        this.logger = logger;
        this.villagerService = villagerService;
        this.metrics = metrics;
        this.playerInteractionTimestamps = new ConcurrentHashMap<>();
        
        // Optional services (may be null for unit tests)
        this.walletService = (plugin != null) ? plugin.getWalletService() : null;
        this.projectService = (plugin != null) ? plugin.getProjectService() : null;
        this.villageService = (plugin != null) ? plugin.getVillageService() : null;
    }
    
    /**
     * Intercept player interact entity events for Custom Villagers
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        Entity entity = event.getRightClicked();
        Player player = event.getPlayer();
        
        // Check if this is a Custom Villager
        CustomVillager villager = villagerService.getVillagerByEntityId(entity.getUniqueId());
        if (villager == null) {
            return; // Not a custom villager, allow vanilla behavior
        }
        
        // Cancel vanilla trading UI
        event.setCancelled(true);
        
        // Rate limit check
        long now = System.currentTimeMillis();
        Long lastInteraction = playerInteractionTimestamps.get(player.getUniqueId());
        if (lastInteraction != null && (now - lastInteraction) < RATE_LIMIT_MS) {
            logger.fine("Rate limit: Player " + player.getName() + " interacted too quickly");
            metrics.increment("npc.interaction_denied");
            return;
        }
        playerInteractionTimestamps.put(player.getUniqueId(), now);
        
        // Metrics
        metrics.increment("npc.interactions");
        
        // Update villager interaction timestamp
        villager.setLastInteractionAt(now);
        
        // Route to custom interaction flow
        handleCustomInteraction(player, villager, entity);
    }
    
    /**
     * Handle custom interaction flow
     * 
     * Integrates with US1: simulates trade and contributes to village projects
     * Future: Open inventory GUI with dialogue/trades/contracts
     */
    private void handleCustomInteraction(Player player, CustomVillager villager, Entity entity) {
        // Validation: entity still exists and in same world
        if (!entity.isValid() || entity.isDead()) {
            player.sendMessage(Component.text("This villager has departed.", NamedTextColor.GRAY));
            logger.warning("Interaction with invalid entity: " + entity.getUniqueId());
            return;
        }
        
        // MVP: Send greeting via chat
        String greeting = getGreeting(villager);
        player.sendMessage(Component.text(greeting, NamedTextColor.YELLOW));
        
        logger.fine("Player " + player.getName() + " interacted with custom villager " + 
                   villager.getDefinitionId() + " (entity: " + entity.getUniqueId() + ")");
        
        // US1 Integration: Simulate trade and contribute to village projects
        if (plugin != null && walletService != null && projectService != null && villageService != null) {
            handleTradeContribution(player, villager);
        } else {
            // Fallback for unit tests or incomplete initialization
            player.sendActionBar(Component.text("Trade system not available", NamedTextColor.GRAY));
        }
        
        // TODO: Open inventory GUI with trades/contracts (US1/US2 integration)
        // TODO: Load dialogue keys from localization
        // TODO: Apply reputation requirements from interactionFlags
    }
    
    /**
     * Handle simulated trade and project contribution (US1 integration)
     * 
     * For US1 MVP, we simulate trade completion on interaction.
     * In production, this would be replaced with actual trade validation
     * that checks inventory changes and applies server-side rules.
     */
    private void handleTradeContribution(Player player, CustomVillager villager) {
        UUID playerId = player.getUniqueId();
        UUID villageId = villager.getVillageId();
        
        // Find the village
        Optional<Village> villageOpt = villageService.getVillage(villageId);
        if (villageOpt.isEmpty()) {
            logger.warning("No village found for custom villager: " + villageId);
            player.sendMessage(Component.text("This villager's village is not available.", NamedTextColor.RED));
            return;
        }
        
        Village village = villageOpt.get();
        
        // Calculate trade proceeds
        long tradeValueMillz = BASE_TRADE_VALUE_MILLZ;
        long playerEarnings = (long) (tradeValueMillz * (1.0 - PROJECT_CONTRIBUTION_RATE));
        long projectContribution = tradeValueMillz - playerEarnings;
        
        // Credit player wallet
        boolean credited = walletService.credit(playerId, playerEarnings);
        if (!credited) {
            logger.warning("Failed to credit player wallet: " + playerId);
            player.sendMessage(Component.text("Failed to process trade.", NamedTextColor.RED));
            return;
        }
        
        logger.info(String.format("Trade completed: player=%s earned=%d millz (custom villager)",
                player.getName(), playerEarnings));
        
        // Contribute to village project
        List<Project> activeProjects = projectService.getActiveVillageProjects(village.getId());
        if (activeProjects.isEmpty()) {
            logger.fine("No active projects in village: " + village.getName());
            // Store in village treasury for future projects
            village.addWealth(projectContribution);
            logger.info(String.format("Added %d millz to village treasury (no active projects)",
                    projectContribution));
            player.sendMessage(Component.text("Trade completed! The village prospers.", NamedTextColor.GREEN));
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
        player.sendMessage(Component.text("OK Village project completed: " + project.getBuildingRef(), 
            NamedTextColor.GREEN));
                player.sendMessage(Component.text("The village thanks you for your contributions!", 
                        NamedTextColor.GRAY));
                
                // Trigger upgrade
                logger.info("Project completed, triggering upgrade: " + project.getId());
                plugin.getUpgradeExecutor().executeUpgrade(project, village);
            } else {
                // Show progress
                int percent = project.getCompletionPercent();
                player.sendMessage(Component.text(
                        String.format("Village project: %s (%d%% complete)",
                                project.getBuildingRef(), percent),
                        NamedTextColor.GOLD));
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
     * Get greeting message for a villager
     * 
     * @param villager CustomVillager
     * @return Greeting string
     */
    private String getGreeting(CustomVillager villager) {
        // MVP: Simple greeting based on culture/profession
        String culture = villager.getCultureId();
        String profession = villager.getProfessionId();
        
        // TODO: Load from localization keys (dialogueKeys[])
        return "Greetings from a " + culture + " " + profession + "!";
    }
    
    /**
     * Cleanup rate limit tracking for disconnected players
     * 
     * @param playerId Player UUID
     */
    public void cleanupPlayer(UUID playerId) {
        playerInteractionTimestamps.remove(playerId);
    }
    
    /**
     * Programmatically trigger interaction between player and custom villager (for testing)
     * 
     * This method bypasses rate limiting and is intended for automated CI tests only.
     * 
     * @param player Player who is interacting
     * @param entity Villager entity
     */
    public void handleInteraction(Player player, org.bukkit.entity.Villager entity) {
        // Check if this is a Custom Villager
        CustomVillager villager = villagerService.getVillagerByEntityId(entity.getUniqueId());
        if (villager == null) {
            logger.warning("[TEST] Entity is not a custom villager: " + entity.getUniqueId());
            return;
        }
        
        // Metrics
        metrics.increment("npc.interactions");
        
        // Update villager interaction timestamp
        villager.setLastInteractionAt(System.currentTimeMillis());
        
        // Route to custom interaction flow (bypassing rate limit)
        handleCustomInteraction(player, villager, entity);
        
        logger.info("[TEST] Programmatic interaction: player=" + player.getName() + 
                   " villager=" + villager.getDefinitionId());
    }
}

