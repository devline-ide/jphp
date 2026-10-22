# PCRE2 extension for JPHP

This optional public extension implements `preg_match`, `preg_match_all`,
`preg_grep`, `preg_split`, `preg_quote`, `preg_replace`, `preg_filter`,
`preg_replace_callback`, `preg_replace_callback_array`, `preg_last_error` and
`preg_last_error_msg` in the PHP_8_5 profile. It uses PCRE2's 8-bit API through
Java 25 FFM. The existing `php\util\Regex` retains its separate Java-regex API.

Global matching supports pattern/set order, named captures, byte offsets and
unmatched-as-null. Its native subject buffer, match data and context are reused
for the complete call; no cross-call pattern cache is retained.

Splitting uses the same per-call native session and supports limits, captured
delimiters, byte offsets and omission of empty pieces. Quoting escapes PHP string
bytes (including NUL) and the first byte of an optional delimiter; it does not
load a native library or alter the last-match error state.

Array filtering preserves keys, original value types and reference cells. A COW
view protects traversal across Stringable/warning callbacks. One lexical native
match-data/context pair serves the call; each subject buffer is released after
its test. PCRE execution errors preserve the entries already selected.

Replacement and match-only filtering support scalar/array subjects, ordered
pattern/replacement arrays, numbered backreferences, byte strings, per-pattern
limits and a final reference count. Each subject owns its native scan session.
Calls with multiple subjects reuse up to eight successful compiled patterns,
keyed by exact pattern bytes and current JIT setting, within a 64 KiB key budget.
Failures and excess/oversized keys use the ordinary one-shot path. Code is freed
before the call returns, including exceptions; no cross-call cache is retained.
Conversions and current execution limits are evaluated for each subject as before.
Ordinary calls retain array snapshots; direct three-argument replacement follows
PHP borrowed-array traversal when Stringable conversions mutate the input.

Callback replacements retain the resolved internal PHP target and pass one
private matches array per match, including named captures, MARK, byte offsets
and unmatched-as-null flags. Changed INI limits apply to subsequent matches.
Regular callback replacement publishes its count even on a body exception;
callback-array replacement publishes it after the complete ordered pipeline
succeeds. Native contexts and captures are never shared with nested callbacks.

## Build and distribution

Build from the JPHP root with Java 25, CMake and a native C/C++ toolchain:

```
./gradlew :exts:jphp-pcre-ext:assembleNativeDistribution
```

The build downloads PCRE2 10.49 from its fixed upstream release and verifies the
source archive SHA-256 before extracting it. `-PpcreSourceArchive=/path/pcre2-10.49.zip`
uses the same hash-pinned archive offline. `-PpcreCmakeExecutable=/path/cmake`
selects the build tool. Native code targets the build host. Windows x64 has real
build and runtime verification; Linux/macOS and ARM64 mappings require their own
platform verification before a release claims support.

A normal Gradle project dependency builds the selected JAR and its companions:

```
build/libs/jphp-pcre-ext-<version>.jar
build/libs/native/<classifier>/<native-library>
build/libs/native/<classifier>/LICENCE-PCRE2.md
```

`assembleNativeDistribution` creates this complete layout under
`build/native-distribution/lib`. Publishing also produces a `native-<classifier>`
ZIP containing the complete layout. A Maven JAR alone does not include the native
companion; install the matching native bundle with it. Keep the JAR, native file
and license together when relocating the distribution. The extension never
searches PATH or installs/extracts libraries into a global cache.

For an explicit source-built native artifact, provide all three properties:
`pcreNativeLibrary`, `pcreNativeSha256`, and `pcreNativeLicense`. The build verifies
the binary digest and runs the real configuration probe on the build host.

## Registration and execution

Register `new org.develnext.jphp.pcre.PcreExtension()` in the CompileScope. Pure
registration, reflection and PHP compilation read only the selected JAR's
metadata; they do not require its DLL or native access. The first actual match
verifies the host classifier, native digest and actual version/JIT configuration,
then initializes one shared backend for that extension instance. Missing or
incompatible native payloads fail explicitly. Error state stays per Environment.

The runtime JVM needs `--enable-native-access=ALL-UNNAMED` when the extension is
loaded on the classpath and matching executes. Do not add this flag merely to
compile PHP. `PCRE_VERSION` and related constants describe the selected artifact;
JPHP may inline them into PHB. Compile and execute against the same provider
artifact/configuration.

Native ABI declarations support 64-bit targets only. Existing tests retain raw
native PHP oracle rows and distinguish actual PCRE2 version metadata from exact
behavior comparisons. This module does not claim full PHP PCRE API parity,
managed capability certification, or performance parity.
