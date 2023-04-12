package me.hackerguardian.main.aicore;

import org.neuroph.core.NeuralNetwork;
import org.neuroph.core.data.DataSet;
import org.neuroph.nnet.MultiLayerPerceptron;
import org.neuroph.nnet.learning.BackPropagation;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;

/**
 * @author JumpWatch on 27-03-2023
 * @Project HackerGuardian
 * v1.0.0
 */
public class HackerguardianAI {
    private final int numInputs;
    private final int numOutputs;
    private final int numHiddenNodes;
    public final NeuralNetwork network;

    public HackerguardianAI(int numInputs, int numOutputs, int numHiddenNodes) {
        this.numInputs = numInputs;
        this.numOutputs = numOutputs;
        this.numHiddenNodes = numHiddenNodes;

        // Define the neural network architecture
        network = new MultiLayerPerceptron(numInputs, numHiddenNodes, numOutputs);

        // Set up the backpropagation learning rule
        BackPropagation backpropagation = new BackPropagation();
        backpropagation.setLearningRate(0.1);
        backpropagation.setMaxIterations(1000);

        // Assign the learning rule to the network
        network.setLearningRule(backpropagation);
    }

    public void train(DataSet trainingData) {
        network.learn(trainingData);
    }

    public double predict(double[] input) {
        network.setInput(input);
        network.calculate();
        return network.getOutput()[0];
    }
    public void save(String fileName) {
        try {
            FileOutputStream fos = new FileOutputStream(fileName);
            ObjectOutputStream oos = new ObjectOutputStream(fos);
            oos.writeObject(network);
            oos.close();
            fos.close();
        } catch (IOException e) {
            System.out.println("Error saving network to file: " + e.getMessage());
        }

    }


}
