# Security policy

jpx-imageio decodes JPEG 2000 images, which very often come from untrusted sources (PDFs,
uploads, web content). Treating every input as hostile is part of its design, and reports of
inputs that get past that are welcome.

## Supported versions

Until 1.0, only the latest release gets fixes. A fix ships as a new release on Maven Central.

| Version | Supported |
|---|---|
| latest release | yes |
| anything older | no |

## Reporting a vulnerability

Please report privately through GitHub: **Security → Report a vulnerability** on
https://github.com/pcorless/jpx-imageio (GitHub's private vulnerability reporting). Please don't
open a public issue for something exploitable.

A useful report has the input file (or how to produce it), the jpx-imageio version, the JDK, and
what happened: the exception and stack trace, or how long it ran and how much memory it took.

This is a small project kept by one maintainer, so handling is best effort: expect an
acknowledgement within a week or so, and a fix and release as soon as a fix is understood. You'll
be credited in the release notes unless you'd rather not be.

## What counts

Anything a crafted input can make the decoder do beyond failing cleanly, for example:

- an exception other than `JpxException` / `IOException` escaping the decoder or the ImageIO
  reader (`ArrayIndexOutOfBoundsException`, `NullPointerException`, `CMMException`, ...);
- a decode that doesn't finish, or takes far longer than the image's size explains;
- memory far beyond what the image's declared size needs, or anything that gets past the sample
  limit below;
- output that depends on memory it shouldn't read (samples from outside the input or from
  another decode).

## What doesn't

- **Rejecting malformed input.** A `JpxException` (an `IOException`) is the intended outcome for a
  stream that can't be decoded, truncated or not.
- **Large images within the limit.** A decode is refused before allocating anything when its output,
  or one tile, would hold more than 2^28 samples (`-Dio.github.pcorless.jpx.maxSamples`,
  `Jpeg2000ImageReadParam.setMaxSamples`, `Jpeg2000Decoder.Options.maxSamples`). Below that, an image
  can still need more heap than a host gives it; a read through ImageIO holds about 9 bytes per
  sample at its peak. Hosts that decode untrusted images on a small heap should lower the limit.
- **The JDK itself.** ICC profiles are handed to the JDK's colour management (LittleCMS) and
  images are built with `java.awt.image`; vulnerabilities in those belong to the JDK vendor. A
  profile that makes jpx-imageio throw something other than `IOException` does count, as above.
- **Tools.** `tools/` (fixture generator, benchmarks, oracle programs) isn't part of the library.

## Hardening so far

- Header values are range-checked before use; offsets and sizes are computed in `long`; component,
  tile, precinct and code-block counts are bounded.
- The sample limit above, checked before any allocation (`docs/DECISIONS.md` D-22).
- Hostile-input fixtures (`src/test/resources/fixtures/hostile`, `jp2mut`) run in every build.
- Mutation fuzzing of the fixtures; no coverage-guided fuzzing yet.
