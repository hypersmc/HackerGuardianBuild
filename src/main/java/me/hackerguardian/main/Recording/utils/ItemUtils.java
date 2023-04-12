package me.hackerguardian.main.Recording.utils;

import java.util.ArrayList;
import java.util.List;

import me.hackerguardian.main.utils.recordinghandler.VersionUtil;
import me.hackerguardian.main.utils.recordinghandler.VersionUtil.VersionEnum;
import org.bukkit.Material;

public class ItemUtils {

    private static final List<Material> INTERACTABLE = new ArrayList<>();

    public static boolean isInteractable(Material mat) {
        if (mat == null) return false;

        return INTERACTABLE.contains(mat);
    }

    public static boolean isUsable(Material mat) {
        if (mat == null) return false;

        return mat.isEdible() || mat == Material.POTION || mat == Material.MILK_BUCKET || mat == Material.BOW || (!VersionUtil.isCompatible(VersionEnum.V1_8) && mat == Material.SHIELD) || (VersionUtil.isCompatible(VersionEnum.V1_8) && isSword(mat));
    }

    public static boolean isSword(Material mat) {
        return mat == Material.WOODEN_SWORD || mat == Material.GOLDEN_SWORD || mat == Material.IRON_SWORD || mat == Material.DIAMOND_SWORD;
    }

    static {
        INTERACTABLE.add(Material.STONE_BUTTON);
        INTERACTABLE.add(Material.LEVER);
        INTERACTABLE.add(Material.CHEST);
        INTERACTABLE.add(Material.STONE_BUTTON);
        INTERACTABLE.add(Material.POLISHED_BLACKSTONE_BUTTON);
        INTERACTABLE.add(Material.OAK_BUTTON);
        INTERACTABLE.add(Material.SPRUCE_BUTTON);
        INTERACTABLE.add(Material.BIRCH_BUTTON);
        INTERACTABLE.add(Material.JUNGLE_BUTTON);
        INTERACTABLE.add(Material.ACACIA_BUTTON);
        INTERACTABLE.add(Material.DARK_OAK_BUTTON);
        INTERACTABLE.add(Material.CRIMSON_BUTTON);
        INTERACTABLE.add(Material.WARPED_BUTTON);
        INTERACTABLE.add(Material.COMPARATOR);
        if(!VersionUtil.isCompatible(VersionEnum.V1_8)){
            INTERACTABLE.add(Material.COMMAND_BLOCK);
            INTERACTABLE.add(Material.CHAIN_COMMAND_BLOCK);
            INTERACTABLE.add(Material.COMMAND_BLOCK_MINECART);
            INTERACTABLE.add(Material.REPEATING_COMMAND_BLOCK);
        }
        INTERACTABLE.add(Material.BREWING_STAND);
        INTERACTABLE.add(Material.FURNACE);
        INTERACTABLE.add(Material.TRAPPED_CHEST);
        INTERACTABLE.add(Material.ENCHANTING_TABLE);
        INTERACTABLE.add(Material.DROPPER);
        INTERACTABLE.add(Material.DISPENSER);
        INTERACTABLE.add(Material.ENDER_CHEST);
        INTERACTABLE.add(Material.BEACON);
        INTERACTABLE.add(Material.NOTE_BLOCK);
        INTERACTABLE.add(Material.JUKEBOX);
        INTERACTABLE.add(Material.HOPPER);
        INTERACTABLE.add(Material.SPRUCE_DOOR);
        INTERACTABLE.add(Material.ACACIA_DOOR);
        INTERACTABLE.add(Material.DARK_OAK_DOOR);
        INTERACTABLE.add(Material.JUNGLE_DOOR);
        INTERACTABLE.add(Material.BIRCH_DOOR);
        INTERACTABLE.add(Material.SPRUCE_FENCE_GATE);
        INTERACTABLE.add(Material.ACACIA_FENCE_GATE);
        INTERACTABLE.add(Material.JUNGLE_FENCE_GATE);
        INTERACTABLE.add(Material.BIRCH_FENCE_GATE);
        INTERACTABLE.add(Material.DARK_OAK_FENCE_GATE);
        INTERACTABLE.add(Material.SPRUCE_FENCE);
        INTERACTABLE.add(Material.JUNGLE_FENCE);
        INTERACTABLE.add(Material.ACACIA_FENCE);
        INTERACTABLE.add(Material.BIRCH_FENCE);
        INTERACTABLE.add(Material.DARK_OAK_FENCE);
        INTERACTABLE.add(Material.NETHER_BRICK_FENCE);
        INTERACTABLE.add(Material.ANVIL);
        INTERACTABLE.add(Material.DAYLIGHT_DETECTOR);
        INTERACTABLE.add(Material.CRAFTING_TABLE);
    }
}
