package me.hackerguardian.main.Checkings;

import me.hackerguardian.main.HackerGuardian;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;

/**
 * @author JumpWatch on 17-04-2024
 * @Project HackerGuardian
 * v1.0.0
 */
public class ExemptHandler extends MiniHandlerHG{
    private final Map<Player, Long> exemptMap = new HashMap<>();
    private final Map<Player, Long> exemptBlockMap = new HashMap<>();
    private final Map<Player, String> exemptReasonMap = new HashMap<>();

    public ExemptHandler(HackerGuardian plugin) {
        super("Exemption Handler", plugin);
    }
    public boolean isExemptBlock(Player player) {

        if (exemptBlockMap.containsKey(player)) {
            if (System.currentTimeMillis() < exemptBlockMap.get(player)) {
                return true;
            } else {
                exemptBlockMap.remove(player);
                return false;
            }
        }
        return false;
    }

    public void removeExemptionBlock(Player player) {
        exemptBlockMap.remove(player);
    }

    public void addExemptionBlock(Player player, long durationMs) {
        if (isExempt(player)) {
            removeExemption(player);
        }
        exemptBlockMap.put(player, System.currentTimeMillis() + durationMs);
    }

    public boolean isExempt(Player player) {
        if (exemptMap.containsKey(player)) {
            if (System.currentTimeMillis() < exemptMap.get(player) && !isExemptBlock(player)) {
                return true;
            } else {
                exemptMap.remove(player);
                exemptReasonMap.remove(player);
                return false;
            }
        }
        return false;
    }

    public void removeExemption(Player player) {
        exemptMap.remove(player);
        exemptReasonMap.remove(player);
    }

    public String getExemptReason(Player player) {
        return exemptReasonMap.getOrDefault(player, "unknown/notexempt");
    }

    public void addExemption(Player player, long durationMs, String reason) {
        if (isExemptBlock(player)) {
            return;
        }
        if (isExempt(player)) {
            removeExemption(player);
        }
        exemptReasonMap.put(player, reason);
        exemptMap.put(player, System.currentTimeMillis() + durationMs);
    }
}