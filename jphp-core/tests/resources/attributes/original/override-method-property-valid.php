<?php
require __DIR__ . "/helper.php";
interface I { public function m(); } class P { public int $x=1; public function m() {} }
trait T { #[Override] public function m() {} }
class C extends P implements I { use T { m as alias; } #[Override] public int $x=2; }
class D extends P { public function __construct(#[Override] public int $x=3) {} #[Override] public function m() {} }
emit('classes', [(new C)->x,(new D)->x]);
