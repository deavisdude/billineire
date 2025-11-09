package com.davisodom.villageoverhaul;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import com.davisodom.villageoverhaul.core.TickEngine;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MockBukkit test harness for deterministic tick simulation
 * 
 * Tests that:
 * - Plugin loads correctly
 * - Tick engine runs deterministically
 * - Systems maintain state across ticks
 * 
 * Constitution compliance:
 * - Principle II: Deterministic Multiplayer Sync
 * - Principle VIII: Observability, Testing, and QA Discipline
 */
class TickHarnessTest {
    
    private ServerMock server;
    private VillageOverhaulPlugin plugin;
    
    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(VillageOverhaulPlugin.class);
    }
    
    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }
    
    @Test
    @DisplayName("Plugin should load successfully")
    void testPluginLoads() {
        assertNotNull(plugin);
        assertTrue(plugin.isEnabled());
        assertEquals("VillageOverhaul", plugin.getName());
    }
    
    @Test
    @DisplayName("Plugin instance should be accessible")
    void testPluginInstance() {
        assertNotNull(VillageOverhaulPlugin.getInstance());
        assertEquals(plugin, VillageOverhaulPlugin.getInstance());
    }
    
    @Test
    @DisplayName("Tick engine should initialize")
    void testTickEngineInit() {
        TickEngine engine = new TickEngine(plugin);
        assertNotNull(engine);
        assertEquals(0, engine.getCurrentTick(), "Tick counter should start at 0");
    }
    
    @Test
    @DisplayName("Deterministic tick simulation")
    void testDeterministicTicks() {
        TickEngine engine = new TickEngine(plugin);
        
        // Register a test system that records tick values
        final long[] lastTickReceived = {0};
        engine.registerSystem("test", tick -> {
            lastTickReceived[0] = tick;
        });
        
        // Manually trigger 10 ticks
        for (int i = 1; i <= 10; i++) {
            engine.tick();
            assertEquals(i, engine.getCurrentTick(), "Tick counter should increment by 1 each call");
            assertEquals(i, lastTickReceived[0], "System should receive current tick number");
        }
        
        // Verify final state
        assertEquals(10, engine.getCurrentTick(), "Should have ticked 10 times");
        assertEquals(10, lastTickReceived[0], "System should have received tick 10");
    }
    
    @Test
    @DisplayName("Multiple systems tick in order")
    void testMultipleSystemsTickInOrder() {
        TickEngine engine = new TickEngine(plugin);
        
        StringBuilder order = new StringBuilder();
        
        engine.registerSystem("first", tick -> order.append("1"));
        engine.registerSystem("second", tick -> order.append("2"));
        engine.registerSystem("third", tick -> order.append("3"));
        
        // Tick once
        engine.tick();
        assertEquals("123", order.toString(), "Systems should tick in registration order");
        
        // Reset and tick again to verify order is consistent
        order.setLength(0);
        engine.tick();
        assertEquals("123", order.toString(), "System order should be deterministic across ticks");
        
        // Verify tick count
        assertEquals(2, engine.getCurrentTick(), "Should have ticked twice");
    }
    
    @Test
    @DisplayName("MockBukkit scheduled tick integration")
    void testMockBukkitSchedulerIntegration() {
        TickEngine engine = plugin.getTickEngine();
        assertNotNull(engine, "Plugin should have tick engine initialized");
        
        // Record initial tick count
        long initialTick = engine.getCurrentTick();
        
        // Register a test system
        final int[] tickCount = {0};
        engine.registerSystem("mockbukkit-test", tick -> tickCount[0]++);
        
        // Advance server ticks using MockBukkit scheduler
        server.getScheduler().performTicks(5);
        
        // Verify ticks advanced
        assertTrue(engine.getCurrentTick() > initialTick, 
            "Tick count should increase after performTicks");
        assertTrue(tickCount[0] > 0, 
            "Registered system should have been ticked by scheduler");
    }
}

