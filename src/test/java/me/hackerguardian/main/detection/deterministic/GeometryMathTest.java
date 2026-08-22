package me.hackerguardian.main.detection.deterministic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GeometryMathTest {

    @Test
    void computesDistanceToClosestPointOnBox() {
        assertEquals(0.0, GeometryMath.distanceToAabb(
                0.5, 0.5, 0.5,
                0.0, 0.0, 0.0,
                1.0, 1.0, 1.0
        ), 1.0E-9);

        assertEquals(1.0, GeometryMath.distanceToAabb(
                2.0, 0.5, 0.5,
                0.0, 0.0, 0.0,
                1.0, 1.0, 1.0
        ), 1.0E-9);

        assertEquals(Math.sqrt(3.0), GeometryMath.distanceToAabb(
                2.0, 2.0, 2.0,
                0.0, 0.0, 0.0,
                1.0, 1.0, 1.0
        ), 1.0E-9);
    }
}
