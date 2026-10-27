# Intentional JPHP differences and performance decisions

## Direction agreed on 2026-10-25

JPHP is a JVM language implementation with its own product goals. Reproducing
every observable detail of Zend PHP is not an unconditional priority over
performance. Deliberate differences are allowed when their exact behavior,
benefit and compatibility impact are recorded here and verified.

This is a decision ledger, not a claim that every current incompatibility is
intentional. It is not an exhaustive audit of the existing fork. The initial
ledger on 2026-10-25 recorded proposals without runtime changes. OPT-001 through
OPT-004 and DEV-001 were verified in a captured local working tree on 2026-10-26.
That capture preceded a source commit or release. The first implementing source
commit is now `39bcd48d`, which also contains later ownership/control-flow work;
it does not reproduce the historical ABI-30 capture by itself. A release/tag is
not established by these local receipts. Later entries retain their own
verification status and are outside that original scalar/numeric capture.

Before updating the compiler/runtime for a newer PHP version, read this file.
An upstream PHP difference alone is not sufficient reason to undo an accepted
JPHP decision. Preserve accepted decisions and their regression tests unless a
later product/user decision explicitly supersedes them; record that decision
and migration here. Do not label an accidental bug as an intentional difference
to avoid fixing it.

## Status and compatibility boundary

- **Proposed:** a possible optimization or language rule, not an implemented
  feature or an accepted compatibility promise.
- **Accepted:** the exact behavior and scope have been selected, with rationale.
- **Implemented:** accepted behavior has compiler/runtime changes, tests and
  evidence attached. Record the first implementing commit/version.
- **Superseded:** keep the old record and link its replacement and migration.

Preserve existing project behavior unless its documented contract is explicitly
changed. If an optimization changes observable semantics, identify its profile,
opt-in or version boundary. New profiles/attributes mentioned during discussion
do not exist until implementation and verification are recorded here.

Changes to lowering, profiles or generated calling conventions must participate
in compiler ABI/cache invalidation and artifact metadata as appropriate. Source
Run, PHB, cached output and exported applications must select the same behavior.
Do not silently reinterpret old compiled artifacts under a different contract.

## Implemented scalar optimizations, limited supported subset

OPT-001 through OPT-004 are implemented and verified locally for supported pure
typed static methods, not for arbitrary PHP code. Supported bodies use scalar
parameters/results, scalar local assignments, supported arithmetic/comparisons
and statically resolved pure same-class helper calls. Unsupported bodies retain
the ordinary PHP implementation. Inspect and extend the existing mechanisms
before introducing a second competing lowering or broadening eligibility.

| ID | Implemented local behavior | Boundary |
| --- | --- | --- |
| OPT-001 | Primitive sibling kernels keep supported scalar intermediates in JVM `long`, `double` and `boolean` slots, with boxing at the PHP-visible result boundary. | `COMPATIBLE` kernels use checked integer arithmetic and fall back on overflow. References, captures, coercion and unsupported expressions retain the ordinary path. |
| OPT-002 | Guarded scalar entries call primitive kernels; resolved pure same-class helper composition can call kernels directly. | Direct resolution is limited to supported positional calls with a non-overridable target, such as a final class/method or private method. Inherited, cross-module, late-bound, named/unpacked or unsupported calls retain PHP dispatch. |
| OPT-003 | The admitted pure scalar path bypasses property-write contexts and ownership bookkeeping that its effect analysis proves unnecessary. | Cached entry selection is disabled in active ownership Session/Authority contexts. The original implementation and metadata remain available. Type declarations or `strict_types=1` alone do not authorize bypassing this proof. |
| OPT-004 | Small scalar bodies are separated from the preserved ordinary fallback for shape/coercion failures and compatible overflow. | This is cold-path separation for the supported subset, not a general dead-code-elimination pass over all PHP methods. Existing original method bodies are not globally stripped. |

For checked integer arithmetic, an overflow guard or a statically proved range
can keep the common path primitive while preserving the existing result/error
behavior. A plain JVM `LADD` for arbitrary PHP integers does not by itself do so.

### Source investigation pointers

- `jphp-core/src/org/develnext/jphp/core/compiler/jvm/statement/ScalarMethodCompiler.java`
- `jphp-core/src/org/develnext/jphp/core/compiler/jvm/statement/ScalarCallResolver.java`
- `jphp-core/src/org/develnext/jphp/core/compiler/jvm/statement/MethodStmtCompiler.java`
- `jphp-core/src/org/develnext/jphp/core/compiler/jvm/statement/OwnershipFrameCompiler.java`
- `jphp-core/src/org/develnext/jphp/core/compiler/jvm/statement/PrimitiveCellFrameCompiler.java`
- `jphp-runtime/src/php/runtime/PhpArithmetic.java`
- `jphp-runtime/src/php/runtime/PhpPropertyWriteContext.java`
- `jphp-runtime/src/php/runtime/PhpPrimitiveCellRuntime.java`
- `jphp-runtime/src/php/runtime/PhpRuntimeOwnership.java`
- `jphp-runtime/src/php/runtime/reflection/MethodEntity.java`
- `jphp-runtime/src/php/runtime/common/PhpOptimizationProfile.java`
- `jphp-runtime/src/php/runtime/loader/dump/ModuleDumper.java`
- `jphp-runtime/src/php/runtime/env/ModuleCacheKey.java`

The historical typed-add disassembly contained 349 instructions in its original
PHP ABI method, including property context enter/leave, ownership branches and
`PhpArithmetic.binary`. The current design retains the original ABI method and
adds real selected sibling paths. It does not replace every PHP call with four
instructions or establish that every original branch executes on each call.
Bytecode length and instruction count are not runtime performance measurements.

## Accepted intentional semantic differences

The user authorized implementing the optimization work and the explicit fast
numeric mode on 2026-10-25. DEV-001 is the selected contract and is implemented
and verified locally as of 2026-10-26. The receipts below describe that captured
working tree, not a published release or every subsequent change by other agents.

### DEV-001: fixed-width integer overflow policy

- **Status:** accepted on 2026-10-25; implemented and verified locally on
  2026-10-26. First implementing source commit: `39bcd48d` (also includes later
  compiler work). Release version/tag: not established by these receipts.
- **PHP reference:** integer arithmetic outside the integer range returns a
  floating-point value: <https://www.php.net/manual/en/language.types.integer.php>.
  Typed return rules may subsequently affect the function result.
- **Selected JPHP rule:** `FAST_INT64_WRAP` uses signed 64-bit wrapping for
  integer addition, subtraction, multiplication, unary negation, increment and
  decrement, including folded expressions and compound/reference/property/array
  operations. Existing numeric coercion still determines whether operands are
  integers. Division, modulo, power and mixed floating-point operations retain
  PHP behavior. This is wrapping, not an overflow exception or float promotion.
  `COMPATIBLE` retains PHP overflow behavior; its primitive kernels use checked
  operations and the ordinary PHP path on overflow.
- **Measured outcome:** scalar specialization reduces allocation and improves
  the measured compatible PHP call workloads below. `FAST_INT64_WRAP` has the
  same measured allocation as current `COMPATIBLE` and does not outperform it
  in this capture. It is an explicit numeric semantics choice, not a guarantee
  of faster execution or the recommended performance default.
- **Observable cost:** different results, value types or errors at boundaries.
  Wrapping can turn a positive counter into a negative one: `PHP_INT_MAX + 1`
  produces `PHP_INT_MIN`; `PHP_INT_MIN - 1` produces `PHP_INT_MAX`; negating the
  minimum preserves it. For `$n = PHP_INT_MAX`, `++$n` becomes `PHP_INT_MIN`;
  for `$n = PHP_INT_MIN`, `--$n` becomes `PHP_INT_MAX`. Typed return checks can
  consequently differ from the compatible overflow-to-float/error path.
  Division of the minimum by `-1`, modulo and power retain their existing PHP rules.
- **Selection boundary:** optimization profile is separate from language
  version. `COMPATIBLE` is ID 0/default; `FAST_INT64_WRAP` is ID 1 and requires
  PHP_8_5. Set it explicitly on `Context` or as a scope default for standalone
  compilation. Each module/callee owns its profile; it is not inherited from a
  fast caller. Old three-argument property-context entries reset to compatible.
  The optimization ID was first stored in PHB compiler ABI 30; ABI 0..29 artifacts
  retain compatible semantics. Later ABI revisions must preserve that selection.
  Source cache identities include the profile. Binary
  artifacts own their stored choice and ignore source resolvers.
  Dynamic `eval` compilation receives the lexical caller's selected profile.
- **DevLine activation:** project setting `compiler.phpOptimization` accepts
  `compatible` (default) or `fast-int64-wrap`. Owned payload resource
  `META-INF/devline/php-optimization.properties` carries source-run selection;
  authored PHP_8_5 files receive it while legacy SDK/package scripts remain
  compatible. Compile requests/cache keys and exports retain the choice.
- **Evidence:** the new regression suites cover min/max overflow, folded and
  dynamic arithmetic, typed returns, static/dynamic dispatch and fallbacks,
  references/properties/arrays, eval, source/PHB, old-header compatibility and
  profile/cache isolation. Focused core and coordinating DevLine integration
  receipts are recorded below.
- **Reconsideration:** expanding kernel eligibility or changing numeric rules
  requires corresponding semantic tests and new full-boundary measurements.
  A newer upstream PHP version or a shorter disassembly alone does not supersede
  this explicit numeric contract.

`strict_types=1` is not the switch for this rule. Select the optimization profile explicitly.

### Scalar lowering and inspection

`ScalarMethodCompiler` emits primitive synthetic `__$scalar$...` bodies and
guarded `__$scalarEntry$...` adapters for supported pure typed static methods.
The original PHP ABI method and its ownership metadata remain intact.
`MethodEntity` caches a matching entry and selects it only outside active
ownership Session/Authority contexts. The kernel entry checks exact scalar
carriers and argument count; unsupported/coercion/compatible-overflow cases use
the original body. Resolved pure same-class helper calls can use primitive
kernels directly. Debugger/dynamic locals, references/defaults, attributes,
effects, recursion and unsupported constructs stay on the ordinary path.

A four-op FAST add kernel does not mean that every PHP call executes only four
instructions: the boundary adapter/runtime and return boxing still have a cost.
The original method can still be large. The relevant comparison is actual PHP
execution, not an isolated sibling that callers never enter.

Current regression entry points:

- `jphp-core/tests/org/develnext/jphp/core/compiler/jvm/PhpOptimizationProfileTest.java`
- `jphp-core/tests/org/develnext/jphp/core/compiler/jvm/ScalarMethodCompilerTest.java`
- `jphp-core/tests/org/develnext/jphp/core/compiler/jvm/ScalarMethodDispatchTest.java`
- `jphp-core/tests/org/develnext/jphp/core/compiler/jvm/ScalarCallResolverTest.java`

### Local verification and performance receipts, 2026-10-26

The focused core regression run passed 154 tests with zero failures, including
21 new scalar/profile tests. This is the selected regression set, not a claim
that every test in the entire repository was run. The coordinating DevLine
workspace retains these receipts under its `build/` directory:

- `build/jphp-scalar-regression-r4.log`: successful focused core regression.
- `build/php-scalar-private-r2.log`: successful private compiler/cache,
  source-profile, settings and bytecode-viewer integration checks.
- `build/php-scalar-delivery-r1.log`: the named `projectSettingsCheck` and
  `phpOptimizationPipelineCheck` targets passed, including the
  `PHP_OPTIMIZATION_SOURCE_PHB_SDK_ISOLATION_CACHE_NO_EXECUTION_PASS` receipt.
  Its later distribution task failed; this file is not an overall successful
  distribution/build receipt.
- `build/php-scalar-benchmark-20261026-r3/proof.json`, `provenance.json` and
  `harness-snapshot/`: the captured comparison and source/JAR provenance.
- `build/php-scalar-main-qa/run-1792956931557/proof.json`: all 14 actual
  Main/headless checks passed, including visible scalar kernels/adapters,
  profile switching and stale results, real build PHB inspection, source/PHB
  numeric boundary behavior, and profile/settings persistence after a fresh JVM.
  The accompanying `scalar-runtime-proof.json` records the compatible typed-add
  overflow `TypeError` and FAST wrapped integer for both source and PHB.
- `build/php-scalar-qa-20261026-r1/capture-seal.json` and
  `post-main-seal-verification.json`: the frozen delivery capture. The Main
  `frozen-home-check.json` confirms all 641 original file hashes still match,
  with no files added or removed. Captured JPHP core/runtime agree with the
  benchmark provenance. `cleanup.json` confirms the owned host/browser exited.

The benchmark uses the same Java 25 runtime, sequential rotated variants,
three fresh JVM forks, at least two seconds of warmup per workload and seven
measured samples per variant/fork. Each sample targets one million logical
additions: 1,000,000 outer add calls, 500,000 composition calls, or 7,813 PHP loop
calls (128 additions each, rounded up to 1,000,064 logical additions). The timed call
entrance is real `InvokeHelper.callStatic` with normal PHP binding and class
scope, not direct invocation of an isolated primitive kernel. Varied input
rows are prepared before measurement; output checksums match across variants.
All timed values are within the common non-overflow domain. Boundary semantics
are tested separately by the regression suites.

| Workload | Baseline compatible ns/call | Current compatible ns/call | Ratio | Baseline -> current allocated bytes/call |
| --- | ---: | ---: | ---: | ---: |
| Typed add | 489.2872 | 434.9537 | 1.125x | 664 -> 600 |
| Typed helper composition | 1944.5172 | 452.0176 | 4.302x | 2824 -> 632 |
| PHP loop invoking helpers | 79788.8775 | 74665.6086 | 1.069x | 97417.285 -> 93321.285 |

For `FAST_INT64_WRAP`, the corresponding medians are 447.1975, 460.1852 and
75360.1945 ns/call, with the same allocation as current compatible mode.
This capture establishes no additional FAST-mode speedup. `COMPATIBLE` remains
the default. Earlier short-warmup exploratory measurements are retained in
their original receipts; the warmed r3 capture above supersedes them as the
basis for these reported ratios.

These numbers describe warmed PHP call/loop timing and current-thread allocated
bytes. They do not establish game FPS, retained application RAM, startup gains,
physical GPU performance or cross-OS performance. The named Main receipts verify
the frozen ABI30 scalar/numeric capture; they do not validate DEV-002 or later
changes to the working tree. Further delivery details belong to the separate
DevLine audit.

## Required record for each accepted difference

1. Stable ID, status, decision date and scope of the product decision.
2. Relevant upstream PHP version/rule with an authoritative reference.
3. Exact JPHP rule with small examples, including boundary/error cases.
4. Why the difference is intentional; user benefit and affected programs.
5. Activation/defaults, compatibility/migration and dynamic boundary behavior.
6. Compiler/runtime source pointers, first commit/version and ABI/cache impact.
7. Regression tests asserting the JPHP rule, with any upstream oracle mismatch
   explicitly classified. Do not weaken unrelated oracle coverage.
8. Performance evidence for the actual use case and any regressions; identify
   unmeasured expectations instead of presenting them as achieved results.
9. Conditions for reconsidering the rule and links to superseding decisions.

## Verification for performance work

Compare cold and warmed execution, time and bytes allocated per operation.
Measure the full PHP call boundary as well as any isolated primitive body.
Consume results and vary inputs so JIT constant folding/dead-code removal does
not turn the benchmark into an empty loop. Include relevant exceptional cases.
For game-related work, also measure an unchanged copied game workload with the
same input/scene/backend, frame-time distribution, allocation/GC and retained
memory. A smaller disassembly alone does not establish an FPS or RAM improvement.

HotSpot can remove some allocations and inline code, but that is an optimization
opportunity, not a substitute for measuring the emitted design:
<https://docs.oracle.com/en/java/javase/25/vm/java-hotspot-virtual-machine-performance-enhancements.html>.


### DEV-002: module-owned ownership instrumentation modes

- **Status:** implemented and locally verified on 2026-10-26; not yet committed
  or released.
- **Contract:** `PhpRuntimeOwnershipMode.AUTO` (ID 0/default) retains guarded
  ownership entries and conservative scalar dispatch. `CHECKED` (ID 1) keeps
  ordinary ownership-capable PHP ABI bodies and disables scalar sibling
  emission/dispatch. `OFF` (ID 2) omits generated Session/ordinary ownership
  lanes and frame transport for newly compiled bodies. PHP language version,
  numeric profile, property-write context, type checks, ordinary references,
  array VALUE copies/COW and ordinary lifecycle remain separate contracts.
- **Activation:** per Context, CompileScope default or frozen source resolver.
  A callee/PHB owns its choice; caller defaults cannot reinterpret it. Compiler
  ABI31 persists the mode; ABI0..30 defaults to AUTO. Source cache keys include
  the mode; PHB caches ignore source policy and retain stored metadata. Eval
  inherits the lexical module's mode. Non-AUTO artifacts require ABI31
  provenance when re-saved. Previously compiled artifacts need recompilation
  to select another mode.
- **Admission boundary:** OFF cannot execute or be admitted under explicit
  Session/Authority contexts. Missing physical plans cannot be manufactured by
  a consumer's CHECKED/AUTO module. Exact trait-hook source witnesses preserve
  the donor's physical ownership mode across nested/runtime copies; imports do
  not silently reinterpret donor bytecode or ban cross-mode ordinary imports.
- **Compatibility limit:** OFF is not a promise of universal Zend PHP 8.5
  equivalence. Explicit graph release/cycle collection/destructor ordering,
  certified reference/array publication and cleanup-failure composition are
  not replaced by ordinary Java memory management. Never drop guards while
  allowing certified values into an unmanaged lane. AUTO/CHECKED do not claim
  that every PHP feature has a complete admission protocol either.
- **Safe AUTO increment:** a supported pure static method with typed scalar
  parameters (or no parameters), straight-line whitelisted locals and one final
  explicit return may infer its primitive return kind when no return type was
  declared. References, defaults, effects, instance/dynamic locals and unsupported
  bodies remain ordinary. COMPATIBLE arithmetic overflow falls back through the
  original PHP body and can return DOUBLE; FAST_INT64_WRAP retains DEV-001.
- **Verification:** `PhpRuntimeOwnershipModeTest` and `ScalarUntypedReturnTest`
  pass all ten focused cases, including source/PHB matrices, bytecode absence,
  lexical eval, source resolver/cache isolation, active admission rejection and
  physical trait-hook provenance. A 33-class, 203-test regression run found one
  stale header-length expectation (corrected and rerun) and one recursive-array
  lifetime discrepancy proven identical against the frozen pre-change runtime.
  Ordinary lifetime production code was not changed to hide that baseline issue.
  Product integration additionally verified cache, previews, actual workers,
  settings persistence and Windows Web/JavaFX portable execution.
- **Measured scope:** a warmed two-fork normal-PHP-call fixture reduced the
  inferred-return method from 543.1 to 428.1 ns/call and from 688 to 624 allocated
  bytes/call in AUTO. OFF reduced that generated fixture class from 24,821 to
  8,505 bytes, with little additional scalar throughput over AUTO. These bounded
  measurements do not promise whole-application speedup or universal PHP parity.
- **Focused implementation receipt, 2026-10-26:** the two new regression classes
  passed all 10 test methods with no failures, errors or skips. Their internal
  variants cover source/PHB, all three modes, OFF bytecode absence, ordinary
  references/COW/closures/hooks/destructors/finally, mixed donor contracts,
  active Session/Authority rejection, source policy/cache/eval/autoload, old
  headers and compatible/wrapping scalar overflow. Cold PHB hook validation uses
  the explicit owning module before decoded methods are class-bound. Broader
  existing regressions and performance A/B remain pending; no release commit or
  end-to-end performance gain is established by this focused receipt.

## OPT-005: primitive numeric loops and structured branches

- **Status:** implemented and verified locally on 2026-10-26. First source
  commit: `39bcd48d`. This extends the earlier straight-line OPT-001..004 scope;
  historical frozen receipts continue to describe their original artifacts.
- **Contract:** supported pure typed-static PHP_8_5 methods may retain primitive
  locals through `for`, `while`, `do/while`, `if/else`, early returns and nested
  constant-level `break`/`continue`. Supported updates, bitwise operations and
  short-circuit conditions participate in the same guarded sibling mechanism.
- **No new semantic deviation:** COMPATIBLE/AUTO remain defaults, overflow uses
  the original pure fallback, inferred return kinds must agree exactly, and
  local type/definite-assignment proof is required. Debug/ownership guards and
  the original PHP ABI stay intact. Unsupported effects, aliases and dispatch
  retain ordinary execution. There is no new PHB metadata format or setting.
- **Existing arithmetic boundary:** shifts outside `[0,63]` fall back to the
  ordinary fork implementation. This pass does not silently fix a generic
  compatibility difference or declare that old difference intentional.
- **Proof:** 362 selected tests/52 classes pass with no failures/errors/skips,
  including independent CHECKED comparisons, source/PHB, both numeric profiles,
  exceptional paths and emitted JVM verification. Six actual packaged Main
  checks pass, including source and PHB application results and clean exit.
- **Measured scope:** final COMPATIBLE/AUTO A/B against the already optimized
  ownership-r1 baseline uses three rotated JVM forks, at least two seconds of
  warmup and 21 samples per workload/variant, each at least 200 ms. Ordinary PHP
  helper-loop calls improve from 73052.81 to 545.29 ns/call (133.97x), allocating
  93369.25 -> 672 bytes/call. Numeric for/sum is 4.39x, nested loops 21.78x,
  while/break/continue 4.30x and branch-heavy loops 6.73x. General arrays and the
  untyped game-distance control are approximately unchanged. No game-FPS,
  retained-RAM, startup or cross-platform improvement is established.
- **Tradeoff:** original bodies and added siblings can increase generated
  bytecode size and compilation work. Faster warmed loops are not proof of
  smaller PHB files or lower retained memory.
- **Feature record:** [JPHP_OPTIMIZATION_FEATURES.md](JPHP_OPTIMIZATION_FEATURES.md).
  Source: `ScalarControlFlowCompiler` and `ScalarMethodCompiler`. Tests:
  `ScalarNumericControlFlowTest` and `ScalarControlFlowSafetyTest`. Coordinating
  evidence: `build/php-loop-benchmark-20261026-r2/proof.json` with provenance and
  snapshots, plus `docs/audits/jphp-loop-optimization-20261026.md`.

The separately measured packed-array read shortcut was rejected and removed.
Its observations and four preserved array-contract tests are recorded in the
feature document/audit. Do not restore it as an assumed optimization or present
the exploratory array/runtime receipt as the final delivery.

## 2026-10-27 general runtime candidate (compiler ABI 32)

This working-tree follow-up addresses the independent master-2020 comparison,
not the older incremental scalar/ownership baseline. Verification receipts live
in the coordinating IDE workspace under `build/jphp-general-perf-20261027-r1`.
The frozen master is `eefaac6d8195025ba53c2b7b0996a9c128b2ffb7`; the frozen
prechange current source is `0ddb33655da3c6f9c0e4f80acf123099ebfe6fb0`.
No release or implementing commit is asserted for these uncommitted changes.

- **OPT-006, general runtime paths:** bounded ASCII-name normalization reuse,
  ordinary cached static dispatch, lazy return-error naming, reflected-member
  invocation handles, frame-owned positional argument-vector transfer, shorter
  untracked-reference paths, packed-copy capacity and single-append lowering
  reduce repeated work. These are intended to preserve existing language
  semantics, including mixed language profiles, visibility, called class,
  deprecation, NoDiscard, coercion, references, COW, readonly/property checks,
  generator/capture lifetime and exception translation. They do not authorize
  bypassing these rules. Named/unpacked, native/custom invokers and explicit
  ownership protocols keep their established paths where the proof does not
  cover them. Java/cached caller-owned argument arrays are never borrowed.
- **OPT-007, guarded numeric shapes and recursion:** the existing pure static
  scalar compiler may assume a primitive integer for an untyped parameter only
  behind its exact `LongMemory` entry guard. Other PHP values retain the ordinary
  implementation. Recursive calls require closed declared scalar results and
  complete analysis of every participating body, plus the existing resolver's
  non-overridable-target restrictions. Inferred recursive return types and
  cycles containing unsupported effects retain ordinary dispatch. Compatible
  overflow replays the original pure body; the accepted wrapping profile keeps
  its existing explicit behavior. Debug and active ownership guards remain.
- **Compatibility:** no new Zend-PHP divergence or user setting is introduced.
  In particular, an omitted PHP parameter type does not become an integer type.
  Compiler ABI 32 invalidates newly generated/cached output consistently; old
  PHB files retain their recorded compiler provenance and ordinary fallback.
- **Verification status:** frozen `candidate-r5` passes 171 of 172 selected tests
  in each SOURCE/PHB lane, with no skipped tests. The only failure is the exact
  same 24 cyclic-reference serialization mismatches reproduced on the frozen
  prechange source. The separate 1519 boundary checks pass. All full states in
  the copied Stonewatch model agree across eight seeds and six JVM processes.
  The unchanged 19-workload local control passes all oracles, but its single
  fork and background activity do not establish final timing coefficients.
  Independent three-fork verification is delegated with explicit provenance
  in `independent-verification-handoff.json`. The historical master fails JVM
  verification in the unchanged full
  Stonewatch model, so that application comparison cannot establish a speedup
  against master. Do not disable verification or patch the game to hide this.

The first experiment to reuse arbitrary prepared argument arrays was rejected:
a generator may retain a vector after its call frame closes. The replacement
is restricted to a vector physically owned by a generated call frame, detached
without clearing after transfer; coercion/default/reference slot replacement
still copies before changing the original SEND vector. Preserve the associated
`PositionalVectorLifetimeTest` and cached-vector isolation tests.

The bounded local application check allocates 13,570,857 -> 9,725,683 bytes per
map/build/64-tick/state-serialization operation (28.33% less). Its timing median
is 32.081 -> 25.366 ms, but one of three paired JVM comparisons reverses that
direction, and external activity was observed. Treat timing as directional
only. This neither establishes faster rendering nor reduced retained RAM.
