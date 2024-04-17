package me.hackerguardian.main.aicore;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import me.hackerguardian.main.HackerGuardian;
import me.hackerguardian.main.utils.ErrorHandler;
import org.neuroph.core.Layer;
import org.neuroph.core.NeuralNetwork;
import org.neuroph.core.Neuron;

/**
 * @author JumpWatch on 04-04-2023
 * @Project HackerGuardian
 * v1.0.0
 */
public class NeuralNetworkVisualizer {

    public static void visualize(NeuralNetwork network, String fileName) {
        HackerGuardian main = HackerGuardian.getInstance();
        int imageSize = 600;

        List<Layer> layers = new ArrayList<>();
        layers.addAll(network.getLayers());

        int maxNeurons = layers.stream().mapToInt(Layer::getNeuronsCount).max().orElse(0);

        int layerSpacing = imageSize / (layers.size() + 1);
        int neuronSpacing = imageSize / (maxNeurons + 1);

        BufferedImage image = new BufferedImage(imageSize, imageSize, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        graphics.setBackground(Color.WHITE);
        graphics.clearRect(0, 0, imageSize, imageSize);

        graphics.setColor(Color.BLACK);
        graphics.setFont(new Font("Arial", Font.BOLD, 18));

        int prevX = 0;
        int prevY = 0;

        for (int i = 0; i < layers.size(); i++) {
            Layer layer = layers.get(i);

            int x = layerSpacing * (i + 1);
            int y = 0;

            for (int j = 0; j < layer.getNeuronsCount(); j++) {
                Neuron neuron = layer.getNeuronAt(j);

                y = neuronSpacing * (j + 1);

                graphics.setColor(Color.BLUE);
                graphics.fillOval(x - 5, y - 5, 10, 10);

                if (i > 0) {
                    for (int k = 0; k < layers.get(i - 1).getNeuronsCount(); k++) {
                        Neuron prevNeuron = layers.get(i - 1).getNeuronAt(k);

                        int prevY2 = neuronSpacing * (k + 1);

                        graphics.setColor(Color.RED);
                        graphics.drawLine(prevX, prevY2, x, y);
                    }
                }
            }

            // Add layer text
            graphics.setColor(Color.BLACK);
            graphics.drawString("Layer " + i, x - 20, y + 30);

            prevX = x;
            prevY = y;
        }

        // Add title text
        graphics.setColor(Color.BLACK);
        graphics.setFont(new Font("Arial", Font.BOLD, 24));
        graphics.drawString("Neural Network Architecture", 40, 35);

        try {
            ImageIO.write(image, "png", new File(fileName));
        } catch (IOException e) {
            ErrorHandler.handleIOException(e, "Could not save image");

        }
    }
}