package me.hackerguardian.main.detection.packet;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PacketCourseGeometryTest {
    @Test
    void courseContainsSixLegsAndQuarterTurns() {
        for (boolean left : List.of(false, true)) {
            var legs = PacketCourseGeometry.build(0, 0, 0, 1, left);
            assertEquals(6, legs.size());
            assertEquals(List.of(12, 8, 8, 10, 8, 8),
                    legs.stream().map(PacketCourseGeometry.Leg::length).toList());
            for (int i = 1; i < legs.size(); i++) {
                assertEquals(legs.get(i - 1).to(), legs.get(i).from());
                var a = legs.get(i - 1);
                var b = legs.get(i);
                int ax = a.to().x() - a.from().x();
                int az = a.to().z() - a.from().z();
                int bx = b.to().x() - b.from().x();
                int bz = b.to().z() - b.from().z();
                if (i == 3) assertTrue(ax * bx + az * bz > 0);
                else assertEquals(0, ax * bx + az * bz);
            }
            assertFalse(legs.get(2).phase().expectsSprint());
            assertTrue(legs.get(3).phase().expectsSprint());
        }
    }

    @Test
    void refusesNonCardinalHeading() {
        assertThrows(IllegalArgumentException.class,
                () -> PacketCourseGeometry.build(0, 0, 1, 1, false));
    }
}
