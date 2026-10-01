package com.nyr.guardian.smarttick;

import be.seeseemelk.mockbukkit.Coordinate;
import be.seeseemelk.mockbukkit.WorldMock;
import be.seeseemelk.mockbukkit.block.BlockMock;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.Material;

/**
 * A MockBukkit world whose blocks answer {@code Block#isPassable()} (MockBukkit's do not): air lets a villager through,
 * everything else blocks it, as far as these tests build. Stone up to y 63, air above.
 */
public final class TestWorld extends WorldMock {

    private static final Set<Material> PASSABLE = Set.of(Material.AIR, Material.CAVE_AIR, Material.VOID_AIR);
    private final Map<Coordinate, BlockMock> blocks = new HashMap<>();

    public TestWorld(String name) {
        super(Material.STONE, -64, 320, 63);
        setName(name);
    }

    @Override
    public BlockMock getBlockAt(Coordinate coordinate) {
        return blocks.computeIfAbsent(coordinate, this::createBlock);
    }

    @Override
    public BlockMock createBlock(Coordinate coordinate) {
        BlockMock plain = super.createBlock(coordinate);
        return new PassableBlock(plain.getType(), plain.getLocation());
    }

    /** A block that knows whether a mob walks through it. */
    static final class PassableBlock extends BlockMock {

        PassableBlock(Material type, Location location) {
            super(type, location);
        }

        @Override
        public boolean isPassable() {
            return PASSABLE.contains(getType());
        }
    }
}
