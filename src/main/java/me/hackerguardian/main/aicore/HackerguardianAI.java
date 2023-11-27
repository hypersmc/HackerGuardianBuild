package me.hackerguardian.main.aicore;

import org.neuroph.core.NeuralNetwork;
import org.neuroph.core.data.DataSet;
import org.neuroph.nnet.MultiLayerPerceptron;
import org.neuroph.nnet.learning.BackPropagation;

public class HackerguardianAI {
    private final int totalInputs; // Total number of inputs from all events
    private final int numOutputs;
    private final int numHiddenNodes;
    public final NeuralNetwork network;

    public HackerguardianAI(int totalInputs, int numOutputs, int numHiddenNodes) {
        this.totalInputs = totalInputs;
        this.numOutputs = numOutputs;
        this.numHiddenNodes = numHiddenNodes;

        // Define the neural network architecture
        network = new MultiLayerPerceptron(totalInputs, numHiddenNodes, numOutputs);

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

    public double[] predict(double[] input) {
        network.setInput(input);
        network.calculate();
        return network.getOutput();
    }
}
