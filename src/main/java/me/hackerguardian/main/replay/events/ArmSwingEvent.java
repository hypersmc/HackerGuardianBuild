package me.hackerguardian.main.replay.events;

import me.hackerguardian.main.replay.ReplayCodec;
import me.hackerguardian.main.replay.ReplayEvent;
import me.hackerguardian.main.replay.ReplayEventType;

public final class ArmSwingEvent implements ReplayEvent {
    @Override public ReplayEventType type() { return ReplayEventType.ARM_SWING; }
    @Override public void encode(ReplayCodec.Out out) { /* no fields */ }
}
