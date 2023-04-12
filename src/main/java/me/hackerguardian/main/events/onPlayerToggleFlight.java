package me.hackerguardian.main.events;

import me.hackerguardian.main.aicore.TrainingData;
import me.hackerguardian.main.hackerguardian;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerToggleFlightEvent;

import java.util.Objects;

/**
 * @author JumpWatch on 07-04-2023
 * @Project HackerGuardian
 * v1.0.0
 */
public class onPlayerToggleFlight implements Listener {
    static hackerguardian main = hackerguardian.getInstance();
    private static final TrainingData trainingData = new TrainingData(3, 1);

    @EventHandler
    public void onPlayerToggleFlight(PlayerToggleFlightEvent event){
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
        trainingData.addRow(input, new double[] {isCheating ? 1 : 0});

    }
    private boolean isPlayerCheating(Player player) {
        // Example method for determining player cheat status
        // Replace with your own implementation
        return player.getFlySpeed() > 0.3;
    }
    public static TrainingData getTrainingData(){
        main.ai.train(trainingData.getDataSet());
        return trainingData;
    }
    public static void setTrainingData(TrainingData data, String name){
        if (Objects.equals(name, "onPlayerToggleFlight")) {
            trainingData.setDataSet(data.getDataSet());
        }
    }
}
