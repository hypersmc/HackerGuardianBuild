package me.hackerguardian.main.detection.packet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MovementPacketDecoderTest {

    private static MovementPacketDecoder.FieldReader fields(
            Double[] doubles, Float[] floats, Boolean[] booleans) {
        return new MovementPacketDecoder.FieldReader() {
            @Override
            public Double doubleAt(int index) {
                return index < doubles.length ? doubles[index] : null;
            }

            @Override
            public Float floatAt(int index) {
                return index < floats.length ? floats[index] : null;
            }

            @Override
            public Boolean booleanAt(int index) {
                return index < booleans.length ? booleans[index] : null;
            }

            @Override
            public int booleanCount() {
                return booleans.length;
            }
        };
    }

    @Test
    void modernPositionLookCopiesCoordinatesRotationAndGroundFlags() {
        // 1.21.2+ shape: onGround, horizontalCollision, hasPosition, hasRotation
        var result = MovementPacketDecoder.normalize(MovementPacketBuffer.Kind.POSITION_LOOK,
                fields(new Double[]{10.5, 64.0, -2.25},
                        new Float[]{450.0F, -30.0F},
                        new Boolean[]{false, true, true, true}));
        assertTrue(result.decoded());
        assertEquals(new MovementPacketBuffer.Position(10.5, 64.0, -2.25), result.position());
        assertEquals(new MovementPacketBuffer.Rotation(450.0F, -30.0F), result.rotation());
        assertEquals(false, result.onGround());
        assertEquals(true, result.horizontalCollision());
    }

    @Test
    void legacyPositionHasNoHorizontalCollisionFlag() {
        // 1.20.x movement shape: onGround, hasPosition, hasRotation.
        // The OLD decoder incorrectly treated hasPosition=true as collision=true.
        var flying = MovementPacketDecoder.normalize(MovementPacketBuffer.Kind.POSITION,
                fields(new Double[]{4.0, 150.0, 8.0}, new Float[0],
                        new Boolean[]{false, true, false}));
        assertTrue(flying.decoded());
        assertEquals(false, flying.onGround());
        assertNull(flying.horizontalCollision());
        assertEquals(new MovementPacketBuffer.Position(4.0, 150.0, 8.0),
                flying.position());

        var ground = MovementPacketDecoder.normalize(MovementPacketBuffer.Kind.GROUND,
                fields(new Double[0], new Float[0],
                        new Boolean[]{true, false, false}));
        assertTrue(ground.decoded());
        assertEquals(true, ground.onGround());
        assertNull(ground.horizontalCollision());

        var both = MovementPacketDecoder.normalize(MovementPacketBuffer.Kind.POSITION_LOOK,
                fields(new Double[]{1.0, 2.0, 3.0}, new Float[]{45F, 15F},
                        new Boolean[]{false, true, true}));
        assertEquals(false, both.onGround());
        assertNull(both.horizontalCollision());
    }

    @Test
    void modernPositionAndLookUseSeparateTrailingPresenceFlags() {
        var position = MovementPacketDecoder.normalize(MovementPacketBuffer.Kind.POSITION,
                fields(new Double[]{1.0, 2.0, 3.0}, new Float[0],
                        new Boolean[]{true, false, true, false}));
        assertTrue(position.decoded());
        assertEquals(new MovementPacketBuffer.Position(1.0, 2.0, 3.0), position.position());
        assertNull(position.rotation());
        assertEquals(true, position.onGround());
        assertEquals(false, position.horizontalCollision());

        var look = MovementPacketDecoder.normalize(MovementPacketBuffer.Kind.LOOK,
                fields(new Double[0], new Float[]{90F, 15F},
                        new Boolean[]{false, true, false, true}));
        assertTrue(look.decoded());
        assertNull(look.position());
        assertEquals(new MovementPacketBuffer.Rotation(90F, 15F), look.rotation());
        assertEquals(false, look.onGround());
        assertEquals(true, look.horizontalCollision());
    }

    @Test
    void unexpectedBooleanOrderOrFieldCountNeverGuessesPhysicsFlags() {
        // A reordered or new NMS layout must not be mistaken for the known shape.
        var mismatched = MovementPacketDecoder.normalize(MovementPacketBuffer.Kind.POSITION,
                fields(new Double[]{1.0, 2.0, 3.0}, new Float[0],
                        new Boolean[]{true, true, false, true}));
        assertTrue(mismatched.decoded());
        assertNull(mismatched.onGround());
        assertNull(mismatched.horizontalCollision());
        assertEquals(new MovementPacketBuffer.Position(1.0, 2.0, 3.0),
                mismatched.position());

        var insufficient = MovementPacketDecoder.normalize(MovementPacketBuffer.Kind.GROUND,
                fields(new Double[0], new Float[0], new Boolean[]{true, true}));
        assertTrue(insufficient.decoded());
        assertNull(insufficient.onGround());
        assertNull(insufficient.horizontalCollision());

        var futureUnknown = MovementPacketDecoder.normalize(MovementPacketBuffer.Kind.GROUND,
                fields(new Double[0], new Float[0],
                        new Boolean[]{true, true, false, false, true}));
        assertTrue(futureUnknown.decoded());
        assertNull(futureUnknown.onGround());
        assertNull(futureUnknown.horizontalCollision());
    }

    @Test
    void missingRequiredFieldsDoNotFabricateClientPosition() {
        var result = MovementPacketDecoder.normalize(MovementPacketBuffer.Kind.POSITION,
                fields(new Double[]{3.0, 5.0}, new Float[0],
                        new Boolean[]{true, true, false}));
        assertFalse(result.decoded());
        assertNull(result.position());
        assertNull(result.rotation());
        assertNull(result.onGround());
    }

    @Test
    void nonFiniteValuesAndMalformedAccessFailClosed() {
        var nan = MovementPacketDecoder.normalize(MovementPacketBuffer.Kind.POSITION,
                fields(new Double[]{Double.NaN, 1.0, 2.0}, new Float[0], new Boolean[0]));
        assertFalse(nan.decoded());
        var infinity = MovementPacketDecoder.normalize(MovementPacketBuffer.Kind.LOOK,
                fields(new Double[0], new Float[]{Float.POSITIVE_INFINITY, 0F}, new Boolean[0]));
        assertFalse(infinity.decoded());

        var inaccessible = MovementPacketDecoder.normalize(MovementPacketBuffer.Kind.POSITION,
                new MovementPacketDecoder.FieldReader() {
                    @Override
                    public Double doubleAt(int i) {
                        throw new IllegalStateException("unsupported layout");
                    }

                    @Override
                    public Float floatAt(int i) {
                        return null;
                    }

                    @Override
                    public Boolean booleanAt(int i) {
                        return null;
                    }

                    @Override
                    public int booleanCount() {
                        return 3;
                    }
                });
        assertFalse(inaccessible.decoded());
    }

    @Test
    void missingBooleanFieldsRemainUnknownRatherThanFalse() {
        var result = MovementPacketDecoder.normalize(MovementPacketBuffer.Kind.GROUND,
                fields(new Double[0], new Float[0], new Boolean[0]));
        assertTrue(result.decoded());
        assertNull(result.onGround());
        assertNull(result.horizontalCollision());
    }
}
