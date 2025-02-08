package me.hackerguardian.main.Checkings;

import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;


/**
 * @author JumpWatch on 28-02-2024
 * @Project HackerGuardian
 * v1.0.0
 */
public class UtilPlayer {

    public static boolean isFlying(Player p) {
        return p.isFlying() || p.isGliding() || p.getInventory().getChestplate() != null && p.getInventory().getChestplate().getType() == Material.ELYTRA;
    }

    public static boolean isSwimming(Player p) {
        if (p.getLocation().getBlock().getType() == Material.WATER)
            return true;
        if (p.getLocation().getBlock().getRelative(BlockFace.DOWN).getType() == Material.WATER)
            return true;
        if (p.isSwimming())
            return true;
        if (p.getLocation().getBlock().getRelative(BlockFace.UP).getType() == Material.WATER)
            return true;

        return false;
    }

    public static boolean hasEfficiency(Player player) {
        if (player.hasPotionEffect(PotionEffectType.FAST_DIGGING)
                && player.getPotionEffect(PotionEffectType.FAST_DIGGING).getAmplifier() > 3) {
            return true;
        }
        if (player.getInventory().getItemInMainHand() != null) {
            if (player.getInventory().getItemInMainHand().containsEnchantment(Enchantment.DIG_SPEED)) {
                return true;
            }
        }
        if (player.getInventory().getItemInOffHand() != null) {
            if (player.getInventory().getItemInOffHand().containsEnchantment(Enchantment.DIG_SPEED)) {
                return true;
            }
        }
        return false;
    }
}