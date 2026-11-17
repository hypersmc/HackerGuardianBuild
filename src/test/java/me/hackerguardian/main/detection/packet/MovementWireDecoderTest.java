package me.hackerguardian.main.detection.packet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MovementWireDecoderTest {

    @Test
    void legacyVelocityUsesQuantizedEightThousandScale() {
        var decoded = MovementWireDecoder.legacyVelocity(4000, -8000, 2000);
        assertEquals(new MovementPacketBuffer.Velocity(0.5, -1.0, 0.25), decoded);
    }

    @Test
    void missingQuantizedComponentsStayUnknown() {
        assertNull(MovementWireDecoder.legacyVelocity(null, 0, 0));
        assertNull(MovementWireDecoder.legacyVelocity(0, null, 0));
        assertNull(MovementWireDecoder.legacyVelocity(0, 0, null));
    }

    @Test
    void nonFiniteVelocityIsRejectedRatherThanUsedAsPhysicsEvidence() {
        assertNull(MovementWireDecoder.finiteVelocity(Double.NaN, 0, 0));
        assertNull(MovementWireDecoder.finiteVelocity(1, Double.POSITIVE_INFINITY, 0));
        assertEquals(new MovementPacketBuffer.Velocity(0, 0, 0),
                MovementWireDecoder.finiteVelocity(0, 0, 0));
    }

    @Test
    void nullPacketIsNotAnAcknowledgmentOrSelfVelocity() {
        assertNull(MovementWireDecoder.teleportId(null));
        assertEquals(MovementWireDecoder.SelfVelocity.notForSelf(),
                MovementWireDecoder.selfVelocity(null, 7));
    }
}
