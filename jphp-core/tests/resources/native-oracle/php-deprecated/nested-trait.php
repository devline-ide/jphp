<?php
trait NestedPlainBase { public function value() { return 73; } }
trait NestedPlainUse { use NestedPlainBase; }
function make_nested_plain() { return new class { use NestedPlainUse; }; }
class NestedPlainNamed { use NestedPlainUse; }
echo 'nested-anonymous:', json_encode(make_nested_plain()->value()), "\n";
echo 'nested-named:', json_encode((new NestedPlainNamed)->value()), "\n";
