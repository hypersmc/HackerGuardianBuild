package me.hackerguardian.main.report;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class ReportService {

    private final JavaPlugin plugin;
    private final ReportRepository repo;

    public ReportService(JavaPlugin plugin, ReportRepository repo) {
        this.plugin = plugin;
        this.repo = repo;
    }

    public CompletableFuture<Integer> reportsRemainingInWindow(String reporterUuid, int max, long windowMs) {
        long since = System.currentTimeMillis() - windowMs;
        return supplyAsync(() -> {
            int count = repo.countReportsByReporterSince(reporterUuid, since);
            return Math.max(0, max - count);
        });
    }

    public CompletableFuture<Long> createReport(Player reporter, String reportedUuid, String reportedName, String reason) {
        long now = System.currentTimeMillis();
        return supplyAsync(() -> repo.createReport(
                reportedUuid, reportedName,
                reporter.getUniqueId().toString(), reporter.getName(),
                reason, now
        ));
    }

    public CompletableFuture<Optional<ReportRow>> getReport(long id) {
        return supplyAsync(() -> repo.getReport(id));
    }

    public CompletableFuture<Integer> countReportsForPlayer(String reportedUuid) {
        return supplyAsync(() -> repo.countReportsForPlayer(reportedUuid));
    }

    public CompletableFuture<List<ReportRow>> listReportsForPlayer(String reportedUuid, int page, int pageSize) {
        int offset = Math.max(0, (page - 1)) * pageSize;
        return supplyAsync(() -> repo.listReportsForPlayer(reportedUuid, pageSize, offset));
    }

    public CompletableFuture<List<CommentRow>> listComments(long reportId) {
        return supplyAsync(() -> repo.listComments(reportId));
    }

    public CompletableFuture<Void> addComment(long reportId, Player staff, String comment) {
        long now = System.currentTimeMillis();
        return runAsync(() -> repo.addComment(reportId, staff.getUniqueId().toString(), staff.getName(), comment, now));
    }

    public CompletableFuture<Boolean> close(long reportId, Player staff) {
        long now = System.currentTimeMillis();
        return supplyAsync(() -> repo.setStatus(reportId, "CLOSED", staff.getUniqueId().toString(), now));
    }

    public CompletableFuture<Boolean> dismiss(long reportId, Player staff) {
        long now = System.currentTimeMillis();
        return supplyAsync(() -> repo.setStatus(reportId, "DISMISSED", staff.getUniqueId().toString(), now));
    }

    public CompletableFuture<Boolean> reopen(long reportId) {
        long now = System.currentTimeMillis();
        return supplyAsync(() -> repo.setStatus(reportId, "OPEN", null, now));
    }

    private CompletableFuture<Void> runAsync(SqlRunnable r) {
        CompletableFuture<Void> f = new CompletableFuture<>();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try { r.run(); f.complete(null); }
            catch (Exception ex) { f.completeExceptionally(ex); }
        });
        return f;
    }

    private <T> CompletableFuture<T> supplyAsync(SqlSupplier<T> s) {
        CompletableFuture<T> f = new CompletableFuture<>();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try { f.complete(s.get()); }
            catch (Exception ex) { f.completeExceptionally(ex); }
        });
        return f;
    }

    @FunctionalInterface private interface SqlRunnable { void run() throws Exception; }
    @FunctionalInterface private interface SqlSupplier<T> { T get() throws Exception; }
}