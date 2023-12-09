package me.hackerguardian.main;
import me.hackerguardian.main.utils.textHandling;
import org.bukkit.Bukkit;

import java.sql.*;
import java.util.*;

public class MySQL {

    public static textHandling text = new textHandling();
    public static Connection db = null;
    private String host = hackerguardian.getInstance().getConfig().getString("SQLHost");
    private String port = hackerguardian.getInstance().getConfig().getString("SQLPort");
    private String database = hackerguardian.getInstance().getConfig().getString("SQLDatabaseName");
    private String user = hackerguardian.getInstance().getConfig().getString("SQLUsername");
    private String pass = hackerguardian.getInstance().getConfig().getString("SQLPassword");
    //TODO Add ny liste så man kan se hvad spillern sidst er blivet "kicket" for af checks.

    public void setupCoreSystem(){
        String url = null;
        if (this.user.equals("changeme") && this.pass.equals("changeme")){
            text.SendconsoleTextWp("");
            text.SendconsoleTextWp("---------- Core MySQL ----------");
            text.SendconsoleTextWp("Please setup MySQL in the config. When done reboot the server.");
            text.SendconsoleTextWp("Disabling plugin. Please reboot to reload config.");
            text.SendconsoleTextWp("-----------------------------");
            text.SendconsoleTextWp("");
            Bukkit.getPluginManager().disablePlugin(hackerguardian.getInstance());
            return;
        }
        try {
            String driver = "com.mysql.jdbc.Driver";
            url = "jdbc:mysql://" + this.host + ":" + this.port + "/" + this.database + "?user=" + this.user + "&password=" + this.pass + "?autoReconnect=true?useUnicode=yes";
            Class.forName(driver);
            String finalUrl = url;
            Bukkit.getScheduler().runTaskAsynchronously(hackerguardian.getInstance(), () -> {
                try {
                    db = DriverManager.getConnection(finalUrl, this.user, this.pass);
                    formatCoreDatabase();
                    text.SendconsoleTextWp("Connection to MySQL database successful.");
                } catch (SQLException e) {
                    text.SendconsoleTextWp("Could not connect to the '" + this.database + "' Database");
                    if (hackerguardian.getInstance().getConfig().getBoolean("debug")) e.printStackTrace();
                }
            });

        } catch (Exception e) {
            text.SendconsoleTextWp("Could not connect to the '" + this.database + "' Database");
            text.SendconsoleTextWp("Info: " + url);
            if (hackerguardian.getInstance().getConfig().getBoolean("debug")) e.printStackTrace();
        }
    }
    /*
    Website basted
     */

    public static void InitializeWebsiteContentCheck(){
        String host = hackerguardian.getInstance().getConfig().getString("SQLHost");
        String port = hackerguardian.getInstance().getConfig().getString("SQLPort");
        String database = hackerguardian.getInstance().getConfig().getString("SQLDatabaseName");
        String user = hackerguardian.getInstance().getConfig().getString("SQLUsername");
        String pass = hackerguardian.getInstance().getConfig().getString("SQLPassword");
        String url = null;
    }
    /*
    END
     */

    public static void InitializeDatabaseConnectionCheck() {
        String host = hackerguardian.getInstance().getConfig().getString("SQLHost");
        String port = hackerguardian.getInstance().getConfig().getString("SQLPort");
        String database = hackerguardian.getInstance().getConfig().getString("SQLDatabaseName");
        String user = hackerguardian.getInstance().getConfig().getString("SQLUsername");
        String pass = hackerguardian.getInstance().getConfig().getString("SQLPassword");
        String url = null;
        try {
            String driver = "com.mysql.jdbc.Driver";
            url = "jdbc:mysql://" + host + ":" + port + "/" + database + "?user=" + user + "&password=" + pass + "?autoReconnect=true?useUnicode=yes";
            Class.forName(driver);

            String finalUrl = url;
            Bukkit.getScheduler().runTaskAsynchronously(hackerguardian.getInstance(), () -> {
                try {
                    db = DriverManager.getConnection(finalUrl, user, pass);
                    text.SendconsoleTextWp("Reconnection to MySQL database successful.");
                } catch (SQLException e) {
                    text.SendconsoleTextWp("Could not connect to the '" + database + "' Database");
                    if (hackerguardian.getInstance().getConfig().getBoolean("debug")) e.printStackTrace();
                }
            });

        } catch (Exception e) {
            text.SendconsoleTextWp("Could not connect to the '" + database + "' Database");
            text.SendconsoleTextWp("Info: " + url);
            if (hackerguardian.getInstance().getConfig().getBoolean("debug")) e.printStackTrace();
        }
    }

    public void InitializeDBShutdown(){
        try {
            db.close();
            text.SendconsoleTextWp("MysQL database connection closed.");
        } catch (SQLException e) {
            text.SendconsoleTextWp("Failed to close connection.");
            if (hackerguardian.getInstance().getConfig().getBoolean("debug")) e.printStackTrace();
        }
    }

    private void initializeDatabaseCleanup() {
        String[] tablesToDrop = {
                "CorePlayerStats", "Playerstats", "PlayerIPTable", "Reports",
                "Comments", "Flags", "Triggers", "OtherReasons", "PlayerMods"
        };

        try {
            for (String table : tablesToDrop) {
                String query = "DROP TABLE `" + table + "`";
                try (PreparedStatement preparedStatement = db.prepareStatement(query)) {
                    preparedStatement.executeUpdate();
                }
            }
            text.SendconsoleTextWp("Database Cleanup successful!");
            doNewCoreDatabase();
        } catch (SQLException e) {
            if (hackerguardian.getInstance().getConfig().getBoolean("debug")) e.printStackTrace();
        }
    }


    public void formatCoreDatabase() {
        try {
            PreparedStatement checkIfExists = db.prepareStatement("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = ?");
            checkIfExists.setString(1, this.database);
            checkIfExists.setString(2, "CorePlayerStats");

            ResultSet resultSet = checkIfExists.executeQuery();

            if (resultSet.next()) {
                int count = resultSet.getInt(1);
                if (count > 0) {
                    text.SendconsoleTextWp("V1 HackerGuardian Installation found!");
                    text.SendconsoleTextWsp("Eradicating old database tables and data!");
                    initializeDatabaseCleanup();
                } else {
                    text.SendconsoleTextWp("New installation!");
                    doNewCoreDatabase();
                }
            }

            resultSet.close();
            checkIfExists.close();
            text.SendconsoleTextWp("Successfully checked tables.");
        } catch (SQLException e) {
            text.SendconsoleTextWp("Error while checking Core system SQL tables.");
            if (hackerguardian.getInstance().getConfig().getBoolean("debug")) e.printStackTrace();
        }
    }
    public void doNewCoreDatabase(){

    }

}
