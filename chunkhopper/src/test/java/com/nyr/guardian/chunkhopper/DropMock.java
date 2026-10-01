package com.nyr.guardian.chunkhopper;

import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.ItemEntityMock;
import java.util.UUID;
import org.bukkit.inventory.ItemStack;

/** An item entity that knows who threw it: MockBukkit leaves the thrower unimplemented, and the plugin asks for it. */
public final class DropMock extends ItemEntityMock {

    private UUID thrower;

    public DropMock(ServerMock server, ItemStack stack) {
        super(server, UUID.randomUUID(), stack);
    }

    @Override
    public UUID getThrower() {
        return thrower;
    }

    @Override
    public void setThrower(UUID thrower) {
        this.thrower = thrower;
    }
}
