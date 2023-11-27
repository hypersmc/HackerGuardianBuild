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
    private static final TrainingData trainingData = new TrainingData(main.calculateTotalInputs(), 1);

    @EventHandler
    public void onPlayerChat(PlayerChatEvent event) {
        if (main.learning) {
            // Learning Mode: Collect input data and add it to the training data set
            String message = event.getMessage();
            boolean isCheating = isPlayerCheating(message);

            // Convert message to input values
            double[] input = new double[message.length()];
            for (int i = 0; i < message.length(); i++) {
                input[i] = (double) message.charAt(i);
            }

            // Add input and output to training data set
            trainingData.addRow(input, new double[]{isCheating ? 1 : 0});
        } else {
            // Prediction Mode: Use the trained AI for prediction or perform certain actions
            // Perform actions or predictions based on the learned knowledge
            // ...
            if (main.ai != null){
                String message = event.getMessage();

                // Convert message to input values (similar to how it was done in learning mode)
                double[] input = new double[message.length()];
                for (int i = 0; i < message.length(); i++) {
                    input[i] = (double) message.charAt(i);
                }

                // Perform prediction using the trained AI
                double[] prediction = main.ai.predict(input);

                // Interpret the prediction result
                if (prediction[0] >= 0.5) {
                    event.getPlayer().sendMessage("Cheating behavior detected!");
                } else {
                    event.getPlayer().sendMessage("No cheating detected.");
                }
            }else{

            }
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
    public static void setTrainingData(TrainingData data, String name){
         trainingData.setDataSet(data.getDataSet());
        main.text.SendconsoleTextWsp("Data for " + name + " is now added!");

    }

    public static void trainTrainingData(){
        main.ai.train(trainingData.getDataSet());

    }

}
