package me.hackerguardian.main.utils;


import org.apache.commons.io.FileUtils;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.Objects;
/**
 * @author JumpWatch on 08-04-2023
 * @Project HackerGuardian
 * v1.0.0
 */
public class FileUtil {

    // Replace '\' in url with separator of local system (avoid path issue when running on non-Windows systems)
    public static String checkSeparator(String URL) {
        return URL.replace("\\", File.separator);
    }

    // Mkdir with ease
    @SuppressWarnings("ResultOfMethodCallIgnored")
    public static void createDirectoryIfAbsent(File dataFolder, String directoryName) {
        new File(dataFolder, checkSeparator(directoryName)).mkdirs();
    }

    // Save resource file to destination
    public static void saveResourceIfAbsent(Plugin plugin, String fileName, String releasePath) throws IOException {
        File toBeReleased = new File(plugin.getDataFolder(), checkSeparator(releasePath));
        if (!toBeReleased.exists())
            FileUtils.copyInputStreamToFile(Objects.requireNonNull(plugin.getResource(fileName)), toBeReleased);
    }
}