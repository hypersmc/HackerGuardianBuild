package me.hackerguardian.bungee.utils;

import me.hackerguardian.bungee.HackerGuardianB;
import me.hackerguardian.main.HackerGuardian;
import me.hackerguardian.main.utils.ErrorHandler;
import net.md_5.bungee.api.ProxyServer;

import java.sql.*;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * @author JumpWatch on 19-04-2024
 * @Project HackerGuardian
 * v1.0.0
 */
public class BMySQL {

    public static Connection db = null;
    HackerGuardianB main = HackerGuardianB.getInstance();
    Logger logger = Logger.getLogger("HGBungee_Link_Database");
    private String host = main.configuration.getString("SQLHost");
    private String port = main.configuration.getString("SQLPort");
    private String database = main.configuration.getString("SQLDatabaseName");
    private String user = main.configuration.getString("SQLUsername");
    private String pass = main.configuration.getString("SQLPassword");
    public void setupCoreSystem(){
        String url = null;
        if (this.user.equals("changeme") && this.pass.equals("changeme")){
            logger.info("");
            logger.info("---------- Core MySQL ----------");
            logger.info("Please setup MySQL in the config. When done reboot the server.");
            logger.info("Disabling plugin. Please reboot to reload config.");
            logger.info("-----------------------------");
            logger.info("");
            PluginDisabler.disablePlugin(HackerGuardianB.getInstance());
            return;
        }
        try {
            String driver = "com.mysql.cj.jdbc.Driver";
            url = "jdbc:mysql://" + this.host + ":" + this.port + "/" + this.database + "?user=" + this.user + "&password=" + this.pass + "?autoReconnect=true?useUnicode=yes";
            Class.forName(driver);
            String finalUrl = url;
            ProxyServer.getInstance().getScheduler().runAsync(HackerGuardianB.getInstance(), () -> {
                try {
                    db = DriverManager.getConnection(finalUrl, this.user, this.pass);
//                    formatCoreDatabase();
                    logger.info("Connection to MySQL database successful.");
                } catch (SQLException e) {
                    ErrorHandler.handleGenericException(e, "Could not connect to the database");

                }
            });

        } catch (Exception e) {
            ErrorHandler.handleGenericException(e, "Could not connect to the database");
        }
    }
    public void checkdbconnection(){
        String url = null;
        try {
            String driver = "com.mysql.cj.jdbc.Driver";
            url = "jdbc:mysql://" + this.host + ":" + this.port + "/" + this.database + "?user=" + this.user + "&password=" + this.pass + "?autoReconnect=true?useUnicode=yes";
            Class.forName(driver);
            String finalUrl = url;
            ProxyServer.getInstance().getScheduler().runAsync(HackerGuardianB.getInstance(), () -> {
                try {
                    db = DriverManager.getConnection(finalUrl, this.user, this.pass);
//                    formatCoreDatabase();
                    logger.info("Connection to MySQL database successful.");
                } catch (SQLException e) {
                    ErrorHandler.handleGenericException(e, "Could not connect to the database");

                }
            });
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
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
                    logger.info("V1 HackerGuardian Installation found!");
                    logger.info("Eradicating old database tables and data!");
//                    initializeDatabaseCleanup();
                } else {
                    logger.info("New installation!");
//                    doNewCoreDatabase();
                }
            }

            resultSet.close();
            checkIfExists.close();
            logger.info("Successfully checked tables.");
        } catch (SQLException e) {
            ErrorHandler.handleGenericException(e, "Error finding SQL Tables");
        }
    }


    public void firstPlayerMods(UUID playeruuid, String modData){
        PreparedStatement first = null;
//        checkdbconnection();
        try {
            first = db.prepareStatement("INSERT INTO " + this.database + ".PlayerMods VALUES ('" + playeruuid + "','" + modData + "');");
            first.executeUpdate();
            first.close();
        } catch (Exception ignored) {

        }
    }
    @SuppressWarnings({"lgtm [java/concatenated-sql-query]", "lgtm [java/dereferenced-value-is-always-null]", "lgtm [java/dereferenced-value-may-be-null]"})
    public void addPlayerMods(UUID playeruuid, List<String> modData){
        if (modData.isEmpty()) return;
        PreparedStatement first = null;
        PreparedStatement second = null;
        ResultSet firesult = null;
        checkdbconnection();
        try {
            second = db.prepareStatement("SELECT * FROM " + this.database + ".PlayerMods WHERE PlayerUUID='" + playeruuid + "';");
            firesult = second.executeQuery();
            System.out.println("Player: " + playeruuid + " Mods: " + modData);
            while (firesult.next()){
                String s = firesult.getString("Mods");
                System.out.println("Player: " + playeruuid + " Mods: " + modData + " trying to add!");

                if (s != null && !s.isEmpty()) {
                    System.out.println(s + " is not empty");
                    for (String modName : modData) {
                        if (!s.contains(modName)) {
                            System.out.println(modName + " is not in list");
                            first = db.prepareStatement("INSERT INTO " + this.database + ".PlayerMods (PlayerUUID, Mods) VALUES ('" + playeruuid + "','" + modName.replace("[", "").replace("]", "") + "');");
                            System.out.println("Player: " + playeruuid + " Mods: " + modName + " Added to database!");
                        }else {
                            System.out.println(modName + " is in list");
                        }

                    }
                    return;
                }
                first.executeUpdate();
                return;
            }
            first.close();
            second.close();
            firesult.close();
        }catch (Exception ignored) {
        }
    }
    public String getplayerban(UUID playeruuid){
        PreparedStatement second = null;
        ResultSet firesult = null;
        try {
            second = db.prepareStatement("SELECT * FROM " + this.database + ".Playerstats WHERE PlayerUUID='" + playeruuid + "';");
            firesult = second.executeQuery();
            while (firesult.next()){
                String s = firesult.getString("Banned");
                if (s != null && !s.isEmpty()) return s;
            }
            second.close();
            firesult.close();
        }catch (Exception ignored){
        }
        return "null";
    }
    public String getPlayerbanreason(UUID playeruuid){
        PreparedStatement second = null;
        ResultSet firesult = null;
        try {
            second = db.prepareStatement("SELECT * FROM " + this.database + ".OtherReasons WHERE PlayerUUID='" + playeruuid + "' AND Handler = 'Ban' ORDER BY Reason ASC LIMIT 1;");
            firesult = second.executeQuery();
            while (firesult.next()){
                String s = firesult.getString("Reason");
                if (s != null && !s.isEmpty()) return s;
            }
        }catch (Exception ignored){

        }
        return "Reason not found!";
    }
    public String getPlayerkickreason(UUID playeruuid){
        PreparedStatement second = null;
        ResultSet firesult = null;
        try {
            second = db.prepareStatement("SELECT * FROM " + this.database + ".OtherReasons WHERE PlayerUUID='" + playeruuid + "' AND Handler = 'Kick' ORDER BY Reason ASC LIMIT 1;");
            firesult = second.executeQuery();
            while (firesult.next()){
                String s = firesult.getString("Reason");
                if (s != null && !s.isEmpty()) return s;
            }
        }catch (Exception ignored){

        }
        return "Reason not found!";
    }
}
