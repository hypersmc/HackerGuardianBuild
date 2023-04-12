package me.hackerguardian.main.Recording.filesys;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;

import me.hackerguardian.main.hackerguardian;
import me.hackerguardian.main.utils.recordinghandler.MaterialBridge;
import me.hackerguardian.main.utils.recordinghandler.VersionUtil;
import me.hackerguardian.main.utils.recordinghandler.VersionUtil.VersionEnum;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

public class ItemConfig {

    public static File file = new File(hackerguardian.getInstance().getDataFolder(), "items.yml");
    public static FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);

    public static HashMap<ItemConfigType, ItemConfigOption> items = new HashMap<>();


    public static void loadConfig() {
        addDefaults();

        if (!file.exists()) {
            for (ItemConfigType type : items.keySet()) {
                String name = type.name().toLowerCase();
                ItemConfigOption item = items.get(type);

                cfg.set("items." + name + ".name" , item.getName());
                cfg.set("items." + name + ".id" , item.getMaterial().name() + (item.getData() != 0 ? ":" + item.getData() : ""));
                cfg.set("items." + name + ".slot" , item.getSlot());

                if (item.getOwner() != null) {
                    cfg.set("items." + name + ".owner" , item.getOwner());
                }

                cfg.set("items." + name + ".enabled" , item.isEnabled());


            }

            try {
                cfg.save(file);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }


    public static void loadData() {
        for (ItemConfigType type : ItemConfigType.values()) {
            String name = type.name().toLowerCase();

            String displayName = cfg.getString("items." + name + ".name");
            String owner = cfg.getString("items." + name + ".owner");
            String matString = cfg.getString("items." + name + ".id").toUpperCase();
            int slot = cfg.getInt("items." + name + ".slot");
            boolean enabled = cfg.getBoolean("items." + name + ".enabled", true);

            int data = 0;
            if (matString.contains(":")) {
                String[] split = matString.split(":");
                matString = split[0];
                data = Integer.valueOf(split[1]);
            }

            Material material = Material.valueOf(matString);

            items.put(type, new ItemConfigOption(material, displayName, slot, owner, data).enable(enabled));

        }
    }


    public static ItemConfigOption getItem(ItemConfigType type) {
        return items.get(type);
    }

    public static ItemConfigType getByIdAndName(Material material, String name) {
        for (ItemConfigType type : items.keySet()) {
            ItemConfigOption option = items.get(type);
            if (option.getMaterial() == Material.OAK_DOOR && (VersionUtil.isCompatible(VersionEnum.V1_13) || VersionUtil.isCompatible(VersionEnum.V1_14) || VersionUtil.isCompatible(VersionEnum.V1_15) || VersionUtil.isCompatible(VersionEnum.V1_16) || VersionUtil.isCompatible(VersionEnum.V1_17) || VersionUtil.isCompatible(VersionEnum.V1_18))) {
                if (material.name().equals(MaterialBridge.WOOD_DOOR.getMaterialName()) && option.getName().equals(name)){
                    return type;
                }
            }
            if (option.getMaterial() == material && option.getName().equals(name)) {
                return type;
            }
        }
        return null;
    }

    private static void addDefaults() {
        items.put(ItemConfigType.TELEPORT, new ItemConfigOption(Material.COMPASS, "&7Teleport", 0));
        items.put(ItemConfigType.SPEED, new ItemConfigOption(Material.CLOCK, "&cSlow &8[&eRight&8] &aFast &8[&eShift Right&8]", 1));
        items.put(ItemConfigType.LEAVE, new ItemConfigOption(Material.OAK_DOOR, "&7Leave replay", 8));
        items.put(ItemConfigType.FORWARD, new ItemConfigOption(Material.PLAYER_HEAD, "&e10 seconds", 5, "MHF_ArrowRight", 3));
        items.put(ItemConfigType.BACKWARD, new ItemConfigOption(Material.PLAYER_HEAD, "&e10 seconds&c", 3, "MHF_ArrowLeft", 3));
        items.put(ItemConfigType.RESUME, new ItemConfigOption(Material.SLIME_BLOCK, "&aResume", 4));
        items.put(ItemConfigType.PAUSE, new ItemConfigOption(Material.PLAYER_HEAD, "&cPause", 4, "Push_red_button", 3));

    }


}
