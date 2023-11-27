package me.hackerguardian.main.events;

import me.hackerguardian.main.aicore.TrainingData;
import me.hackerguardian.main.hackerguardian;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionData;
import org.bukkit.potion.PotionType;

import java.util.Objects;

/**
 * @author JumpWatch on 07-04-2023
 * @Project HackerGuardian
 * v1.0.0
 */
public class onPlayerItemConsume implements Listener {
    static hackerguardian main = hackerguardian.getInstance();
    private static final TrainingData trainingData = new TrainingData(main.calculateTotalInputs(), 1);

    @EventHandler
    public void onPlayerItemConsume(PlayerItemConsumeEvent event) {
        if (main.learning) {
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
            input[11] = itemType.getMaxDurability() / 1562.0; // Normalize item durability to range [0, 1]
        }
    }
    private boolean isPlayerCheating(Player player) {
        // Check if the player's movement speed is unusually high
        boolean highSpeed = player.getWalkSpeed() > 0.3 || player.getFlySpeed() > 0.3;

        // Check for abnormal health regeneration
        boolean abnormalHealthRegen = player.getHealth() > player.getMaxHealth();

        // Check for abnormal consumption of specific items (e.g., suspicious potions)
        boolean suspiciousConsumption = checkSuspiciousConsumption(player);

        // Combine checks to identify potential cheating
        return highSpeed || abnormalHealthRegen || suspiciousConsumption;
    }

    private boolean checkSuspiciousConsumption(Player player) {
        // Implement checks for specific items consumed that might indicate cheating
        ItemStack item = player.getItemInHand(); // Assuming the consumed item is in the player's hand

        // Example: Check for suspicious potion consumption by checking potion types
        if (item != null && item.getType() == Material.POTION) {
            PotionMeta potionMeta = (PotionMeta) item.getItemMeta();
            if (potionMeta != null) {
                PotionData potionData = potionMeta.getBasePotionData();
                PotionType potionType = potionData.getType();
                // Check if the consumed potion type is suspicious (e.g., strength, speed, invisibility)
                return potionType == PotionType.STRENGTH || potionType == PotionType.SPEED || potionType == PotionType.INVISIBILITY;
            }
        }

        // Add more checks for other suspicious item consumption here if needed

        return false; // Return false if no suspicious consumption detected
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
