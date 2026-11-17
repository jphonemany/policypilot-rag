package com.policypilot.catalog;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Turns the NIST SP 800-53 Rev 5 OSCAL catalog into one {@link ControlChunk} per control and
 * enhancement.
 *
 * <p>Catalog shape: catalog.groups[] (families such as AC) → controls[] (AC-2) → controls[]
 * (enhancements such as AC-2(3)). Each control has parts: a "statement" (the requirement, as nested
 * items a. / 1. / (a)), "guidance" (NIST's discussion) and 800-53A assessment parts, which are skipped.
 * Statements contain placeholders like {{ insert: param, ac-02_odp.01 }} that are rendered the way
 * the printed standard shows them: [Assignment: organization-defined time period].
 */
public final class OscalCatalogParser {

    private static final Pattern INSERT = Pattern.compile("\\{\\{\\s*insert:\\s*param,\\s*([^}\\s]+)\\s*}}");
    /** Parameters can nest (a choice can contain another parameter); this stops a malformed cycle. */
    private static final int MAX_PARAM_DEPTH = 4;

    private final ObjectMapper mapper;

    public OscalCatalogParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public List<ControlChunk> parse(InputStream catalogJson) throws IOException {
        JsonNode catalog = mapper.readTree(catalogJson).path("catalog");
        if (catalog.isMissingNode()) {
            throw new IllegalArgumentException("not an OSCAL catalog: no top-level \"catalog\" object");
        }
        // Collect every parameter first: guidance text sometimes references another control's parameters.
        Map<String, JsonNode> params = new HashMap<>();
        for (JsonNode group : catalog.path("groups")) {
            collectParams(group.path("controls"), params);
        }
        Renderer renderer = new Renderer(params);

        List<ControlChunk> chunks = new ArrayList<>();
        for (JsonNode group : catalog.path("groups")) {
            String family = group.path("id").asText().toUpperCase(Locale.ROOT);
            String familyTitle = group.path("title").asText();
            for (JsonNode control : group.path("controls")) {
                addControl(control, family, familyTitle, null, renderer, chunks);
            }
        }
        return chunks;
    }

    private void addControl(JsonNode control, String family, String familyTitle, String parentId,
                            Renderer renderer, List<ControlChunk> out) {
        // Withdrawn controls have no requirement text left, only a pointer to where it moved.
        if (isWithdrawn(control)) {
            return;
        }
        String id = displayLabel(control);
        String statement = "";
        String guidance = "";
        for (JsonNode part : control.path("parts")) {
            switch (part.path("name").asText()) {
                case "statement" -> statement = renderer.statement(part);
                case "guidance" -> guidance = renderer.prose(part.path("prose").asText("")).trim();
                default -> { } // assessment-objective / assessment-method belong to SP 800-53A
            }
        }
        out.add(new ControlChunk(id, prop(control, "sort-id", id.toLowerCase(Locale.ROOT)), family, familyTitle,
                parentId, control.path("title").asText(), statement, guidance));

        for (JsonNode enhancement : control.path("controls")) {
            addControl(enhancement, family, familyTitle, id, renderer, out);
        }
    }

    private static void collectParams(JsonNode controls, Map<String, JsonNode> into) {
        for (JsonNode control : controls) {
            for (JsonNode param : control.path("params")) {
                into.put(param.path("id").asText(), param);
            }
            collectParams(control.path("controls"), into);
        }
    }

    private static boolean isWithdrawn(JsonNode control) {
        for (JsonNode p : control.path("props")) {
            if ("status".equals(p.path("name").asText()) && "withdrawn".equals(p.path("value").asText())) {
                return true;
            }
        }
        return false;
    }

    /** The human label ("AC-2(3)") is the "label" prop without a class; the classed ones are zero-padded variants. */
    private static String displayLabel(JsonNode control) {
        for (JsonNode p : control.path("props")) {
            if ("label".equals(p.path("name").asText()) && !p.has("class")) {
                return p.path("value").asText();
            }
        }
        return control.path("id").asText().toUpperCase(Locale.ROOT);
    }

    private static String prop(JsonNode node, String name, String fallback) {
        for (JsonNode p : node.path("props")) {
            if (name.equals(p.path("name").asText())) {
                return p.path("value").asText();
            }
        }
        return fallback;
    }

    /** Renders statement trees and parameter placeholders into plain text. */
    private static final class Renderer {
        private final Map<String, JsonNode> params;

        Renderer(Map<String, JsonNode> params) {
            this.params = params;
        }

        String statement(JsonNode statementPart) {
            StringBuilder sb = new StringBuilder();
            item(statementPart, 0, sb);
            return sb.toString().trim();
        }

        /** Depth 0 is the statement itself; its items (a., b.) start at no indent, sub-items (1., (a)) indent. */
        private void item(JsonNode part, int depth, StringBuilder sb) {
            String label = prop(part, "label", "");
            String prose = prose(part.path("prose").asText(""));
            if (!label.isEmpty() || !prose.isEmpty()) {
                sb.append("  ".repeat(Math.max(0, depth - 1)));
                if (!label.isEmpty()) {
                    sb.append(label).append(' ');
                }
                sb.append(prose).append('\n');
            }
            for (JsonNode child : part.path("parts")) {
                if ("item".equals(child.path("name").asText())) {
                    item(child, depth + 1, sb);
                }
            }
        }

        String prose(String text) {
            return substitute(text, 0);
        }

        private String substitute(String text, int depth) {
            Matcher m = INSERT.matcher(text);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                m.appendReplacement(sb, Matcher.quoteReplacement(param(m.group(1), depth)));
            }
            m.appendTail(sb);
            return sb.toString();
        }

        /** Same wording as the published PDF: [Assignment: organization-defined X] or [Selection: a; b]. */
        private String param(String id, int depth) {
            JsonNode p = params.get(id);
            if (p == null || depth >= MAX_PARAM_DEPTH) {
                return "[Assignment: " + id + "]";
            }
            JsonNode select = p.path("select");
            if (!select.isMissingNode()) {
                List<String> choices = new ArrayList<>();
                for (JsonNode c : select.path("choice")) {
                    choices.add(substitute(c.asText(), depth + 1));
                }
                String how = "one-or-more".equals(select.path("how-many").asText()) ? "Selection (one or more): " : "Selection: ";
                return "[" + how + String.join("; ", choices) + "]";
            }
            if (p.hasNonNull("label")) {
                return "[Assignment: organization-defined " + p.path("label").asText() + "]";
            }
            JsonNode guideline = p.path("guidelines").path(0).path("prose");
            return "[Assignment: " + (guideline.isMissingNode() ? id : guideline.asText()) + "]";
        }
    }
}
