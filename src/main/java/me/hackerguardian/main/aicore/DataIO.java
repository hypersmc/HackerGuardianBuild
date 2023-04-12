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

    public static void saveTrainingData(DataSet trainingData, String fileName) {
        try {
            FileOutputStream fos = new FileOutputStream(fileName);
            ObjectOutputStream oos = new ObjectOutputStream(fos);
            oos.writeObject(trainingData);
            oos.close();
            fos.close();
        } catch (IOException e) {
            System.out.println("Error saving training data to file: " + e.getMessage());
        }
    }

    public static TrainingData loadTrainingData(String fileName) {
        TrainingData trainingData = null;
        File file = new File(fileName);
        if (file.exists()) {
            try {
                FileInputStream fis = new FileInputStream(fileName);
                ObjectInputStream ois = new ObjectInputStream(fis);
                trainingData = (TrainingData) ois.readObject();
                System.out.println("training data: " + trainingData.getDataSet());
                ois.close();
                fis.close();
            } catch (IOException | ClassNotFoundException e) {
                System.out.println("Error loading training data from file: " + e.getMessage());
            }
        } else {
            System.out.println("File " + fileName + " does not exist!");
        }
        return trainingData;
    }
}
