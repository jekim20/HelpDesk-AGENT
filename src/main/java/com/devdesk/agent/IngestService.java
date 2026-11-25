package com.devdesk.agent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

/**
 * 6장 — RAG 인제스트(Indexing).
 *
 * <p>읽기(Reader) → 나누기(Splitter) → 메타데이터 보강 → 저장(VectorStore).
 * 임베딩 호출은 VectorStore 가 알아서 한다.
 */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);
    private static final Set<String> REQUIRED_POLICY_SOURCES = Set.of(
            "vpn-guide.md",
            "database-access-policy.md",
            "access-request-guide.md",
            "incident-response-guide.md");

    private final VectorStore vectorStore;
    private final RagIndexReadiness readiness;

    public IngestService(VectorStore vectorStore) {
        this(vectorStore, new RagIndexReadiness());
    }

    @Autowired
    public IngestService(VectorStore vectorStore, RagIndexReadiness readiness) {
        this.vectorStore = vectorStore;
        this.readiness = readiness;
    }

    /** Rebuilds the full required classpath corpus and publishes readiness only after all files succeed. */
    public synchronized List<IngestResult> reindexPolicyCorpus() {
        readiness.requireConfigured();
        Resource[] resources;
        try {
            resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath:/docs/*.md");
        } catch (Exception failure) {
            readiness.markUnavailable(AiIntegrationException.Kind.POLICY_INDEX);
            throw new AiIntegrationException(AiIntegrationException.Kind.POLICY_INDEX, failure);
        }
        return reindexPolicyDocuments(resources);
    }

    synchronized List<IngestResult> reindexPolicyDocuments(Resource[] resources) {
        readiness.requireConfigured();
        readiness.beginIndexing();
        try {
            if (resources.length == 0) {
                throw new IllegalStateException("policy corpus is empty");
            }

            Set<String> names = new HashSet<>();
            for (Resource resource : resources) {
                String name = resource.getFilename();
                if (name == null || !names.add(name)) {
                    throw new IllegalStateException("policy corpus contains a missing or duplicate filename");
                }
            }
            if (!names.containsAll(REQUIRED_POLICY_SOURCES)) {
                throw new MissingPolicyDocumentsException();
            }

            List<IngestResult> results = java.util.Arrays.stream(resources)
                    .sorted(java.util.Comparator.comparing(resource -> resource.getFilename()))
                    .map(resource -> ingest(resource, "policy", "IT"))
                    .toList();
            if (results.size() != resources.length || results.stream().anyMatch(result -> result.chunks() < 1)) {
                throw new IllegalStateException("policy corpus indexing was incomplete");
            }
            readiness.markReady();
            return results;
        } catch (Exception failure) {
            AiIntegrationException.Kind kind = failure instanceof MissingPolicyDocumentsException
                    ? AiIntegrationException.Kind.POLICY_INDEX
                    : AiFailureClassifier.classify(failure);
            readiness.markUnavailable(kind);
            if (failure instanceof AiIntegrationException aiFailure) {
                throw aiFailure;
            }
            throw new AiIntegrationException(kind == AiIntegrationException.Kind.PROVIDER
                    ? AiIntegrationException.Kind.POLICY_INDEX : kind, failure);
        }
    }

    public record IngestResult(String source, int chunks) {}

    /**
     * 문서 한 건을 인제스트한다.
     *
     * <p>같은 문서를 다시 넣으면 청크가 중복 적재되어 검색 결과가 같은 문장으로 도배된다.
     * 그래서 source 기준으로 먼저 지우고 다시 넣는다.
     */
    public IngestResult ingest(Resource file, String docType, String dept) {
        String source = file.getFilename() == null ? "unknown" : file.getFilename();

        deleteExisting(source);

        // ① Read — Tika 하나로 PDF·DOCX·HTML·TXT 를 모두 읽는다
        List<Document> raw = new TikaDocumentReader(file).get();

        // ② Split — 청크 크기는 "질문 하나에 답할 만한 분량"이 기준이다
        List<Document> chunks = TokenTextSplitter.builder()
                .withChunkSize(800)
                .withMinChunkSizeChars(350)
                .withKeepSeparator(true)
                .build()
                .apply(raw);

        if (chunks.isEmpty()) {
            throw new IllegalStateException("policy document produced no text chunks");
        }

        // ③ Enrich — 인제스트 때 안 넣은 메타데이터는 나중에 넣을 수 없다
        List<Document> enriched = chunks.stream()
                .map(chunk -> {
                    Map<String, Object> meta = new HashMap<>(chunk.getMetadata());
                    meta.put("source", source);
                    meta.put("docType", docType);
                    meta.put("dept", dept);
                    meta.put("version", LocalDate.now().toString());
                    return new Document(chunk.getText(), meta);
                })
                .toList();

        // ④ Write — 임베딩 + 저장
        vectorStore.add(enriched);
        log.info("인제스트 완료 source={} chunks={}", source, enriched.size());

        return new IngestResult(source, enriched.size());
    }

    /** 재색인 대비 — 이 문서에서 나온 청크를 모두 지운다. */
    private void deleteExisting(String source) {
        try {
            vectorStore.delete("source == '" + source + "'");
        } catch (Exception e) {
            // Continuing would leave stale/duplicate chunks and could report a false success.
            throw new IllegalStateException("existing policy chunks could not be replaced", e);
        }
    }

    private static final class MissingPolicyDocumentsException extends RuntimeException {}
}
