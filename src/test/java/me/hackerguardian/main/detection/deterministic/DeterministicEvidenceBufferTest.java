package me.hackerguardian.main.detection.deterministic;

import me.hackerguardian.main.detection.DetectionCategory;
import me.hackerguardian.main.detection.DetectionFinding;
import me.hackerguardian.main.detection.EvidenceStrength;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DeterministicEvidenceBufferTest {

    @Test
    void collapsesRepeatedCheckEventsToStrongestFinding() {
        DeterministicEvidenceBuffer buffer = new DeterministicEvidenceBuffer(5000L, 32);
        UUID player = UUID.randomUUID();

        buffer.record(player, finding(0.70, 0.90, EvidenceStrength.STRONG), 1000L);
        buffer.record(player, finding(0.95, 0.98, EvidenceStrength.HARD), 1200L);

        List<DetectionFinding> recent = buffer.recent(player, 1500L, 1000L);
        assertEquals(1, recent.size());
        assertEquals(0.95, recent.get(0).getScore(), 1.0E-9);
        assertEquals(EvidenceStrength.HARD, recent.get(0).getStrength());
        assertEquals(2.0, recent.get(0).getEvidence().get("recent_events"), 1.0E-9);
    }

    @Test
    void dropsEvidenceOutsideAssessmentWindow() {
        DeterministicEvidenceBuffer buffer = new DeterministicEvidenceBuffer(5000L, 32);
        UUID player = UUID.randomUUID();
        buffer.record(player, finding(0.9, 0.9, EvidenceStrength.STRONG), 1000L);

        assertEquals(0, buffer.recent(player, 2501L, 1000L).size());
    }

    private static DetectionFinding finding(double score, double reliability, EvidenceStrength strength) {
        return new DetectionFinding(
                "test.check",
                DetectionCategory.BLOCK,
                score,
                reliability,
                strength,
                "test",
                Map.of("sample", 1.0)
        );
    }
}
