package me.hackerguardian.main.events;

import me.hackerguardian.main.aicore.TrainingData;
import me.hackerguardian.main.hackerguardian;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChatEvent;

import java.util.Objects;

/**
 * @author JumpWatch on 07-04-2023
 * @Project HackerGuardian
 * v1.0.0
 */
public class onPlayerChat implements Listener {
    static hackerguardian main = hackerguardian.getInstance();
    private static final TrainingData trainingData = new TrainingData(13, 1);

    @EventHandler
    public void onPlayerChat(PlayerChatEvent event) {
        String message = event.getMessage();
        boolean isCheating = isPlayerCheating(message);

        // Convert message to input values
        double[] input = new double[message.length()];
        for (int i = 0; i < message.length(); i++) {
            input[i] = (double) message.charAt(i);
        }
        // Add input and output to training data set
        trainingData.addRow(input, new double[] {isCheating ? 1 : 0});
    }

    private boolean isPlayerCheating(String message) {
        // Example method for determining player cheat status from chat message
        // Replace with your own implementation
        return message.matches(".*\\d.*");
    }
    public static TrainingData getTrainingData(){
        return trainingData;
    }
    public static void setTrainingData(TrainingData data, String name){
        if (Objects.equals(name, "onPlayerChat")) {
            trainingData.setDataSet(data.getDataSet());
        }
    }

    public static void trainTrainingData(){
        main.ai.train(trainingData.getDataSet());
    }

}
