package me.hackerguardian.main.Recording.filesys.saving;

import me.hackerguardian.main.Recording.Replay;
import me.hackerguardian.main.utils.recordinghandler.fetcher.Consumer;

import java.util.List;

public interface IReplaySaver {

    void saveReplay(Replay replay);

    void loadReplay(String replayName, Consumer<Replay> consumer);

    boolean replayExists(String replayName);

    void deleteReplay(String replayName);

    List<String> getReplays();
}
