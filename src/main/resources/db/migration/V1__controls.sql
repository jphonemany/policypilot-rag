-- One row per NIST SP 800-53 control or control enhancement (withdrawn ones are skipped).
-- control_id is the citation key the answer generator must use, e.g. 'AC-2' or 'AC-2(3)'.
CREATE TABLE controls (
    control_id      TEXT PRIMARY KEY,
    sort_id         TEXT NOT NULL,               -- 'ac-02.03': sorts AC-2 before AC-10
    family          TEXT NOT NULL,               -- 'AC'
    family_title    TEXT NOT NULL,               -- 'Access Control'
    parent_id       TEXT,                        -- 'AC-2' for enhancement 'AC-2(3)', NULL for base controls
    title           TEXT NOT NULL,
    statement       TEXT NOT NULL,               -- what the control requires, parameters rendered as [Assignment: ...]
    guidance        TEXT NOT NULL DEFAULT '',    -- NIST's discussion section
    -- Keyword search: ID and title count most, then the requirement, then the discussion.
    search_vector   TSVECTOR GENERATED ALWAYS AS (
        setweight(to_tsvector('english', control_id || ' ' || title), 'A') ||
        setweight(to_tsvector('english', statement), 'B') ||
        setweight(to_tsvector('english', guidance), 'C')
    ) STORED,
    -- Semantic search: embedding of title + statement + start of guidance.
    -- REAL[] instead of pgvector: ~1,000 rows are ranked in memory (see VectorIndex).
    embedding       REAL[],
    embedding_model TEXT,
    content_hash    TEXT NOT NULL,               -- SHA-256 of the embedded text: unchanged rows skip re-embedding
    ingested_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX controls_search_idx ON controls USING GIN (search_vector);
CREATE INDEX controls_family_idx ON controls (family);
