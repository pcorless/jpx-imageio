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
 * Provenance: ORIGINAL. Oracle: opj_dump (OpenJPEG v2.5.4) output in fixtures/dump/.
 */
package org.icepdf.jpx.codestream;

import org.icepdf.jpx.JpxException;
import org.icepdf.jpx.codestream.Codestream.ComponentCoding;
import org.icepdf.jpx.codestream.Codestream.Tile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class CodestreamReaderTest {

    static byte[] fixture(String path) {
        try (InputStream in = CodestreamReaderTest.class.getResourceAsStream("/fixtures/" + path)) {
            if (in == null) throw new IllegalArgumentException("no fixture " + path);
            return in.readAllBytes();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    static String text(String path) {
        return new String(fixture(path), StandardCharsets.US_ASCII);
    }

    static Codestream parse(String name) throws JpxException {
        byte[] b = fixture(name);
        return CodestreamReader.read(b, 0, b.length);
    }

    static List<String> codestreamFixtures() {
        List<String> out = new ArrayList<>();
        for (String line : text("references.tsv").split("\n")) {
            if (line.startsWith("#") || line.isEmpty()) continue;
            String file = line.split("\t")[1];
            if (file.endsWith(".j2k") && !out.contains(file)) out.add(file);
        }
        return out;
    }

    static int field(String block, String name) {
        // opj_dump prints some fields with %#x ("0x1", but plain "0")
        Matcher m = Pattern.compile("\\b" + name + "=(0x[0-9a-fA-F]+|\\d+)").matcher(block);
        if (!m.find()) throw new AssertionError("no " + name + " in dump");
        String v = m.group(1);
        return v.startsWith("0x") ? Integer.parseInt(v.substring(2), 16) : Integer.parseInt(v);
    }

    @DisplayName("main-header parameters match opj_dump")
    @ParameterizedTest(name = "{0}")
    @MethodSource("codestreamFixtures")
    public void matchesOpjDump(String file) throws JpxException {
        Codestream cs = parse(file);
        String dump = text("dump/" + file.replace(".j2k", ".txt"));
        String image = dump.substring(0, dump.indexOf("Codestream info"));
        assertEquals(field(image, "x0"), cs.x0);
        assertEquals(field(image, "y0"), cs.y0);
        assertEquals(field(image, "x1"), cs.x1);
        assertEquals(field(image, "y1"), cs.y1);
        assertEquals(field(image, "numcomps"), cs.components.length);
        String[] comps = image.split("component \\d+ \\{");
        for (int c = 0; c < cs.components.length; c++) {
            String b = comps[c + 1];
            assertEquals(field(b, "dx"), cs.components[c].dx, "dx " + c);
            assertEquals(field(b, "dy"), cs.components[c].dy, "dy " + c);
            assertEquals(field(b, "prec"), cs.components[c].precision, "prec " + c);
            assertEquals(field(b, "sgnd") == 1, cs.components[c].signed, "sgnd " + c);
        }
        String code = dump.substring(dump.indexOf("Codestream info"));
        assertEquals(field(code, "tx0"), cs.tileX0);
        assertEquals(field(code, "ty0"), cs.tileY0);
        assertEquals(field(code, "tdx"), cs.tileWidth);
        assertEquals(field(code, "tdy"), cs.tileHeight);
        assertEquals(field(code, "tw"), cs.tilesX);
        assertEquals(field(code, "th"), cs.tilesY);
        String def = code.substring(code.indexOf("default tile"));
        String head = def.substring(0, def.indexOf("comp 0"));
        Codestream.TileCoding d = cs.defaults;
        assertEquals(field(head, "csty"), d.csty, "Scod");
        assertEquals(field(head, "prg"), d.progression, "progression");
        assertEquals(field(head, "numlayers"), d.numLayers, "layers");
        assertEquals(field(head, "mct"), d.mct, "mct");
        String[] tccps = def.split("comp \\d+ \\{");
        for (int c = 0; c < cs.components.length; c++) {
            String b = tccps[c + 1];
            ComponentCoding t = d.components[c];
            String at = file + " comp " + c + ": ";
            assertEquals(field(b, "csty"), t.csty, at + "csty");
            assertEquals(field(b, "numresolutions"), t.numResolutions, at + "numresolutions");
            Matcher cb = Pattern.compile("cblkw=2\\^(\\d+)\\s+cblkh=2\\^(\\d+)").matcher(b);
            assertTrue(cb.find());
            assertEquals(Integer.parseInt(cb.group(1)), t.cblkWidthExp, at + "cblkw");
            assertEquals(Integer.parseInt(cb.group(2)), t.cblkHeightExp, at + "cblkh");
            assertEquals(field(b, "cblksty"), t.cblkStyle, at + "cblksty");
            assertEquals(field(b, "qmfbid"), t.transform, at + "qmfbid");
            assertEquals(field(b, "qntsty"), t.quantStyle, at + "qntsty");
            assertEquals(field(b, "numgbits"), t.guardBits, at + "numgbits");
            assertEquals(field(b, "roishift"), t.roiShift, at + "roishift");
            Matcher prc = Pattern.compile("\\((\\d+),(\\d+)\\)").matcher(b.substring(b.indexOf("preccintsize"), b.indexOf("qntsty")));
            for (int r = 0; prc.find(); r++) {
                assertEquals(Integer.parseInt(prc.group(1)), t.precinctWidthExp[r], at + "prcw " + r);
                assertEquals(Integer.parseInt(prc.group(2)), t.precinctHeightExp[r], at + "prch " + r);
            }
            Matcher st = Pattern.compile("\\((\\d+),(\\d+)\\)").matcher(b.substring(b.indexOf("stepsizes"), b.indexOf("roishift")));
            for (int band = 0; st.find(); band++) {
                assertEquals(Integer.parseInt(st.group(1)), t.stepMantissa[band], at + "mant " + band);
                assertEquals(Integer.parseInt(st.group(2)), t.stepExponent[band], at + "expn " + band);
            }
        }
    }

    @DisplayName("every fixture tile has its tile-parts, and their data lies inside the stream")
    @ParameterizedTest(name = "{0}")
    @MethodSource("codestreamFixtures")
    public void tilePartsAreLocated(String file) throws JpxException {
        byte[] b = fixture(file);
        Codestream cs = CodestreamReader.read(b, 0, b.length);
        for (int t = 0; t < cs.numTiles(); t++) {
            Tile tile = cs.tile(t);
            assertNotNull(tile, "tile " + t + " has no tile-part");
            assertTrue(tile.parts.size() >= 1);
            for (Codestream.TilePart p : tile.parts) {
                assertTrue(p.dataOffset >= 0 && p.dataOffset + p.dataLength <= b.length, "tile " + t);
            }
        }
    }

    @DisplayName("TNsot one short (GH-553): all 6 tile-parts per tile are found, with a warning, no repair")
    @Test
    public void tnsotUndercount() throws JpxException {
        Codestream broken = parse("tnsot-undercount.j2k");
        Codestream intact = parse("tileparts-res.j2k");
        assertEquals(intact.numTiles(), broken.numTiles());
        for (int t = 0; t < broken.numTiles(); t++) {
            assertEquals(6, broken.tile(t).parts.size());
            assertEquals(intact.tile(t).dataLength(), broken.tile(t).dataLength());
            assertEquals(5, broken.tile(t).parts.get(0).declaredCount);
        }
        assertTrue(broken.warnings().stream().anyMatch(w -> w.contains("TNsot")), broken.warnings().toString());
        assertTrue(intact.warnings().isEmpty(), intact.warnings().toString());
    }

    @DisplayName("PPT and PPM headers are gathered per tile and add up to the headers moved out of sop-eph-tiles")
    @Test
    public void packedHeaders() throws JpxException {
        Codestream ppt = parse("ppt.j2k");
        Codestream ppm = parse("ppm.j2k");
        Codestream plain = parse("sop-eph-tiles.j2k");
        for (int t = 0; t < plain.numTiles(); t++) {
            byte[] a = ppt.tile(t).packedHeaders();
            byte[] m = ppm.tile(t).packedHeaders();
            assertNotNull(a);
            assertNotNull(m);
            assertEquals(new String(a, StandardCharsets.ISO_8859_1), new String(m, StandardCharsets.ISO_8859_1), "tile " + t);
            assertNull(plain.tile(t).packedHeaders());
            // what moved out of the tile body is exactly the headers plus SOP (6) and EPH (2) per packet
            int packets = 3 * 6 * plain.coding(t).numLayers;
            assertEquals(plain.tile(t).dataLength() - packets * 8, ppt.tile(t).dataLength() + a.length, "tile " + t);
        }
        assertEquals(Markers.CSTY_SOP | Markers.CSTY_EPH, plain.defaults.csty);
        assertEquals(0, ppt.defaults.csty);
    }

    @DisplayName("POC entries in the tile-part header are read; the main header has none")
    @Test
    public void poc() throws JpxException {
        Codestream cs = parse("poc.j2k");
        assertTrue(cs.defaults.pocs.isEmpty());
        List<Codestream.Poc> pocs = cs.tile(0).coding.pocs;
        assertEquals(2, pocs.size());
        assertEquals(0, pocs.get(0).resStart);
        assertEquals(3, pocs.get(0).resEnd);
        assertEquals(3, pocs.get(0).compEnd);
        assertEquals(Markers.CPRL, pocs.get(0).progression);
        assertEquals(3, pocs.get(1).resStart);
        assertEquals(6, pocs.get(1).resEnd);
        assertEquals(Markers.LRCP, pocs.get(1).progression);
    }

    @DisplayName("COC/QCC win over COD/QCD in the same header regardless of order (D-9)")
    @Test
    public void cocPrecedence() throws JpxException {
        // rev53-rgb with its COD moved after a COC that sets component 1 to 2 decomposition levels
        byte[] b = fixture("rev53-rgb.j2k");
        int siz = 2, sizEnd = siz + 2 + (((b[siz + 2] & 0xff) << 8) | (b[siz + 3] & 0xff));
        int cod = sizEnd, codLen = 2 + (((b[cod + 2] & 0xff) << 8) | (b[cod + 3] & 0xff));
        byte[] codSeg = java.util.Arrays.copyOfRange(b, cod, cod + codLen);
        // COC: FF53 Lcoc Ccoc=1 Scoc=0 SPcoc = levels 2, then COD's cblk/style/transform
        byte[] coc = {(byte) 0xFF, 0x53, 0, 9, 1, 0, 2, codSeg[10], codSeg[11], codSeg[12], codSeg[13]};
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(b, 0, sizEnd);
        out.write(coc, 0, coc.length);
        out.write(codSeg, 0, codSeg.length);
        out.write(b, cod + codLen, b.length - cod - codLen);
        byte[] m = out.toByteArray();
        Codestream cs = CodestreamReader.read(m, 0, m.length);
        assertEquals(6, cs.defaults.components[0].numResolutions);
        assertEquals(3, cs.defaults.components[1].numResolutions);
        assertEquals(6, cs.defaults.components[2].numResolutions);
    }

    @DisplayName("hostile streams fail with JpxException or parse; nothing else escapes")
    @Test
    public void hostileStreams() {
        int failed = 0;
        for (String f : text("hostile.txt").split("\n")) {
            if (f.isEmpty()) continue;
            byte[] b = fixture(f);
            try {
                CodestreamReader.read(b, 0, b.length);
            } catch (JpxException expected) {
                failed++;
            } catch (RuntimeException e) {
                fail(f + ": " + e, e);
            }
        }
        assertTrue(failed >= 10, "only " + failed + " hostile streams rejected");
    }

    @DisplayName("truncated mid-packet: parses with a warning and the data cut at the end")
    @Test
    public void truncatedData() throws JpxException {
        Codestream cs = parse("hostile/truncated-mid-packet.j2k");
        assertTrue(cs.warnings().stream().anyMatch(w -> w.contains("truncated") || w.contains("past the end")), cs.warnings().toString());
    }

    @DisplayName("not a codestream")
    @Test
    public void notACodestream() {
        assertThrows(JpxException.class, () -> CodestreamReader.read(new byte[]{0, 0, 0, 12}, 0, 4));
    }
}
