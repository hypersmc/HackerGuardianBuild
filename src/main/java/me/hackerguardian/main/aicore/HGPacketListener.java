package me.hackerguardian.main.aicore;


import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.ListeningWhitelist;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.events.PacketListener;
import me.hackerguardian.main.hackerguardian;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * @author JumpWatch on 14-04-2023
 * @Project HackerGuardian
 * v1.0.0
 */
public class HGPacketListener implements PacketListener {
    private List<PacketType> sendingTypes = new ArrayList<>();
    private List<PacketType> receivingTypes = new ArrayList<>();
    private ListeningWhitelist sendingWhitelist = ListeningWhitelist.newBuilder().types(sendingTypes).build();
    private ListeningWhitelist receivingWhitelist = ListeningWhitelist.newBuilder().types(receivingTypes).build();
    @Override
    public void onPacketSending(PacketEvent event) {
        Player player = event.getPlayer();
        PacketType type = event.getPacketType();
        byte[] bytes = event.getPacket().getByteArrays().read(0);
        // Do something with the packet data, such as logging it
        hackerguardian.getInstance().getLogger().info(String.format("Packet received from player %s: %s - %s", player.getName(), type, bytes));
    }

    @Override
    public void onPacketReceiving(PacketEvent event) {
        Player player = event.getPlayer();
        PacketType type = event.getPacketType();
        byte[] bytes = event.getPacket().getByteArrays().read(0);
        // Do something with the packet data, such as logging it
        hackerguardian.getInstance().getLogger().info(String.format("Packet received from player %s: %s - %s", player.getName(), type, bytes));
    }

    @Override
    public ListeningWhitelist getSendingWhitelist() {
        return sendingWhitelist;
    }

    @Override
    public ListeningWhitelist getReceivingWhitelist() {
        return receivingWhitelist;
    }

    @Override
    public Plugin getPlugin() {
        return hackerguardian.getInstance();
    }
}
