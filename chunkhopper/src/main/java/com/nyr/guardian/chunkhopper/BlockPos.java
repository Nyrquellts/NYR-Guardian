package com.nyr.guardian.chunkhopper;

import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.block.Block;

/** A block position of a world: a map key that does not hold the world or the chunk. */
record BlockPos(UUID world, int x, int y, int z) {

    static BlockPos of(Block block) {
        return new BlockPos(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
    }

    /** The block cell a location is in. */
    static BlockPos of(Location at) {
        return new BlockPos(at.getWorld().getUID(), at.getBlockX(), at.getBlockY(), at.getBlockZ());
    }
}
