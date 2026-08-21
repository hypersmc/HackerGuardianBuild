package me.hackerguardian.main.replay.events;

import me.hackerguardian.main.replay.ReplayCodec;
import me.hackerguardian.main.replay.ReplayEvent;
import me.hackerguardian.main.replay.ReplayEventType;
import org.bukkit.inventory.ItemStack;

public final class ItemDropReplayEvent implements ReplayEvent {
    private final String material;
    private final int amount;

    public ItemDropReplayEvent(String material, int amount) {
        this.material = material;
        this.amount = amount;
    }

    public static ItemDropReplayEvent from(ItemStack item) {
        if (item == null || item.getType() == null) return new ItemDropReplayEvent("AIR", 0);
        return new ItemDropReplayEvent(item.getType().name(), item.getAmount());
    }

    @Override public ReplayEventType type() { return ReplayEventType.ITEM_DROP; }

    @Override
    public void encode(ReplayCodec.Out out) throws Exception {
        out.writeString(material, 64);
        out.writeVarInt(amount);
    }
}
