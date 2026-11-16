package me.hackerguardian.main.detection.packet;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import me.hackerguardian.main.HackerGuardian;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ProtocolLib -> bounded packet-kind/timing observations.
 *
 * ProtocolLib normally invokes client packet handlers asynchronously.
 * This listener performs no Bukkit API lookups or mutations in the packet
 * callback; UUIDs are captured on the Bukkit thread at join/startup.
 */
public final class MovementPacketObserver implements Listener {

    private final HackerGuardian plugin;
    private final MovementPacketBuffer buffer;
    private final Map<Player, Session> sessions = new ConcurrentHashMap<>();
    private PacketAdapter adapter;
    private boolean running;

    public MovementPacketObserver(HackerGuardian plugin, int maxPerPlayer) {
        this.plugin = plugin;
        this.buffer = new MovementPacketBuffer(maxPerPlayer);
    }

    public void start() {
        if (running) return;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (Player player : Bukkit.getOnlinePlayers()) {
            sessions.put(player, new Session(player.getUniqueId()));
        }

        try {
            PacketAdapter newAdapter = new PacketAdapter(
                    plugin,
                    ListenerPriority.MONITOR,
                    PacketType.Play.Client.GROUND,
                    PacketType.Play.Client.POSITION,
                    PacketType.Play.Client.LOOK,
                    PacketType.Play.Client.POSITION_LOOK
            ) {
                @Override
                public void onPacketReceiving(PacketEvent event) {
                    if (event.isCancelled()) return;
                    Session session = sessions.get(event.getPlayer());
                    if (session == null) return;
                    MovementPacketBuffer.Kind kind = classify(event.getPacketType());
                    if (kind != null) session.record(kind);
                }
            };
            ProtocolLibrary.getProtocolManager().addPacketListener(newAdapter);
            adapter = newAdapter;
            running = true;
        } catch (RuntimeException e) {
            stop();
            throw e;
        }
    }

    public void stop() {
        if (adapter != null) {
            ProtocolLibrary.getProtocolManager().removePacketListener(adapter);
            adapter = null;
        }
        HandlerList.unregisterAll(this);
        for (Session session : sessions.values()) session.close();
        sessions.clear();
        buffer.clear();
        running = false;
    }

    public boolean isRunning() {
        return running;
    }

    public MovementPacketBuffer.Snapshot snapshot(UUID playerId) {
        return buffer.snapshot(playerId);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        sessions.put(event.getPlayer(), new Session(event.getPlayer().getUniqueId()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Session session = sessions.remove(event.getPlayer());
        if (session != null) session.close();
    }

    private static MovementPacketBuffer.Kind classify(PacketType type) {
        if (PacketType.Play.Client.POSITION.equals(type)) return MovementPacketBuffer.Kind.POSITION;
        if (PacketType.Play.Client.POSITION_LOOK.equals(type)) return MovementPacketBuffer.Kind.POSITION_LOOK;
        if (PacketType.Play.Client.LOOK.equals(type)) return MovementPacketBuffer.Kind.LOOK;
        if (PacketType.Play.Client.GROUND.equals(type)) return MovementPacketBuffer.Kind.GROUND;
        return null;
    }

    private final class Session {
        private final UUID playerId;
        private boolean active = true;

        private Session(UUID playerId) {
            this.playerId = playerId;
        }

        private synchronized void record(MovementPacketBuffer.Kind kind) {
            if (active) buffer.record(playerId, kind);
        }

        private synchronized void close() {
            active = false;
            buffer.clearPlayer(playerId);
        }
    }
}
