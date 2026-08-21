package me.hackerguardian.main.replay.events;

import me.hackerguardian.main.replay.ReplayCodec;
import me.hackerguardian.main.replay.ReplayEvent;
import me.hackerguardian.main.replay.ReplayEventType;

public final class SneakToggleEvent implements ReplayEvent {
    private final boolean sneaking;
    public SneakToggleEvent(boolean sneaking) { this.sneaking = sneaking; }
    @Override public ReplayEventType type() { return ReplayEventType.SNEAK_TOGGLE; }
    @Override public void encode(ReplayCodec.Out out) throws Exception { out.writeBoolean(sneaking); }
}
