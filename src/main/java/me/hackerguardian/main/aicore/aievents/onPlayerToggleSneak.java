package me.hackerguardian.main.aicore.aievents;

import me.hackerguardian.main.HackerGuardian;
import me.hackerguardian.main.aicore.TrainingData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.neuroph.core.data.DataSet;

/**
 * @author JumpWatch on 07-04-2023
 * @Project HackerGuardian
 * v1.0.0
 */
public class onPlayerToggleSneak implements Listener {
    static HackerGuardian main = HackerGuardian.getInstance();
    private static final TrainingData trainingData = new TrainingData(5, 1);

    @EventHandler
    public void onPlayerToggleSneak(PlayerToggleSneakEvent event) {
        if (main.learning) {
            Player player = event.getPlayer();
            boolean isSneaking = event.isSneaking();

            double[] input = new double[5]; // Define an array of size 5 (maximum input size)

            // Set the first element based on the sneaking status
            input[0] = isSneaking ? 1 : 0;

            // Rest of the elements will be zeros
            for (int i = 1; i < input.length; i++) {
                input[i] = 0; // Padding with zeros for the rest of the inputs
            }

            // Get player's cheat status (example)
            boolean isCheating = isPlayerCheating(player, isSneaking);

            // Add input and output to training data set
            trainingData.addRow(input, new double[]{isCheating ? 1 : 0});


        }

    }

    private boolean isPlayerCheating(Player player, boolean isSneaking) {
        // Check if the player is sneaking while not on the ground
        boolean sneakingNotOnGround = isSneaking && !player.isOnGround();

        // Check for abnormal velocity (indicating potential hacks)
        boolean abnormalVelocity = player.getVelocity().lengthSquared() > 0.5; // Adjust threshold as needed

        // Combine checks to identify potential cheating
        return sneakingNotOnGround || abnormalVelocity;
    }

    public static TrainingData getTrainingData(){
        return trainingData;
    }
    public static void setTrainingData(DataSet data, String name){
        trainingData.setDataSet(data);
        main.text.SendconsoleTextWsp("Data for " + name + " is now added!");
        main.ai2.train(trainingData.getDataSet());
        main.text.SendconsoleTextWsp("Data for " + name + " is has been trained!");
    }

}
