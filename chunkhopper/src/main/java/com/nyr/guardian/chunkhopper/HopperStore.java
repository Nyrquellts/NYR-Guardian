package com.nyr.guardian.chunkhopper;

import java.util.UUID;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Hopper;
import org.bukkit.block.TileState;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * Reads and writes what the world keeps about Chunk Hoppers: each hopper's TileState (owner, filter, auto-sell, counters) and
 * each chunk's pointer to its hopper. Runs only on the thread that owns the block.
 */
final class HopperStore {

    /** What a placed Chunk Hopper's TileState holds; {@code id} is null for a hopper written before ids existed. */
    record Data(UUID id, UUID owner, String ownerName, Filter filter, boolean autoSell, long collected, double earned, long saleSeq) {
    }

    private static final int FORMAT = 1;

    private final Keys keys;

    HopperStore(Keys keys) {
        this.keys = keys;
    }

    boolean isChunkHopper(TileState state) {
        return state.getPersistentDataContainer().has(keys.hopper, PersistentDataType.INTEGER);
    }

    /** The Chunk Hopper data in a block state, or null when it is not a Chunk Hopper. */
    Data read(BlockState state) {
        if (!(state instanceof Hopper hopper)) {
            return null;
        }
        PersistentDataContainer data = hopper.getPersistentDataContainer();
        if (!data.has(keys.hopper, PersistentDataType.INTEGER)) {
            return null;
        }
        UUID owner = uuid(data.get(keys.owner, PersistentDataType.STRING));
        String ownerName = data.get(keys.ownerName, PersistentDataType.STRING);
        Byte sell = data.get(keys.autoSell, PersistentDataType.BYTE);
        Long collected = data.get(keys.collected, PersistentDataType.LONG);
        Double earned = data.get(keys.earned, PersistentDataType.DOUBLE);
        Long saleSeq = data.get(keys.saleSeq, PersistentDataType.LONG);
        return new Data(uuid(data.get(keys.id, PersistentDataType.STRING)), owner, ownerName == null ? "?" : ownerName,
            Filter.parse(data.get(keys.filter, PersistentDataType.STRING)), sell != null && sell != 0, collected == null ? 0 : collected,
            earned == null ? 0 : earned, saleSeq == null ? 0 : saleSeq);
    }

    /** The Chunk Hopper data of the block now, or null. */
    Data read(Block block) {
        if (block.getType() != Material.HOPPER) {
            return null;
        }
        return read(block.getState());
    }

    /**
     * Writes a record into the hopper's TileState, from a snapshot taken here and nowhere else. {@code BlockState#update}
     * copies the whole snapshot back into the block, items included: a snapshot whose live inventory was changed after it
     * was taken would put the old items back. {@code create} marks a plain hopper as a Chunk Hopper; otherwise a hopper
     * without the mark is left alone.
     *
     * @return whether the block was a (Chunk) Hopper and now holds the record
     */
    boolean write(Block block, HopperRecord record, boolean create) {
        if (block.getType() != Material.HOPPER) {
            return false;
        }
        BlockState state = block.getState();
        if (!(state instanceof Hopper hopper)) {
            return false;
        }
        PersistentDataContainer data = hopper.getPersistentDataContainer();
        if (!create && !data.has(keys.hopper, PersistentDataType.INTEGER)) {
            return false;
        }
        data.set(keys.hopper, PersistentDataType.INTEGER, FORMAT);
        data.set(keys.id, PersistentDataType.STRING, record.id.toString());
        data.set(keys.saleSeq, PersistentDataType.LONG, record.saleSeq());
        if (record.owner() != null) {
            data.set(keys.owner, PersistentDataType.STRING, record.owner().toString());
        }
        data.set(keys.ownerName, PersistentDataType.STRING, record.ownerName() == null ? "?" : record.ownerName());
        data.set(keys.filter, PersistentDataType.STRING, record.filter().serialize());
        data.set(keys.autoSell, PersistentDataType.BYTE, (byte) (record.autoSell() ? 1 : 0));
        data.set(keys.collected, PersistentDataType.LONG, record.collected());
        data.set(keys.earned, PersistentDataType.DOUBLE, record.earned());
        return hopper.update(true, false);
    }

    /** Where the chunk says its Chunk Hopper stands, or null. */
    int[] marked(Chunk chunk) {
        int[] position = chunk.getPersistentDataContainer().get(keys.position, PersistentDataType.INTEGER_ARRAY);
        return position != null && position.length == 3 ? position : null;
    }

    void mark(Chunk chunk, int x, int y, int z) {
        chunk.getPersistentDataContainer().set(keys.position, PersistentDataType.INTEGER_ARRAY, new int[] {x, y, z});
    }

    /** Removes the chunk's pointer if it points at {@code x y z} (or at nothing valid). */
    void unmark(Chunk chunk, int x, int y, int z) {
        int[] position = chunk.getPersistentDataContainer().get(keys.position, PersistentDataType.INTEGER_ARRAY);
        if (position != null && (position.length != 3 || (position[0] == x && position[1] == y && position[2] == z))) {
            chunk.getPersistentDataContainer().remove(keys.position);
        }
    }

    private static UUID uuid(String text) {
        if (text == null) {
            return null;
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException broken) {
            return null;
        }
    }
}
