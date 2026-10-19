<?php
require __DIR__ . "/helper.php";
#[AllowDynamicProperties] class P {} class C extends P {} class Plain {} class Magic { public function __set($n,$v) { emit('magic',[$n,$v]); } }
foreach ([new P,new C,new Plain,new stdClass,new Magic] as $o) { $o->extra=1; emit('class',get_class($o)); }
