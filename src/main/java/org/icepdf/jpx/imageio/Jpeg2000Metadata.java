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
 * Provenance: ORIGINAL. The standard tree follows the javax_imageio_1.0 DTD (JDK
 * javax.imageio.metadata package documentation); pixel sizes come from the JP2 res box
 * (T.800 I.5.3.7).
 */
package org.icepdf.jpx.imageio;

import org.icepdf.jpx.Jpeg2000Headers;
import org.icepdf.jpx.codestream.Codestream;
import org.icepdf.jpx.jp2.Jp2Header;

import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataFormat;
import javax.imageio.metadata.IIOMetadataFormatImpl;
import javax.imageio.metadata.IIOMetadataNode;
import org.w3c.dom.Node;

/** Read-only image metadata: the standard tree, and a native summary of the boxes and SIZ/COD. */
final class Jpeg2000Metadata extends IIOMetadata {

    static final String NATIVE = "org_icepdf_jpx_1.0";

    private final Jpeg2000Headers headers;
    private final Jpeg2000Layout layout;

    Jpeg2000Metadata(Jpeg2000Headers headers, Jpeg2000Layout layout) {
        super(true, NATIVE, Format.class.getName(), null, null);
        this.headers = headers;
        this.layout = layout;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public IIOMetadataFormat getMetadataFormat(String formatName) {
        if (NATIVE.equals(formatName)) return Format.INSTANCE;
        return super.getMetadataFormat(formatName);
    }

    @Override
    public Node getAsTree(String formatName) {
        if (NATIVE.equals(formatName)) return nativeTree();
        if (IIOMetadataFormatImpl.standardMetadataFormatName.equals(formatName)) return getStandardTree();
        throw new IllegalArgumentException("unsupported metadata format " + formatName);
    }

    @Override
    public void mergeTree(String formatName, Node root) {
        throw new IllegalStateException("read-only metadata");
    }

    @Override
    public void reset() {
        throw new IllegalStateException("read-only metadata");
    }

    private Node nativeTree() {
        Codestream cs = headers.codestream;
        IIOMetadataNode root = new IIOMetadataNode(NATIVE);
        IIOMetadataNode siz = new IIOMetadataNode("Codestream");
        siz.setAttribute("width", Integer.toString(cs.width()));
        siz.setAttribute("height", Integer.toString(cs.height()));
        siz.setAttribute("x0", Integer.toString(cs.x0));
        siz.setAttribute("y0", Integer.toString(cs.y0));
        siz.setAttribute("tileWidth", Integer.toString(cs.tileWidth));
        siz.setAttribute("tileHeight", Integer.toString(cs.tileHeight));
        siz.setAttribute("tiles", Integer.toString(cs.numTiles()));
        Codestream.TileCoding cod = cs.coding(0);
        siz.setAttribute("layers", Integer.toString(cod.numLayers));
        siz.setAttribute("progression", Integer.toString(cod.progression));
        siz.setAttribute("levels", Integer.toString(cod.components[0].numResolutions - 1));
        siz.setAttribute("reversible", Boolean.toString(cod.components[0].transform == 1));
        root.appendChild(siz);
        for (int c = 0; c < cs.components.length; c++) {
            Codestream.Component k = cs.components[c];
            IIOMetadataNode n = new IIOMetadataNode("Component");
            n.setAttribute("index", Integer.toString(c));
            n.setAttribute("precision", Integer.toString(k.precision));
            n.setAttribute("signed", Boolean.toString(k.signed));
            n.setAttribute("dx", Integer.toString(k.dx));
            n.setAttribute("dy", Integer.toString(k.dy));
            root.appendChild(n);
        }
        Jp2Header h = headers.jp2;
        if (h != null) {
            IIOMetadataNode jp2 = new IIOMetadataNode("JP2");
            jp2.setAttribute("brand", Jp2Header.boxName(h.brand));
            if (h.enumeratedColourSpace() >= 0) jp2.setAttribute("enumCS", Integer.toString(h.enumeratedColourSpace()));
            if (h.iccProfile() != null) jp2.setAttribute("iccProfileLength", Integer.toString(h.iccProfile().length));
            if (h.palette != null) {
                jp2.setAttribute("paletteEntries", Integer.toString(h.palette.entries));
                jp2.setAttribute("paletteColumns", Integer.toString(h.palette.columns));
            }
            if (h.channelDefinitions != null) {
                jp2.setAttribute("channelDefinitions", Integer.toString(h.channelDefinitions.length));
            }
            root.appendChild(jp2);
        }
        return root;
    }

    @Override
    protected IIOMetadataNode getStandardChromaNode() {
        IIOMetadataNode chroma = new IIOMetadataNode("Chroma");
        IIOMetadataNode type = new IIOMetadataNode("ColorSpaceType");
        String name = layout.colourSpaceName;
        type.setAttribute("name", "ICC".equals(name) ? iccTypeName() : name);
        chroma.appendChild(type);
        IIOMetadataNode num = new IIOMetadataNode("NumChannels");
        num.setAttribute("value", Integer.toString(layout.channels));
        chroma.appendChild(num);
        if ("GRAY".equals(type.getAttribute("name"))) {
            IIOMetadataNode bz = new IIOMetadataNode("BlackIsZero");
            bz.setAttribute("value", "TRUE");
            chroma.appendChild(bz);
        }
        return chroma;
    }

    private String iccTypeName() {
        switch (layout.colorModel.getColorSpace().getType()) {
            case java.awt.color.ColorSpace.TYPE_GRAY: return "GRAY";
            case java.awt.color.ColorSpace.TYPE_RGB: return "RGB";
            case java.awt.color.ColorSpace.TYPE_CMYK: return "CMYK";
            case java.awt.color.ColorSpace.TYPE_Lab: return "Lab";
            case java.awt.color.ColorSpace.TYPE_YCbCr: return "YCbCr";
            default: return Jpeg2000Layout.clrName(layout.colorModel.getColorSpace().getNumComponents());
        }
    }

    @Override
    protected IIOMetadataNode getStandardCompressionNode() {
        IIOMetadataNode c = new IIOMetadataNode("Compression");
        IIOMetadataNode name = new IIOMetadataNode("CompressionTypeName");
        name.setAttribute("value", "JPEG2000");
        c.appendChild(name);
        IIOMetadataNode lossless = new IIOMetadataNode("Lossless");
        lossless.setAttribute("value", headers.codestream.coding(0).components[0].transform == 1 ? "TRUE" : "FALSE");
        c.appendChild(lossless);
        return c;
    }

    @Override
    protected IIOMetadataNode getStandardDataNode() {
        IIOMetadataNode d = new IIOMetadataNode("Data");
        IIOMetadataNode planar = new IIOMetadataNode("PlanarConfiguration");
        planar.setAttribute("value", "PixelInterleaved");
        d.appendChild(planar);
        StringBuilder bits = new StringBuilder();
        for (int b = 0; b < layout.channels; b++) {
            int c = layout.displayOrder[b];
            if (b > 0) bits.append(' ');
            bits.append(layout.precision[c]);
        }
        IIOMetadataNode format = new IIOMetadataNode("SampleFormat");
        // signed channels are level-shifted into unsigned bands
        format.setAttribute("value", "UnsignedIntegral");
        d.appendChild(format);
        IIOMetadataNode bps = new IIOMetadataNode("BitsPerSample");
        bps.setAttribute("value", bits.toString());
        d.appendChild(bps);
        return d;
    }

    @Override
    protected IIOMetadataNode getStandardDimensionNode() {
        IIOMetadataNode d = new IIOMetadataNode("Dimension");
        IIOMetadataNode orientation = new IIOMetadataNode("ImageOrientation");
        orientation.setAttribute("value", "Normal");
        d.appendChild(orientation);
        Jp2Header h = headers.jp2;
        Jp2Header.Resolution r = h == null ? null : h.displayResolution != null ? h.displayResolution
                : h.captureResolution;
        if (r != null && r.horizontalPerMetre() > 0 && r.verticalPerMetre() > 0) {
            IIOMetadataNode hs = new IIOMetadataNode("HorizontalPixelSize");
            hs.setAttribute("value", Float.toString((float) (1000 / r.horizontalPerMetre())));
            d.appendChild(hs);
            IIOMetadataNode vs = new IIOMetadataNode("VerticalPixelSize");
            vs.setAttribute("value", Float.toString((float) (1000 / r.verticalPerMetre())));
            d.appendChild(vs);
            IIOMetadataNode aspect = new IIOMetadataNode("PixelAspectRatio");
            aspect.setAttribute("value", Float.toString((float) (r.verticalPerMetre() / r.horizontalPerMetre())));
            d.appendChild(aspect);
        }
        return d;
    }

    @Override
    protected IIOMetadataNode getStandardTransparencyNode() {
        IIOMetadataNode t = new IIOMetadataNode("Transparency");
        IIOMetadataNode alpha = new IIOMetadataNode("Alpha");
        alpha.setAttribute("value", !layout.hasAlpha() ? "none"
                : layout.colorModel.isAlphaPremultiplied() ? "premultiplied" : "nonpremultiplied");
        t.appendChild(alpha);
        return t;
    }

    /** The native format: Codestream, Component*, JP2?; all attributes are informational strings. */
    public static final class Format extends IIOMetadataFormatImpl {
        static final Format INSTANCE = new Format();

        public static IIOMetadataFormat getInstance() {
            return INSTANCE;
        }

        private Format() {
            super(NATIVE, CHILD_POLICY_SOME);
            addElement("Codestream", NATIVE, CHILD_POLICY_EMPTY);
            for (String a : new String[]{"width", "height", "x0", "y0", "tileWidth", "tileHeight", "tiles", "layers",
                    "progression", "levels", "reversible"}) {
                addAttribute("Codestream", a, DATATYPE_STRING, true, null);
            }
            addElement("Component", NATIVE, CHILD_POLICY_EMPTY);
            for (String a : new String[]{"index", "precision", "signed", "dx", "dy"}) {
                addAttribute("Component", a, DATATYPE_STRING, true, null);
            }
            addElement("JP2", NATIVE, CHILD_POLICY_EMPTY);
            for (String a : new String[]{"brand", "enumCS", "iccProfileLength", "paletteEntries", "paletteColumns",
                    "channelDefinitions"}) {
                addAttribute("JP2", a, DATATYPE_STRING, false, null);
            }
        }

        @Override
        public boolean canNodeAppear(String elementName, ImageTypeSpecifier imageType) {
            return true;
        }
    }
}
