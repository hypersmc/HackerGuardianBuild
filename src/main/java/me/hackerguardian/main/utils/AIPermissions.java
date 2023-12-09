package me.hackerguardian.main.utils;

import me.hackerguardian.main.hackerguardian;

import java.io.File;

/**
 * @author JumpWatch on 30-11-2023
 * @Project HackerGuardian
 * v1.0.0
 */
public class AIPermissions {

    public static void setLearningFilesPermissions(hackerguardian main){
        String[] fileNames = {
                "onPlayerChat.bin",
                "onPlayerInteract.bin",
                "onPlayerItemConsume.bin",
                "onPlayerMove.bin",
                "onPlayerToggleFlight.bin",
                "onPlayerToggleSneak.bin"
        };
        try {
            main.text.SendconsoleTextWsp("Trying to set permissions for AI data-core files");
            for (String fileName : fileNames) {
                File file = new File(main.getDataFolder() + "/datacore/" + fileName);
                file.setExecutable(true, false);
                file.setReadable(true, false);
                file.setWritable(true, false);
            }
            main.text.SendconsoleTextWsp("Permissions for AI data-core files successful");
        } catch (Exception e) {
            main.text.SendconsoleTextWsp("Was not able to set full RWE permissions for files!");
            if(main.getConfig().getBoolean("debug")) e.printStackTrace();
        }
    }
}
