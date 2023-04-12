package me.hackerguardian.main.Recording.data.types;

import org.bukkit.Material;

public class ItemData extends PacketData{

    /**
     *
     */
    private static final long serialVersionUID = 3882181315164039909L;


    private Material id, subId;

    private SerializableItemStack itemStack;

    public ItemData(Material id, Material subId) {
        this.id = id;
        this.subId = subId;
    }

    public ItemData(SerializableItemStack itemStack) {
        this.itemStack = itemStack;
    }

    public Material getId() {
        return id;
    }

    public Material getSubId() {
        return subId;
    }

    public SerializableItemStack getItemStack() {
        return itemStack;
    }

}
