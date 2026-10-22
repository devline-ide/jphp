# Experimental mbstring subset

Optional PHP_8_5 provider: `mb_trim`, `mb_ltrim`, `mb_rtrim`, `mb_ucfirst`, `mb_lcfirst`, and `mb_internal_encoding`. Legacy-profile registration is intentionally absent. This is not a complete mbstring extension.

The encoding allowlist is case-insensitive:

| Canonical encoding | Additional accepted aliases |
| --- | --- |
| UTF-8 | UTF8 |
| UTF-16LE | none |
| UTF-16BE | none |
| UTF-32LE | none |
| UTF-32BE | none |
| ASCII | US-ASCII |
| ISO-8859-1 | ISO8859-1, LATIN1 |
| Windows-1251 | CP1251 |

Other valid PHP encoding names are outside this subset and currently raise ValueError. Stateful encodings, general conversion functions and `mb_substitute_character` are not implemented. Replacement is fixed to this implementation's default behavior; configurable substitution is unsupported.

ICU4J `icu4j:77.1` and `icu4j-charset:77.1` provide converters and Unicode mappings. Internal encoding is local to each Environment, with initial `mbstring.internal_encoding`/`default_charset` fallback. Setter coercion completes before publication. No global encoding state or new managed-native capability is introduced.

Activation is explicit: add the module JAR and both pinned ICU dependency JARs to the embedding classpath, then call `scope.registerExtension(new org.develnext.jphp.ext.mbstring.MbstringExtension())`. The Extension service descriptor identifies the provider; it does not establish automatic activation by DevLine or a default SDK. This source change does not install or wire optional dependencies into an SDK.

The shared optional contract has 49 actual PHP8.5.11 rows (weak/state/raw bytes, strict arguments and Reflection). All 196 SOURCE/real-PHB/debug comparisons and three JUnit tests passed on the isolated optional build; the separate intl module is needed for the combined contract. These are bounded subset checks, not full extension compatibility or a performance claim. The original QA wrapper failure remains preserved: JUnit progress dots prefixed two CASEPASS lines; an exact anchored reparse verified all twelve matrix rows without rerunning or altering expectations.
