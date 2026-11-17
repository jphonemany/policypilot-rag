package com.policypilot.store;

import java.sql.Array;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.policypilot.catalog.ControlChunk;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Plain JDBC: the queries use Postgres full-text features (tsvector, websearch_to_tsquery) that an ORM would hide. */
@Repository
public class ControlRepository {

    private final JdbcTemplate jdbc;

    public ControlRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Insert or update one control. A null embedding keeps the stored vector only while the text is unchanged. */
    public void upsert(ControlChunk c, float[] embedding, String embeddingModel) {
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO controls (control_id, sort_id, family, family_title, parent_id, title, statement, guidance,
                                          embedding, embedding_model, content_hash, ingested_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                    ON CONFLICT (control_id) DO UPDATE SET
                        sort_id = EXCLUDED.sort_id, family = EXCLUDED.family, family_title = EXCLUDED.family_title,
                        parent_id = EXCLUDED.parent_id, title = EXCLUDED.title, statement = EXCLUDED.statement,
                        guidance = EXCLUDED.guidance, ingested_at = now(),
                        -- No new vector: keep the old one only if the text it was made from is unchanged.
                        embedding = CASE WHEN EXCLUDED.embedding IS NOT NULL THEN EXCLUDED.embedding
                                         WHEN controls.content_hash = EXCLUDED.content_hash THEN controls.embedding END,
                        embedding_model = CASE WHEN EXCLUDED.embedding IS NOT NULL THEN EXCLUDED.embedding_model
                                               WHEN controls.content_hash = EXCLUDED.content_hash THEN controls.embedding_model END,
                        content_hash = EXCLUDED.content_hash
                    """);
            ps.setString(1, c.controlId());
            ps.setString(2, c.sortId());
            ps.setString(3, c.family());
            ps.setString(4, c.familyTitle());
            ps.setString(5, c.parentId());
            ps.setString(6, c.title());
            ps.setString(7, c.statement());
            ps.setString(8, c.guidance());
            ps.setArray(9, embedding == null ? null : toSqlArray(con, embedding));
            ps.setString(10, embedding == null ? null : embeddingModel);
            ps.setString(11, c.contentHash());
            return ps;
        });
    }

    /** control_id → "hash|model" for rows that already have a vector, so ingest can skip unchanged ones. */
    public Map<String, String> embeddedFingerprints() {
        Map<String, String> out = new HashMap<>();
        jdbc.query("SELECT control_id, content_hash, embedding_model FROM controls WHERE embedding IS NOT NULL",
                rs -> { out.put(rs.getString(1), rs.getString(2) + "|" + rs.getString(3)); });
        return out;
    }

    /** Remove controls that are no longer in the catalog (for example, newly withdrawn ones). */
    public int deleteAllExcept(Collection<String> keepIds) {
        return jdbc.update(con -> {
            var ps = con.prepareStatement("DELETE FROM controls WHERE NOT (control_id = ANY (?))");
            ps.setArray(1, con.createArrayOf("text", keepIds.toArray()));
            return ps;
        });
    }

    public int count() {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM controls", Integer.class);
        return n == null ? 0 : n;
    }

    public int countWithEmbeddings() {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM controls WHERE embedding IS NOT NULL", Integer.class);
        return n == null ? 0 : n;
    }

    /**
     * Keyword search ranked by ts_rank_cd (rewards terms appearing close together). websearch_to_tsquery
     * accepts plain user text, quotes and "-word" without throwing on special characters.
     */
    public List<String> searchText(String query, int limit) {
        return jdbc.queryForList("""
                SELECT control_id
                FROM controls, websearch_to_tsquery('english', ?) q
                WHERE search_vector @@ q
                ORDER BY ts_rank_cd(search_vector, q) DESC, sort_id
                LIMIT ?
                """, String.class, query, limit);
    }

    public float[] findEmbedding(String controlId) {
        List<float[]> rows = jdbc.query("SELECT embedding FROM controls WHERE control_id = ?",
                (rs, i) -> fromSqlArray(rs.getArray(1)), controlId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static Array toSqlArray(java.sql.Connection con, float[] v) throws java.sql.SQLException {
        Float[] boxed = new Float[v.length];
        for (int i = 0; i < v.length; i++) {
            boxed[i] = v[i];
        }
        return con.createArrayOf("float4", boxed);
    }

    private static float[] fromSqlArray(Array a) throws java.sql.SQLException {
        if (a == null) {
            return null;
        }
        Object[] values = (Object[]) a.getArray();
        float[] out = new float[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = ((Number) values[i]).floatValue();
        }
        return out;
    }
}
