package me.hackerguardian.main.detection.packet;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.UUID;

/**
 * Bounded text export of the completed six-leg research summary.
 * Runs once on course completion, not in the network packet callback.
 * Stores no raw position history and never influences detection decisions.
 */
public final class PacketCourseReportExport {
    private PacketCourseReportExport() {}

    /**
     * @return path relative to the plugin's data folder, suitable for staff chat
     */
    public static String write(Path pluginDataFolder, UUID playerId,
                               PacketResearchCourse.Report report) throws IOException {
        if (pluginDataFolder == null || playerId == null || report == null) {
            throw new IllegalArgumentException("Report, player and plugin folder required");
        }
        Path root = pluginDataFolder.toAbsolutePath().normalize();
        Path relative = Path.of("research", "courses",
                "course-" + System.currentTimeMillis() + "-" + playerId + ".txt");
        Path path = root.resolve(relative).normalize();
        if (!path.startsWith(root)) throw new IOException("Invalid report path");
        Files.createDirectories(path.getParent());
        List<String> full = PacketResearchCourse.formatDetailed(report);
        StringBuilder contents = new StringBuilder();
        contents.append("HackerGuardian 2.0 — Guided packet research course\n");
        contents.append("Player: ").append(report.player()).append("\n");
        contents.append("Course status: ").append(report.complete() ? "COMPLETE" : "PARTIAL")
                .append(" | ").append(report.reason()).append("\n");
        contents.append("This is observation-only research; not cheat evidence.\n\n");
        for (String line : full) {
            contents.append(line.replaceAll("§.", "")).append('\n');
        }
        // Collision-safe: never overwrite an earlier capture.
        Files.writeString(path, contents.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        return relative.toString().replace('\\', '/');
    }
}
