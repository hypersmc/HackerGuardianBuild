package me.hackerguardian.main.modsys;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class HGModFingerprintListener implements Listener {

    private final JavaPlugin plugin;
    private final ModFingerprintManager manager;
    private final ProtocolManager protocol;

    public HGModFingerprintListener(JavaPlugin plugin, ModFingerprintManager manager) {
        this.plugin = plugin;
        this.manager = manager;
        this.protocol = ProtocolLibrary.getProtocolManager();
    }

    public void enable() {
        protocol.addPacketListener(new PacketAdapter(
                plugin,
                ListenerPriority.NORMAL,
                PacketType.Play.Client.CUSTOM_PAYLOAD
        ) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                handleCustomPayload(event);
            }
        });

        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void disable() {
        protocol.removePacketListeners(plugin);
        manager.clear();
    }

    private void handleCustomPayload(PacketEvent event) {
        Player player = event.getPlayer();
        if (player == null) return;

        String channel = readChannelId(event.getPacket());
        if (channel == null) return;

        byte[] data = readPayloadBytes(event.getPacket());
        if (data == null) return;

        String lc = channel.toLowerCase();
        long now = System.currentTimeMillis();

        // Brand
        if (lc.equals("minecraft:brand") || lc.equals("mc|brand")) {
            String brand = tryReadMinecraftString(data);
            if (brand != null && !brand.isBlank()) {
                manager.getOrCreate(player.getUniqueId()).setBrand(brand, now);
            }
            return;
        }

        // Channel register list
        if (lc.equals("minecraft:register") || lc.equals("register")) {
            Set<String> chans = parseNullSeparatedStrings(data);
            if (!chans.isEmpty()) {
                manager.getOrCreate(player.getUniqueId()).addChannels(chans, now);
            }
        }
    }

    private String readChannelId(PacketContainer packet) {
        // ProtocolLib differs across versions: sometimes channel is a MinecraftKey, sometimes a String.
        try {
            Object key = packet.getMinecraftKeys().readSafely(0);
            if (key != null) return key.toString();
        } catch (Throwable ignored) {}

        try {
            String s = packet.getStrings().readSafely(0);
            if (s != null) return s;
        } catch (Throwable ignored) {}

        return null;
    }

    private byte[] readPayloadBytes(PacketContainer packet) {
        try {
            byte[] arr = packet.getByteArrays().readSafely(0);
            if (arr != null) return arr;
        } catch (Throwable ignored) {}

        try {
            // Fallback: some builds expose it via a "specific modifier"
            return packet.getSpecificModifier(byte[].class).readSafely(0);
        } catch (Throwable ignored) {}

        return null;
    }

    private Set<String> parseNullSeparatedStrings(byte[] data) {
        String all = new String(data, StandardCharsets.UTF_8);
        String[] parts = all.split("\u0000");
        Set<String> out = new HashSet<>();
        for (String p : parts) {
            if (p == null) continue;
            String s = p.trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    /**
     * Minecraft strings are VarInt length + UTF-8 bytes.
     * This tries to read that. If it doesn't look like it, falls back to plain UTF-8.
     */
    private String tryReadMinecraftString(byte[] data) {
        try {
            int[] idx = {0};
            int len = readVarInt(data, idx);
            if (len < 0 || len > 32767) return null;
            if (idx[0] + len > data.length) return null;

            return new String(data, idx[0], len, StandardCharsets.UTF_8).trim();
        } catch (Throwable ignored) {
            // fallback
            String s = new String(data, StandardCharsets.UTF_8).trim();
            return s.isEmpty() ? null : s;
        }
    }

    private int readVarInt(byte[] data, int[] idxRef) {
        int numRead = 0;
        int result = 0;
        byte read;
        do {
            if (idxRef[0] >= data.length) throw new IllegalArgumentException("VarInt exceeds data");
            read = data[idxRef[0]++];
            int value = (read & 0b01111111);
            result |= (value << (7 * numRead));

            numRead++;
            if (numRead > 5) throw new IllegalArgumentException("VarInt too big");
        } while ((read & 0b10000000) != 0);

        return result;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        cleanup(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKick(PlayerKickEvent e) {
        cleanup(e.getPlayer().getUniqueId());
    }

    private void cleanup(UUID id) {
        manager.remove(id);
    }
}