package com.urlcheck.ai.knowledge.embedding;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.AbstractEmbeddingModel;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.Resource;
import org.springframework.util.Assert;

/**
 * Embeddings from a local ONNX sentence-transformer: no provider, no key, no
 * network call, and nothing to pay per question.
 *
 * <p>Two details decide whether the vectors are any good, and neither shows up
 * as an error when it is wrong:
 *
 * <ul>
 * <li>Pooling is <em>CLS</em>, not mean. The bge-* family is trained with the
 * [CLS] token as the sentence vector, which is what the model's own
 * {@code 1_Pooling/config.json} declares. Averaging the tokens instead still
 * produces the right number of plausible floats and quietly worse retrieval.
 * <li>The vector is L2-normalised, so a cosine score is a plain cosine
 * similarity and the configured threshold means the same thing for every
 * question.
 * </ul>
 *
 * <p>The model sits on the classpath, so startup is the only thing that can
 * fail: {@link #afterPropertiesSet()} loads and runs it once, which turns a
 * wrong path or a mismatched pair of files into a clear error instead of a bad
 * answer.
 */
public class OnnxEmbeddingModel extends AbstractEmbeddingModel implements InitializingBean, AutoCloseable {

    /** A BERT encoder's token-level output; row 0 is the CLS token. */
    private static final String HIDDEN_STATE_OUTPUT = "last_hidden_state";

    private final Resource modelResource;
    private final Resource tokenizerResource;
    private final int dimensions;
    private final int maxSequenceLength;

    private final OrtEnvironment environment = OrtEnvironment.getEnvironment();
    private HuggingFaceTokenizer tokenizer;
    private OrtSession session;
    private Set<String> modelInputs;

    public OnnxEmbeddingModel(Resource modelResource, Resource tokenizerResource, int dimensions,
            int maxSequenceLength) {
        Assert.notNull(modelResource, "model resource must not be null");
        Assert.notNull(tokenizerResource, "tokenizer resource must not be null");
        Assert.isTrue(dimensions > 0, "dimensions must be positive");
        Assert.isTrue(maxSequenceLength > 0, "maxSequenceLength must be positive");
        this.modelResource = modelResource;
        this.tokenizerResource = tokenizerResource;
        this.dimensions = dimensions;
        this.maxSequenceLength = maxSequenceLength;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        try (InputStream tokenizerJson = this.tokenizerResource.getInputStream()) {
            this.tokenizer = HuggingFaceTokenizer.newInstance(tokenizerJson, Map.of());
        }

        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            this.session = this.environment.createSession(this.modelResource.getContentAsByteArray(), options);
        }

        this.modelInputs = this.session.getInputNames();
        Assert.isTrue(this.session.getOutputNames().contains(HIDDEN_STATE_OUTPUT),
                () -> "This ONNX model has no " + HIDDEN_STATE_OUTPUT + " output, so it is not a BERT-style"
                        + " embedder. Outputs found: " + this.session.getOutputNames());

        // One throwaway pass: it proves the model runs, that pooling produces
        // the width the Qdrant collection is sized for, and that the tokenizer
        // and the model belong together.
        int produced = embed("dimension probe").length;
        Assert.isTrue(produced == this.dimensions, () -> "app.ai.knowledge.embedding.dimensions is "
                + this.dimensions + " but the model produces " + produced);
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> instructions = request.getInstructions();
        if (instructions.isEmpty()) {
            return new EmbeddingResponse(List.of());
        }

        Encoding[] encodings = this.tokenizer.batchEncode(instructions);

        // One sequence length for the whole batch, capped at what the model was
        // trained for, and never zero (an empty tensor is not a valid input).
        int width = 1;
        for (Encoding encoding : encodings) {
            width = Math.max(width, Math.min(encoding.getIds().length, this.maxSequenceLength));
        }

        long[][] inputIds = new long[encodings.length][width];
        long[][] attentionMask = new long[encodings.length][width];
        long[][] tokenTypeIds = new long[encodings.length][width];
        for (int row = 0; row < encodings.length; row++) {
            // Truncation is head-first, which keeps the CLS token at index 0.
            int length = Math.min(encodings[row].getIds().length, width);
            System.arraycopy(encodings[row].getIds(), 0, inputIds[row], 0, length);
            System.arraycopy(encodings[row].getAttentionMask(), 0, attentionMask[row], 0, length);
            System.arraycopy(encodings[row].getTypeIds(), 0, tokenTypeIds[row], 0, length);
        }

        List<Embedding> embeddings = new ArrayList<>(encodings.length);
        try (OnnxTensor ids = OnnxTensor.createTensor(this.environment, inputIds);
                OnnxTensor mask = OnnxTensor.createTensor(this.environment, attentionMask);
                OnnxTensor types = OnnxTensor.createTensor(this.environment, tokenTypeIds)) {

            // Only the inputs this model declares: some exports do not take
            // token_type_ids, and handing a session an undeclared input fails.
            Map<String, OnnxTensor> inputs = new LinkedHashMap<>();
            inputs.put("input_ids", ids);
            inputs.put("attention_mask", mask);
            inputs.put("token_type_ids", types);
            inputs.keySet().retainAll(this.modelInputs);

            try (OrtSession.Result results = this.session.run(inputs)) {
                float[][][] hidden = (float[][][]) results.get(HIDDEN_STATE_OUTPUT).get().getValue();
                for (int row = 0; row < hidden.length; row++) {
                    embeddings.add(new Embedding(l2Normalised(hidden[row][0]), row));
                }
            }
        }
        catch (OrtException ex) {
            throw new IllegalStateException("The embedding model failed to run", ex);
        }

        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
        String content = getEmbeddingContent(document);
        Assert.notNull(content, "The document has no text to embed");
        return embed(content);
    }

    /**
     * The vector width, known from configuration rather than measured. The base
     * implementation would embed a dummy string to find it out, and the Qdrant
     * collection is sized from this before anything is embedded.
     */
    @Override
    public int dimensions() {
        return this.dimensions;
    }

    @Override
    public void close() throws OrtException {
        try {
            if (this.session != null) {
                this.session.close();
            }
        }
        finally {
            if (this.tokenizer != null) {
                this.tokenizer.close();
            }
        }
    }

    private static float[] l2Normalised(float[] vector) {
        double sumOfSquares = 0;
        for (float value : vector) {
            sumOfSquares += value * value;
        }
        double norm = Math.sqrt(sumOfSquares);
        if (norm < 1e-12) {
            return vector.clone();
        }
        float[] normalised = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            normalised[i] = (float) (vector[i] / norm);
        }
        return normalised;
    }
}