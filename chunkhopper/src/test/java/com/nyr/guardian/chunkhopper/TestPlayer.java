package com.nyr.guardian.chunkhopper;

import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.bukkit.block.Block;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;

/**
 * A player whose menus open in a {@link MenuView} (MockBukkit's own views cannot be clicked in) and who looks at a block the
 * test chooses (MockBukkit cannot trace a player's line of sight).
 */
@SuppressWarnings("unchecked") // MockBukkit implements generic Bukkit methods with raw types
public final class TestPlayer extends PlayerMock {

    private Block lookingAt;

    public TestPlayer(ServerMock server, String name) {
        super(server, name, UUID.nameUUIDFromBytes(("chunkhopper-test:" + name).getBytes(StandardCharsets.UTF_8)));
    }

    public void lookAt(Block block) {
        this.lookingAt = block;
    }

    @Override
    public Block getTargetBlockExact(int maxDistance) {
        return lookingAt;
    }

    @Override
    public InventoryView openInventory(Inventory inventory) {
        MenuView view = new MenuView(this, inventory);
        openInventory(view);
        return getOpenInventory() == view ? view : null;
    }

    @Override
    public void saveData() {
        // nothing to write: the in-memory player is its own saved data
    }
}
