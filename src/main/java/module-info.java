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
/** A pure-Java JPEG 2000 decoder and its ImageIO reader. */
module io.github.pcorless.jpx {
    requires transitive java.desktop;

    exports io.github.pcorless.jpx;
    exports io.github.pcorless.jpx.codestream;
    exports io.github.pcorless.jpx.jp2;
    exports io.github.pcorless.jpx.imageio;

    provides javax.imageio.spi.ImageReaderSpi with io.github.pcorless.jpx.imageio.Jpeg2000ImageReaderSpi;
}
