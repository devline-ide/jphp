<?php
require __DIR__ . "/helper.php";
interface I { #[NoDiscard] public function m(); } abstract class P { #[NoDiscard] abstract public function m(); }
class C extends P implements I { public function m() { emit('body','unmarked'); return 1; } }
trait T { #[NoDiscard] public function m() { emit('body','trait'); return 2; } } class D { use T {m as alias;} }
(new C)->m(); (new D)->m(); (new D)->alias();
