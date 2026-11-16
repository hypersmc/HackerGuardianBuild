package me.hackerguardian.main.detection.packet;

import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.reflect.StructureModifier;

/**
 * Normalizes the four inbound movement packet shapes into nullable typed values.
 *
 * In modern Minecraft implementations, movement fields live on an NMS packet
 * superclass, not the concrete subclass. The usual PacketContainer#getDoubles
 * can therefore be empty. Build a cached superclass-aware modifier template
 * per concrete NMS class and rebind its target for each packet.
 *
 * Missing/invalid required fields fail closed: no fabricated coordinates,
 * no recycled previous value, and no detection side effect.
 */
public final class MovementPacketDecoder {

    private MovementPacketDecoder() {}

    interface FieldReader {
        Double doubleAt(int index);
        Float floatAt(int index);
        Boolean booleanAt(int index);
    }

    private record Fields(StructureModifier<Double> doubles,
                          StructureModifier<Float> floats,
                          StructureModifier<Boolean> booleans) {}

    private static final ClassValue<Fields> CACHE = new ClassValue<>() {
        @Override
        protected Fields computeValue(Class<?> type) {
            // null superclass exclusion = include inherited movement fields.
            StructureModifier<Object> base = new StructureModifier<Object>(type, null, false);
            return new Fields(base.withType(double.class),
                    base.withType(float.class),
                    base.withType(boolean.class));
        }
    };

    /**
     * Copies values during the ProtocolLib receive callback; no Bukkit lookups
     * or original mutable packet references survive this method.
     */
    public static MovementPacketBuffer.PacketState decode(
            PacketContainer packet, MovementPacketBuffer.Kind kind) {
        if (packet == null || kind == null) return MovementPacketBuffer.PacketState.missing();
        try {
            Object handle = packet.getHandle();
            if (handle == null) return MovementPacketBuffer.PacketState.missing();
            Fields fields = CACHE.get(handle.getClass());
            FieldReader reader = new FieldReader() {
                @Override
                public Double doubleAt(int index) {
                    return read(fields.doubles().withTarget(handle), index);
                }

                @Override
                public Float floatAt(int index) {
                    return read(fields.floats().withTarget(handle), index);
                }

                @Override
                public Boolean booleanAt(int index) {
                    return read(fields.booleans().withTarget(handle), index);
                }
            };
            return normalize(kind, reader);
        } catch (RuntimeException | LinkageError exception) {
            return MovementPacketBuffer.PacketState.missing();
        }
    }

    /** Package-private seam for unit tests without a running Minecraft server. */
    static MovementPacketBuffer.PacketState normalize(
            MovementPacketBuffer.Kind kind, FieldReader fields) {
        if (kind == null || fields == null) return MovementPacketBuffer.PacketState.missing();
        try {
            MovementPacketBuffer.Position position = null;
            MovementPacketBuffer.Rotation rotation = null;
            if (kind == MovementPacketBuffer.Kind.POSITION || kind == MovementPacketBuffer.Kind.POSITION_LOOK) {
                Double x = fields.doubleAt(0);
                Double y = fields.doubleAt(1);
                Double z = fields.doubleAt(2);
                if (x == null || y == null || z == null) return MovementPacketBuffer.PacketState.missing();
                position = new MovementPacketBuffer.Position(x, y, z);
            }
            if (kind == MovementPacketBuffer.Kind.LOOK || kind == MovementPacketBuffer.Kind.POSITION_LOOK) {
                Float yaw = fields.floatAt(0);
                Float pitch = fields.floatAt(1);
                if (yaw == null || pitch == null) return MovementPacketBuffer.PacketState.missing();
                rotation = new MovementPacketBuffer.Rotation(yaw, pitch);
            }

            // Optional fields: known layouts normally provide on-ground at index 0
            // and horizontal collision at index 1. Keep absent flags unknown.
            Boolean ground = fields.booleanAt(0);
            Boolean horizontal = ground == null ? null : fields.booleanAt(1);
            return new MovementPacketBuffer.PacketState(position, rotation, ground, horizontal, true);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return MovementPacketBuffer.PacketState.missing();
        }
    }

    private static <T> T read(StructureModifier<T> modifier, int index) {
        return modifier.size() > index ? modifier.read(index) : null;
    }
}
