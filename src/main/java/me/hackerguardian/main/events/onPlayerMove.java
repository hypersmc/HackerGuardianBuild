package me.hackerguardian.main.events;

import me.hackerguardian.main.aicore.TrainingData;
import me.hackerguardian.main.hackerguardian;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.util.Vector;

import java.util.Objects;

/**
 * @author JumpWatch on 04-04-2023
 * @Project HackerGuardian
 * v1.0.0
 */
public class onPlayerMove implements Listener {
    static hackerguardian main = hackerguardian.getInstance();
    private static final TrainingData trainingData = new TrainingData(5, 1);


    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event){
        if (main.learning) {
            // Get player's movement and look data
            Player player = event.getPlayer();
            Location from = event.getFrom();
            Location to = event.getTo();
            Vector direction = to.clone().subtract(from.clone()).toVector().normalize();
            Vector look = player.getLocation().getDirection().normalize();

            // Calculate input values
            double[] input = new double[5];
            input[0] = direction.getX();
            input[1] = direction.getY();
            input[2] = direction.getZ();
            input[3] = look.getX();
            input[4] = look.getZ();

            // Get player's cheat status (example)
            boolean isCheating = isPlayerCheating(player);

            // Add input and output to training data set
            trainingData.addRow(input, new double[]{isCheating ? 1 : 0});
        }

    }
    private boolean isPlayerCheating(Player player) {
        // Check if the player's movement speed is unusually high
        boolean highSpeed = player.getWalkSpeed() > 1.5 || player.getFlySpeed() > 2;

        // Check for abnormal positions between previous and current location
        Location from = player.getLocation();
        Location to = player.getLocation();
        double distance = from.clone().distance(to); // Calculate the distance between locations
        boolean abnormalPositionChange = distance > 0.5; // Adjust distance threshold as needed

        // Check for abnormal health regeneration
        boolean abnormalHealthRegen = player.getHealth() > player.getMaxHealth();

        // Combine checks to identify potential cheating
        return highSpeed || abnormalPositionChange || abnormalHealthRegen;
    }

    public static TrainingData getTrainingData(){
        main.ai.train(trainingData.getDataSet());
        return trainingData;
    }
    public static void setTrainingData(TrainingData data, String name){
        trainingData.setDataSet(data.getDataSet());
        main.text.SendconsoleTextWsp("Data for " + name + " is now added!");

    }
}
