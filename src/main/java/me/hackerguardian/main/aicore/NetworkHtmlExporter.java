package me.hackerguardian.main.aicore;

import org.neuroph.core.Layer;
import org.neuroph.nnet.MultiLayerPerceptron;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

/**
 * Exports a modern HTML+SVG visualization of the current neural network:
 *  - Input layer with feature names
 *  - One hidden layer
 *  - Output neuron
 *  - Lines ("wires") between layers
 *
 * The page uses a modern layout and follows system dark/light theme
 * via prefers-color-scheme.
 */
public class NetworkHtmlExporter {

    public static void exportHtml(MultiLayerPerceptron net, File outFile) throws IOException {
        if (net == null) {
            throw new IllegalArgumentException("Network is null");
        }

        // Ensure dirs exist
        File parent = outFile.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }

        int inputCount = net.getInputsCount();
        int hiddenCount = 0;
        if (net.getLayersCount() > 1) {
            Layer hiddenLayer = net.getLayerAt(1);
            hiddenCount = hiddenLayer.getNeuronsCount();
        }
        int outputCount = net.getOutputsCount();

        int width = 1100;
        int height = 650;

        int xInput = 200;
        int xHidden = 550;
        int xOutput = 900;

        double inputSpacing = height / (double) (inputCount + 1);
        double hiddenSpacing = height / (double) (hiddenCount + 1);
        double outputSpacing = height / (double) (outputCount + 1);

        StringBuilder svg = new StringBuilder();
        svg.append("<svg class=\"nn-graph\" width=\"").append(width).append("\" height=\"").append(height)
                .append("\" viewBox=\"0 0 ").append(width).append(" ").append(height).append("\">\n");

        // wires: input -> hidden
        for (int i = 0; i < inputCount; i++) {
            double yIn = (i + 1) * inputSpacing;
            for (int j = 0; j < hiddenCount; j++) {
                double yHid = (j + 1) * hiddenSpacing;
                svg.append("<line class=\"wire\" x1=\"").append(xInput).append("\" y1=\"").append(yIn)
                        .append("\" x2=\"").append(xHidden).append("\" y2=\"").append(yHid)
                        .append("\" />\n");
            }
        }

        // wires: hidden -> output
        for (int j = 0; j < hiddenCount; j++) {
            double yHid = (j + 1) * hiddenSpacing;
            for (int k = 0; k < outputCount; k++) {
                double yOut = (k + 1) * outputSpacing;
                svg.append("<line class=\"wire\" x1=\"").append(xHidden).append("\" y1=\"").append(yHid)
                        .append("\" x2=\"").append(xOutput).append("\" y2=\"").append(yOut)
                        .append("\" />\n");
            }
        }

        // input nodes with labels
        for (int i = 0; i < inputCount; i++) {
            double y = (i + 1) * inputSpacing;
            String label = (i < FeatureCollector.FEATURE_NAMES.length)
                    ? FeatureCollector.FEATURE_NAMES[i]
                    : "input_" + i;

            svg.append("<circle class=\"node input\" cx=\"").append(xInput)
                    .append("\" cy=\"").append(y)
                    .append("\" r=\"9\" />\n");

            svg.append("<text class=\"label input-label\" x=\"")
                    .append(xInput - 24)
                    .append("\" y=\"").append(y + 4)
                    .append("\" text-anchor=\"end\">")
                    .append(escapeHtml(label))
                    .append("</text>\n");
        }

        // hidden nodes
        for (int j = 0; j < hiddenCount; j++) {
            double y = (j + 1) * hiddenSpacing;
            svg.append("<circle class=\"node hidden\" cx=\"").append(xHidden)
                    .append("\" cy=\"").append(y)
                    .append("\" r=\"11\" />\n");

            svg.append("<text class=\"label hidden-label\" x=\"")
                    .append(xHidden)
                    .append("\" y=\"").append(y - 16)
                    .append("\" text-anchor=\"middle\">H")
                    .append(j)
                    .append("</text>\n");
        }

        // output nodes
        for (int k = 0; k < outputCount; k++) {
            double y = (k + 1) * outputSpacing;
            svg.append("<circle class=\"node output\" cx=\"").append(xOutput)
                    .append("\" cy=\"").append(y)
                    .append("\" r=\"13\" />\n");

            svg.append("<text class=\"label output-label\" x=\"")
                    .append(xOutput + 22)
                    .append("\" y=\"").append(y + 4)
                    .append("\" text-anchor=\"start\">suspicion</text>\n");
        }

        svg.append("</svg>");

        String html = "<!DOCTYPE html>\n" +
                "<html lang=\"en\">\n" +
                "<head>\n" +
                "  <meta charset=\"UTF-8\" />\n" +
                "  <title>HackerGuardian Neural Network</title>\n" +
                "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\" />\n" +
                "  <style>\n" +
                "    :root {\n" +
                "      color-scheme: light dark;\n" +
                "      --bg: #f5f5f7;\n" +
                "      --bg-elevated: #ffffff;\n" +
                "      --border-subtle: #e0e0e6;\n" +
                "      --text: #15151a;\n" +
                "      --text-muted: #63636c;\n" +
                "      --accent: #4f46e5;\n" +
                "      --accent-soft: rgba(79, 70, 229, 0.08);\n" +
                "      --node-input: #2563eb;\n" +
                "      --node-hidden: #a855f7;\n" +
                "      --node-output: #ef4444;\n" +
                "      --wire: rgba(148, 163, 184, 0.8);\n" +
                "      --shadow-soft: 0 18px 40px rgba(15, 23, 42, 0.12);\n" +
                "    }\n" +
                "    @media (prefers-color-scheme: dark) {\n" +
                "      :root {\n" +
                "        --bg: #020617;\n" +
                "        --bg-elevated: #020617;\n" +
                "        --border-subtle: #1f2937;\n" +
                "        --text: #f9fafb;\n" +
                "        --text-muted: #9ca3af;\n" +
                "        --accent: #8b5cf6;\n" +
                "        --accent-soft: rgba(139, 92, 246, 0.08);\n" +
                "        --node-input: #38bdf8;\n" +
                "        --node-hidden: #c4b5fd;\n" +
                "        --node-output: #fb7185;\n" +
                "        --wire: rgba(148, 163, 184, 0.6);\n" +
                "        --shadow-soft: 0 24px 55px rgba(0, 0, 0, 0.66);\n" +
                "      }\n" +
                "    }\n" +
                "    * { box-sizing: border-box; }\n" +
                "    body {\n" +
                "      margin: 0;\n" +
                "      font-family: system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;\n" +
                "      background: radial-gradient(circle at top left, rgba(79, 70, 229, 0.14), transparent 55%),\n" +
                "                  radial-gradient(circle at bottom right, rgba(15, 23, 42, 0.9), transparent 55%),\n" +
                "                  var(--bg);\n" +
                "      color: var(--text);\n" +
                "      min-height: 100vh;\n" +
                "      display: flex;\n" +
                "      align-items: stretch;\n" +
                "      justify-content: center;\n" +
                "      padding: 32px 16px;\n" +
                "    }\n" +
                "    .page {\n" +
                "      width: 100%;\n" +
                "      max-width: 1320px;\n" +
                "      margin: 0 auto;\n" +
                "      background: linear-gradient(145deg, rgba(15, 23, 42, 0.12), transparent 40%), var(--bg-elevated);\n" +
                "      border-radius: 24px;\n" +
                "      border: 1px solid var(--border-subtle);\n" +
                "      box-shadow: var(--shadow-soft);\n" +
                "      overflow: hidden;\n" +
                "      display: flex;\n" +
                "      flex-direction: column;\n" +
                "    }\n" +
                "    .header {\n" +
                "      padding: 20px 28px 6px 28px;\n" +
                "      border-bottom: 1px solid rgba(148, 163, 184, 0.3);\n" +
                "    }\n" +
                "    .header-title {\n" +
                "      font-size: 24px;\n" +
                "      font-weight: 650;\n" +
                "      letter-spacing: 0.01em;\n" +
                "      display: flex;\n" +
                "      align-items: center;\n" +
                "      gap: 8px;\n" +
                "    }\n" +
                "    .header-dot {\n" +
                "      width: 9px;\n" +
                "      height: 9px;\n" +
                "      border-radius: 999px;\n" +
                "      background: radial-gradient(circle at 30% 30%, #e5e7eb, var(--accent));\n" +
                "      box-shadow: 0 0 0 5px var(--accent-soft);\n" +
                "    }\n" +
                "    .header-subtitle {\n" +
                "      margin-top: 4px;\n" +
                "      font-size: 13px;\n" +
                "      color: var(--text-muted);\n" +
                "    }\n" +
                "    .content {\n" +
                "      display: flex;\n" +
                "      flex-wrap: wrap;\n" +
                "      gap: 0;\n" +
                "    }\n" +
                "    .svg-shell {\n" +
                "      flex: 2 1 60%;\n" +
                "      padding: 18px 10px 16px 20px;\n" +
                "      min-width: 0;\n" +
                "    }\n" +
                "    .info-panel {\n" +
                "      flex: 1 1 40%;\n" +
                "      min-width: 260px;\n" +
                "      border-left: 1px solid rgba(148, 163, 184, 0.35);\n" +
                "      padding: 18px 20px 18px 20px;\n" +
                "      background: radial-gradient(circle at top, var(--accent-soft), transparent 55%);\n" +
                "    }\n" +
                "    .info-panel h2 {\n" +
                "      font-size: 15px;\n" +
                "      letter-spacing: 0.08em;\n" +
                "      text-transform: uppercase;\n" +
                "      color: var(--text-muted);\n" +
                "      margin: 0 0 10px 0;\n" +
                "    }\n" +
                "    .info-panel p {\n" +
                "      font-size: 13px;\n" +
                "      margin: 4px 0 10px 0;\n" +
                "      color: var(--text-muted);\n" +
                "      line-height: 1.5;\n" +
                "    }\n" +
                "    .info-card {\n" +
                "      border-radius: 14px;\n" +
                "      padding: 10px 12px;\n" +
                "      margin-bottom: 10px;\n" + "\n" +
                "      background: linear-gradient(135deg, rgba(15,23,42,0.35), rgba(15,23,42,0.0));\n" +
                "      border: 1px solid rgba(148, 163, 184, 0.35);\n" +
                "    }\n" +
                "    .info-metric {\n" +
                "      font-size: 13px;\n" +
                "      display: flex;\n" +
                "      justify-content: space-between;\n" +
                "      margin-bottom: 2px;\n" +
                "    }\n" +
                "    .info-metric span.key { color: var(--text-muted); }\n" +
                "    .info-metric span.value { font-weight: 550; }\n" +
                "    .pill {\n" +
                "      display: inline-flex;\n" +
                "      align-items: center;\n" +
                "      gap: 6px;\n" +
                "      font-size: 11px;\n" +
                "      padding: 4px 9px;\n" +
                "      border-radius: 999px;\n" +
                "      background: rgba(15, 23, 42, 0.76);\n" +
                "      color: #e5e7eb;\n" +
                "      border: 1px solid rgba(148, 163, 184, 0.5);\n" +
                "      margin-top: 4px;\n" +
                "    }\n" +
                "    .pill-dot {\n" +
                "      width: 6px; height: 6px; border-radius: 999px; background: var(--accent);\n" +
                "    }\n" +
                "    .nn-graph {\n" +
                "      width: 100%;\n" +
                "      border-radius: 18px;\n" +
                "      background: radial-gradient(circle at center, rgba(15, 23, 42, 0.92), rgba(2, 6, 23, 1));\n" +
                "      border: 1px solid rgba(148, 163, 184, 0.28);\n" +
                "      box-shadow: inset 0 0 0 1px rgba(15, 23, 42, 0.8);\n" +
                "    }\n" +
                "    .node { stroke-width: 1.5; stroke: rgba(15, 23, 42, 0.9); }\n" +
                "    .node.input { fill: var(--node-input); }\n" +
                "    .node.hidden { fill: var(--node-hidden); }\n" +
                "    .node.output { fill: var(--node-output); }\n" +
                "    .wire { stroke: var(--wire); stroke-width: 1.1; opacity: 0.6; }\n" +
                "    text.label { fill: #e5e7eb; font-size: 11px; }\n" +
                "    .input-label { fill: #bfdbfe; }\n" +
                "    .hidden-label { fill: #e5e7eb; }\n" +
                "    .output-label { fill: #fecaca; font-weight: 500; }\n" +
                "    .feature-list { font-size: 12px; margin: 8px 0 0 0; max-height: 310px; overflow: auto; padding-right: 4px; }\n" +
                "    .feature-list dt { font-weight: 600; margin-top: 6px; color: var(--text); }\n" +
                "    .feature-list dd { margin: 0 0 4px 10px; color: var(--text-muted); }\n" +
                "    @media (max-width: 900px) {\n" +
                "      .content { flex-direction: column; }\n" +
                "      .info-panel { border-left: none; border-top: 1px solid rgba(148,163,184,0.35); }\n" +
                "    }\n" +
                "  </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "  <main class=\"page\">\n" +
                "    <header class=\"header\">\n" +
                "      <div class=\"header-title\">\n" +
                "        <span class=\"header-dot\"></span>\n" +
                "        <span>HackerGuardian Neural Network</span>\n" +
                "      </div>\n" +
                "      <p class=\"header-subtitle\">Inputs on the left are player features, hidden neurons in the middle learn patterns, and the output neuron on the right estimates a cheating suspicion score.</p>\n" +
                "    </header>\n" +
                "    <section class=\"content\">\n" +
                "      <div class=\"svg-shell\">\n" +
                svg +
                "      </div>\n" +
                "      <aside class=\"info-panel\">\n" +
                "        <h2>Model overview</h2>\n" +
                "        <div class=\"info-card\">\n" +
                "          <div class=\"info-metric\"><span class=\"key\">Inputs</span><span class=\"value\">" + inputCount + "</span></div>\n" +
                "          <div class=\"info-metric\"><span class=\"key\">Hidden neurons</span><span class=\"value\">" + hiddenCount + "</span></div>\n" +
                "          <div class=\"info-metric\"><span class=\"key\">Outputs</span><span class=\"value\">" + outputCount + " (suspicion)</span></div>\n" +
                "          <div class=\"pill\"><span class=\"pill-dot\"></span><span>Higher output ≈ higher cheating likelihood</span></div>\n" +
                "        </div>\n" +
                "        <h2>Input features</h2>\n" +
                "        <p>Each blue dot on the left corresponds to a feature below. The network learns how to combine these signals into one suspicion score.</p>\n" +
                "        <dl class=\"feature-list\">\n" +
                buildFeatureDescriptions() +
                "        </dl>\n" +
                "      </aside>\n" +
                "    </section>\n" +
                "  </main>\n" +
                "</body>\n" +
                "</html>\n";

        try (FileWriter fw = new FileWriter(outFile, false)) {
            fw.write(html);
        }
    }

    private static String buildFeatureDescriptions() {
        StringBuilder sb = new StringBuilder();
        String[] names = FeatureCollector.FEATURE_NAMES;
        for (int i = 0; i < names.length; i++) {
            String name = names[i];
            sb.append("        <dt>")
                    .append(i).append(": ").append(escapeHtml(name))
                    .append("</dt>\n");
            sb.append("        <dd>")
                    .append(escapeHtml(getFeatureExplanation(name)))
                    .append("</dd>\n");
        }
        return sb.toString();
    }

    private static String getFeatureExplanation(String name) {
        // classic switch for Java 8 compatibility
        switch (name) {
            case "avgSpeed":
                return "Average horizontal movement speed in the recent time window.";
            case "maxHorizontalSpeed":
                return "Maximum horizontal speed reached in the window.";
            case "acceleration":
                return "Approximate change in speed over the window.";
            case "yawDeltaAvg":
                return "Average yaw (left/right) rotation per tick.";
            case "yawDeltaStd":
                return "How much yaw rotation varies (smooth vs jittery).";
            case "pitchDeltaAvg":
                return "Average pitch (up/down) rotation per tick.";
            case "pitchDeltaStd":
                return "How much pitch rotation varies.";
            case "groundRatio":
                return "Fraction of recent ticks spent on the ground.";
            case "airRatio":
                return "Fraction of recent ticks spent in the air.";
            case "jumpCount":
                return "Approximate number of jumps in the window.";
            case "cps":
                return "Estimated clicks/attacks per second.";
            case "hitRate":
                return "Ratio of successful hits to total swings.";
            case "avgHitDistance":
                return "Average distance to the target when a hit lands.";
            case "maxHitDistance":
                return "Maximum distance to the target when a hit lands.";
            case "yawDeltaOnHitAvg":
                return "Average yaw adjustment when hitting a target.";
            case "pitchDeltaOnHitAvg":
                return "Average pitch adjustment when hitting a target.";
            case "flyingPacketsPerSecond":
                return "Rate of FLYING packets sent by the client.";
            case "lookPacketsPerSecond":
                return "Rate of LOOK packets sent by the client.";
            case "positionPacketsPerSecond":
                return "Rate of POSITION/POSITION_LOOK packets.";
            case "keepAlivePacketsPerSecond":
                return "Rate of KEEP_ALIVE packets.";
            case "keepAliveIntervalAvgMs":
                return "Average time between KEEP_ALIVE packets (ms).";
            case "keepAliveIntervalStdMs":
                return "Variation in KEEP_ALIVE intervals.";
            case "inWater":
                return "Whether the player is currently inside liquid.";
            case "onLadder":
                return "Whether the player is standing on a ladder.";
            case "hasSpeedEffect":
                return "Speed potion effect is active.";
            case "hasJumpBoostEffect":
                return "Jump boost potion effect is active.";
            case "isSprinting":
                return "Whether the player is sprinting.";
            case "isSneaking":
                return "Whether the player is sneaking.";
            case "gmSurvival":
                return "Game mode: Survival (1 if yes).";
            case "gmCreative":
                return "Game mode: Creative (1 if yes).";
            case "gmAdventure":
                return "Game mode: Adventure (1 if yes).";
            case "gmSpectator":
                return "Game mode: Spectator (1 if yes).";
            case "pingMs":
                return "Measured ping of the player in ms.";
            case "tps":
                return "Server ticks per second for performance context.";
            default:
                return "";
        }
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}