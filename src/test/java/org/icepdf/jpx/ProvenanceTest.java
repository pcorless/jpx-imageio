/*
 * Copyright 2026 Patrick Corless
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Provenance: ORIGINAL.
 */
package org.icepdf.jpx;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The audit trail of docs/PROVENANCE-RULES.md, checked mechanically:
 * <ul>
 *     <li>every {@code src/main} file has a {@code Provenance:} line with known origin tags;</li>
 *     <li>every file is listed in PROVENANCE.md, with the same tags;</li>
 *     <li>PROVENANCE.md lists no file that doesn't exist;</li>
 *     <li>files derived from OpenJPEG name the pinned revision and keep its BSD-2 notice; files
 *     derived from pdf.js name the pinned revision and keep the Mozilla copyright line.</li>
 * </ul>
 */
public class ProvenanceTest {

    static final Set<String> TAGS = new TreeSet<>(Arrays.asList("PORTED", "ADAPTED", "SPEC", "ORIGINAL", "INFERRED"));
    static final String OPENJPEG_REV = "6c4a29b0";
    static final String PDFJS_REV = "674e7ee38";
    static final Pattern TAG = Pattern.compile("\\b(PORTED|ADAPTED|SPEC|ORIGINAL|INFERRED)\\b");

    static Path root() {
        return Paths.get(System.getProperty("jpx.projectDir", "."));
    }

    /** "Provenance:" header text: from the tag line up to the first blank comment line. */
    static String provenance(String source) {
        int i = source.indexOf("Provenance:");
        if (i < 0) return null;
        int end = source.indexOf("\n *\n", i);
        int close = source.indexOf("*/", i);
        if (end < 0 || (close >= 0 && close < end)) end = close;
        return source.substring(i, end < 0 ? source.length() : end);
    }

    static Set<String> tags(String text) {
        Set<String> out = new TreeSet<>();
        Matcher m = TAG.matcher(text);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    /** PROVENANCE.md file table: path -> origin column. */
    static Map<String, String> table() throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        boolean inFiles = false;
        for (String line : Files.readAllLines(root().resolve("PROVENANCE.md"), StandardCharsets.UTF_8)) {
            if (line.startsWith("## ")) inFiles = line.startsWith("## Files");
            if (!inFiles || !line.startsWith("| `")) continue;
            String[] cols = line.split("\\|");
            out.put(cols[1].trim().replace("`", ""), cols[2].trim());
        }
        return out;
    }

    @Test
    public void everySourceFileHasAnAuditTrail() throws IOException {
        Path main = root().resolve("src/main");
        List<Path> files;
        try (Stream<Path> s = Files.walk(main)) {
            files = s.filter(Files::isRegularFile).sorted().collect(Collectors.toList());
        }
        Map<String, String> table = table();
        List<String> problems = new ArrayList<>();
        for (Path f : files) {
            String rel = root().relativize(f).toString().replace('\\', '/');
            String text = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
            if (rel.startsWith("src/main/resources/META-INF/services/")) {
                if (!table.containsKey(rel)) problems.add(rel + ": not in PROVENANCE.md");
                continue;
            }
            String prov = provenance(text);
            if (prov == null) {
                problems.add(rel + ": no 'Provenance:' line");
                continue;
            }
            Set<String> tags = tags(prov);
            if (tags.isEmpty()) problems.add(rel + ": Provenance line has no origin tag " + TAGS);
            String row = table.get(rel);
            if (row == null) {
                problems.add(rel + ": not in PROVENANCE.md");
            } else if (!tags(row).equals(tags)) {
                problems.add(rel + ": header tags " + tags + " but PROVENANCE.md says " + tags(row));
            }
            boolean derived = tags.contains("PORTED") || tags.contains("ADAPTED");
            if (derived && prov.contains("OpenJPEG")) {
                if (!prov.contains(OPENJPEG_REV)) problems.add(rel + ": OpenJPEG-derived but no @" + OPENJPEG_REV);
                if (!text.contains("Redistribution and use in source and binary forms")
                        || !text.contains("Universite catholique de Louvain")) {
                    problems.add(rel + ": OpenJPEG-derived but the BSD-2 notice is missing");
                }
            }
            if (derived && prov.contains("pdf.js")) {
                if (!prov.contains(PDFJS_REV)) problems.add(rel + ": pdf.js-derived but no @" + PDFJS_REV);
                if (!text.contains("Copyright 2012 Mozilla Foundation")) {
                    problems.add(rel + ": pdf.js-derived but the Mozilla copyright line is missing");
                }
            }
            if (derived && !prov.contains("OpenJPEG") && !prov.contains("pdf.js")) {
                problems.add(rel + ": " + tags + " but no upstream named");
            }
        }
        for (String listed : table.keySet()) {
            if (!Files.exists(root().resolve(listed))) problems.add(listed + ": listed in PROVENANCE.md but missing");
        }
        assertTrue(problems.isEmpty(), "provenance problems:\n  " + String.join("\n  ", problems));
    }
}
