package me.hackerguardian.main.replay.events;

import me.hackerguardian.main.replay.ReplayCodec;
import me.hackerguardian.main.replay.ReplayEvent;
import me.hackerguardian.main.replay.ReplayEventType;

public final class SprintToggleEvent implements ReplayEvent {
    private final boolean sprinting;
    public SprintToggleEvent(boolean sprinting) { this.sprinting = sprinting; }
    @Override public ReplayEventType type() { return ReplayEventType.SPRINT_TOGGLE; }
    @Override public void encode(ReplayCodec.Out out) throws Exception { out.writeBoolean(sprinting); }
}
