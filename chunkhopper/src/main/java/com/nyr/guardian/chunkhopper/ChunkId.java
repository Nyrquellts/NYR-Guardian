package com.nyr.guardian.chunkhopper;

import java.util.UUID;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.Block;

/** A chunk of a world, by world UUID and chunk coordinates. */
record ChunkId(UUID world, int x, int z) {

    static ChunkId of(Chunk chunk) {
        return new ChunkId(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
    }

    static ChunkId of(Block block) {
        return new ChunkId(block.getWorld().getUID(), block.getX() >> 4, block.getZ() >> 4);
    }

    static ChunkId of(World world, int blockX, int blockZ) {
        return new ChunkId(world.getUID(), blockX >> 4, blockZ >> 4);
    }
}
