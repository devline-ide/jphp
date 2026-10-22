# Experimental intl grapheme subset

Optional PHP_8_5 provider: `grapheme_str_split` only, using ICU4J 77.1 extended grapheme boundaries over UTF-8 input and preserving original byte slices. Legacy-profile registration is intentionally absent. This is not a complete intl extension: locale/error APIs, normalization, collators and other intl functions are not supplied.

The companion mbstring module supports exactly eight encodings with an explicit case-insensitive alias list: UTF-8 (UTF8), UTF-16LE, UTF-16BE, UTF-32LE, UTF-32BE, ASCII (US-ASCII), ISO-8859-1 (ISO8859-1, LATIN1), and Windows-1251 (CP1251). These are mbstring arguments/state, not an encoding argument for grapheme_str_split. General encoding/substitution configuration is outside the subset; conversion uses fixed defaults. Encoding state belongs to each Environment in the companion module, with no JVM-global encoding mutation.

Activation is explicit: put this module JAR plus `com.ibm.icu:icu4j:77.1` and `com.ibm.icu:icu4j-charset:77.1` on the embedding classpath, then call `scope.registerExtension(new org.develnext.jphp.ext.intl.IntlExtension())`. The service descriptor identifies the Extension provider; default SDK activation, dependency installation and live IDE delivery are not performed. No new active managed-native capability is claimed.

The combined mbstring/intl portable contract contains 49 actual PHP8.5.11 rows and passed all 196 SOURCE/real-PHB/debug comparisons plus three JUnit tests using isolated optional JARs. This proves only the named subset and controls. The original QA wrapper result remains failed because its post-parser missed two JUnit-dot-prefixed CASEPASS lines; an independent exact twelve-row reparse proves the completed comparisons without changing native goldens or rerunning JVMs.
