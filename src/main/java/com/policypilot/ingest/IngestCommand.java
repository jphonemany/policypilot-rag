package com.policypilot.ingest;

import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Command line entry point:
 *   mvn spring-boot:run -Dspring-boot.run.arguments="ingest"
 *   mvn spring-boot:run -Dspring-boot.run.arguments="ingest --skip-embeddings"   (text only, no model needed)
 *   java -jar target/policypilot-0.1.0.jar ingest --catalog=path/to/catalog.json
 */
@Component
public class IngestCommand implements ApplicationRunner {

    private final IngestService ingest;

    public IngestCommand(IngestService ingest) {
        this.ingest = ingest;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!args.getNonOptionArgs().contains("ingest")) {
            return;
        }
        Path catalog = args.containsOption("catalog") ? Path.of(args.getOptionValues("catalog").get(0)) : ingest.defaultCatalogPath();
        if (!Files.exists(catalog)) {
            throw new IllegalStateException("catalog not found at " + catalog.toAbsolutePath() + ". Run scripts/fetch-catalog.ps1 (or .sh) first.");
        }
        ingest.ingest(catalog, !args.containsOption("skip-embeddings"));
    }
}
