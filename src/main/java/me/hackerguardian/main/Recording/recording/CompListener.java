package me.hackerguardian.main.Recording.recording;

import java.lang.reflect.InvocationTargetException;

import me.hackerguardian.main.Recording.listener.AbstractListener;
import me.hackerguardian.main.Recording.data.types.InvData;
import me.hackerguardian.main.Recording.data.types.MetadataUpdate;
import me.hackerguardian.main.Recording.utils.NPCManager;
import me.hackerguardian.main.utils.recordinghandler.ReflectionHelper;
import me.hackerguardian.main.utils.recordinghandler.VersionUtil;
import me.hackerguardian.main.utils.recordinghandler.VersionUtil.VersionEnum;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;


public class CompListener extends AbstractListener {

    private PacketRecorder packetRecorder;

    public CompListener(PacketRecorder packetRecorder) {
        super();

        this.packetRecorder = packetRecorder;
    }

    @EventHandler (ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        Player p = e.getPlayer();
        if (this.packetRecorder.getRecorder().getPlayers().contains(p.getName())) {

            InvData data = NPCManager.copyFromPlayer(p, true, true);
            data.setMainHand(NPCManager.fromItemStack(e.getMainHandItem()));
            data.setOffHand(NPCManager.fromItemStack(e.getOffHandItem()));

            this.packetRecorder.addData(p.getName(), data);
        }

    }

    @EventHandler (ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onGlide(EntityToggleGlideEvent e) {
        if (e.getEntity() instanceof Player) {
            Player p = (Player) e.getEntity();
            PlayerWatcher watcher = this.packetRecorder.getRecorder().getData().getWatcher(p.getName());

            if (this.packetRecorder.getRecorder().getPlayers().contains(p.getName())) {
                watcher.setElytra(!p.isGliding());

                this.packetRecorder.addData(p.getName(), new MetadataUpdate(watcher.isBurning(), watcher.isBlocking(), watcher.isElytra()));
            }

        }
    }

    public void onSwim(Event e) {
        Class<?> swimEvent = e.getClass();

        try {
            Entity en = (Entity) swimEvent.getMethod("getEntity").invoke(e);

            if (en instanceof Player) {
                Player p = (Player) en;

                PlayerWatcher watcher = this.packetRecorder.getRecorder().getData().getWatcher(p.getName());
                if (this.packetRecorder.getRecorder().getPlayers().contains(p.getName())) {
                    boolean isSwimming = (boolean) swimEvent.getMethod("isSwimming").invoke(e);

                    watcher.setSwimming(isSwimming);
                    this.packetRecorder.addData(p.getName(), MetadataUpdate.fromWatcher(watcher));
                }

            }
        } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException | NoSuchMethodException
                 | SecurityException e1) {

            e1.printStackTrace();
        }

    }


    @Override
    public void register() {
        super.register();

        if (VersionUtil.isAbove(VersionEnum.V1_13)) {
            ReflectionHelper.getInstance().registerEvent(ReflectionHelper.getInstance().getSwimEvent(), this, this::onSwim);
        }
    }

}

