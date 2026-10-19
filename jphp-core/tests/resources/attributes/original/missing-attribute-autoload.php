<?php
require __DIR__ . "/helper.php";
#[Later('value')] function f() {}
$events=[]; spl_autoload_register(function($name) use (&$events) { $events[]=$name; if($name==='Later') eval('#[Attribute] class Later { public function __construct(public $v) {} }'); });
$a=(new ReflectionFunction('f'))->getAttributes()[0]; emit('enumeration',$events); emit('arguments',[$a->getArguments(),$events]); $o=$a->newInstance(); emit('instance',[$o->v,$events]);
