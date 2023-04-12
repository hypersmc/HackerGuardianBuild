package me.hackerguardian.main.utils.recordinghandler;
import me.hackerguardian.main.hackerguardian;

public class LogUtils {

    public static void log(String message){
        hackerguardian.getInstance().getLogger().info(message);
    }

}
