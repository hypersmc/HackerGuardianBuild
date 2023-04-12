package me.hackerguardian.main.Recording.replaying;

import java.util.HashMap;

import me.hackerguardian.main.Recording.filesys.ItemConfig;
import me.hackerguardian.main.Recording.filesys.ItemConfigOption;
import me.hackerguardian.main.Recording.filesys.ItemConfigType;
import me.hackerguardian.main.utils.recordinghandler.ReflectionHelper;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import com.comphenix.packetwrapper.WrapperPlayServerTitle;
import com.comphenix.protocol.wrappers.EnumWrappers.TitleAction;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import me.hackerguardian.main.utils.recordinghandler.VersionUtil;
import me.hackerguardian.main.utils.recordinghandler.VersionUtil.VersionEnum;

public class ReplayHelper {

    public static HashMap<String, Replayer> replaySessions = new HashMap<String, Replayer>();

    public static ItemStack createItem(ItemConfigOption option) {
        String displayName = ChatColor.translateAlternateColorCodes('&', option.getName());

        ItemStack stack = createItem(option.getMaterial(), displayName, option.getData());


        if (option.getOwner() != null && stack.getItemMeta() instanceof SkullMeta) {

            SkullMeta meta = (SkullMeta) stack.getItemMeta();
            meta.setOwner(option.getOwner());
            meta.setDisplayName(displayName);
            stack.setItemMeta(meta);
        }

        return stack;
    }

    public static ItemStack createItem(Material material, String name, int data) {
        ItemStack stack = new ItemStack(material, 1, (byte) data);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(name);
        stack.setItemMeta(meta);

        return stack;
    }

    public static ItemStack getPauseItem() {
        return createItem(ItemConfig.getItem(ItemConfigType.PAUSE));
    }

    public static ItemStack getResumeItem() {
        return createItem(ItemConfig.getItem(ItemConfigType.RESUME));
    }

    public static void createTeleporter(Player player, Replayer replayer) {
        Inventory inv = Bukkit.createInventory(null, ((int)replayer.getNPCList().size() / 9) > 0 ? ((int)Math.floor(replayer.getNPCList().size() / 9)) * 9 : 9 , "§7Teleporter");

        int index = 0;

        for (String name : replayer.getNPCList().keySet()) {
            ItemStack stack = new ItemStack(Material.PLAYER_HEAD,1,(short)3);
            SkullMeta meta = (SkullMeta) stack.getItemMeta();
            meta.setDisplayName("§6" + name);
            meta.setOwner(name);
            stack.setItemMeta(meta);

            inv.setItem(index, stack);

            index++;
        }

        player.openInventory(inv);
    }


    public static void sendTitle(Player player, String title, String subTitle, int stay) {
        if (VersionUtil.isAbove(VersionEnum.V1_17)) {
            ReflectionHelper.getInstance().sendTitle(player, title, subTitle, 0, stay, 20);
            return;
        }

        WrapperPlayServerTitle packet = new WrapperPlayServerTitle();
        packet.setAction(TitleAction.TIMES);
        packet.setStay(stay);
        packet.setFadeIn(0);
        packet.setFadeOut(20);

        packet.sendPacket(player);

        if (subTitle != null) {
            WrapperPlayServerTitle sub = new WrapperPlayServerTitle();
            sub.setAction(TitleAction.SUBTITLE);
            sub.setTitle(WrappedChatComponent.fromText(subTitle));

            sub.sendPacket(player);
        }

        WrapperPlayServerTitle titlePacket = new WrapperPlayServerTitle();
        titlePacket.setAction(TitleAction.TITLE);
        titlePacket.setTitle(title != null ? WrappedChatComponent.fromText(title) : WrappedChatComponent.fromText(""));

        titlePacket.sendPacket(player);


    }

    public static boolean isInRange(Location loc1, Location loc2) {
        return loc1.getWorld().getName().equals(loc2.getWorld().getName()) && (loc1.distance(loc2) <= 48D);
    }

}

