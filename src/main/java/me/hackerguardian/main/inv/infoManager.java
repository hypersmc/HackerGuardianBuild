package me.hackerguardian.main.inv;

import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;

public class infoManager {
    private final Map<Player, info> playerInfo = new HashMap<>();

    public info getInfo(Player player, String target) {
        if(!playerInfo.containsKey(player)) {
            playerInfo.put(player, new info(target));
        }
        return playerInfo.get(player);
    }

    public void removeinfo(Player player) {
        playerInfo.remove(player);
    }
    public void resetInfo(Player player, String target) {
        playerInfo.put(player, new info(target));
    }
}
