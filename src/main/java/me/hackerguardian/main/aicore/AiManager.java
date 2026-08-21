package me.hackerguardian.main.aicore;

import org.neuroph.core.Layer;
import org.neuroph.core.NeuralNetwork;
import org.neuroph.core.Neuron;
import org.neuroph.core.data.DataSet;
import org.neuroph.core.data.DataSetRow;
import org.neuroph.nnet.MultiLayerPerceptron;
import org.neuroph.nnet.learning.BackPropagation;
import org.neuroph.util.TransferFunctionType;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Central AI manager:
 *  - holds a single neural network for behavior
 *  - can evaluate a feature vector -> suspicion score [0, 1]
 *  - can accumulate training data and retrain from time to time
 */
public class AiManager {

    private final int inputSize;
    private final boolean learningMode;
    private final MultiLayerPerceptron network;
    private final TrainingBuffer buffer;
    private long totalLearnSamples = 0;   // samples added to the buffer
    private long totalTrainedSamples = 0; // samples actually used in training
    private long totalTrainCalls = 0;     // how many times trainFromBuffer() ran

    public AiManager(int inputSize, boolean learningMode) {
        this.inputSize = inputSize;
        this.learningMode = learningMode;
        this.buffer = new TrainingBuffer(inputSize, 1);

        // TODO: if we have an existing trained network file, load it here instead of creating new
        this.network = createNetwork(inputSize);
    }

    private MultiLayerPerceptron createNetwork(int inputSize) {
        // Simple 2-layer net: input -> hidden -> 1 output
        int hidden = Math.max(8, inputSize / 2);

        MultiLayerPerceptron net = new MultiLayerPerceptron(
                TransferFunctionType.SIGMOID,
                inputSize,
                hidden,
                1
        );

        BackPropagation bp = net.getLearningRule();
        bp.setLearningRate(0.01);
        bp.setMaxError(0.01);
        bp.setMaxIterations(1000);
        net.setLearningRule(bp);

        return net;
    }

    /**
     * Evaluate the given feature vector and return suspicion score in [0, 1].
     */
    public synchronized double evaluate(double[] features) {
        return evaluateWithDetails(features).getFinalOutput();
    }

    /**
     * Evaluate the given feature vector and return:
     *  - final suspicion score
     *  - per-layer neuron activations (0..1)
     *  - original input features
     */
    public synchronized EvaluationDetails evaluateWithDetails(double[] features) {
        if (features == null || features.length != inputSize) {
            throw new IllegalArgumentException("Expected feature vector of size " + inputSize);
        }

        // forward pass
        network.setInput(features);
        network.calculate();

        // collect outputs of every layer except the input layer
        int layerCount = network.getLayersCount();
        double[][] layerOutputs = new double[layerCount - 1][]; // [0] = first hidden, last = output layer

        for (int layerIndex = 1; layerIndex < layerCount; layerIndex++) {
            Layer layer = network.getLayerAt(layerIndex);

            // In your Neuroph version this is a List<Neuron>, not Neuron[]
            List<Neuron> neurons = layer.getNeurons();

            double[] outputs = new double[neurons.size()];
            for (int i = 0; i < neurons.size(); i++) {
                outputs[i] = neurons.get(i).getOutput(); // 0..1 for sigmoid
            }
            layerOutputs[layerIndex - 1] = outputs;
        }

        // final network output (your suspicion score)
        double[] out = network.getOutput();
        double raw = (out != null && out.length > 0) ? out[0] : 0.0;
        if (Double.isNaN(raw)) raw = 0.0;
        if (raw < 0.0) raw = 0.0;
        if (raw > 1.0) raw = 1.0;

        return new EvaluationDetails(features.clone(), layerOutputs, raw);
    }

    /**
     * Add a labeled sample to the training buffer.
     * label: 0.0 = legit, 1.0 = cheating (or any value in [0,1])
     */
    public void learn(double[] features, double label) {
        if (!learningMode) return;
        if (features == null || features.length != inputSize) return;

        buffer.add(features, new double[]{label});
        totalLearnSamples++;
    }

    /**
     * Train network from the current buffer, then clear it.
     * Call this from a BukkitRunnable / scheduled task every X seconds or minutes.
     */
    public synchronized void trainFromBuffer() {
        DataSet dataSet = buffer.toDataSetAndClear();
        if (dataSet == null || dataSet.size() == 0) {
            return;
        }
        network.learn(dataSet);
        totalTrainCalls++;
        totalTrainedSamples += dataSet.size();
    }

    /**
     * Load an existing trained network from a file.
     */
    public synchronized void loadFromFile(File file) {
        if (file == null || !file.exists()) return;

        NeuralNetwork<?> loaded = NeuralNetwork.createFromFile(file);
        if (loaded instanceof MultiLayerPerceptron) {
            MultiLayerPerceptron mlp = (MultiLayerPerceptron) loaded;

            // Optional sanity checks
            if (mlp.getInputsCount() != inputSize || mlp.getOutputsCount() != 1) {
                //log a warning here instead of returning maybe?
                return;
            }

            // getWeights() returns Double[] but setWeights needs double[]
            Double[] boxed = mlp.getWeights();
            double[] primitive = new double[boxed.length];
            for (int i = 0; i < boxed.length; i++) {
                primitive[i] = boxed[i];   // auto-unboxing
            }

            this.network.setWeights(primitive);
        }
    }

    /**
     * Save the current network to a file.
     */
    public synchronized void saveToFile(File file) {
        if (file == null) return;
        network.save(file.getAbsolutePath());
    }

    // ------------------- Simple Evaluation of data -------------------
    public static class EvaluationDetails {
        private final double[] inputFeatures;
        private final double[][] layerOutputs; // [layerIndex][neuronIndex], excludes input layer
        private final double finalOutput;

        public EvaluationDetails(double[] inputFeatures, double[][] layerOutputs, double finalOutput) {
            this.inputFeatures = inputFeatures;
            this.layerOutputs = layerOutputs;
            this.finalOutput = finalOutput;
        }

        public double[] getInputFeatures() {
            return inputFeatures;
        }

        public double[][] getLayerOutputs() {
            return layerOutputs;
        }

        public double getFinalOutput() {
            return finalOutput;
        }
    }

    // ------------------- Simple in-memory training buffer -------------------

    private static class TrainingBuffer {
        private final int inputSize;
        private final int outputSize;
        private final List<double[]> inputs = new ArrayList<>();
        private final List<double[]> outputs = new ArrayList<>();

        private static final int MAX_BUFFER_SIZE = 10_000; // safety cap

        public TrainingBuffer(int inputSize, int outputSize) {
            this.inputSize = inputSize;
            this.outputSize = outputSize;
        }

        public synchronized void add(double[] in, double[] out) {
            if (in == null || out == null) return;
            if (in.length != inputSize || out.length != outputSize) return;

            if (inputs.size() >= MAX_BUFFER_SIZE) {
                // Simple strategy: drop oldest
                inputs.remove(0);
                outputs.remove(0);
            }

            inputs.add(in.clone());
            outputs.add(out.clone());
        }

        public synchronized DataSet toDataSetAndClear() {
            if (inputs.isEmpty()) {
                return null;
            }
            DataSet dataSet = new DataSet(inputSize, outputSize);
            for (int i = 0; i < inputs.size(); i++) {
                DataSetRow row = new DataSetRow(inputs.get(i), outputs.get(i));
                dataSet.addRow(row);
            }
            inputs.clear();
            outputs.clear();
            return dataSet;
        }
    }

    // ------------------- Simple getters for the entire class -------------------


    public long getTotalLearnSamples() {
        return totalLearnSamples;
    }

    public long getTotalTrainedSamples() {
        return totalTrainedSamples;
    }

    public long getTotalTrainCalls() {
        return totalTrainCalls;
    }

    public boolean hasAnyTraining() {
        return totalTrainedSamples > 0;
    }

    public MultiLayerPerceptron getNetwork() {
        return network;
    }
}