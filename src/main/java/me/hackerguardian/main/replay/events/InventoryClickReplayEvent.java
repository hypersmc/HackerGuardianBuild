package me.hackerguardian.main.replay.events;

import me.hackerguardian.main.replay.ReplayCodec;
import me.hackerguardian.main.replay.ReplayEvent;
import me.hackerguardian.main.replay.ReplayEventType;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.inventory.ItemStack;

public final class InventoryClickReplayEvent implements ReplayEvent {
    private final int slot;
    private final String clickType;
    private final String action;
    private final String currentItem;
    private final int currentAmount;

    public InventoryClickReplayEvent(int slot, String clickType, String action, String currentItem, int currentAmount) {
        this.slot = slot;
        this.clickType = clickType;
        this.action = action;
        this.currentItem = currentItem;
        this.currentAmount = currentAmount;
    }

    public static InventoryClickReplayEvent from(int slot, ClickType ct, InventoryAction act, ItemStack current) {
        String mat = (current == null || current.getType() == null) ? "AIR" : current.getType().name();
        int amt = (current == null) ? 0 : current.getAmount();
        return new InventoryClickReplayEvent(slot, ct.name(), act.name(), mat, amt);
    }

    @Override public ReplayEventType type() { return ReplayEventType.INVENTORY_CLICK; }

    @Override
    public void encode(ReplayCodec.Out out) throws Exception {
        out.writeVarInt(slot);
        out.writeString(clickType, 32);
        out.writeString(action, 64);
        out.writeString(currentItem, 64);
        out.writeVarInt(currentAmount);
    }
}