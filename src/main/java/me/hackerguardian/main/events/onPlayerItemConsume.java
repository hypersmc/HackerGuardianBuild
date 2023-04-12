package me.hackerguardian.main.events;

import me.hackerguardian.main.aicore.TrainingData;
import me.hackerguardian.main.hackerguardian;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;

import java.util.Objects;

/**
 * @author JumpWatch on 07-04-2023
 * @Project HackerGuardian
 * v1.0.0
 */
public class onPlayerItemConsume implements Listener {
    static hackerguardian main = hackerguardian.getInstance();
    private static final TrainingData trainingData = new TrainingData(13, 1);

    @EventHandler
    public void onPlayerItemConsume(PlayerItemConsumeEvent event) {
        // Get the player and the consumed item type
        Player player = event.getPlayer();
        Material itemType = event.getItem().getType();

        // Calculate input values
        double[] input = new double[13];
        input[0] = player.getHealth() / 20.0; // Normalize health to range [0, 1]
        input[1] = player.getFoodLevel() / 20.0; // Normalize food level to range [0, 1]
        input[2] = player.getSaturation() / 20.0; // Normalize saturation to range [0, 1]
        input[3] = player.getLocation().getX() / 1000.0; // Normalize X coordinate to range [-1, 1]
        input[4] = player.getLocation().getY() / 1000.0; // Normalize Y coordinate to range [-1, 1]
        input[5] = player.getLocation().getZ() / 1000.0; // Normalize Z coordinate to range [-1, 1]
        input[6] = player.getLocation().getPitch() / 90.0; // Normalize pitch to range [-1, 1]
        input[7] = player.getLocation().getYaw() / 180.0; // Normalize yaw to range [-1, 1]
        input[8] = player.isOnGround() ? 1 : 0; // Is on ground (binary)
        input[9] = player.isSprinting() ? 1 : 0; // Is sprinting (binary)
        input[10] = player.isBlocking() ? 1 : 0; // Is blocking (binary)
        input[11] = itemType.getId() / 32000.0; // Normalize item ID to range [0, 1]
        input[12] = itemType.getMaxDurability() / 1562.0; // Normalize item durability to range [0, 1]
        
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
        if (Objects.equals(name, "onPlayerItemConsume")) {
            trainingData.setDataSet(data.getDataSet());
        }
    }

}
