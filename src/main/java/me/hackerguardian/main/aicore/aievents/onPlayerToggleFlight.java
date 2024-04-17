package me.hackerguardian.main.aicore.aievents;

import me.hackerguardian.main.HackerGuardian;
import me.hackerguardian.main.aicore.TrainingData;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.neuroph.core.data.DataSet;

/**
 * @author JumpWatch on 07-04-2023
 * @Project HackerGuardian
 * v1.0.0
 */
public class onPlayerToggleFlight implements Listener {
    static HackerGuardian main = HackerGuardian.getInstance();

    private static final TrainingData trainingData = new TrainingData(3, 1);

    @EventHandler
    public void onPlayerToggleFlight(PlayerToggleFlightEvent event){
        if (main.learning) {
            // Get player data
            Player player = event.getPlayer();
            Location location = player.getLocation();
            // Calculate input values
            double[] input = new double[3];
            input[0] = location.getX();
            input[1] = location.getY();
            input[2] = location.getZ();
            // Get player's cheat status (example)
            boolean isCheating = isPlayerCheating(player);
            // Add input and output to training data set
            trainingData.addRow(input, new double[]{isCheating ? 1 : 0});


        }

    }
    private boolean isPlayerCheating(Player player) {
        // Check if the player's flight speed is unusually high
        boolean highFlightSpeed = player.getFlySpeed() > 0.3;

        // Check if the player is in creative mode (creative mode might allow flight without hacks)
        boolean isCreative = player.getGameMode() == GameMode.CREATIVE;

        // Check if the player's total experience is abnormally high (indicative of potential cheating)
        boolean abnormalExperience = player.getTotalExperience() > 10000; // Adjust the threshold as needed

        // Combine checks to identify potential cheating during flight
        return highFlightSpeed || !isCreative || abnormalExperience;
    }

    public static TrainingData getTrainingData(){
        return trainingData;
    }
    public static void setTrainingData(DataSet data, String name){
        trainingData.setDataSet(data);
        main.text.SendconsoleTextWsp("Data for " + name + " is now added!");
        main.ai6.train(trainingData.getDataSet());
        main.text.SendconsoleTextWsp("Data for " + name + " is has been trained!");
    }
}
