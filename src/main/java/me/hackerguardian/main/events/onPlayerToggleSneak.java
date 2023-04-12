package me.hackerguardian.main.events;

import me.hackerguardian.main.aicore.TrainingData;
import me.hackerguardian.main.hackerguardian;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerToggleSneakEvent;

import java.util.Objects;

/**
 * @author JumpWatch on 07-04-2023
 * @Project HackerGuardian
 * v1.0.0
 */
public class onPlayerToggleSneak implements Listener {
    static hackerguardian main = hackerguardian.getInstance();
    private static final TrainingData trainingData = new TrainingData(2, 1);

    @EventHandler
    public void onPlayerToggleSneak(PlayerToggleSneakEvent event) {
        Player player = event.getPlayer();
        boolean isSneaking = event.isSneaking();

        // Calculate input values
        double[] input = new double[1];
        input[0] = isSneaking ? 1 : 0;
        // Get player's cheat status (example)
        boolean isCheating = isPlayerCheating(player, isSneaking);
        // Add input and output to training data set
        trainingData.addRow(input, new double[] {isCheating ? 1 : 0});

    }

    private boolean isPlayerCheating(Player player, boolean isSneaking) {
        // Replace with your own implementation
        return isSneaking && player.isOnGround();
    }
    public static TrainingData getTrainingData(){
        main.ai.train(trainingData.getDataSet());
        return trainingData;
    }
    public static void setTrainingData(TrainingData data, String name){
        if (Objects.equals(name, "onPlayerToggleSneak")) {
            trainingData.setDataSet(data.getDataSet());
        }
    }


}
