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

## Performance policy reaffirmed on 2026-10-28

**Accepted user decision:** JPHP may depart from PHP 8.5's internal execution
model to improve measured performance. Familiar PHP syntax and language APIs
remain the reference for authors; the JVM implementation may use its own value
representation, specialization, call lowering, ownership and execution strategy.
Reproducing Zend's internal mechanisms is not a product requirement.

The current optimization work is authorized to implement bounded differences
where useful. For an observable language difference, record the selected JPHP
behavior, its rationale, measured benefit, affected programs, compatibility or
version/profile boundary and regression evidence here. A proposed tradeoff
becomes an implemented feature only after that behavior and its tests exist.
Internal changes with equivalent observable results are optimizations rather
than new language differences; record their supported subset and fallback.

When adding support for a later PHP version, preserve these accepted JPHP rules
and tests. Replace a decision through an explicit superseding ledger entry and
migration, rather than automatically restoring PHP parity. Keep negative timing
results and distinguish allocation rate from retained memory and game FPS.

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
The r5 capture preceded the later `d3b30922` checkpoint of this implementation.
The frozen source manifest remains its reproduction identity; that checkpoint
does not establish a released IDE build. Later follow-ups retain separate
snapshots and verification rather than overwriting r5.

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

### Independent r5 verification received, 2026-10-27

The independent unchanged 19-workload run completed nine fresh JVM processes
with 1197 validated samples, three rotated forks per version, two-second warmup
and seven samples of at least 200 ms. Every variant (master, prechange, r5)
also passes 1519 boundary checks. Source/JAR/class bytes remain unchanged.

By pooled median, r5 is over 5% faster on nine workloads and slower on ten
against master 2020. Against prechange modern JPHP it improves sixteen, regresses
two (strings and closures by pooled median) and leaves references approximately
unchanged. Per-process directions matter: strings and closures are mixed against
prechange, so their pooled regression alone does not prove a particular patch
caused the slowdown. Against master, eight workloads improve in every paired
fork, eight lose in every pair, and scalar composition/packed/associative arrays
change direction. No universal language speedup is established.

The machine was not isolated: observed CPU spans 15.0-91.8%, median 46.9%,
including benchmark/JIT/GC. Keep background and per-fork spread with coefficients.
The selected regression suite retains its identical prechange cycle failure.
Detailed tables and raw samples are retained in the coordinating workspace as
`build/jphp-general-perf-20261027-r1/jphp-r5-independent-report.txt`,
`jphp-r5-independent-results.csv` and `jphp-r5-independent-results.json`.

The next runtime-only candidate restores use of the existing integer decimal
cache during concatenation, bounds closure diagnostic-name reuse, separates
standard integer arithmetic from coercion and proves one plain reference hop
before omitting sparse-CELL scans. These do not introduce new PHP divergences
or change compiler ABI 32. Candidate r6 is separate from the independently
measured r5; do not attach r5's timing coefficients to those new changes.

### Selected runtime follow-up: r8

The intermediate r6/r7 generic integer `binary` entry was not retained. R8
restores the r5 generic binary body and applies the numeric shortcut only to
compound assignment with a raw proof: exact ordinary `ReferenceMemory`, at
most one exact ordinary alias, no sparse/foreign authority, exact `LongMemory`
payload and right operand. Interned `plus`/`minus`/`mul` opcodes take that path;
dynamic opcode strings, custom getters/subclasses, other types, longer chains
and managed carriers use the original implementation. The result is published
through ordinary `target.assign(result).toImmutable()`, including property,
readonly and type checks. Existing numeric-profile/overflow rules remain.

String concatenation reuses the *existing* decimal cache for integers 0-9999;
its size and range are unchanged. Other integers retain standard conversion.
Ordinary closure diagnostic names have a bounded 256-entry cache storing only
file strings, line numbers and display strings, with file length at most 1024.
Captured first-class callable names retain their own qualified-name path.
These caches exchange bounded retained strings for fewer transient allocations;
they are not evidence that retained application RAM shrank. The unmeasured
shared-empty-argument-vector experiment was removed.

The r8 local comparison uses six fresh JVMs, three alternating r5/r8 pairs and
798 oracle-checked samples of the unchanged 19 workloads. Reference arithmetic
improves in every pair (1.998x/2.320x/2.233x); string construction does too
(1.251x/1.168x/1.203x). Closure timings remain mixed while allocated bytes change
from 21136.5 to 12944.5 per 64-call operation. String allocation changes from
14881.5 to 11809.5 bytes. Packed-array pooled allocation remains 4352.5 bytes;
its transient-builder elimination varied across exploratory JVM processes, and
the isolated arithmetic rollback did not establish a causal regression.
Whole-machine CPU in the final control spans 14.0-60.3%, median 18.8%, including
the benchmark/JIT/GC. Keep all workloads and per-process variation with results.

Full selected SOURCE/PHB lanes each run 187 tests: 186 pass, the same prechange
cycle-serialization failure remains, and none are skipped. Both directory and
packaged-JAR runs pass 1519 boundary checks. In the unchanged Stonewatch model,
all eight complete states agree across six JVMs and previous receipts. Allocation
falls in all three pairs (9728280 -> 9199855 bytes/operation, 5.43% less), but
application timing gain is **not established**: paired ratios are
0.985x/1.020x/0.891x under observed 33-64% machine load. Preserve the earlier
r7 negative application receipt as well. No FPS or retained-RAM claim follows.

This is a sealed, uncommitted compiler/runtime candidate on top of checkpoint
`d3b30922`, not an installed IDE release. ABI remains 32. Exact classpath, source
and JAR hashes, all-workload CSV, raw samples, tests and negative observations
are recorded at `build/jphp-general-perf-20261027-r1/independent-verification-handoff-r8.json`.
Independent post-seal verification with renamed code and previously unseen inputs
is completed below; its mixed timing results supersede general local claims.

Pure sibling kernels do not construct a PHP call frame for each internal helper
or recursive kernel call. Debug mode retains ordinary execution. Resource
exhaustion depth and internal diagnostic stack shape need not match ordinary
execution; do not extend the pure-path claim to exact diagnostic-stack parity.

### Independent r8 verification (2026-10-27)

The sealed r8 passed a post-seal holdout with renamed classes and new inputs:
nine classes plus an interface, polymorphic methods, references, captured
closures/cycles and COW checkpoints. All 36 full states match an independent
Python oracle in each of three fresh JVMs for both r5 and r8. Allocations fall
6.06%/6.32%/6.09% on small/medium/large cases. Small and medium are faster in
all three paired processes; large has mixed timing directions. These are model
operations including setup/export/oracle checks, not renderer FPS or retained RAM.

The unchanged 19-workload independent control uses nine fresh JVMs and 1197
checked samples. Against master 2020 r8 has eight pooled wins above 5% and eleven
losses; against r5 it has four wins, ten losses and five near-equal cases.
Reference arithmetic is about 2.035x faster than r5 with all three pairs winning.
String/closure timing gains are mixed across pairs. oldLoop, numericFor,
scalarAdd and scalarCompose regress against r5 in every pair; their cause remains
under investigation. No universal runtime speedup is established.

Master's full holdout changes original COW checkpoints with their copies and is
excluded from holdout timing. Three smaller reductions pass on all versions,
so this is a contextual observed mismatch with the selected array-copy contract,
not a claim that all master array copies fail. Native PHP CLI was not run.
Each version passes 1519 boundary checks. The independent audit read existing
187-test SOURCE/PHB XML (186 pass, one identical known failure, zero skips);
it did not rerun that suite. System CPU includes benchmark/JIT/GC and other work:
microbench 23.9-88.8% (median 39.2%), holdout 12.1-64.5% (median 20.0%).

The immutable audit, full tables, per-process directions and reproduction bundle
are `C:/Users/ozlig/Documents/Codex/2026-10-27/x20/outputs/`
`jphp-r8-independent-report.txt`, `jphp-r8-independent-results.json` and
`jphp-r8-independent-proof.zip`. JAR SHA-256 remains
`ae775f45d34ffd83cf2bfde98db3d8d91d65f71c089fa107efc20b171978c6ac`.
The first holdout harness-context error and negative observations are preserved.
R8 remains a candidate, not a replacement of the running IDE.

### Rejected positional-preparation experiment (r9/r10, 2026-10-28)

The r9 trial added a data-only proof for a full positional vector of exact
scalar values and known pure parameter checkers. It preserved caller-vector
cloning, existing frame-owned transfer and ordinary fallback. Adversarial tests
cover custom metadata/default/checker effects, unusual boolean tags, coercion,
references, names/unpacking and generator lifetime. Its 475-byte proof added
another argument scan and did not establish a consistent general call benefit;
scalarAdd was slower than r5 in all three paired processes. The proof and its
delegate were removed from production, with the test retained as a regression
contract. The exact rejected source and measurements remain in the laboratory.

The nine-JVM r5/r9/r10 control checks all 19 unchanged workloads and all 1197
samples. Whole-machine CPU was 14.5-97.6%, median 42.8%, including our benchmark,
JIT and GC; many timing directions varied. Keep the full table and negative
results in `build/jphp-general-perf-20261027-r1/r9-r10-local-report.txt` and
`r9-r10-local-results.csv`. R9 and r10 are experiments, not installed releases.
The follow-up r11 separates existing cold coercion/error handling and packed
append fallback; its bytecode layout is recorded in `r11-method-layout.json`.
Layout size and crossing an inlining heuristic do not establish acceleration;
the separate r8/r11 control and rejection decision are recorded below.

### R11 layout experiment rejected as the general default (2026-10-28)

R11 split existing failed type-coercion/error handling from its successful path
and tried a narrowly guarded ordinary packed append. The relevant hot Code
sizes were 136 and 212 bytes, respectively. All 67 selected SOURCE tests and
67 PHB tests pass with zero skips. A separate replay passes the 36 already-known
independent model cases plus all eight historical Stonewatch full states; this
replay is not a new unseen holdout.

The r8/r11 all-19-workload control retains six JVMs, three pairs and all 798
sample oracles. Numeric-loop, scalar-composition, associative-array and COW
cases improve in all pairs, but several other timing directions vary. Packed
array allocation increases from 4352.5 to 8448.5 bytes/operation and its pooled
time worsens. A JIT/escape-analysis interaction is possible, not a proved cause.
Whole-machine CPU spans 16.0-59.4%, median 31.4%, including our benchmark/JIT/GC.
The actual-model AUTO comparison also has mixed paired timing and effectively
unchanged allocation. R11 is therefore retained only as a laboratory experiment.

Both production runtime files were restored surgically, and every current
core/runtime source hash matches the previously sealed r8 snapshot. The two
new regression test classes remain. See `selected-source-r8-after-r11.json`,
`r11-local-report.txt`, `r11-local-results.csv`, `final-r11-summary.json` and
`replay-r11-after-ownership/receipt.json` in the coordinating laboratory.
The running IDE and original game projects were never replaced.

### Ownership investigation boundary (2026-10-28)

Ownership instrumentation is an implementation mechanism, not a requirement
that every scalar or ordinary exclusive array must carry an active ownership
graph. Eliding irrelevant work is permitted by the accepted performance policy.
Existing DEV-002 OFF is the explicit module-level contract for omitting generated
ownership lanes; its admission and lifecycle limits still apply. Ordinary COW,
references, types and property rules remain separate from that instrumentation.

Distinguish an active Session/Authority graph from passive generated guards.
The ordinary Environment lazily allocates no ownership-context ThreadLocal and
its current-session getter can simply return null. The model harness does not
construct an explicit Session/Authority. AUTO/OFF model comparisons audit the
cost of generated instrumentation; they do not prove the cost of an active
ownership graph or that every IDE execution path is passive. Generated class
bytes are neither executable-Code-only bytes nor retained process RAM.

### Selected r8 AUTO/OFF actual-model audit (2026-10-28)

A final same-engine comparison uses frozen r8 in both modes, six fresh JVMs,
three alternating pairs, two seconds of warmup and seven samples of at least
200 ms. The helper, four game model sources, actions and all eight full golden
states are unchanged. Every measured invocation verifies its complete state.
The scope, all five source Contexts and stored ModuleEntity metadata are checked
for the selected mode. The exact compiled helper from the earlier r8/r11 mode
control is reused, with its source/class hashes verified.

OFF allocates 9217352 -> 8488691 bytes per model operation (7.905% less); all three
pairs improve by 7.69-7.98%. The 12 generated class files total 1910845 -> 411367
bytes (78.47% less, including metadata/attributes). This is not a process-RAM,
renderer or FPS measurement. The pooled time is 28.284 -> 20.973 ms, but paired
AUTO/OFF ratios are 1.484x/1.499x/0.577x: the third direction reverses. Stable
application acceleration is not established. Whole-machine CPU full-window
min/median/max is 43.9/68.6/97.1%, including benchmark/JIT/GC and other work.
Do not discard the negative pair or transfer the earlier r11-only timing claim
to r8. Existing OFF is a useful instrumentation/size tradeoff to evaluate within
DEV-002's documented boundary, not permission to remove all ownership rules.

Evidence is in the coordinating laboratory at
`build/jphp-general-perf-20261027-r1/ownership-r8-auto-off/summary.json`,
`config-manifest.json`, `notes.json` and the adjacent
`ownership-r8-auto-off-cpu.json`. Earlier r8/r11/AUTO/OFF control and all negative
candidates remain preserved. AUTO remains the default; original projects and
the running IDE were not modified. The two newly retained regression classes
also pass on the selected r8 itself: SOURCE 11/11 and PHB 11/11, zero skips,
reusing the frozen compiled test classes without a compiler rebuild. See
`selected-r8-regression-tests/receipt.json`.


### ABI33: opt-in local integer array reductions (2026-10-28)

`Context.setPhpArrayOptimizationEnabled` enables a conservative, complete-body
proof for local unkeyed value-array sums in authored PHP_8_5 class methods.
The original PHP method remains the fallback. Exact argument guards, checked
addition/overflow fallback in COMPATIBLE, normal PHP binding/access/dispatch,
and the existing FAST_INT64_WRAP contract remain authoritative. Debug and
CHECKED compilation disable specialization. No new semantic deviation is intended.

This first form accepts at most64 direct integer literal/parameter elements,
zero accumulator, keyless by-value foreach addition and returning the sum.
References, effects, escaped arrays and unsupported shapes retain ordinary PHP.
Instance entries require a receiver-free whole-body proof. This is direct JVM
lowering, not a PHP-to-Kotlin source translator or a universal array optimization.

The option defaults false and participates in source cache identity. ABI33 PHB
headers retain it, require PHP_8_5 and original ABI33 provenance; older headers
default false. Compiled artifact policy is independent of later scope defaults.
DevLine carries the same policy through worker/cache/build/run and authored
source inventories; vendor and SDK sources remain excluded. See
`ScalarArrayOptimizationTest` and the coordinating
`docs/audits/php-numeric-arrays-20261028.md` / isolated evidence directory.


### ABI34: optional Kotlin/JVM kernels (2026-10-28)

The new string-kernel option is independent of ABI33's array option and defaults
false. ABI34 stores both flags, requires original ABI34/PHP_8_5 provenance for
the string flag, and keeps old headers disabled for that option. Source module
and artifact cache identity includes both settings. Artifact policy remains
authoritative after loading; SDK/vendor sources keep their own profiles.

Public compiler/runtime SPIs are optional. The private DevLine module supplies
bounded nonescaping array projections (still verified by the standard complete
scalar proof) and whole integer string-building kernels on Kotlin/JVM primitives.
It does not copy covered compiler implementations into private code. No source
PHP is rewritten, and no new language deviation is intended. Ordinary PHP binding,
access, dispatch, return checks, compatible overflow and unsupported/effectful
bodies remain authoritative. FAST_INT64_WRAP retains its existing exception.
Debug/CHECKED/active ownership paths retain ordinary execution.

String entries call an optional pure runtime provider and fall back when absent,
so generated PHB does not hard-link a proprietary implementation. Private limits
(1 MiB result/iteration budget), raw-byte/non-ASCII cases, incompatible arguments,
overflow and interruption request ordinary PHP execution; no answer cache or
benchmark/class/seed-specific admission is used.

See coordinating docs/php-kotlin-kernels.md, devline-php-optimizer tests,
phpKotlinKernelsPipelineCheck and phpKernelSettingsPersistenceCheck. Timing
receipts belong to build/php-numeric-arrays-20261028-r2 and are not game FPS.
