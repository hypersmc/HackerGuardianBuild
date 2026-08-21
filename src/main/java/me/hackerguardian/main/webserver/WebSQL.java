package me.hackerguardian.main.webserver;

import me.hackerguardian.main.aicore.AiManager;
import org.bukkit.entity.Player;

import javax.sql.DataSource;
import java.sql.*;
import java.util.UUID;

public class WebSQL {

    private final DataSource ds;

    public WebSQL(DataSource ds) {
        this.ds = ds;
    }

    public void ensureTables() throws SQLException {
        try (Connection c = ds.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "CREATE TABLE IF NOT EXISTS hg_webdb (" +
                            "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                            "player_uuid CHAR(36) NOT NULL," +
                            "player_name VARCHAR(16) NOT NULL," +
                            "inputs VARCHAR(255) NOT NULL," +
                            "layers VARCHAR(255) NOT NULL," +
                            "output TINYINT(1) NOT NULL," +
                            "created_at BIGINT NOT NULL" +
                            ");"
            )) {
                ps.execute();
            }
        }
    }

    public long createNeuralPData(Player player, AiManager.EvaluationDetails details,
                                  long createdAt) throws SQLException {

        double[] input = details.getInputFeatures();
        double[][] layers = details.getLayerOutputs();
        double output = details.getFinalOutput();
        UUID uuid = player.getUniqueId();
        String playerName = player.getName();
        String inputCsv = toCsv(input);               // "0.12,0.77,0.03,..."
        String hiddenCsv = toLayerCsv(layers);        // e.g. "0.1;0.2;0.9|0.3;0.8" for 2 layers
        double suspicion = output;                    // 0..1
        String sql =
                "INSERT INTO hg_webdb (player_uuid, player_name, inputs, layers, output, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

            ps.setString(1, uuid.toString());
            ps.setString(2, playerName);
            ps.setString(3, inputCsv);
            ps.setString(4, hiddenCsv);
            ps.setDouble(5, suspicion);
            ps.setLong(6, createdAt);

            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
            }
            throw new SQLException("No generated key for NeuralPData insert");
        }

    }

    private String toCsv(double[] arr) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(arr[i]);
        }
        return sb.toString();
    }

    private String toLayerCsv(double[][] layers) {
        // Example format: each layer's neurons comma-separated, layers separated by "|"
        // L1: n1,n2,n3 | L2: n1
        StringBuilder sb = new StringBuilder();
        for (int l = 0; l < layers.length; l++) {
            if (l > 0) sb.append("|");
            double[] layer = layers[l];
            for (int i = 0; i < layer.length; i++) {
                if (i > 0) sb.append(",");
                sb.append(layer[i]);
            }
        }
        return sb.toString();
    }
}
