package me.hackerguardian.main.inv;

import ca.tweetzy.skulls.Skulls;
import ca.tweetzy.skulls.api.SkullsAPI;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.material.MaterialData;

import java.util.List;

public class info implements Listener {
    private static final int ITEMS_PER_PAGE = 45;
    private List<String> entries;
    protected int currentPage = 1;
    protected int totalPages;
    protected final String target;
    public Inventory inv = null;
    protected Player p;
    protected OfflinePlayer op;

    public info(String target) {
        this.target = target;
        Player p1 = Bukkit.getPlayer(target);
        if (p1 != null && p1.isOnline()) {
            p = p1;
        } else {
            op = Bukkit.getOfflinePlayer(target);
        }
        totalPages = 2;
        updateInventoryTitle();
    }

    private void updateInventoryTitle() {
        inv = Bukkit.createInventory(null, 54, ChatColor.translateAlternateColorCodes('&',
                "&6&lInfo on &r&8" + target + " &r&6&lPage: &r&l" + currentPage + "/" + totalPages));
    }

    public void open(Player sender) {
        init(sender);
        sender.openInventory(inv);
    }

    private void init(Player sender) {
        MaterialData grayStainedGlass = new MaterialData(Material.GRAY_STAINED_GLASS_PANE);
        ItemStack glasspane = grayStainedGlass.toItemStack(1);
        ItemMeta meta = glasspane.getItemMeta();
        meta.setDisplayName(ChatColor.RESET.toString());
        glasspane.setItemMeta(meta);

        SkullsAPI api = Skulls.getAPI();
        ItemStack right = api.getSkullItem(7826);
        ItemMeta rightmeta = right.getItemMeta();
        rightmeta.setDisplayName(ChatColor.translateAlternateColorCodes('&',
                "&aNext page " + (currentPage + 1) + "/" + totalPages));
        right.setItemMeta(rightmeta);

        ItemStack left = api.getSkullItem(7827);
        ItemMeta leftmeta = left.getItemMeta();
        leftmeta.setDisplayName(ChatColor.translateAlternateColorCodes('&',
                "&aGo back to page " + (currentPage - 1) + "/" + totalPages));
        left.setItemMeta(leftmeta);

        ItemStack close = new ItemStack(Material.BARRIER);
        ItemMeta closemeta = close.getItemMeta();
        closemeta.setDisplayName(ChatColor.translateAlternateColorCodes('&', "&4Close"));
        close.setItemMeta(closemeta);

        inv.setItem(45, currentPage > 1 ? left : glasspane);
        inv.setItem(53, currentPage < totalPages ? right : glasspane);
        inv.setItem(49, close);
        inv.setItem(46, glasspane);
        inv.setItem(47, glasspane);
        inv.setItem(48, glasspane);
        inv.setItem(50, glasspane);
        inv.setItem(51, glasspane);
        inv.setItem(52, glasspane);
        sender.openInventory(inv);
    }

    public void nextPage(Player sender) {
        if (currentPage < totalPages) {
            currentPage++;
            updateInventoryTitle();
            init(sender);
        }
    }

    public void previousPage(Player sender) {
        if (currentPage > 1) {
            currentPage--;
            updateInventoryTitle();
            init(sender);
        }
    }
}
