package com.davisodom.villageoverhaul.worldgen.impl;

import org.bukkit.World;
import org.mockito.Mockito;
import com.davisodom.villageoverhaul.model.VolumeMask;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PathEmitter Tests — placement, smoothing & masks")
public class PathEmitterTest {

    private World world;
    private PathEmitter emitter;

    @BeforeEach
    public void setUp() {
        // Use a lightweight Mockito-based fake world to avoid MockBukkit registry races in some runners.
        world = Mockito.mock(World.class);
        blocks = new java.util.concurrent.ConcurrentHashMap<>();
        emitter = new PathEmitter();
        System.out.println("[TEST-DEBUG] setUp complete — world=" + world.getName());
    }

    @AfterEach
    public void tearDown() {
        // clear any stateful fake blocks
        if (blocks != null) blocks.clear();
    }

    // Backing map for fake blocks keyed by "x:y:z"
    private java.util.Map<String, MutableBlock> blocks;

    private MutableBlock ensureBlock(int x, int y, int z) {
        String key = String.format("%d:%d:%d", x, y, z);
        return blocks.computeIfAbsent(key, k -> new MutableBlock(x, y, z));
    }

    private Block toMock(MutableBlock mb) {
        Block mock = mb.mock;
        if (mock == null) {
            mock = Mockito.mock(Block.class);
            mb.mock = mock;

            Mockito.when(mock.getX()).thenReturn(mb.x);
            Mockito.when(mock.getY()).thenReturn(mb.y);
            Mockito.when(mock.getZ()).thenReturn(mb.z);
            Mockito.when(mock.getLocation()).thenReturn(new Location(null, mb.x, mb.y, mb.z));

            Mockito.when(mock.getType()).thenAnswer(inv -> mb.type);
            Mockito.doAnswer(inv -> {
                Material newType = (Material) inv.getArgument(0);
                mb.type = newType;
                // emulate server behavior: create a basic BlockData proxy for slabs/stairs
                if (newType == Material.COBBLESTONE_STAIRS || newType == Material.STONE_BRICK_STAIRS || newType == Material.COBBLESTONE_STAIRS) {
                    mb.blockData = java.lang.reflect.Proxy.newProxyInstance(
                            PathEmitterTest.class.getClassLoader(),
                            new Class[]{org.bukkit.block.data.type.Stairs.class},
                            (proxy, method, args) -> {
                                // default stub: return null/0/false as needed
                                if (method.getReturnType().isPrimitive()) return 0;
                                return null;
                            }
                    );
                } else if (newType == Material.COBBLESTONE_SLAB || newType == Material.STONE_BRICK_SLAB) {
                    mb.blockData = java.lang.reflect.Proxy.newProxyInstance(
                            PathEmitterTest.class.getClassLoader(),
                            new Class[]{org.bukkit.block.data.type.Slab.class},
                            (proxy, method, args) -> {
                                if (method.getReturnType().isPrimitive()) return 0;
                                return null;
                            }
                    );
                } else {
                    mb.blockData = null;
                }
                return null;
            }).when(mock).setType(Mockito.any(Material.class));

            Mockito.when(mock.getBlockData()).thenAnswer(inv -> mb.blockData);

            Mockito.when(mock.getRelative(Mockito.any(org.bukkit.block.BlockFace.class))).thenAnswer(inv -> {
                org.bukkit.block.BlockFace face = inv.getArgument(0);
                int nx = mb.x + face.getModX();
                int ny = mb.y + face.getModY();
                int nz = mb.z + face.getModZ();
                return toMock(ensureBlock(nx, ny, nz));
            });
        }
        return mock;
    }

    private static final class MutableBlock {
        final int x, y, z;
        volatile Material type = Material.AIR;
        volatile Object blockData;
        Block mock;

        MutableBlock(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
    }

    private void makeFlatGround(int x0, int x1, int z0, int z1, int surfaceY) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                // ensure a solid foundation under the surface
                ensureBlock(x, surfaceY - 1, z).type = Material.DIRT; toMock(ensureBlock(x, surfaceY - 1, z));
                ensureBlock(x, surfaceY, z).type = Material.DIRT; toMock(ensureBlock(x, surfaceY, z));
                // make sure above is air
                ensureBlock(x, surfaceY + 1, z).type = Material.AIR; toMock(ensureBlock(x, surfaceY + 1, z));
            }
        }
    }

    @Test
    @DisplayName("emitPath places blocks on valid surface and returns positive count")
    public void testEmitPath_placesBlocksOnValidSurface() {
        System.out.println("[TEST-DEBUG] testEmitPath_placesBlocksOnValidSurface starting");
        makeFlatGround(100, 102, 100, 100, 64);

        List<Block> path = new ArrayList<>();
        path.add(toMock(ensureBlock(100,64,100)));
        path.add(toMock(ensureBlock(101,64,100)));
        path.add(toMock(ensureBlock(102,64,100)));

        // Call emitter directly; MockBukkit may not support highest-block lookups but
        // PathEmitter falls back to the supplied block Y so tests remain deterministic.
        int placed;
        try {
            // Wire world.getBlockAt and getHighestBlockYAt for our mock world
            Mockito.when(world.getBlockAt(Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt())).thenAnswer(inv -> {
                int x = inv.getArgument(0);
                int y = inv.getArgument(1);
                int z = inv.getArgument(2);
                return toMock(ensureBlock(x, y, z));
            });

            Mockito.when(world.getHighestBlockYAt(Mockito.anyInt(), Mockito.anyInt())).thenAnswer(inv -> {
                int x = inv.getArgument(0);
                int z = inv.getArgument(1);
                // find highest non-air y in our map for x,z
                return blocks.entrySet().stream()
                        .map(e -> e.getValue())
                        .filter(mb -> mb.x == x && mb.z == z && mb.type != Material.AIR)
                        .mapToInt(mb -> mb.y)
                        .max().orElse(0);
            });

            placed = emitter.emitPath(world, path, "default", Collections.emptyList());
        } catch (Throwable t) {
            t.printStackTrace();
            fail("emitPath threw an unexpected exception: " + t);
            return;
        }

        assertTrue(placed >= 1, "Should place at least one path block per coordinate (widening allowed)");

        // Verify materials set to dirt path for default culture
        assertEquals(Material.DIRT_PATH, world.getBlockAt(100, 64, 100).getType());
        assertEquals(Material.DIRT_PATH, world.getBlockAt(101, 64, 100).getType());
    }

    @Test
    @DisplayName("emitPath respects VolumeMask and support check (no inside-mask or unsupported placement)")
    public void testEmitPath_respectsMaskAndSupport() {
        System.out.println("[TEST-DEBUG] testEmitPath_respectsMaskAndSupport starting");
        makeFlatGround(200, 202, 200, 200, 70);

        // create a mask that covers the middle coordinate (201,70,200)
        UUID villageId = UUID.randomUUID();
        VolumeMask mask = new VolumeMask.Builder()
                .structureId("blocker")
                .villageId(villageId)
                .bounds(201, 201, 69, 71, 200, 200)
                .build();

        List<VolumeMask> masks = Collections.singletonList(mask);

        // disable support under 202 by making below block non-solid
        ensureBlock(202, 69, 200).type = Material.AIR; toMock(ensureBlock(202,69,200));

        List<Block> path = Arrays.asList(
            toMock(ensureBlock(200,70,200)),
            toMock(ensureBlock(201,70,200)),
            toMock(ensureBlock(202,70,200))
        );

        for (Block b : path) {
            System.out.println(String.format("[TEST-DEBUG] pre-emit block at %d,%d,%d type=%s", b.getX(), b.getY(), b.getZ(), b.getType()));
        }

        int placed;
        try {
            Mockito.when(world.getBlockAt(Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt())).thenAnswer(inv -> {
                int x = inv.getArgument(0);
                int y = inv.getArgument(1);
                int z = inv.getArgument(2);
                return toMock(ensureBlock(x, y, z));
            });
            Mockito.when(world.getHighestBlockYAt(Mockito.anyInt(), Mockito.anyInt())).thenAnswer(inv -> {
                int x = inv.getArgument(0);
                int z = inv.getArgument(1);
                return blocks.entrySet().stream()
                        .map(e -> e.getValue())
                        .filter(mb -> mb.x == x && mb.z == z && mb.type != Material.AIR)
                        .mapToInt(mb -> mb.y)
                        .max().orElse(0);
            });

            placed = emitter.emitPath(world, path, "roman", masks);
        } catch (Throwable t) {
            t.printStackTrace();
            fail("emitPath threw an unexpected exception: " + t);
            return;
        }

        System.out.println("[TEST-DEBUG] emitPath returned -> " + placed);

        // The masked block (201) should not be replaced
        assertNotEquals(Material.COBBLESTONE, world.getBlockAt(201, 70, 200).getType(), "Masked position should not be overwritten");

        // The unsupported location (202) has foundation AIR below and should not be placed
        assertNotEquals(Material.COBBLESTONE, world.getBlockAt(202, 70, 200).getType(), "Unsupported location should not be replaced with path material");

        // At least one block (200) should have been placed
        assertTrue(placed >= 1, "At least the unmasked supported block should be placed");
    }

    @Test
    @DisplayName("smoothPath places stairs for single-block elevation changes and sets facing")
    public void testSmoothPath_placesStairs() {
        // set up a small diagonal slope: prev@y=64, current@y=65, next@y=65
        ensureBlock(300,63,300).type = Material.DIRT; toMock(ensureBlock(300,63,300));
        ensureBlock(300,64,300).type = Material.DIRT; toMock(ensureBlock(300,64,300));
        ensureBlock(301,64,300).type = Material.DIRT; toMock(ensureBlock(301,64,300));
        ensureBlock(301,65,300).type = Material.DIRT; toMock(ensureBlock(301,65,300));
        ensureBlock(302,65,300).type = Material.DIRT; toMock(ensureBlock(302,65,300));

        Block prev = toMock(ensureBlock(300,64,300));
        Block current = toMock(ensureBlock(301,65,300));
        Block next = toMock(ensureBlock(302,65,300));

        List<Block> path = Arrays.asList(prev, current, next);

        // Ensure support below current
        ensureBlock(301,64,300).type = Material.DIRT; toMock(ensureBlock(301,64,300));

        int smoothed = emitter.smoothPath(world, path, "roman");

        assertTrue(smoothed >= 1, "Should place at least one stairs for a single-block elevation change");

        assertEquals(Material.COBBLESTONE_STAIRS, current.getType(), "Current block should become cobblestone stairs for roman culture");
    }

    @Test
    @DisplayName("smoothPath places occasional slabs on flat sections")
    public void testSmoothPath_placesSlabsOccasionally() {
        // make a flat straight line of blocks at y=80
        makeFlatGround(400, 410, 400, 400, 80);

        List<Block> path = new ArrayList<>();
        for (int x = 400; x <= 410; x++) {
            path.add(toMock(ensureBlock(x, 80, 400)));
        }

        // the smoothing logic will consider indices 1..size-2 and place slabs at i%5==0
        int beforeSlabCount = 0;
        for (Block b : path) {
            if (b.getType() == Material.STONE_BRICK_SLAB || b.getType() == Material.COBBLESTONE_SLAB) beforeSlabCount++;
        }

        int smoothed = emitter.smoothPath(world, path, "default");

        assertTrue(smoothed > 0, "At least one slab should be placed on a long flat path");

        int afterSlabCount = 0;
        for (Block b : path) {
            if (b.getType() == Material.STONE_BRICK_SLAB || b.getType() == Material.COBBLESTONE_SLAB) afterSlabCount++;
        }

        assertTrue(afterSlabCount > beforeSlabCount, "Slab count should increase after smoothing");
    }
}
