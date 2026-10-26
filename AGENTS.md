# JPHP compiler/runtime decisions

Before changing language semantics, PHP-version compatibility, compiler lowering
or runtime performance, read [JPHP_SEMANTIC_DEVIATIONS.md](JPHP_SEMANTIC_DEVIATIONS.md).

Preserve accepted intentional JPHP differences and their tests when updating PHP
support. Upstream PHP behavior alone does not supersede those product decisions.
Record new intentional differences, rationale, scope and evidence in that ledger.
Proposed entries are not implemented features or permission to silently change
existing project behavior. Follow the user's current instructions when a decision
is explicitly changed, and record the replacement rather than erasing its history.

Keep JPHP compiler/runtime changes in this owning repository. Preserve unrelated
work and compiled artifacts used by running DevLine installations.
