package me.hackerguardian.main.events;

import me.hackerguardian.main.aicore.TrainingData;
import me.hackerguardian.main.hackerguardian;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.util.Vector;

import java.util.Objects;

/**
 * @author JumpWatch on 07-04-2023
 * @Project HackerGuardian
 * v1.0.0
 */
public class onPlayerInteract implements Listener {
    static hackerguardian main = hackerguardian.getInstance();
    private static final TrainingData trainingData = new TrainingData(5, 1);
    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        Block block = event.getClickedBlock();
        Material material = block.getType();
        Vector direction = player.getLocation().getDirection().normalize();

        // Calculate input values
        double[] input = new double[7];
        input[0] = block.getX();
        input[1] = block.getY();
        input[2] = block.getZ();
        input[3] = material.getId();
        input[4] = direction.getX();
        input[5] = direction.getY();
        input[6] = direction.getZ();

        // Get player's cheat status (example)
        boolean isCheating = isPlayerCheating(player);

        // Add input and output to training data set
        trainingData.addRow(input, new double[] {isCheating ? 1 : 0});
    }

    private boolean isPlayerCheating(Player player) {
        // Example method for determining player cheat status
        // Replace with your own implementation
        return player.getWalkSpeed() > 0.3 || player.getFlySpeed() > 0.3;
    }
    public static TrainingData getTrainingData(){
        main.ai.train(trainingData.getDataSet());
        return trainingData;
    }
    public static void setTrainingData(TrainingData data, String name){
        if (Objects.equals(name, "onPlayerInteract")) {
            trainingData.setDataSet(data.getDataSet());
        }
    }

}
