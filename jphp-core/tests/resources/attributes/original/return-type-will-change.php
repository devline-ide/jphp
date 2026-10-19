<?php
require __DIR__ . "/helper.php";
class Plain implements Countable { public function count() { return 1; } }
class Compatible implements Countable { #[ReturnTypeWillChange] public function count() { return 2; } }
emit('counts', [count(new Plain),count(new Compatible)]); emit('attributes',attribute_view(new ReflectionMethod('Compatible','count')));
