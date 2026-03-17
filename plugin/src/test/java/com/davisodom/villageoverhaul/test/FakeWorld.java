package com.davisodom.villageoverhaul.test;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.mockito.Mockito;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight Mockito-backed fake World for unit tests.
 * Provides getBlockAt and getHighestBlockYAt semantics with mutable block mocks.
 */
public final class FakeWorld {

    private final World world;
    private final Map<String, MutableBlock> blocks = new ConcurrentHashMap<>();

    public FakeWorld() {
        this.world = Mockito.mock(World.class);

        Mockito.when(world.getBlockAt(Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt()))
                .thenAnswer(inv -> {
                    int x = inv.getArgument(0);
                    int y = inv.getArgument(1);
                    int z = inv.getArgument(2);
                    return toMock(ensureBlock(x, y, z));
                });

        Mockito.when(world.getBlockAt(Mockito.any(Location.class)))
                .thenAnswer(inv -> {
                    Location loc = inv.getArgument(0);
                    return world.getBlockAt(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
                });

        Mockito.when(world.getHighestBlockYAt(Mockito.anyInt(), Mockito.anyInt()))
                .thenAnswer(inv -> {
                    int x = inv.getArgument(0);
                    int z = inv.getArgument(1);
                    return blocks.values().stream()
                            .filter(mb -> mb.x == x && mb.z == z && mb.type != Material.AIR)
                            .mapToInt(mb -> mb.y)
                            .max().orElse(0);
                });
    }

    public World getWorld() { return world; }

    public void setBlockType(int x, int y, int z, Material type) {
        MutableBlock mb = ensureBlock(x, y, z);
        mb.type = type;
        if (mb.mock != null) {
            Mockito.when(mb.mock.getType()).thenReturn(type);
        }
    }

    public Block getBlock(int x, int y, int z) { return toMock(ensureBlock(x,y,z)); }

    private MutableBlock ensureBlock(int x, int y, int z) {
        String key = String.format("%d:%d:%d", x, y, z);
        return blocks.computeIfAbsent(key, k -> new MutableBlock(x, y, z));
    }

    private Block toMock(MutableBlock mb) {
        block:
        if (mb.mock == null) {
            Block mock = Mockito.mock(Block.class);
            mb.mock = mock;

            Mockito.when(mock.getX()).thenReturn(mb.x);
            Mockito.when(mock.getY()).thenReturn(mb.y);
            Mockito.when(mock.getZ()).thenReturn(mb.z);
            Mockito.when(mock.getLocation()).thenReturn(new Location(null, mb.x, mb.y, mb.z));

            Mockito.when(mock.getType()).thenAnswer(inv -> mb.type);
            Mockito.doAnswer(inv -> { mb.type = (Material) inv.getArgument(0); return null; })
                    .when(mock).setType(Mockito.any(Material.class));

            Mockito.doAnswer(inv -> { mb.blockData = inv.getArgument(0); return null; })
                    .when(mock).setBlockData(Mockito.any());

            Mockito.when(mock.getBlockData()).thenAnswer(inv -> mb.blockData);
        }
        return mb.mock;
    }

    private static final class MutableBlock {
        final int x, y, z;
        volatile Material type = Material.AIR;
        volatile Object blockData = null;
        Block mock;

        MutableBlock(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
    }
}
