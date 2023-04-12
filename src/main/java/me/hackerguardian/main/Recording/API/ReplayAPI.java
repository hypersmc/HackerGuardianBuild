package me.hackerguardian.main.Recording.API;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;


import me.hackerguardian.main.Recording.Replay;
import me.hackerguardian.main.Recording.filesys.saving.IReplaySaver;
import me.hackerguardian.main.Recording.filesys.saving.ReplaySaver;
import me.hackerguardian.main.Recording.replaying.ReplayHelper;
import me.hackerguardian.main.Recording.replaying.Replayer;
import me.hackerguardian.main.utils.recordinghandler.ReplayManager;
import me.hackerguardian.main.utils.recordinghandler.fetcher.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
public class ReplayAPI {

    private static ReplayAPI instance;

    private HookManager hookManager;

    private ReplayAPI() {
        this.hookManager = new HookManager();
    }

    public void registerHook(IReplayHook hook) {
        this.hookManager.registerHook(hook);
    }

    public void unregisterHook(IReplayHook hook) {
        this.hookManager.unregisterHook(hook);
    }


    public Replay recordReplay(String name, CommandSender sender, Player... players) {
        List<Player> toRecord = new ArrayList<Player>();

        if (players != null && players.length > 0) {
            toRecord = Arrays.asList(players);
        } else {
            for (Player all : Bukkit.getOnlinePlayers()) {
                toRecord.add(all);
            }
        }

        return recordReplay(name, sender, toRecord);
    }

    public Replay recordReplay(String name, CommandSender sender, List<Player> players) {
        Replay replay = new Replay();
        if (name != null) replay.setId(name);
        replay.recordAll(players, sender);

        return replay;
    }

    public void stopReplay(String name, boolean save) {
        stopReplay(name, save, false);
    }

    public void stopReplay(String name, boolean save, boolean ignoreEmpty) {
        if (ReplayManager.activeReplays.containsKey(name)) {
            Replay replay = ReplayManager.activeReplays.get(name);

            boolean shouldSave = save && (replay.getRecorder().getData().getActions().size() > 0 || !ignoreEmpty);
            if (replay.isRecording()) replay.getRecorder().stop(shouldSave);
        }
    }

    public void playReplay(String name, Player watcher) {
        if (ReplaySaver.exists(name) && !ReplayHelper.replaySessions.containsKey(watcher.getName())) {
            ReplaySaver.load(name, new Consumer<Replay>() {

                @Override
                public void accept(Replay replay) {
                    replay.play(watcher);

                }
            });
        }
    }

    public void jumpToReplayTime(Player watcher, Integer second) {
        if (ReplayHelper.replaySessions.containsKey(watcher.getName())) {
            Replayer replayer = ReplayHelper.replaySessions.get(watcher.getName());
            if (replayer != null) {
                int duration = replayer.getReplay().getData().getDuration() / 20;
                if (second > 0 && second <= duration) {
                    replayer.getUtils().jumpTo(second);
                }
            }
        }
    }

    public void registerReplaySaver(IReplaySaver replaySaver) {
        ReplaySaver.register(replaySaver);
    }

    public HookManager getHookManager() {
        return hookManager;
    }


    public static ReplayAPI getInstance() {
        if (instance == null) instance = new ReplayAPI();

        return instance;
    }
}
