package me.hackerguardian.velocity;

import com.velocitypowered.api.scheduler.ScheduledTask;
import me.hackerguardian.main.utils.ErrorHandler;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.logging.Logger;

public class VMySQL {
    private ScheduledTask scheduledTask;
    public static Connection db = null;
    HackerGuardianV main = HackerGuardianV.getInstance();
    Logger logger = Logger.getLogger("HGVelocity_Link_Database");
    private String host = (String) HackerGuardianV.sql.getOrDefault("SQLHost", "127.0.0.1");
    private String port = (String) HackerGuardianV.sql.getOrDefault("SQLPort", "3306");
    private String database = (String) HackerGuardianV.sql.getOrDefault("SQLDatabaseName", "myDatabase");
    private String user = (String) HackerGuardianV.sql.getOrDefault("SQLUsername", "changeme");
    private String pass = (String) HackerGuardianV.sql.getOrDefault("SQLPassword", "changeme");
    public void setupCoreSystem(){
        String url = null;
        if (this.user.equals("changeme") && this.pass.equals("changeme")) {
            logger.info("");
            logger.info("---------- Core MySQL ----------");
            logger.info("Please setup MySQL in the config. When done reboot the server.");
            logger.info("Disabling plugin. Please reboot to reload config.");
            logger.info("-----------------------------");
            logger.info("");
            return;
        }
        try {
            String driver = "com.mysql.cj.jdbc.Driver";
            url = "jdbc:mysql://" + this.host + ":" + this.port + "/" + this.database + "?user=" + this.user + "&password=" + this.pass + "?autoReconnect=true?useUnicode=yes";
            Class.forName(driver);
            String finalUrl = url;
            scheduledTask = main.getServer().getScheduler().buildTask(this, () -> {
                try {
                    db = DriverManager.getConnection(finalUrl, this.user, this.pass);
                    logger.info("Connection to MySQL database successful.");
                } catch (SQLException e) {
                    ErrorHandler.handleGenericException(e, "Could not connect to the database");
                }
            }).schedule();
        }catch (Exception e) {
            ErrorHandler.handleGenericException(e, "Could not connect to the database");
        }
    }


    /*
     * Get, Put, Delete and more
     */
}
