package me.hackerguardian.main.detection.packet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PacketCourseReportExportTest {
    @TempDir Path temp;

    private PacketResearchCourse.Report sampleReport() {
        var capture = new PacketCourseCapture();
        capture.enter(PacketCourseCapture.Phase.WALK_STRAIGHT);
        capture.observe(PacketGroundReachability.Status.CONSERVATIVE_REACHABLE, null, null);
        capture.observe(PacketGroundReachability.Status.TICK_GAP, null, null);
        capture.enter(PacketCourseCapture.Phase.WALK_TURN_1);
        capture.observe(PacketGroundReachability.Status.WARMUP, null, null);
        return new PacketResearchCourse.Report("HypersMC", false,
                "Stopped by player", capture.finish());
    }

    @Test
    void chatIsCompactButFullResultsAreExported() throws Exception {
        var report = sampleReport();
        var chat = PacketResearchCourse.format(report);
        assertEquals(4, chat.size());
        assertTrue(chat.get(0).contains("PARTIAL"));
        assertTrue(chat.get(0).contains("1/3 modeled"));
        assertTrue(chat.stream().noneMatch(s -> s.contains("corner")));

        var playerId = UUID.randomUUID();
        String relative = PacketCourseReportExport.write(temp, playerId, report);
        assertTrue(relative.startsWith("research/courses/"));
        Path file = temp.resolve(relative).normalize();
        assertTrue(file.startsWith(temp));
        assertTrue(Files.exists(file));
        String saved = Files.readString(file);
        assertTrue(saved.contains("HypersMC"));
        assertTrue(saved.contains("Walk straight"));
        assertTrue(saved.contains("Walk 90 turn 1"));
        assertTrue(saved.contains("timing gaps 1"));
        assertTrue(saved.contains("tick hints adjacent"));
        assertTrue(saved.contains("Course status: PARTIAL"));
        assertFalse(saved.contains("§"), "Only plain text belongs in the exported report");
    }

    @Test
    void refusesInvalidSaveInputs() {
        assertThrows(IllegalArgumentException.class,
                () -> PacketCourseReportExport.write(null, UUID.randomUUID(), sampleReport()));
        assertThrows(IllegalArgumentException.class,
                () -> PacketCourseReportExport.write(temp, null, sampleReport()));
        assertThrows(IllegalArgumentException.class,
                () -> PacketCourseReportExport.write(temp, UUID.randomUUID(), null));
    }
}
