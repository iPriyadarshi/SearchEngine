package com.searchengine.semantic;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import java.io.IOException;
import java.nio.LongBuffer;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns text into a 384-dimensional unit vector with the bge-small-en-v1.5
 * sentence-embedding model, run on the CPU through ONNX Runtime. Texts with
 * similar meaning get vectors with a high dot product, even when they share
 * no words.
 * <p>
 * Follows the model's intended use: the {@code [CLS]} token's final hidden
 * state is the embedding, L2-normalized, and search queries (not documents)
 * get the instruction prefix {@value #QUERY_PREFIX}.
 * <p>
 * A model directory holds {@code model.onnx} and {@code vocab.txt}.
 * Thread-safe: ONNX Runtime sessions may be run concurrently.
 */
public final class Embedder implements AutoCloseable {

    public static final String QUERY_PREFIX = "Represent this sentence for searching relevant passages: ";

    public static final int QUERY_MAX_TOKENS = 64;

    private final OrtEnvironment env;

    private final OrtSession session;

    private final WordPieceTokenizer tokenizer;

    private final boolean takesTokenTypes;

    private int dimensions;

    private Embedder(OrtEnvironment env, OrtSession session, WordPieceTokenizer tokenizer) {

        this.env = env;

        this.session = session;

        this.tokenizer = tokenizer;

        this.takesTokenTypes = session.getInputNames().contains("token_type_ids");
    }

    /**
     * @param threads threads ONNX Runtime may use inside one inference call
     */
    public static Embedder load(Path modelDir, int threads) throws IOException {

        try {

            OrtEnvironment env = OrtEnvironment.getEnvironment();

            OrtSession.SessionOptions options = new OrtSession.SessionOptions();

            options.setIntraOpNumThreads(threads);

            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);

            OrtSession session = env.createSession(modelDir.resolve("model.onnx").toString(), options);

            WordPieceTokenizer tokenizer = WordPieceTokenizer.load(modelDir.resolve("vocab.txt"));

            Embedder embedder = new Embedder(env, session, tokenizer);

            // The model defines the vector size; ask it once.
            embedder.dimensions = embedder.embedRaw(List.of("probe"), 8)[0].length;

            return embedder;

        } catch (OrtException e) {

            throw new IOException("Cannot load embedding model from " + modelDir, e);
        }
    }

    public int dimensions() {

        return dimensions;
    }

    /**
     * Embed documents (no instruction prefix), at most {@code maxTokens} tokens each.
     */
    public float[][] embedDocuments(List<String> texts, int maxTokens) throws IOException {

        try {

            return embedRaw(texts, maxTokens);

        } catch (OrtException e) {

            throw new IOException("Embedding failed", e);
        }
    }

    public float[] embedQuery(String query) throws IOException {

        try {

            return embedRaw(List.of(QUERY_PREFIX + query), QUERY_MAX_TOKENS)[0];

        } catch (OrtException e) {

            throw new IOException("Embedding failed", e);
        }
    }

    private float[][] embedRaw(List<String> texts, int maxTokens) throws OrtException {

        int[][] ids = new int[texts.size()][];

        int length = 0;

        for (int i = 0; i < texts.size(); i++) {

            ids[i] = tokenizer.encode(texts.get(i), maxTokens);

            length = Math.max(length, ids[i].length);
        }

        int batch = texts.size();

        long[] inputIds = new long[batch * length];

        long[] mask = new long[batch * length];

        for (int i = 0; i < batch; i++) {

            for (int j = 0; j < ids[i].length; j++) {

                inputIds[i * length + j] = ids[i][j];

                mask[i * length + j] = 1;
            }
        }

        long[] shape = {batch, length};

        Map<String, OnnxTensor> inputs = new HashMap<>();

        try {

            inputs.put("input_ids", OnnxTensor.createTensor(env, LongBuffer.wrap(inputIds), shape));

            inputs.put("attention_mask", OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape));

            if (takesTokenTypes) {

                inputs.put("token_type_ids", OnnxTensor.createTensor(env, LongBuffer.wrap(new long[batch * length]), shape));
            }

            try (OrtSession.Result result = session.run(inputs)) {

                float[][][] hidden = (float[][][]) result.get(0).getValue();

                float[][] vectors = new float[batch][];

                for (int i = 0; i < batch; i++) {

                    vectors[i] = normalize(hidden[i][0]);
                }

                return vectors;
            }

        } finally {

            for (OnnxTensor tensor : inputs.values()) {

                tensor.close();
            }
        }
    }

    static float[] normalize(float[] v) {

        double norm = 0;

        for (float x : v) {

            norm += x * x;
        }

        norm = Math.sqrt(norm);

        float[] out = new float[v.length];

        for (int i = 0; i < v.length; i++) {

            out[i] = (float) (v[i] / Math.max(norm, 1e-12));
        }

        return out;
    }

    @Override
    public void close() throws IOException {

        try {

            session.close();

        } catch (OrtException e) {

            throw new IOException(e);
        }
    }
}
