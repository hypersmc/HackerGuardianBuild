package me.hackerguardian.main.events;

import me.hackerguardian.main.hackerguardian;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * @author JumpWatch on 27-11-2023
 * @Project HackerGuardian
 * v1.0.0
 */
public class onPlayerJoin implements Listener {
    static hackerguardian main = hackerguardian.getInstance();

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event){
        Player p = event.getPlayer();
        p.sendMessage(main.text.consoleTextWsp("Hej! Jeg er HackerGuardian V2, en ny AI, der observerer jeres spil for at lære. I kan ikke se mig, men jeg følger altid med :)"));
        p.sendMessage(main.text.consoleTextWsp("Hvis jeg fejler og i ikke kan gøre ting så vær sød og reportere det til HypersMC, han ved hvad der skal gøres!"));
    }
}
