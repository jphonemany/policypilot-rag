# Roadmap

## Milestone 1: Ingestion (week 1)
- [ ] Scaffold Spring Boot 3 (Java 21, Maven) with Docker Compose for PostgreSQL + pgvector
- [ ] Download the NIST SP 800-53 Rev 5 OSCAL catalog JSON into `data/` with a fetch script
- [ ] Parse controls and enhancements into chunks keyed by control ID (for example AC-2, AC-2(3))
- [ ] Flyway schema: controls table with tsvector column and pgvector embedding column
- [ ] Ingest CLI that embeds chunks through an OpenAI-compatible endpoint, with a unit test on the parser
- [ ] GitHub Actions CI: build and run tests on every push

## Milestone 2: Retrieval and grounded answers (week 2)
- [ ] Hybrid search: full-text rank + vector similarity, merged with reciprocal rank fusion
- [ ] `POST /api/ask` returns an answer plus cited control IDs and source snippets
- [ ] Citation validator: reject or regenerate when the answer cites an ID not in the retrieved set
- [ ] Out-of-scope refusal ("not covered by 800-53") with a test
- [ ] Integration tests against a real Postgres (Testcontainers)

## Milestone 3: Evaluation harness (week 3)
- [ ] Write a 100-question gold set (`evals/gold.jsonl`) with expected control IDs
- [ ] Eval runner: recall@5, MRR, citation precision and refusal accuracy
- [ ] Publish results to `docs/evals.md` with the commit SHA
- [ ] CI gate: fail the build if recall@5 drops more than 3 points
- [ ] Experiment: chunk size or reranker, and record before/after numbers

## Milestone 4: UI and deploy (week 4)
- [ ] Angular chat UI with a citation side panel showing control text
- [ ] FedRAMP baseline filter (Low, Moderate, High)
- [ ] Terraform: AWS App Runner or ECS Fargate + RDS, with a $10 budget alarm
- [ ] Deploy from GitHub Actions; add the live URL to the README
- [ ] README: architecture diagram, demo GIF, eval table
- [ ] Add measured facts to job-autopilot portfolio-bank.md
