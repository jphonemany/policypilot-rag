package com.policypilot.ingest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.policypilot.PolicyPilotProperties;
import com.policypilot.catalog.ControlChunk;
import com.policypilot.catalog.OscalCatalogParser;
import com.policypilot.embed.EmbeddingClient;
import com.policypilot.store.ControlRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Catalog file → chunks → embeddings → Postgres. Safe to re-run: rows are upserted, controls that
 * left the catalog are deleted, and only chunks whose text or embedding model changed are re-embedded.
 */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    private final OscalCatalogParser parser;
    private final EmbeddingClient embedder;
    private final ControlRepository repo;
    private final PolicyPilotProperties props;

    public IngestService(ObjectMapper mapper, EmbeddingClient embedder, ControlRepository repo, PolicyPilotProperties props) {
        this.parser = new OscalCatalogParser(mapper);
        this.embedder = embedder;
        this.repo = repo;
        this.props = props;
    }

    public record Result(int chunks, int embedded, int reused, int deleted) {
    }

    public Result ingest(Path catalog, boolean withEmbeddings) throws IOException {
        List<ControlChunk> chunks;
        try (InputStream in = Files.newInputStream(catalog)) {
            chunks = parser.parse(in);
        }
        log.info("parsed {} controls and enhancements from {}", chunks.size(), catalog);

        // Skip chunks whose embedded text and model match what is stored: re-runs cost nothing.
        Map<String, String> existing = withEmbeddings ? repo.embeddedFingerprints() : Map.of();
        List<ControlChunk> toEmbed = new ArrayList<>();
        for (ControlChunk c : chunks) {
            if (withEmbeddings && !(c.contentHash() + "|" + embedder.model()).equals(existing.get(c.controlId()))) {
                toEmbed.add(c);
            } else {
                repo.upsert(c, null, null);
            }
        }

        int batch = Math.max(1, props.embeddings().batchSize());
        for (int i = 0; i < toEmbed.size(); i += batch) {
            List<ControlChunk> slice = toEmbed.subList(i, Math.min(i + batch, toEmbed.size()));
            List<float[]> vectors = embedder.embed(slice.stream().map(ControlChunk::embeddingText).toList());
            for (int j = 0; j < slice.size(); j++) {
                repo.upsert(slice.get(j), vectors.get(j), embedder.model());
            }
            log.info("embedded {}/{}", Math.min(i + batch, toEmbed.size()), toEmbed.size());
        }

        int deleted = repo.deleteAllExcept(chunks.stream().map(ControlChunk::controlId).collect(Collectors.toSet()));
        Result r = new Result(chunks.size(), toEmbed.size(), withEmbeddings ? chunks.size() - toEmbed.size() : 0, deleted);
        log.info("ingest done: {} chunks, {} embedded, {} unchanged, {} removed", r.chunks(), r.embedded(), r.reused(), r.deleted());
        return r;
    }

    public Path defaultCatalogPath() {
        return Path.of(props.catalogPath());
    }
}
