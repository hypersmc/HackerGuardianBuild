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
        // Get player's movement and look data
        Player player = event.getPlayer();
        Location from = event.getFrom();
        Location to = event.getTo();
        Vector direction = to.subtract(from).toVector().normalize();
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
        if (Objects.equals(name, "onPlayerMove")) {
            trainingData.setDataSet(data.getDataSet());
        }
    }
}
