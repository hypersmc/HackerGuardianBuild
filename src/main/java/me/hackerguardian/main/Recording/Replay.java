package me.hackerguardian.main.Recording;
import java.util.Arrays;

import java.util.List;

import me.hackerguardian.main.hackerguardian;
import me.hackerguardian.main.Recording.data.ReplayData;
import me.hackerguardian.main.Recording.data.ReplayInfo;
import me.hackerguardian.main.Recording.recording.Recorder;
import me.hackerguardian.main.Recording.replaying.Replayer;
import me.hackerguardian.main.utils.recordinghandler.ReplayManager;
import me.hackerguardian.main.utils.recordinghandler.StringUtils;
import org.bukkit.Bukkit;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class Replay {
    private String id;

    private ReplayData data;

    private ReplayInfo replayInfo;

    private Recorder recorder;
    private Replayer replayer;

    private boolean isRecording, isPlaying;

    public Replay() {
        this.id = StringUtils.getRandomString(6);
        this.data = new ReplayData();
        this.isRecording = false;
        this.isPlaying = false;
    }

    public Replay(String id, ReplayData data) {
        this.id = id;
        this.data = data;
    }

    public void record(CommandSender sender, Player... players) {
        recordAll(Arrays.asList(players), sender);
    }

    public void recordAll(List<Player> players, CommandSender sender) {
        this.recorder = new Recorder(this, players, sender);
        this.recorder.start();
        this.isRecording = true;

        ReplayManager.activeReplays.put(this.id, this);

    }

    public void play(Player watcher) {
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(hackerguardian.getInstance(), () -> startReplay(watcher));
        } else {
            startReplay(watcher);
        }

    }

    private void startReplay(Player watcher) {
        this.replayer = new Replayer(this, watcher);
        this.replayer.start();
        this.isPlaying = true;
    }

    public String getId() {
        return id;
    }

    public ReplayData getData() {
        return data;
    }

    public void setData(ReplayData data) {
        this.data = data;
    }

    public Recorder getRecorder() {
        return recorder;
    }

    public Replayer getReplayer() {
        return replayer;
    }

    public boolean isRecording() {
        return isRecording;
    }

    public void setId(String id) {
        this.id = id;
    }

    public void setRecording(boolean recording) {
        this.isRecording = recording;
    }

    public boolean isPlaying() {
        return isPlaying;
    }

    public void setPlaying(boolean isPlaying) {
        this.isPlaying = isPlaying;
    }

    public void setReplayInfo(ReplayInfo replayInfo) {
        this.replayInfo = replayInfo;
    }

    public ReplayInfo getReplayInfo() {
        return replayInfo;
    }
}
