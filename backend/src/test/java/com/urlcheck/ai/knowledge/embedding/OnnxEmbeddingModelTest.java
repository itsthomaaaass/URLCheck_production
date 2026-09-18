package com.urlcheck.ai.knowledge.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import ai.onnxruntime.OrtException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

/**
 * Exercises the real shipped model. It is slow by unit-test standards (loading
 * the ONNX session costs a second or two) and that is the point: it is the only
 * test that can tell whether the model file, the tokenizer and the pooling
 * actually work together.
 */
class OnnxEmbeddingModelTest {

    private static final Resource MODEL =
            new ClassPathResource("ai/models/bge-small-zh-v1.5/model_quantized.onnx");
    private static final Resource TOKENIZER =
            new ClassPathResource("ai/models/bge-small-zh-v1.5/tokenizer.json");
    private static final int DIMENSIONS = 512;

    private static OnnxEmbeddingModel model;

    @BeforeAll
    static void loadTheShippedModel() throws Exception {
        model = new OnnxEmbeddingModel(MODEL, TOKENIZER, DIMENSIONS, 512);
        model.afterPropertiesSet();
    }

    @AfterAll
    static void closeTheSession() throws OrtException {
        model.close();
    }

    @Test
    void producesUnitVectorsOfTheConfiguredWidth() {
        float[] vector = model.embed("URLCheck 如何判断网页内容是否发生变化？");

        assertThat(model.dimensions()).isEqualTo(DIMENSIONS);
        assertThat(vector).hasSize(DIMENSIONS);
        assertThat(norm(vector)).isCloseTo(1.0, within(1e-3));
    }

    /**
     * The pair matters: a question and a passage that share only meaning must
     * beat a passage that merely reuses the question's words. An earlier fixture
     * paired "page has changed" with "single page application" and the model
     * preferred the word overlap, which is a property of any small embedder
     * rather than a defect in the wiring.
     */
    @Test
    void ranksTheRelatedPassageAboveAnUnrelatedOne() {
        float[] question = model.embed("How does the application detect that a page has changed?");
        float[] related = model.embed(
                "The page body is hashed with SHA-256 and compared with the stored hash,"
                        + " and a difference marks the URL as changed.");
        float[] unrelated = model.embed("Users sign in with an email address and a password.");

        assertThat(dot(question, related)).isGreaterThan(dot(question, unrelated));
    }

    /** The half of the model's vocabulary that is Chinese, which is its strong side. */
    @Test
    void ranksChinesePassagesToo() {
        float[] question = model.embed("系统怎么知道网页发生了变化？");
        float[] related = model.embed("后端把网页正文计算 SHA-256 哈希，并与库里保存的哈希做比较。");
        float[] unrelated = model.embed("用户使用邮箱和密码登录。");

        assertThat(dot(question, related)).isGreaterThan(dot(question, unrelated));
    }

    @Test
    void shortensTextThatIsLongerThanTheModelAccepts() {
        String tooLong = "change ".repeat(2000);

        assertThat(model.embed(tooLong)).hasSize(DIMENSIONS);
    }

    @Test
    void refusesADimensionThatDoesNotMatchTheModel() {
        assertThatThrownBy(() -> {
            try (OnnxEmbeddingModel wrong = new OnnxEmbeddingModel(MODEL, TOKENIZER, 384, 512)) {
                wrong.afterPropertiesSet();
            }
        }).hasMessageContaining("384");
    }

    private static double norm(float[] vector) {
        return Math.sqrt(dot(vector, vector));
    }

    /** Vectors are L2-normalised, so the dot product is the cosine similarity. */
    private static double dot(float[] left, float[] right) {
        double total = 0;
        for (int i = 0; i < left.length; i++) {
            total += left[i] * right[i];
        }
        return total;
    }
}