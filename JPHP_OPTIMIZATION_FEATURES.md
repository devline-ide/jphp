# JPHP optimization features

This describes the local compiler/runtime work of 2026-10-26. It is not a
release announcement. Intentional language differences and their preservation
rules are recorded in [JPHP_SEMANTIC_DEVIATIONS.md](JPHP_SEMANTIC_DEVIATIONS.md).
An optimization is not permission to change observable PHP behavior silently.

## Primitive numeric control flow

For eligible pure static methods in PHP_8_5 modules, scalar compilation now
supports `for`, `while`, `do/while`, `if/else`, early returns and nested
`break`/`continue`, including explicit constant loop levels. Supported scalar
locals remain JVM `long`, `double` or `boolean` slots across iterations.

The supported expression subset includes numeric addition, subtraction,
multiplication, comparisons, scalar truthiness, short-circuit `&&`/`||`, integer
bitwise operations and guarded shifts. Statement updates include supported
compound assignments and increment/decrement. Pure closed same-class helper
calls can use primitive kernels directly inside a loop.

```php
final class Totals {
    public static function sum(int $count): int {
        $sum = 0;
        for ($i = 0; $i < $count; $i++) {
            $sum += $i;
        }
        return $sum;
    }
}
```

This can generate one primitive loop body and a guarded PHP boundary adapter.
It does not require rewriting the loop into Kotlin or C++. Ordinary PHP call
binding, result boxing and argument guards still have a cost.

### Eligibility and fallback

- Parameters must have supported scalar declarations. Unsupported expressions,
  effects, dynamic/instance/cross-class dispatch, references, captures, default
  or variadic parameters, generators and unsupported attributes retain the
  ordinary PHP path. General array iteration is not a primitive loop kernel.
- Each local has a stable primitive kind. Definite-assignment analysis merges
  the paths that can continue. A variable assigned only in a loop body or one
  conditional branch is not assumed initialized after that construct.
- Inferred return types must agree exactly across all returns. Mixed inferred
  `int`/`float` branches retain their original value types through ordinary
  execution. An explicit `: float` may use its existing numeric widening rule.
- `COMPATIBLE` remains the default. Checked integer overflow replays the
  original method, which is safe only because eligibility excludes effects.
  `FAST_INT64_WRAP` retains its explicit DEV-001 numeric contract.
- Shift distances outside `[0,63]` use ordinary execution. This optimization
  preserves the fork's existing behavior instead of silently changing its
  wide-shift semantics to another PHP implementation's behavior.
  This does not classify a pre-existing incompatibility as an accepted language
  difference; a separate generic arithmetic fix can update that ordinary path.
- Debug mode and ownership CHECKED retain ordinary execution. Active ownership
  contexts keep their existing guards. The original ABI body, metadata and
  PHP adapter remain available; generated scalar siblings do not replace them.
- Analysis has static limits on nesting, nodes and locals. A rejected method
  continues through ordinary compilation; it is not a failed application build.

There is no new numeric or ownership setting for these control-flow features.
The existing module profiles, source/PHB metadata and guarded scalar dispatch
apply. Existing PHB files are not rewritten: recompile to obtain new bodies.
DevLine's bytecode cache fingerprints the actual compiler/runtime inputs.

## Rejected packed-array experiment

A guarded packed-array integer-read shortcut was measured and removed. In the
direct-runtime fixture the existing implementation already allocated zero
bytes per read; the proposed path did not improve allocation and had slower
timings. The normal PHP read-loop observation also gave no reason to ship it.
Its short PHP samples are not a precise published regression estimate.

The existing array implementation is preserved. Cell identity, `UNDEFINED`,
copy-on-write, cursor behavior, subclass overrides and coercion contracts have
focused regression coverage. Benchmark evidence remains in the coordinating
workspace under `build/php-packed-array-benchmark-20261026-r1/`. Do not describe
that experimental path as an available feature or restore it on the assumption
that removing source-level wrapping must improve generated machine code.

## Verification and measurement

Local focused tests cover emitted JVM validity, source/PHB execution, numeric
profiles, ownership modes, control-flow joins, overflow, truthiness, short
circuiting, shifts, aliases, COW and subclass lookup contracts. Independent
tests compare optimized execution with ordinary CHECKED execution. A captured
pre-change distribution provides the performance baseline.

Performance receipts and final integration status are recorded in the
coordinating DevLine audit `docs/audits/jphp-loop-optimization-20261026.md`.
Timing results must distinguish ordinary PHP calls from direct-runtime
diagnostics. Allocated bytes are not retained application RAM. Numeric loop
speed does not establish game FPS, renderer speed or cross-platform performance.
