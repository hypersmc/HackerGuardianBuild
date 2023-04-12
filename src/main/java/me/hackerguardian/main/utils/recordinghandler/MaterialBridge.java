package me.hackerguardian.main.utils.recordinghandler;

import org.bukkit.Material;
import org.bukkit.block.Block;
import me.hackerguardian.main.utils.recordinghandler.VersionUtil.VersionEnum;

public enum MaterialBridge {

    WATCH("CLOCK"),
    WOOD_DOOR("OAK_DOOR");

    private String materialName;

    private MaterialBridge(String materialName) {
        this.materialName = materialName;
    }

    public Material toMaterial() {
        return Material.valueOf(this.toString());

    }

    public String getMaterialName() {
        return materialName;
    }

    @SuppressWarnings("deprecation")
    public static Material fromID(String id) {
        if (VersionUtil.isCompatible(VersionEnum.V1_13) || VersionUtil.isCompatible(VersionEnum.V1_14) || VersionUtil.isCompatible(VersionEnum.V1_15) || VersionUtil.isCompatible(VersionEnum.V1_16) || VersionUtil.isCompatible(VersionEnum.V1_17) || VersionUtil.isCompatible(VersionEnum.V1_18) || VersionUtil.isCompatible(VersionEnum.V1_19)) {
            Material matId = Material.getMaterial(id);
            return matId;
//            for (Material mat : Material.values()) {
//                if (mat.getId() == id){
//                    return mat;
//                }
//            }
        } else {
            return Material.getMaterial(String.valueOf(id));
        }
    }

    public static Material getWithoutLegacy(String material) {
        try {
            Object enumField = ReflectionHelper.getInstance().matchMaterial(material, false);

            return (Material) enumField;
        } catch (Exception e) {
            e.printStackTrace();
        }

        return null;
    }

    public static Material getBlockDataMaterial(Block block) {
        try {
            Object blockData = ReflectionHelper.getInstance().getBlockData(block);
            Object materialField = ReflectionHelper.getInstance().getBlockDataMaterial(blockData);

            return (Material) materialField;
        } catch (Exception e) {
            e.printStackTrace();
        }

        return null;
    }
}
