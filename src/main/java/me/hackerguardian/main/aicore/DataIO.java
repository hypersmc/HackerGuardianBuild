package me.hackerguardian.main.aicore;

import me.hackerguardian.main.HackerGuardian;
import me.hackerguardian.main.utils.ErrorHandler;
import org.neuroph.core.data.DataSet;

import java.io.*;

/**
 * @author JumpWatch on 07-04-2023
 * @Project HackerGuardian
 * v1.0.0
 */
public class DataIO {
    static HackerGuardian main = HackerGuardian.getInstance();
    public static void saveTrainingData(DataSet trainingData, String fileName) {
        try {
            FileOutputStream fos = new FileOutputStream(fileName);
            ObjectOutputStream oos = new ObjectOutputStream(fos);
            oos.writeObject(trainingData);
            oos.close();
            fos.close();
        } catch (IOException e) {
            ErrorHandler.handleIOException(e, "Error saving training data");
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
            ErrorHandler.handleGenericException(e, "Error saving training data");
        }
        return trainingData;
    }
}
