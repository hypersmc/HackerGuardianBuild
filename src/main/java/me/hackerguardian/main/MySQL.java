package me.hackerguardian.main;
import me.hackerguardian.main.utils.ErrorHandler;
import me.hackerguardian.main.utils.textHandling;
import org.bukkit.Bukkit;

import java.sql.*;

public class MySQL {

    public static textHandling text = new textHandling();
    public static Connection db = null;
    private String host = HackerGuardian.getInstance().getConfig().getString("SQLHost");
    private String port = HackerGuardian.getInstance().getConfig().getString("SQLPort");
    private String database = HackerGuardian.getInstance().getConfig().getString("SQLDatabaseName");
    private String user = HackerGuardian.getInstance().getConfig().getString("SQLUsername");
    private String pass = HackerGuardian.getInstance().getConfig().getString("SQLPassword");

    public void setupCoreSystem(){
        String url = null;
        if (this.user.equals("changeme") && this.pass.equals("changeme")){
            text.SendconsoleTextWp("");
            text.SendconsoleTextWp("---------- Core MySQL ----------");
            text.SendconsoleTextWp("Please setup MySQL in the config. When done reboot the server.");
            text.SendconsoleTextWp("Disabling plugin. Please reboot to reload config.");
            text.SendconsoleTextWp("-----------------------------");
            text.SendconsoleTextWp("");
            Bukkit.getPluginManager().disablePlugin(HackerGuardian.getInstance());
            return;
        }
        try {
            String driver = "com.mysql.jdbc.Driver";
            url = "jdbc:mysql://" + this.host + ":" + this.port + "/" + this.database + "?user=" + this.user + "&password=" + this.pass + "?autoReconnect=true?useUnicode=yes";
            Class.forName(driver);
            String finalUrl = url;
            Bukkit.getScheduler().runTaskAsynchronously(HackerGuardian.getInstance(), () -> {
                try {
                    db = DriverManager.getConnection(finalUrl, this.user, this.pass);
                    formatCoreDatabase();
                    text.SendconsoleTextWp("Connection to MySQL database successful.");
                } catch (SQLException e) {
                    ErrorHandler.handleGenericException(e, "Could not connect to the database");

                }
            });

        } catch (Exception e) {
            ErrorHandler.handleGenericException(e, "Could not connect to the database");
        }
    }
    /*
    Website basted
     */

    public static void InitializeWebsiteContentCheck(){
        String host = HackerGuardian.getInstance().getConfig().getString("SQLHost");
        String port = HackerGuardian.getInstance().getConfig().getString("SQLPort");
        String database = HackerGuardian.getInstance().getConfig().getString("SQLDatabaseName");
        String user = HackerGuardian.getInstance().getConfig().getString("SQLUsername");
        String pass = HackerGuardian.getInstance().getConfig().getString("SQLPassword");
        String url = null;
    }
    /*
    END
     */

    public static void InitializeDatabaseConnectionCheck() {
        String host = HackerGuardian.getInstance().getConfig().getString("SQLHost");
        String port = HackerGuardian.getInstance().getConfig().getString("SQLPort");
        String database = HackerGuardian.getInstance().getConfig().getString("SQLDatabaseName");
        String user = HackerGuardian.getInstance().getConfig().getString("SQLUsername");
        String pass = HackerGuardian.getInstance().getConfig().getString("SQLPassword");
        String url = null;
        try {
            String driver = "com.mysql.jdbc.Driver";
            url = "jdbc:mysql://" + host + ":" + port + "/" + database + "?user=" + user + "&password=" + pass + "?autoReconnect=true?useUnicode=yes";
            Class.forName(driver);

            String finalUrl = url;
            Bukkit.getScheduler().runTaskAsynchronously(HackerGuardian.getInstance(), () -> {
                try {
                    db = DriverManager.getConnection(finalUrl, user, pass);
                    text.SendconsoleTextWp("Reconnection to MySQL database successful.");
                } catch (SQLException e) {
                    ErrorHandler.handleGenericException(e, "Could not connect to the database");

                }
            });

        } catch (Exception e) {
            ErrorHandler.handleGenericException(e, "Could not connect to the database");
        }
    }

    public void InitializeDBShutdown(){
        try {
            db.close();
            text.SendconsoleTextWp("MysQL database connection closed.");
        } catch (SQLException e) {
            ErrorHandler.handleGenericException(e, "Could not connect to the database");
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
            ErrorHandler.handleGenericException(e, "Could not connect to the database");
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
            ErrorHandler.handleGenericException(e, "Error finding SQL Tables");
        }
    }
    public void doNewCoreDatabase(){

    }

}
