package me.hackerguardian.main.ui.info;

import me.arcaniax.hdb.api.HeadDatabaseAPI;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.material.MaterialData;

/**
 * @author JumpWatch on 19-05-2024
 * @Project HackerGuardian
 * v1.0.0
 */
public class info {
    public final Inventory inv;
    public info(String playername){
        inv = Bukkit.createInventory(null, 54, ChatColor.translateAlternateColorCodes('&', "&e&lInfo about &r&6" + playername));
    }
    public void open(Player player){
        init(player);
    }

    private void init(Player player) {
        MaterialData grayStainedGlass = new MaterialData(Material.IRON_BARS);
        grayStainedGlass.setData((byte) 7);
        ItemStack glasspane = grayStainedGlass.toItemStack(1);
        ItemMeta meta = glasspane.getItemMeta();
        meta.setDisplayName(ChatColor.RESET.toString());
        glasspane.setItemMeta(meta);
        HeadDatabaseAPI api = new HeadDatabaseAPI();
        ItemStack right = api.getItemHead("7826");
        ItemStack left = api.getItemHead("7827");
        ItemStack back = new ItemStack(Material.BARRIER);
        inv.setItem(45, left);
        inv.setItem(53, right);
        inv.setItem(49, back);
        inv.setItem(46, glasspane);
        inv.setItem(47, glasspane);
        inv.setItem(48, glasspane);
        inv.setItem(50, glasspane);
        inv.setItem(51, glasspane);
        inv.setItem(52, glasspane);
        player.openInventory(inv);
    }
}
