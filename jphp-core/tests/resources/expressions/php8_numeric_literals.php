<?php
const LARGE = 9_223_372_036_854_775_808;
class LiteralValues { const OCTAL = 0o7_7; }
function literalDefault($x = 0X7fff_ffff_ffff_ffff) { return $x; }
function literalIdentity($x) { return $x; }
$values = [
    0, 00, 0_0, 0_7, 0777, 0o777, 0O7_7, 0b1010, 0B1_0_1_0, 0xFf, 0Xf_f,
    1_000_000, 9_223_372_036_854_775_807, 9_223_372_036_854_775_808,
    18446744073709551615, 18446744073709551616, 123456789012345678901234567890,
    0x7fff_ffff_ffff_ffff, 0x8000_0000_0000_0000, 0xffff_ffff_ffff_ffff,
    0x1_0000_0000_0000_0000, 0x123456789abcdef123456789abcdef,
    0777777777777777777777, 01000000000000000000000, 01777777777777777777777,
    0o777777777777777777777, 0o1000000000000000000000, 0o1777777777777777777777,
    0b111111111111111111111111111111111111111111111111111111111111111,
    0b1000000000000000000000000000000000000000000000000000000000000000,
    0b1111111111111111111111111111111111111111111111111111111111111111,
    .5, 1., 00.8, 1.e2, 1_0.e+1_0, 1.2_5e-0_2, 1e1_0, 1E+3,
    1e309, 1e-999, 5e-324, -0.0, -9_223_372_036_854_775_808,
    LARGE, LiteralValues::OCTAL, literalDefault(), literalIdentity(0o10),
    9_223_372_036_854_775_807 + 1, literalIdentity(9_223_372_036_854_775_807) + 1,
    0xffff_ffff_ffff_ffff + 1, literalIdentity(0xffff_ffff_ffff_ffff) + 1,
    0x1e2, 0_123, -0o77,
];
$keys = [0o10 => 41, 8 => 42];
$values[] = count($keys);
$values[] = $keys[0o10];
$parameters = (new ReflectionFunction('literalDefault'))->getParameters();
$values[] = $parameters[0]->getDefaultValue();
return $values;
