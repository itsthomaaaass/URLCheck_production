package com.urlcheck.ai.knowledge.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.urlcheck.ai.knowledge.KnowledgeProperties;

/**
 * Runs ingestion once, at startup.
 *
 * <p>Startup is the moment a new build meets an existing index, which is exactly
 * when knowledge files can have changed. It is not a rebuild: when nothing moved
 * the run only hashes files and reads one table, so restarts stay free, and
 * because the check is a hash comparison rather than a file watcher, the
 * knowledge cannot change underneath a running deployment.
 *
 * <p>A failure here is logged, never thrown. The knowledge base is an
 * enhancement to the assistant, while the application's own job is monitoring
 * URLs, so a Qdrant outage must not stop the process from starting; retrieval
 * reports itself unavailable until a later run succeeds.
 */
@Component
@ConditionalOnProperty(prefix = "app.ai.knowledge", name = "enabled", havingValue = "true")
public class KnowledgeIngestionRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestionRunner.class);

    private final KnowledgeIngestionService ingestion;
    private final KnowledgeProperties properties;

    public KnowledgeIngestionRunner(KnowledgeIngestionService ingestion, KnowledgeProperties properties) {
        this.ingestion = ingestion;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!this.properties.ingestOnStart()) {
            log.info("Knowledge ingestion is switched off (app.ai.knowledge.ingest-on-start=false)");
            return;
        }

        try {
            KnowledgeIngestionService.IngestionReport report = this.ingestion.ingest();
            if (report.changed() || !report.failed().isEmpty()) {
                log.info("Knowledge ingestion finished: {}", report.summary());
            }
            else {
                log.info("Knowledge base is up to date: {} document(s) unchanged, nothing embedded",
                        report.unchanged());
            }
        }
        catch (RuntimeException ex) {
            log.error("Knowledge ingestion failed; the assistant will answer without retrieved knowledge", ex);
        }
    }
}