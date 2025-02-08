package me.hackerguardian.bungee;

import net.md_5.bungee.api.ProxyServer;

import java.util.concurrent.TimeUnit;

/**
 * @author JumpWatch on 19-05-2024
 * @Project HackerGuardian
 * v1.0.0
 */
public class Announcements {

    public Announcements() {
        banAmount();
        resetAmount();
    }

    private void banAmount(){
        ProxyServer.getInstance().getScheduler().schedule(HackerGuardianB.getInstance(), () -> {

        }, 0, 28560, TimeUnit.MILLISECONDS);
    }

    private void resetAmount(){
        ProxyServer.getInstance().getScheduler().schedule(HackerGuardianB.getInstance(), () -> {

        }, 0, 30, TimeUnit.DAYS);
    }
}
