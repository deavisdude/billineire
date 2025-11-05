package com.example.villageoverhaul.core;

import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Deterministic tick engine for Village Overhaul
 * 
 * Manages tick-aligned updates for all subsystems with budgeting and metrics.
 * All game logic updates are routed through this engine to ensure:
 * - Deterministic execution order
 * - Performance budgeting (p95/p99 targets from constitution)
 * - Tick time metrics per subsystem
 * 
 * Constitution compliance: Principle II (Deterministic Multiplayer Sync)
 */
public class TickEngine {
    
    private final Plugin plugin;
    private final Logger logger;
    private final Map<String, TickableSystem> systems;
    private final Map<String, Long> tickTimeMicros;
    private BukkitTask tickTask;
    private long currentTick = 0;
    
    // Performance budgets (microseconds)
    private static final long BUDGET_WARNING_MICROS = 8000; // 8ms p95 target
    private static final long BUDGET_CRITICAL_MICROS = 12000; // 12ms p99 target
    
    public TickEngine(Plugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.systems = new LinkedHashMap<>(); // Preserve registration order for determinism
        this.tickTimeMicros = new ConcurrentHashMap<>();
    }
    
    /**
     * Register a tickable system
     * Systems are ticked in registration order for determinism
     * 
     * @param name Unique system identifier
     * @param system Tickable system implementation
     */
    public void registerSystem(String name, TickableSystem system) {
        if (systems.containsKey(name)) {
            throw new IllegalArgumentException("System already registered: " + name);
        }
        systems.put(name, system);
        tickTimeMicros.put(name, 0L);
        logger.info("Registered tickable system: " + name);
    }
    
    /**
     * Start the tick engine
     * Runs at 20 TPS (every tick = 50ms)
     */
    public void start() {
        if (tickTask != null && !tickTask.isCancelled()) {
            logger.warning("Tick engine already running");
            return;
        }
        
        tickTask = new BukkitRunnable() {
            @Override
            public void run() {
                tick();
            }
        }.runTaskTimer(plugin, 0L, 1L); // Every server tick
        
        logger.info("Tick engine started");
    }
    
    /**
     * Stop the tick engine
     */
    public void stop() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        logger.info("Tick engine stopped");
    }
    
    /**
     * Execute one tick across all registered systems
     * Measures time per system and logs budget violations
     */
    private void tick() {
        currentTick++;
        long tickStart = System.nanoTime();
        long totalBudgetUsed = 0;
        
        // Tick all systems in deterministic order
        for (Map.Entry<String, TickableSystem> entry : systems.entrySet()) {
            String systemName = entry.getKey();
            TickableSystem system = entry.getValue();
            
            long systemStart = System.nanoTime();
            try {
                system.tick(currentTick);
            } catch (Exception e) {
                logger.severe("Error ticking system " + systemName + ": " + e.getMessage());
                e.printStackTrace();
            }
            long systemEnd = System.nanoTime();
            
            long systemMicros = (systemEnd - systemStart) / 1000;
            tickTimeMicros.put(systemName, systemMicros);
            totalBudgetUsed += systemMicros;
        }
        
        long tickEnd = System.nanoTime();
        long totalMicros = (tickEnd - tickStart) / 1000;
        
        // Budget violation warnings
        if (totalMicros > BUDGET_CRITICAL_MICROS) {
            logger.warning(String.format(
                "CRITICAL: Tick %d took %.2fms (budget: %.2fms p99). Systems: %s",
                currentTick,
                totalMicros / 1000.0,
                BUDGET_CRITICAL_MICROS / 1000.0,
                formatSystemTimes()
            ));
        } else if (totalMicros > BUDGET_WARNING_MICROS) {
            logger.fine(String.format(
                "WARNING: Tick %d took %.2fms (budget: %.2fms p95). Systems: %s",
                currentTick,
                totalMicros / 1000.0,
                BUDGET_WARNING_MICROS / 1000.0,
                formatSystemTimes()
            ));
        }
    }
    
    /**
     * Format system tick times for logging
     */
    private String formatSystemTimes() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Long> entry : tickTimeMicros.entrySet()) {
            sb.append(String.format("%s=%.2fms ", entry.getKey(), entry.getValue() / 1000.0));
        }
        return sb.toString().trim();
    }
    
    /**
     * Get current tick number
     */
    public long getCurrentTick() {
        return currentTick;
    }
    
    /**
     * Get tick time metrics
     */
    public Map<String, Long> getTickTimeMicros() {
        return new HashMap<>(tickTimeMicros);
    }
    
    /**
     * Interface for systems that receive tick updates
     */
    public interface TickableSystem {
        /**
         * Called once per server tick
         * MUST be deterministic and idempotent
         * 
         * @param tick Current tick number
         */
        void tick(long tick);
    }
}
