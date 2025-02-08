package me.hackerguardian.main.Checkings;

import me.hackerguardian.main.HackerGuardian;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.spigotmc.event.entity.EntityDismountEvent;


import javax.naming.OperationNotSupportedException;

/**
 * @author JumpWatch on 17-04-2024
 * @Project HackerGuardian
 * v1.0.0
 */
public class DamageHandler extends MiniHandlerHG {

    public DamageHandler(HackerGuardian plugin) {
        super("Damage Handler", plugin);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player) {
            Player player = (Player) event.getEntity();
            HackerGuardian.getInstance().getExemptHandler().addExemption(player, 845, "damaged");
        }
    }

    @EventHandler
    public void onEject(EntityDismountEvent event) {
        if (event.getEntity() instanceof Player) {
            Player player = (Player) event.getEntity();
            HackerGuardian.getInstance().getExemptHandler().addExemption(player, 500, "vehicle exempt");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) throws OperationNotSupportedException {

        if (event.getDamager() instanceof Player) {
            Player p = (Player) event.getDamager();
//            for (HGCheck c : this.getPlugin().All_Checks) {
//                if (c.getEventCall().equals(event.getEventName())
//                        || c.getSecondaryEventCall().equals(event.getEventName())) {
//                    HGCheckResult result = c.performCheck(this.getPlugin().getUser(p), event);
//                    String result2 = c.performCheck(this.getPlugin().getUser(p), event).getDesc();
//                    if (!result.passed()) {
//                        this.getPlugin().addSuspicion(p, result.getCheckName(), result2);
//                    }
//                }
//            }
        }
    }
}