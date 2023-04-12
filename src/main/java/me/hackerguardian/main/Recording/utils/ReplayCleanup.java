package me.hackerguardian.main.Recording.utils;

import java.io.File;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import me.hackerguardian.main.hackerguardian;
import me.hackerguardian.main.Recording.filesys.ConfigManager;
import me.hackerguardian.main.Recording.filesys.saving.DatabaseReplaySaver;
import me.hackerguardian.main.Recording.filesys.saving.DefaultReplaySaver;
import me.hackerguardian.main.Recording.filesys.saving.ReplaySaver;
import me.hackerguardian.main.utils.recordinghandler.LogUtils;
import org.bukkit.Bukkit;

public class ReplayCleanup {

    public static void cleanupReplays() {
        List<String> replays = ReplaySaver.getReplays();

        Bukkit.getScheduler().runTaskAsynchronously(hackerguardian.getInstance(), () -> replays.forEach(ReplayCleanup::checkAndDelete));
    }

    private static void checkAndDelete(String replay) {
        LocalDate creationdDate = getCreationDate(replay);
        LocalDate threshold = LocalDate.now().minusDays(ConfigManager.CLEANUP_REPLAYS);

        if (creationdDate.isBefore(threshold)) {
            LogUtils.log("Replay " + replay + " has expired. Removing it...");
            ReplaySaver.delete(replay);
        }
    }

    private static LocalDate getCreationDate(String replay) {
        if (ReplaySaver.replaySaver instanceof DefaultReplaySaver) {
            return fromMillis(new File(DefaultReplaySaver.DIR, replay + ".replay").lastModified());
        }

        if (ReplaySaver.replaySaver instanceof DatabaseReplaySaver) {
            return fromMillis(DatabaseReplaySaver.replayCache.get(replay).getTime());
        }

        return LocalDate.now();
    }

    private static LocalDate fromMillis(long millis) {
        return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate();

    }
}

