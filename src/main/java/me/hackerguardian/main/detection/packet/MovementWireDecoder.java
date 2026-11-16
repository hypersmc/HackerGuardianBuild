package me.hackerguardian.main.detection.packet;

import com.comphenix.protocol.events.PacketContainer;
import org.bukkit.util.Vector;

/**
 * Strict packet-only readers. A missing/unknown field is not guessed.
 *
 * The player-position outbound packet and its inbound confirmation contain a
 * single integer teleport ID in the supported 1.20.x/1.21.x packet layouts.
 * Outbound entity velocity is either entity ID + 3 quantized integers
 * (legacy; 1/8000 blocks/tick) or entity ID + a Vec3 (newer).
 */
public final class MovementWireDecoder {
    private MovementWireDecoder() {}

    public record SelfVelocity(boolean forSelf, MovementPacketBuffer.Velocity velocity) {
        public static SelfVelocity notForSelf() {
            return new SelfVelocity(false, null);
        }
    }

    /** Unknown or ambiguous integer layout must not fabricate an ID. */
    public static Integer teleportId(PacketContainer packet) {
        if (packet == null) return null;
        try {
            var integers = packet.getIntegers();
            if (integers.size() != 1) return null;
            return integers.read(0);
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    public static SelfVelocity selfVelocity(PacketContainer packet, int selfEntityId) {
        if (packet == null || selfEntityId < 0) return SelfVelocity.notForSelf();
        try {
            var ints = packet.getIntegers();
            if (ints.size() < 1) return SelfVelocity.notForSelf();
            Integer entityId = ints.read(0);
            if (entityId == null || entityId.intValue() != selfEntityId) {
                return SelfVelocity.notForSelf();
            }

            // Legacy NMS layout contains entity id, velocityX, velocityY,
            // velocityZ as four primitive integer fields.
            if (ints.size() == 4) {
                Integer x = ints.read(1), y = ints.read(2), z = ints.read(3);
                return new SelfVelocity(true, legacyVelocity(x, y, z));
            }

            // Newer NMS layout contains entity id + one Vec3; ProtocolLib
            // maps Vec3 to Bukkit Vector on supported versions.
            var vectors = packet.getVectors();
            if (vectors.size() == 1) {
                Vector vector = vectors.read(0);
                return new SelfVelocity(true, vector == null ? null
                        : finiteVelocity(vector.getX(), vector.getY(), vector.getZ()));
            }
            // We can still document a relevant outgoing velocity packet even
            // when its payload layout is not supported.
            return new SelfVelocity(true, null);
        } catch (RuntimeException | LinkageError ignored) {
            return SelfVelocity.notForSelf();
        }
    }

    static MovementPacketBuffer.Velocity legacyVelocity(Integer x, Integer y, Integer z) {
        if (x == null || y == null || z == null) return null;
        return finiteVelocity(x / 8000.0, y / 8000.0, z / 8000.0);
    }

    static MovementPacketBuffer.Velocity finiteVelocity(double x, double y, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return null;
        return new MovementPacketBuffer.Velocity(x, y, z);
    }
}
