# JPHP ozLTeam 0.9.4

PHP on the JVM: a compiler and runtime maintained by [DevLine](https://github.com/devline-ide).

This is the [DevLine fork](https://github.com/devline-ide/jphp) of
[jphp-compiler/jphp](https://github.com/jphp-compiler/jphp). It builds on the
original JPHP implementation with a Java 25 toolchain, modern language features,
guarded compiler optimizations and runtime work for DevLine applications.

JPHP compiles PHP source into JVM bytecode. The JVM executes that bytecode and
can optimize it with its JIT compiler. Applications can use JVM libraries through
JPHP's Java integration and extensions. JPHP is also embeddable in Java applications.

## Current development baseline

| Component | Current checkout |
| --- | --- |
| Build artifact version | `0.9.4-ozLTeam-SNAPSHOT` by default |
| Java toolchain and bytecode target | JDK 25 |
| Gradle wrapper | 9.7.1 |
| Bytecode library | ASM 9.8 |
| JavaFX dependencies | OpenJFX 25.0.4; native artifacts in this build are configured for Windows |
| PHB compiler ABI | 31 |
| License | [Apache License 2.0](LICENSE), with component notices retained in their owning files |

These are development settings from this checkout, not a published release or
a claim that every platform has been verified. Old JitPack tags and upstream
Travis badges do not identify this fork's current artifacts.

## Language support

The compiler has two language profiles:

- `JPHP_LEGACY` preserves the historical JPHP language behavior. It is the
  standalone compiler's default when no profile is selected.
- `PHP_8_5` enables the fork's modern PHP language implementation. It is a
  compatibility target, not a guarantee of complete Zend PHP 8.5 equivalence.

Modern-profile implementations and regression tests cover typed properties and
functions, union/intersection types, constructor property promotion, named
arguments, attributes, enums, `match`, nullsafe access, readonly declarations,
property hooks, asymmetric property visibility and the pipe operator. Individual
features have their own admission rules and source/PHB tests; explicit ownership
contexts can reject constructs without a supported ownership plan.

The runtime also retains JPHP facilities such as UTF-16 strings, JVM interop,
environments, threading and extensions. Zend extensions and their entire API
surface are not automatically available. A PHP package may need an extension,
an adapter or compatibility changes before it can run here.

The runtime reports `JPHP_VERSION = 0.9.4-ozLTeam`. The historical PHP compatibility constant remains
`PHP_VERSION = 5.6.99`; it does not identify the build artifact version or the
selected language profile. Use build provenance and module metadata for that.

## Compiler optimizations and runtime modes

Eligible pure static methods can compile to guarded primitive JVM bodies,
keeping supported scalar locals in `long`, `double` or `boolean` slots. The
supported subset includes arithmetic, resolved same-class helper calls, numeric
loops, branches and constant-level `break`/`continue`. Unsupported code retains
the ordinary PHP implementation.

Two independent settings control numeric semantics and ownership instrumentation:

| Setting | Default | Other choices |
| --- | --- | --- |
| Numeric profile | `COMPATIBLE`: checked primitive arithmetic falls back to the ordinary PHP body on overflow | `FAST_INT64_WRAP`: explicit signed 64-bit wrapping for the documented integer operations |
| Ownership mode | `AUTO`: guarded ownership support and eligible scalar optimization | `CHECKED`: ordinary ownership-capable bodies; `OFF`: omit generated ownership lanes, rejecting explicit Session/Authority admission |

`OFF` is not a general replacement for ownership guarantees, and fast integer
wrapping can change results. Neither option promises faster whole applications.
The language profile, numeric profile and ownership mode are separate choices.
PHB files retain their module-owned settings; recompile source to change them.

Read the detailed contracts before changing defaults or extending optimizations:

- [Optimization features](JPHP_OPTIMIZATION_FEATURES.md): eligibility, fallback,
  measured workloads and limitations.
- [Semantic decisions](JPHP_SEMANTIC_DEVIATIONS.md): intentional differences,
  compatibility boundaries and regression evidence.

## Repository layout

| Directory | Purpose |
| --- | --- |
| [`jphp-runtime`](jphp-runtime) | Values, environments, reflection, runtime services and PHB metadata |
| [`jphp-core`](jphp-core) | Lexer, parser, JVM compiler and compiler regression tests |
| [`jphp-scripting`](jphp-scripting) | JVM scripting integration |
| [`jphp-debugger`](jphp-debugger) | Debugger support |
| [`exts`](exts) | Runtime and JavaFX extensions |
| [`sandbox`](sandbox) | Development launcher and bootstrap resources |
| [`bench`](bench) | Historical benchmark suite |

JavaFX GUI support is supplied by extensions. DevLine's project model, Kotlin
tooling, designers and application packaging live in the IDE workspace, not in
the JPHP compiler. Kotlin and Java classes can participate through JVM integration;
embedding arbitrary Kotlin syntax inside a PHP source file is not supported.

## Build from source

Use JDK 25 and the checked-in Gradle wrapper. This build currently expects the
DevLine workspace layout: `settings.gradle` includes `../../jfoenix-modern`, the
sibling JavaFX library build. An isolated clone of this repository alone does
not provide that dependency. In the DevLine checkout, JPHP lives at
`develnext/3rd-party/jphp` and the library at `develnext/jfoenix-modern`.

Run these commands from the JPHP repository directory. On Windows, use
`gradlew.bat` in place of `./gradlew`.

```sh
# Build only the compiler and runtime JARs.
./gradlew :jphp-runtime:jar :jphp-core:jar

# Run compiler regression tests.
./gradlew :jphp-core:test

# Exercise the same test resources through compiled PHB output.
JPHP_TEST_COMPILED=1 ./gradlew :jphp-core:test --rerun-tasks

# Publish source-built modules into the workspace source repository.
./gradlew publishSource
```

In PowerShell, set the PHB test switch with `$env:JPHP_TEST_COMPILED='1'` before
the test command and remove it afterward with `Remove-Item Env:JPHP_TEST_COMPILED`.
Source-mode tests require that variable to be unset.

The default publication directory is `../../.gradle/source-repo` relative to
this repository. Override it with `-Dmaven.repo.local=<directory>`.
`publishToMavenLocal` is also available through Gradle's `maven-publish` plugin;
the old `gradle install` command is no longer the build workflow.

Artifact coordinates retain the original namespace, for example:

```text
org.develnext.jphp:jphp-runtime:0.9.4-ozLTeam-SNAPSHOT
org.develnext.jphp:jphp-core:0.9.4-ozLTeam-SNAPSHOT
```

DevLine's composite build substitutes these artifacts with local projects.
Do not replace JARs inside a running IDE distribution when rebuilding JPHP.

## Selecting a profile when embedding

Configure the source `Context` before passing it to `JvmCompiler`:

```java
Context context = new Context("echo 'Hello from JPHP';");
context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
context.setPhpOptimizationProfile(PhpOptimizationProfile.COMPATIBLE);
context.setPhpOwnershipMode(PhpRuntimeOwnershipMode.AUTO);
```

`Context` is in `php.runtime.env`; the three profile enums are in
`php.runtime.common`. A host can also configure scope defaults. These setters
configure new source compilation, not the stored policy of an existing PHB file.

## Documentation and upstream history

This README and the two linked decision/optimization documents describe the
maintained fork. The original project began in 2013; its older documentation
remains useful for historical APIs, but predates the current compiler and build:

- [Original JPHP repository](https://github.com/jphp-compiler/jphp)
- [Historical API documentation](https://github.com/jphp-compiler/docs)
- [Public DevLine DevelNext fork](https://github.com/devline-ide/develnext)

Performance results apply only to their documented workloads, profiles and
captured builds. They do not establish general PHP speedups, game FPS, lower
retained memory or cross-platform parity.
