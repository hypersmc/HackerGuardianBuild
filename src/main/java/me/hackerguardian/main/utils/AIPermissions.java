package me.hackerguardian.main.utils;

import me.hackerguardian.main.HackerGuardian;

import java.io.File;

/**
 * @author JumpWatch on 30-11-2023
 * @Project HackerGuardian
 * v1.0.0
 */
public class AIPermissions {
    static textHandling text = new textHandling();
    public static void setLearningFilesPermissions(HackerGuardian main){
        String[] fileNames = {
                "onPlayerChat.bin",
                "onPlayerInteract.bin",
                "onPlayerItemConsume.bin",
                "onPlayerMove.bin",
                "onPlayerToggleFlight.bin",
                "onPlayerToggleSneak.bin"
        };
        try {
            text.SendconsoleTextWsp("Trying to set permissions for AI data-core files");
            for (String fileName : fileNames) {
                File file = new File(main.getDataFolder() + "/datacore/" + fileName);
                file.setExecutable(true, false);
                file.setReadable(true, false);
                file.setWritable(true, false);
            }
            text.SendconsoleTextWsp("Permissions for AI data-core files successful");
        } catch (Exception e) {
            ErrorHandler.handleGenericException(e, "Could not set full WRE permissions on files");

        }
    }
}
