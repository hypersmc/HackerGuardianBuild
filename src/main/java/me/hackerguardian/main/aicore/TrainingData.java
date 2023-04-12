package me.hackerguardian.main.aicore;

import org.neuroph.core.data.DataSet;
import org.neuroph.core.data.DataSetRow;

/**
 * @author JumpWatch on 05-04-2023
 * @Project HackerGuardian
 * v1.0.0
 */
public class TrainingData {
    private DataSet dataSet;

    public TrainingData(int numInputs, int numOutputs) {
        dataSet = new DataSet(numInputs, numOutputs);
    }

    public void addRow(double[] input, double[] output) {
        dataSet.addRow(new DataSetRow(input, output));
    }

    public DataSet getDataSet() {
        return dataSet;
    }
    public DataSet setDataSet(DataSet dataset) {
        return dataSet = dataset;
    }
}