package me.hackerguardian.main.Recording.database;


import me.hackerguardian.main.Recording.database.utils.Database;

public class DatabaseRegistry {

    public static Database database;

    public static void registerDatabase(Database d) {
        database = d;
    }

    public static boolean isRegistered() {
        return database != null;
    }

    public static Database getDatabase() {
        return database;
    }
}
