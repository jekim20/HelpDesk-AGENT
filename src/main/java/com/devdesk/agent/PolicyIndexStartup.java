package com.devdesk.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

/** Rebuilds the process-local policy index before the application is marked ready. */
@Component
public class PolicyIndexStartup implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PolicyIndexStartup.class);
    private final IngestService ingestService;
    private final RagIndexReadiness readiness;
    private final String apiKey;
    private final boolean autoIngestEnabled;
    private final TaskExecutor taskExecutor;

    public PolicyIndexStartup(IngestService ingestService,
            RagIndexReadiness readiness,
            @Value("${spring.ai.openai.api-key:not-set}") String apiKey,
            @Value("${app.rag.auto-ingest.enabled:true}") boolean autoIngestEnabled) {
        this(ingestService, readiness, apiKey, autoIngestEnabled, Runnable::run);
    }

    @Autowired
    public PolicyIndexStartup(IngestService ingestService,
            RagIndexReadiness readiness,
            @Value("${spring.ai.openai.api-key:not-set}") String apiKey,
            @Value("${app.rag.auto-ingest.enabled:true}") boolean autoIngestEnabled,
            @Qualifier("policyIndexExecutor") TaskExecutor taskExecutor) {
        this.ingestService = ingestService;
        this.readiness = readiness;
        this.apiKey = apiKey;
        this.autoIngestEnabled = autoIngestEnabled;
        this.taskExecutor = taskExecutor;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!autoIngestEnabled) {
            readiness.markNotConfigured();
            log.info("Policy auto-indexing is disabled by configuration.");
            return;
        }
        if (!isConfigured(apiKey)) {
            readiness.markNotConfigured();
            log.warn("Policy indexing skipped: AI credentials are not configured; non-AI features remain available.");
            return;
        }
        readiness.beginIndexing();
        try {
            taskExecutor.execute(this::indexPolicyCorpus);
        } catch (RuntimeException rejected) {
            readiness.markUnavailable(AiIntegrationException.Kind.POLICY_INDEX);
            log.warn("Policy indexing could not be scheduled.");
        }
    }

    private void indexPolicyCorpus() {
        try {
            var results = ingestService.reindexPolicyCorpus();
            int chunks = results.stream().mapToInt(IngestService.IngestResult::chunks).sum();
            log.info("Policy index is ready: documents={}, chunks={}", results.size(), chunks);
        } catch (AiIntegrationException failure) {
            // Do not log provider response bodies, request content, or credentials.
            log.warn("Policy indexing failed: category={}", failure.kind().code());
        } catch (RuntimeException failure) {
            readiness.markUnavailable(AiFailureClassifier.classify(failure));
            log.warn("Policy indexing failed: category={}", AiIntegrationException.Kind.POLICY_INDEX.code());
        }
    }

    static boolean isConfigured(String apiKey) {
        return apiKey != null && !apiKey.isBlank() && !"not-set".equalsIgnoreCase(apiKey.trim());
    }
}
