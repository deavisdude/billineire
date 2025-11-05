package com.example.villageoverhaul.npc;

import com.example.villageoverhaul.obs.Metrics;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;

import java.util.Map;
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
    
    private final Logger logger;
    private final CustomVillagerService villagerService;
    private final Metrics metrics;
    
    // Rate limiting: player UUID -> last interaction timestamp
    private final Map<UUID, Long> playerInteractionTimestamps;
    private static final long RATE_LIMIT_MS = 500; // Max 2/sec
    
    public VillagerInteractionController(Logger logger, CustomVillagerService villagerService, Metrics metrics) {
        this.logger = logger;
        this.villagerService = villagerService;
        this.metrics = metrics;
        this.playerInteractionTimestamps = new ConcurrentHashMap<>();
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
     * MVP: Send chat/actionbar message with villager greeting
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
        
        // MVP: Send actionbar hint
        player.sendActionBar(Component.text("Right-click again to trade (future)", NamedTextColor.GRAY));
        
        logger.fine("Player " + player.getName() + " interacted with custom villager " + 
                   villager.getDefinitionId() + " (entity: " + entity.getUniqueId() + ")");
        
        // TODO: Open inventory GUI with trades/contracts (US1/US2 integration)
        // TODO: Load dialogue keys from localization
        // TODO: Apply reputation requirements from interactionFlags
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
}
