package me.hackerguardian.main.aicore;

import me.hackerguardian.main.webserver.WebSQL;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.CompletableFuture;

public class AIService {

    private final JavaPlugin plugin;
    private final WebSQL repo;

    public AIService(JavaPlugin plugin, WebSQL repo) {
        this.plugin = plugin;
        this.repo = repo;
    }

    // ---------- CREATE ----------

    public CompletableFuture<Long> PData(Player target, AiManager.EvaluationDetails details) {
        long now = System.currentTimeMillis();

        return supplyAsync(() -> {
            long PDataID = repo.createNeuralPData(target, details, now);
            //Maybe log?
            return PDataID;
        });
    }




    // ---------- async helper ----------

    private <T> CompletableFuture<T> supplyAsync(SqlSupplier<T> s) {
        CompletableFuture<T> f = new CompletableFuture<>();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try { f.complete(s.get()); }
            catch (Exception ex) { f.completeExceptionally(ex); }
        });
        return f;
    }

    @FunctionalInterface private interface SqlSupplier<T> { T get() throws Exception; }
}
