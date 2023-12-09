package me.hackerguardian.main.aicore;

import me.hackerguardian.main.hackerguardian;
import org.neuroph.core.data.DataSet;

import java.io.*;

/**
 * @author JumpWatch on 07-04-2023
 * @Project HackerGuardian
 * v1.0.0
 */
public class DataIO {
    static hackerguardian main = hackerguardian.getInstance();
    public static void saveTrainingData(DataSet trainingData, String fileName) {
        try {
            FileOutputStream fos = new FileOutputStream(fileName);
            ObjectOutputStream oos = new ObjectOutputStream(fos);
            oos.writeObject(trainingData);
            oos.close();
            fos.close();
        } catch (IOException e) {
            main.text.SendconsoleTextWsp("Error saving training data to file: " + e.getMessage());
        }
    }

    public static DataSet loadTrainingData(String fileName) {
        DataSet trainingData = null;
        try {
            FileInputStream fis = new FileInputStream(main.getDataFolder() + "/datacore/" + fileName);
            ObjectInputStream ois = new ObjectInputStream(fis);
            trainingData = (DataSet) ois.readObject();

            ois.close();
            fis.close();
        } catch (IOException | ClassNotFoundException e) {
            main.text.SendconsoleTextWsp("Error loading training data from file: " + e.getMessage());
        }
        return trainingData;
    }
}
