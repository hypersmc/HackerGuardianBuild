package me.hackerguardian.main.replay.events;

import me.hackerguardian.main.replay.ReplayCodec;
import me.hackerguardian.main.replay.ReplayEvent;
import me.hackerguardian.main.replay.ReplayEventType;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

public final class NearbySnapshotEvent implements ReplayEvent {

    public static final class Entry {
        public final UUID uuid;
        public final String name;
        public final double x, y, z;
        public final float yaw, pitch;
        public final String mainHandType; // "DIAMOND_SWORD" etc

        public Entry(UUID uuid, String name,
                     double x, double y, double z,
                     float yaw, float pitch,
                     String mainHandType) {
            this.uuid = uuid;
            this.name = name;
            this.x = x; this.y = y; this.z = z;
            this.yaw = yaw; this.pitch = pitch;
            this.mainHandType = mainHandType;
        }
    }

    private final String world;
    private final List<Entry> entries;

    public NearbySnapshotEvent(String world, List<Entry> entries) {
        this.world = world;
        this.entries = entries;
    }

    @Override
    public ReplayEventType type() {
        return ReplayEventType.NEARBY_SNAPSHOT;
    }

    @Override
    public void encode(ReplayCodec.Out out) throws IOException {
        out.writeString(world, 128);
        out.writeVarInt(entries.size());

        for (Entry e : entries) {
            out.writeUUID(e.uuid);
            out.writeString(e.name, 16);

            out.writeDouble(e.x);
            out.writeDouble(e.y);
            out.writeDouble(e.z);

            out.writeFloat(e.yaw);
            out.writeFloat(e.pitch);

            out.writeString(e.mainHandType == null ? "AIR" : e.mainHandType, 64);
        }
    }

    public String world() { return world; }
    public List<Entry> entries() { return entries; }
    public int entryCount() { return entries.size(); }
}