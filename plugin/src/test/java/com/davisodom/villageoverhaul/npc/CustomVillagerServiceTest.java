package com.davisodom.villageoverhaul.npc;

import com.davisodom.villageoverhaul.VillageOverhaulPlugin;
import com.davisodom.villageoverhaul.obs.Metrics;
import com.davisodom.villageoverhaul.villages.VillageMetadataStore;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.Plugin;
import org.bukkit.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.File;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

public class CustomVillagerServiceTest {

    private Plugin plugin;
    private VillageMetadataStore store;
    private World world;
    private Entity entity;
    private Server server;

    @BeforeEach
    public void setUp() {
        plugin = Mockito.mock(VillageOverhaulPlugin.class);
        Mockito.when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
        File dataDir = new File("build/test-data");
        dataDir.mkdirs();
        Mockito.when(plugin.getDataFolder()).thenReturn(dataDir);

        store = new VillageMetadataStore(plugin);

        world = Mockito.mock(World.class);
        Mockito.when(world.getName()).thenReturn("world");

        entity = Mockito.mock(Entity.class);
        UUID entityId = UUID.randomUUID();
        Mockito.when(entity.getUniqueId()).thenReturn(entityId);
        Mockito.when(world.spawnEntity(Mockito.any(Location.class), Mockito.eq(EntityType.VILLAGER)))
            .thenReturn(entity);

        server = Mockito.mock(Server.class);
        Mockito.when(server.getWorld("world")).thenReturn(world);
        Mockito.when(plugin.getServer()).thenReturn(server);
    }

    @AfterEach
    public void tearDown() {
        try {
            java.nio.file.Path dir = java.nio.file.Paths.get(plugin.getDataFolder().getAbsolutePath(), "villages");
            if (java.nio.file.Files.exists(dir)) {
                java.nio.file.Files.walk(dir)
                    .sorted(java.util.Comparator.reverseOrder())
                    .map(java.nio.file.Path::toFile)
                    .forEach(java.io.File::delete);
            }
        } catch (Exception ignored) {}
    }

    @Test
    public void testSpawnVillagerPersistsRecord() {
        Metrics metrics = new Metrics(plugin.getLogger());
        CustomVillagerService service = new CustomVillagerService(plugin, plugin.getLogger(), metrics, store);

        UUID villageId = UUID.randomUUID();
        Location location = new Location(world, 10, 64, 10);
        CustomVillager villager = service.spawnVillager("roman_merchant", "roman", "merchant", villageId, location);

        assertNotNull(villager, "Villager should spawn");

        List<VillageMetadataStore.VillagerRecord> records = store.getVillagerRecords(villageId);
        assertEquals(1, records.size(), "Should persist one villager record");
        VillageMetadataStore.VillagerRecord record = records.get(0);
        assertEquals("roman_merchant", record.definitionId);
        assertEquals("roman", record.cultureId);
        assertEquals("merchant", record.professionId);
        assertEquals("world", record.worldName);
        assertEquals(10, record.x);
        assertEquals(64, record.y);
        assertEquals(10, record.z);
    }

    @Test
    public void testRestorePersistedVillagers() {
        Metrics metrics = new Metrics(plugin.getLogger());
        CustomVillagerService service = new CustomVillagerService(plugin, plugin.getLogger(), metrics, store);

        UUID villageId = UUID.randomUUID();
        UUID oldEntityId = UUID.randomUUID();
        VillageMetadataStore.VillagerRecord record = new VillageMetadataStore.VillagerRecord(
            oldEntityId.toString(),
            villageId,
            "roman_blacksmith",
            "roman",
            "blacksmith",
            "world",
            20,
            65,
            22,
            System.currentTimeMillis()
        );
        store.addVillagerRecord(record);

        UUID newEntityId = UUID.randomUUID();
        Mockito.when(entity.getUniqueId()).thenReturn(newEntityId);

        int restored = service.restorePersistedVillagers();
        assertEquals(1, restored, "Should restore one villager");
        assertEquals(1, service.getActiveVillagerCount(), "Active count should reflect restored villager");

        List<VillageMetadataStore.VillagerRecord> records = store.getVillagerRecords(villageId);
        assertEquals(1, records.size(), "Should update record list to one entry");
        assertEquals(newEntityId.toString(), records.get(0).entityId, "Record should update entity ID");
    }
}
