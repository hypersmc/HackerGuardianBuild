package me.hackerguardian.main.replay;

public interface ReplayEvent {
    ReplayEventType type();
    void encode(ReplayCodec.Out out) throws Exception;
}
