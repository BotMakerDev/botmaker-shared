# Matching (`com.botmaker.shared.opencv`)

`OpencvManager` (template matching on `org.opencv.core.Mat`) and `ColorMatcher` (CIELAB ΔE clusters behind the
SDK's `Pixel`) live here because the SDK matches at runtime and Studio's Magic Wand matches at edit time.

**Loading the native goes through `OpenCvNative.ensureLoaded()` and nowhere else.** There were three copies of
that loader — the SDK's, Studio's (whose javadoc admitted it mirrored the SDK's) and one inside `OcrNative` —
each with its own `loaded` flag, so nothing stopped the same process from extracting the native repeatedly.
Call it from a `static {}` block on any class that links an `org.opencv` type. `OcrNative` now lives in the
SDK (`com.botmaker.sdk.internal.ocr`) and still delegates here, by fully-qualified name — which is the reason
the OCR move left `opencv/` behind: it has two consumers and the OCR stack had one.

**shared returns raw records; the consumer maps them to its own value types.** `RawMatch`/`RawColorMatch`
carry plain ints and a score and are named "raw" for exactly this reason; the SDK's `vision` layer maps them
onto its public `MatchResult`/`ColorMatch`. The same rule sets the signatures: the authored-resolution
parameter is a `java.awt.Dimension`, not the SDK's `Size` — the SDK converts once, in
`ImageTemplate.authoredSize()`.
