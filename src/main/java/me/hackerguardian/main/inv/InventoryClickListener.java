package me.hackerguardian.main.inv;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

public class InventoryClickListener implements Listener {

    private final infoManager infoManager;
    public InventoryClickListener(infoManager infoManager) {
        this.infoManager = infoManager;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getView().getTitle().contains("Info on")) {
            event.setCancelled(true);
            String target = parseTargetName(event.getView().getTitle());

            ItemStack item = event.getCurrentItem();
            if (item == null || item.getType() == Material.AIR) {
                return;
            }

            Player player = (Player) event.getWhoClicked();
            info infoInstance = infoManager.getInfo(player, target);
            if (item.getItemMeta().getDisplayName().contains("Next page")) {
                infoInstance.nextPage(player);
            } else if (item.getItemMeta().getDisplayName().contains("Go back to page")) {
                infoInstance.previousPage(player);
            } else if (item.getType() == Material.BARRIER) {
                infoManager.removeinfo(player);
                player.closeInventory();
            }
        }
    }

    private String parseTargetName(String inventoryTitle) {
        String startSeparator = "§6§lInfo on §r§8";
        String endSeparator = " §r§6§lPage";
        int startIndex = inventoryTitle.indexOf(startSeparator);
        int endIndex = inventoryTitle.indexOf(endSeparator);
        if (startIndex != -1 && endIndex != -1) {
            return inventoryTitle.substring(startIndex + startSeparator.length(), endIndex);
        }
        return "";
    }
}
