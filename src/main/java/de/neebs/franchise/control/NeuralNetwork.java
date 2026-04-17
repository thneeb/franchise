package de.neebs.franchise.control;

import java.util.Random;

/**
 * Hand-rolled 3-layer MLP (inputSize → h1 → h2 → 1) with ReLU activations.
 * Trained via vanilla SGD with MSE loss. Serializable to/from JSON via Jackson.
 *
 * <p>Weight matrix convention: w[out][in], so z = w * v + b computes
 * z[i] = sum_j(w[i][j] * v[j]) + b[i].
 */
public class NeuralNetwork {

    private int inputSize;
    private int hidden1Size;
    private int hidden2Size;
    private long trainingRuns;

    private float[][] l1w;  // [h1][inputSize]
    private float[]   l1b;  // [h1]
    private float[][] l2w;  // [h2][h1]
    private float[]   l2b;  // [h2]
    private float[][] l3w;  // [1][h2]
    private float[]   l3b;  // [1]

    /** Required by Jackson for deserialization. */
    public NeuralNetwork() {}

    /** Creates and He-initializes a new network with the given architecture. */
    public NeuralNetwork(int inputSize, int hidden1, int hidden2) {
        this.inputSize = inputSize;
        this.hidden1Size = hidden1;
        this.hidden2Size = hidden2;
        Random rng = new Random();
        l1w = heInit(rng, hidden1, inputSize);
        l1b = new float[hidden1];
        l2w = heInit(rng, hidden2, hidden1);
        l2b = new float[hidden2];
        l3w = heInit(rng, 1, hidden2);
        l3b = new float[1];
    }

    // -------------------------------------------------------------------------
    // Inference
    // -------------------------------------------------------------------------

    public float predict(float[] input) {
        float[] a1 = relu(matVecAdd(l1w, input, l1b));
        float[] a2 = relu(matVecAdd(l2w, a1, l2b));
        return matVecAdd(l3w, a2, l3b)[0];
    }

    public float predictClamped(float[] input) {
        return clamp01(predict(input));
    }

    // -------------------------------------------------------------------------
    // Training — single SGD step, MSE loss
    // -------------------------------------------------------------------------

    public void train(float[] input, float target, float lr) {
        // Forward pass — retain pre-activation and activation values
        float[] z1 = matVecAdd(l1w, input, l1b);
        float[] a1 = relu(z1);
        float[] z2 = matVecAdd(l2w, a1, l2b);
        float[] a2 = relu(z2);
        float output = matVecAdd(l3w, a2, l3b)[0];

        // dL/d(output) for MSE loss L = (output - target)^2
        float dOut = 2.0f * (output - target);

        // --- Layer 3 ---
        // Compute gradient for a2 from original weights before updating
        float[] da2 = new float[a2.length];
        for (int j = 0; j < l3w[0].length; j++) {
            da2[j] = dOut * l3w[0][j];
            l3w[0][j] -= lr * dOut * a2[j];
        }
        l3b[0] -= lr * dOut;

        // --- Backprop through ReLU(z2) ---
        float[] dz2 = new float[z2.length];
        for (int i = 0; i < z2.length; i++) {
            dz2[i] = z2[i] > 0 ? da2[i] : 0;
        }

        // --- Layer 2 ---
        float[] da1 = new float[a1.length];
        for (int i = 0; i < l2w.length; i++) {
            for (int j = 0; j < l2w[i].length; j++) {
                da1[j] += dz2[i] * l2w[i][j];   // accumulate from original weights
            }
        }
        for (int i = 0; i < l2w.length; i++) {
            for (int j = 0; j < l2w[i].length; j++) {
                l2w[i][j] -= lr * dz2[i] * a1[j];
            }
            l2b[i] -= lr * dz2[i];
        }

        // --- Backprop through ReLU(z1) ---
        float[] dz1 = new float[z1.length];
        for (int i = 0; i < z1.length; i++) {
            dz1[i] = z1[i] > 0 ? da1[i] : 0;
        }

        // --- Layer 1 ---
        for (int i = 0; i < l1w.length; i++) {
            for (int j = 0; j < l1w[i].length; j++) {
                l1w[i][j] -= lr * dz1[i] * input[j];
            }
            l1b[i] -= lr * dz1[i];
        }
    }

    public void trainBatch(Iterable<ValueTrainingSample> samples, float lr) {
        for (ValueTrainingSample sample : samples) {
            train(sample.input(), sample.target(), lr);
        }
    }

    // -------------------------------------------------------------------------
    // Linear algebra helpers
    // -------------------------------------------------------------------------

    /** w[out][in] × v[in] + b[out] → result[out] */
    private static float[] matVecAdd(float[][] w, float[] v, float[] b) {
        float[] result = new float[w.length];
        for (int i = 0; i < w.length; i++) {
            float sum = b[i];
            for (int j = 0; j < v.length; j++) {
                sum += w[i][j] * v[j];
            }
            result[i] = sum;
        }
        return result;
    }

    private static float[] relu(float[] x) {
        float[] r = new float[x.length];
        for (int i = 0; i < x.length; i++) {
            r[i] = x[i] > 0 ? x[i] : 0;
        }
        return r;
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    private static float[][] heInit(Random rng, int out, int in) {
        float std = (float) Math.sqrt(2.0 / in);
        float[][] w = new float[out][in];
        for (int i = 0; i < out; i++) {
            for (int j = 0; j < in; j++) {
                w[i][j] = (float) (rng.nextGaussian() * std);
            }
        }
        return w;
    }

    // -------------------------------------------------------------------------
    // Getters / setters (required for Jackson serialization)
    // -------------------------------------------------------------------------

    public int getInputSize() { return inputSize; }
    public void setInputSize(int inputSize) { this.inputSize = inputSize; }

    public int getHidden1Size() { return hidden1Size; }
    public void setHidden1Size(int hidden1Size) { this.hidden1Size = hidden1Size; }

    public int getHidden2Size() { return hidden2Size; }
    public void setHidden2Size(int hidden2Size) { this.hidden2Size = hidden2Size; }

    public float[][] getL1w() { return l1w; }
    public void setL1w(float[][] l1w) { this.l1w = l1w; }

    public float[] getL1b() { return l1b; }
    public void setL1b(float[] l1b) { this.l1b = l1b; }

    public float[][] getL2w() { return l2w; }
    public void setL2w(float[][] l2w) { this.l2w = l2w; }

    public float[] getL2b() { return l2b; }
    public void setL2b(float[] l2b) { this.l2b = l2b; }

    public float[][] getL3w() { return l3w; }
    public void setL3w(float[][] l3w) { this.l3w = l3w; }

    public float[] getL3b() { return l3b; }
    public void setL3b(float[] l3b) { this.l3b = l3b; }

    public long getTrainingRuns() { return trainingRuns; }
    public void setTrainingRuns(long trainingRuns) { this.trainingRuns = trainingRuns; }
}
