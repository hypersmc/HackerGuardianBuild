package me.hackerguardian.main.aicore.aievents;

import me.hackerguardian.main.HackerGuardian;
import me.hackerguardian.main.aicore.TrainingData;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChatEvent;
import org.neuroph.core.data.DataSet;

/**
 * @author JumpWatch on 07-04-2023
 * @Project HackerGuardian
 * v1.0.0
 */
public class onPlayerChat implements Listener {
    static HackerGuardian main = HackerGuardian.getInstance();

    private static final int MAX_MESSAGE_LENGTH = 50; // Choose an appropriate maximum message length

    private static final TrainingData trainingData = new TrainingData(50, 1);

    @EventHandler
    public void onPlayerChat(PlayerChatEvent event) {
        if (main.learning) {
            // Learning Mode: Collect input data and add it to the training data set
            String message = event.getMessage();
            boolean isCheating = isPlayerCheating(message);

            // Convert message to input values
            double[] input = new double[MAX_MESSAGE_LENGTH];

            // Truncate or pad the message to fit the fixed input size
            int messageLength = Math.min(message.length(), MAX_MESSAGE_LENGTH);
            for (int i = 0; i < messageLength; i++) {
                input[i] = (double) message.charAt(i);
            }

            // If the message is shorter than the fixed size, pad the remaining slots
            if (messageLength < MAX_MESSAGE_LENGTH) {
                for (int i = messageLength; i < MAX_MESSAGE_LENGTH; i++) {
                    input[i] = 0.0; // You can choose any appropriate padding value
                }
            }

            // Add input and output to the training data set
            trainingData.addRow(input, new double[]{isCheating ? 1 : 0});

        }
    }

    private boolean isPlayerCheating(String message) {
        // Check for messages containing excessive numbers or repeated characters
        boolean containsExcessiveNumbers = message.replaceAll("[^\\d]", "").length() > 5; // Adjust threshold as needed
        boolean containsRepeatedCharacters = message.matches(".*(.)\\1{3,}.*"); // Detects 4 or more repeated characters

        // Check for messages indicating automated or suspicious behavior
        boolean suspiciousBehavior = message.matches(".*[A-Za-z]+[\\W_]+[A-Za-z]+.*"); // Detects unusual patterns

        // Combine checks to identify potential cheating or inappropriate messages
        return containsExcessiveNumbers || containsRepeatedCharacters || suspiciousBehavior;
    }

    public static TrainingData getTrainingData(){
        return trainingData;
    }
    public static void setTrainingData(DataSet data, String name){
        trainingData.setDataSet(data);
        main.text.SendconsoleTextWsp("Data for " + name + " is now added!");
        main.ai3.train(trainingData.getDataSet());
        main.text.SendconsoleTextWsp("Data for " + name + " has been trained!");
    }
    public static void trainTrainingData(){
        main.ai.train(trainingData.getDataSet());
    }

}
