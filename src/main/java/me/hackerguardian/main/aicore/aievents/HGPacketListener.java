package me.hackerguardian.main.aicore.aievents;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import me.hackerguardian.main.HackerGuardian;
import me.hackerguardian.main.aicore.FeatureCollector;
import me.hackerguardian.main.utils.textHandling;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;


public class HGPacketListener extends PacketAdapter {

    private final HackerGuardian main;
    private final FeatureCollector featureCollector;

    public HGPacketListener(Plugin plugin) {
        super(plugin,
                ListenerPriority.NORMAL,
                PacketType.Play.Client.POSITION,
                PacketType.Play.Client.LOOK,
                PacketType.Play.Client.POSITION_LOOK,
                PacketType.Play.Client.KEEP_ALIVE
        );
        this.main = HackerGuardian.getInstance();
        this.featureCollector = main.getFeatureCollector();
    }

    @Override
    public void onPacketReceiving(PacketEvent event) {
        if (!(event.getPlayer() instanceof Player)) {
            return;
        }
        featureCollector.recordPacket(event);
    }
}