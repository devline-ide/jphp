--TEST--
Weak scalar hints accept compatible scalars, reject invalid values and numeric prefixes
--FILE--
<?php
function integerValue(int $value): int { return $value; }
function floatValue(float $value): float { return $value; }
function stringValue(string $value): string { return $value; }
function boolValue(bool $value): bool { return $value; }
function invalidReturn(): int { return 'abc'; }
foreach (['abc', '2abc', '', [], new stdClass(), null, '1e', '1e1000', '9223372036854775808'] as $value) {
    try { integerValue($value); echo "missed\n"; }
    catch (TypeError $error) { echo "rejected\n"; }
}
foreach (['abc', [], null] as $value) {
    try { floatValue($value); echo "missed\n"; }
    catch (TypeError $error) { echo "rejected\n"; }
}
try { stringValue([]); echo "missed\n"; } catch (TypeError $error) { echo "rejected\n"; }
try { boolValue(new stdClass()); echo "missed\n"; } catch (TypeError $error) { echo "rejected\n"; }
try { invalidReturn(); echo "missed\n"; } catch (TypeError $error) { echo "rejected\n"; }
var_dump(integerValue('  +2  '), integerValue('2.9'), integerValue('2e1'), integerValue(true));
var_dump(floatValue('1.e2'), floatValue('.5e1'), stringValue(42), boolValue('0'));
--EXPECT--
rejected
rejected
rejected
rejected
rejected
rejected
rejected
rejected
rejected
rejected
rejected
rejected
rejected
rejected
rejected
int(2)
int(2)
int(20)
int(1)
float(100)
float(5)
string(2) "42"
bool(false)
