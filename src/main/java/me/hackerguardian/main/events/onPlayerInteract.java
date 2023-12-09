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
import org.neuroph.core.data.DataSet;

import java.util.Objects;

/**
 * @author JumpWatch on 07-04-2023
 * @Project HackerGuardian
 * v1.0.0
 */
public class onPlayerInteract implements Listener {
    static hackerguardian main = hackerguardian.getInstance();
    private static final TrainingData trainingData = new TrainingData(main.calculateTotalInputs(), 1);
    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (main.learning) {
            Player player = event.getPlayer();
            Block block = event.getClickedBlock();
            if (block != null) {
                Vector direction = player.getLocation().getDirection().normalize();

                // Calculate input values
                double[] input = new double[6];
                input[0] = block.getX();
                input[1] = block.getY();
                input[2] = block.getZ();
                input[3] = direction.getX();
                input[4] = direction.getY();
                input[5] = direction.getZ();

                // Get player's cheat status (example)
                boolean isCheating = isPlayerCheating(player, event);
                // Add input and output to training data set
                trainingData.addRow(input, new double[]{isCheating ? 1 : 0});


            }
        }
    }

    private boolean isPlayerCheating(Player player, PlayerInteractEvent event) {
        // Check if the player's movement speed is unusually high
        boolean highSpeed = player.getWalkSpeed() > 0.3 || player.getFlySpeed() > 0.3;

        // Check for specific interactions that might indicate cheating
        boolean suspiciousInteractions = false;

        // Example: Check for interacting with specific block types (e.g., bedrock, barrier, etc.)
        if (event.getClickedBlock() != null) {
            Material clickedBlockType = event.getClickedBlock().getType();
            suspiciousInteractions = clickedBlockType == Material.BEDROCK || clickedBlockType == Material.BARRIER;
        }

        // Combine checks to identify potential cheating
        return highSpeed || suspiciousInteractions;
    }

    public static TrainingData getTrainingData(){
        return trainingData;
    }
    public static void setTrainingData(DataSet data, String name){
        trainingData.setDataSet(data);
        main.text.SendconsoleTextWsp("Data for " + name + " is now added!");
        main.ai.train(trainingData.getDataSet());
        main.text.SendconsoleTextWsp("Data for " + name + " is has been trained!");
    }

}
