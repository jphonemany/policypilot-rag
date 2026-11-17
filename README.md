# PolicyPilot

Ask questions about federal security policy in plain English and get answers that **cite the exact NIST SP 800-53 controls** they're based on. A validator rejects any answer that cites a control the retriever didn't return, and a 100-question evaluation suite runs in CI so retrieval quality can't silently regress.

> "What does AC-2 require for reviewing accounts?" → answer + `[AC-2(3)] [AC-2 j.]` with the source text one click away.

## Why this project
- **Domain:** most Northern Virginia employers are federal contractors living with NIST 800-53 and FedRAMP. This speaks their language.
- **Measured quality:** most RAG demos stop at "it answered". This one reports recall@5, MRR, citation precision and refusal accuracy, and fails the build if they drop.
- **Continues your signature:** grounded output with a hard validator, as in fundtrack's dollar check and ai-music-discovery's artist validator.

## Stack
Spring Boot 3.5 (Java 17) · PostgreSQL 16 (weighted full-text search + embeddings, hybrid retrieval) · Flyway · any OpenAI-compatible model (local Ollama by default) · GitHub Actions. Planned: Angular UI, Terraform on AWS (RDS with pgvector).

## Run it locally
Needs Java 17, Maven, PostgreSQL 16 (user `postgres`, databases `policypilot` and `policypilot_test`) and [Ollama](https://ollama.com) with `ollama pull all-minilm`.

```powershell
scripts\fetch-catalog.ps1                                     # NIST catalog, pinned + hash-checked
mvn spring-boot:run "-Dspring-boot.run.arguments=ingest"      # 1,014 controls → Postgres, embedded locally
mvn test                                                      # 19 tests incl. real-Postgres integration
```

Override the database or model with `PP_DB_URL`, `PP_EMBED_URL`, `PP_EMBED_MODEL` (see `application.yml`). How it works, step by step: [docs/walkthrough-m1.md](docs/walkthrough-m1.md).

## Data
NIST SP 800-53 Rev. 5 controls in OSCAL JSON (public domain, from the `usnistgov/oscal-content` repository), chunked by control and enhancement ID so citations are exact.

## Definition of done
- Live demo URL, architecture diagram, demo GIF
- `docs/evals.md` with the latest eval table, and the CI job that produces it
- 80%+ backend test coverage on retrieval and validation code

See [ROADMAP.md](ROADMAP.md) for milestones. github-auto feeds the next unchecked tasks into your daily TODAY.md.
