package me.hackerguardian.main.Recording.API;

import me.hackerguardian.main.Recording.Replay;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public class ReplaySessionFinishEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private Replay replay;

    private Player player;

    public ReplaySessionFinishEvent(Replay replay, Player player) {
        super(!Bukkit.isPrimaryThread());

        this.replay = replay;
        this.player = player;
    }


    public Player getPlayer() {
        return player;
    }

    public Replay getReplay() {
        return replay;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

}
