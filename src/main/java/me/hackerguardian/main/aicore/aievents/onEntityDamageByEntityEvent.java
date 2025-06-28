package me.hackerguardian.main.aicore.aievents;

import me.hackerguardian.main.HackerGuardian;
import me.hackerguardian.main.aicore.TrainingData;
import me.hackerguardian.main.utils.Tps;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

/**
 * @author JumpWatch on 01-05-2024
 * @Project HackerGuardian
 * v1.0.0
 */
public class onEntityDamageByEntityEvent implements Listener {
    static HackerGuardian main = HackerGuardian.getInstance();
    private static final TrainingData trainingData = new TrainingData(7, 1);
    @EventHandler
    public void onEntityDamageByEntityEvent(EntityDamageByEntityEvent event){
        if (main.learning){
            if (!(event.getDamager() instanceof Player)) {
                return;
            }
            double range = event.getEntity().getLocation().distance(event.getDamager().getLocation());
            String rf = range + "";
            try {
                range = Double.parseDouble(rf.substring(0,4));
            } catch (Exception exception) {}

            boolean isCheating = isPlayerCheating(range, (Player) event.getDamager());
            Location locationd = event.getDamager().getLocation();
            Location locationa = event.getEntity().getLocation();
            double[] input = new double[7];
            input[0] = range;
            input[1] = locationd.getX();
            input[2] = locationd.getY();
            input[3] = locationd.getZ();
            input[4] = locationa.getX();
            input[5] = locationa.getY();
            input[6] = locationa.getZ();

            trainingData.addRow(input, new double[]{isCheating ? 1 : 0});
        }
    }
    private boolean isPlayerCheating(double range, Player u) {
        if ((range > 3.48 && !u.getPlayer().isSprinting()) || (Tps.getTPS() < 10) || range > 4.98 && u.getPlayer().isSprinting()) {
            return true;
        }
        return false;
    }
}
