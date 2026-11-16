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
        };
    }

    @Test
    void positionLookCopiesCoordinatesRotationAndGroundFlags() {
        var result = MovementPacketDecoder.normalize(MovementPacketBuffer.Kind.POSITION_LOOK,
                fields(new Double[]{10.5, 64.0, -2.25},
                        new Float[]{450.0F, -30.0F}, new Boolean[]{false, true}));
        assertTrue(result.decoded());
        assertEquals(new MovementPacketBuffer.Position(10.5, 64.0, -2.25), result.position());
        assertEquals(new MovementPacketBuffer.Rotation(450.0F, -30.0F), result.rotation());
        assertEquals(false, result.onGround());
        assertEquals(true, result.horizontalCollision());
    }

    @Test
    void packetKindControlsWhichFieldsAreExpected() {
        var reader = fields(new Double[]{1.0, 2.0, 3.0},
                new Float[]{90F, 15F}, new Boolean[]{true});
        var position = MovementPacketDecoder.normalize(MovementPacketBuffer.Kind.POSITION, reader);
        assertTrue(position.decoded());
        assertEquals(new MovementPacketBuffer.Position(1.0, 2.0, 3.0), position.position());
        assertNull(position.rotation());
        assertEquals(true, position.onGround());
        assertNull(position.horizontalCollision());

        var look = MovementPacketDecoder.normalize(MovementPacketBuffer.Kind.LOOK, reader);
        assertTrue(look.decoded());
        assertNull(look.position());
        assertEquals(new MovementPacketBuffer.Rotation(90F, 15F), look.rotation());

        var ground = MovementPacketDecoder.normalize(MovementPacketBuffer.Kind.GROUND,
                fields(new Double[0], new Float[0], new Boolean[]{false}));
        assertTrue(ground.decoded());
        assertNull(ground.position());
        assertNull(ground.rotation());
        assertEquals(false, ground.onGround());
    }

    @Test
    void missingRequiredFieldsDoNotFabricateAClientPosition() {
        var result = MovementPacketDecoder.normalize(MovementPacketBuffer.Kind.POSITION,
                fields(new Double[]{3.0, 5.0}, new Float[0], new Boolean[]{true}));
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
                    public Double doubleAt(int i) { throw new IllegalStateException("unsupported layout"); }
                    public Float floatAt(int i) { return null; }
                    public Boolean booleanAt(int i) { return null; }
                });
        assertFalse(inaccessible.decoded());
    }

    @Test
    void unknownFlagsRemainUnknownRatherThanFalse() {
        var result = MovementPacketDecoder.normalize(MovementPacketBuffer.Kind.GROUND,
                fields(new Double[0], new Float[0], new Boolean[0]));
        assertTrue(result.decoded());
        assertNull(result.onGround());
        assertNull(result.horizontalCollision());
    }
}
