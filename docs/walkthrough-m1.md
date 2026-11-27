# Milestone 1 walkthrough: ingestion

What got built in Milestone 1, and why each piece is shaped the way it is.

## The problem

A user will ask "What does NIST require for disabling inactive accounts?" and needs an answer that
cites the exact control, like `AC-2(3)`. Before any question can be answered, the standard itself
has to be turned into something searchable. That's this milestone: **get NIST SP 800-53 into a
database in a shape that supports both keyword and meaning-based search, with every piece tied to
one citable ID.**

## The flow

```
scripts/fetch-catalog.ps1      download the official catalog (pinned + hash-checked)
        │
        ▼
OscalCatalogParser             JSON tree  →  1,014 ControlChunk records
        │
        ▼
IngestService                  skip unchanged chunks, embed the rest in batches of 32
        │        ▲
        │        └── OpenAiCompatibleEmbeddingClient → Ollama all-minilm (384 numbers per chunk)
        ▼
ControlRepository              upsert into Postgres `controls` (text + tsvector + vector)
```

Run it: `scripts\fetch-catalog.ps1`, then `mvn spring-boot:run "-Dspring-boot.run.arguments=ingest"`.

## Step by step

### 1. Getting the data (`scripts/fetch-catalog.*`)
NIST publishes 800-53 as **OSCAL**, a machine-readable JSON format, on GitHub. The script downloads it
from **one specific commit** and checks its **SHA-256 hash**.

- *Why pin a commit?* If NIST edits the file, your tests and evaluation numbers would silently change.
  Pinning makes every run use identical data; upgrading becomes a deliberate one-line change.
- *Why the hash?* It proves the file is exactly what was tested, not a partial or tampered download.
- *Why not commit the 10 MB file?* It's reproducible from the script, so it's gitignored.

### 2. Parsing (`catalog/OscalCatalogParser.java`)
The catalog is a tree: 20 **families** (AC = Access Control) → 324 **controls** (AC-2) → 872
**enhancements** (AC-2(3)). 182 are **withdrawn** (moved elsewhere) and are skipped, leaving **1,014**.

Each control has parts. The parser keeps:
- **statement**: what's required, as nested items (`a.`, `1.`, `(a)`), indented so the nesting survives.
- **guidance**: NIST's discussion of why and how.
- It drops the assessment parts, which belong to a different publication (800-53A).

The tricky part is **parameters**. The raw text says
`Disable accounts within {{ insert: param, ac-02.03_odp.01 }}`. The parser looks up that parameter and
writes it the way the printed standard does: `[Assignment: organization-defined time period]`, or for
multiple choice, `[Selection (one or more): organization-level; system-level]`. Parameters are collected
from the whole catalog first because some text references another control's parameters. A depth limit
stops a malformed cycle from looping forever.

### 3. Chunking: one chunk per control (`catalog/ControlChunk.java`)
Most RAG tutorials split documents into fixed 500-token windows. This project splits **by control ID**.

- *Why?* The whole product promise is exact citations. If a chunk is "the second half of AC-2 and the
  first half of AC-3", you can't say which control an answer came from. One chunk = one ID makes
  citations trivial and checkable (that's milestone 2's validator).
- *Tradeoff:* chunks vary in size; some guidance sections are long. The embedding only uses the
  ID, title, statement and the first 600 characters of guidance, because small embedding models only
  read the first few hundred tokens anyway. The full text is still stored for keyword search and display.

### 4. Embeddings (`embed/`)
An **embedding** turns text into a list of numbers (here 384) so that texts with similar meaning have
similar numbers. That's what lets "lock out unused logins" find AC-2(3) even though it shares no words.

- `EmbeddingClient` is an **interface**; `OpenAiCompatibleEmbeddingClient` calls any service that speaks
  the OpenAI `/embeddings` format. Default is **local Ollama** (free, offline, no API key). Switching to
  OpenAI or another provider is a config change, not a code change.
- The client **sorts results by the index the API returns** rather than trusting response order, and
  **fails loudly** if it gets back fewer vectors than it sent. A silent mismatch would attach the wrong
  vector to the wrong control, which is the worst possible bug for a citation tool.

### 5. Storage (`db/migration/V1__controls.sql`, `store/ControlRepository.java`)
One Postgres table, managed by **Flyway** migrations (versioned SQL files applied automatically).

- `search_vector` is a **generated tsvector column**: Postgres's built-in full-text index. It's
  **weighted**: a match in the ID or title (A) outranks the statement (B), which outranks the discussion
  (C). That's why searching "account management" returns AC-2 first instead of a control that merely
  mentions accounts.
- `embedding` is a plain `REAL[]` array, **not pgvector**. With 1,014 rows, comparing a question to every
  vector in Java takes milliseconds; a vector index only pays off at much larger scale. It also avoided
  installing a native extension on Windows. On AWS (milestone 4) RDS includes pgvector, and switching is
  isolated to one class.
- Queries use plain **JDBC (JdbcTemplate)**, not JPA, because they rely on Postgres-specific features
  (`websearch_to_tsquery`, `ts_rank_cd`, arrays) that an ORM would hide or fight.
- `websearch_to_tsquery` accepts raw user text, so input like `"policy & procedures -` doesn't crash
  the query the way `to_tsquery` would.

### 6. Re-running safely (`ingest/IngestService.java`)
Ingest is **idempotent**: running it twice leaves the same result.

- Rows are **upserted** (`INSERT ... ON CONFLICT DO UPDATE`).
- Each chunk stores a **SHA-256 of the text that was embedded** plus the model name. On a re-run, chunks
  whose hash and model match are **not re-embedded**. First run: 1,014 embedded in about 85 s. Second run:
  0 embedded.
- If the text changed but no new vector was computed (`--skip-embeddings`), the old vector is **dropped**
  instead of kept, so a stale vector can never sit under a new fingerprint. There's a test for exactly this.
- Controls that disappear from the catalog are deleted.

### 7. Tests and CI
19 tests, run on every push by GitHub Actions:
- **Parser unit tests** on a real excerpt of the catalog (AC-1, AC-2, AC-2(3), withdrawn AC-2(10)):
  withdrawn skipped, parameters rendered, nesting kept, nothing left unrendered.
- **Full-catalog test**: exactly 1,014 unique IDs across 20 families.
- **Embedding client tests** with a mocked HTTP server: out-of-order responses, short responses.
- **Integration tests against real Postgres**, not an in-memory fake, because what's being tested *is*
  Postgres behavior (tsvector ranking, arrays). CI starts a `postgres:16` container for them.
- The workflow has a read-only token and actions pinned to commit SHAs (supply-chain hygiene).

## Questions to be ready for

1. **Why chunk by control instead of by token count?** Citations: one chunk maps to one ID.
2. **What's an embedding, and why do you also need keyword search?** Embeddings catch meaning
   ("unused logins" ≈ "inactive accounts"); keywords catch exact terms and IDs ("AC-2", "FIPS 140-3")
   that embeddings blur. Milestone 2 merges both (hybrid search).
3. **Why not pgvector?** 1,014 vectors; brute force is milliseconds. Measured need first, indexed later.
4. **How do you avoid paying to re-embed everything?** Content hash + model per row.
5. **What happens if the embedding API returns results out of order?** They're sorted by index; there's a test.
6. **Why pin the dataset?** Reproducible tests and evals; upgrades are deliberate.
7. **Why test against real Postgres?** The logic under test lives in Postgres; a mock would test nothing.
