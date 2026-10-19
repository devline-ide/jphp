<?php
namespace N;
require __DIR__ . '/helper.php';
use Attribute as Marker;
#[Marker(Marker::TARGET_ALL | Marker::IS_REPEATABLE)] class Base { public function __construct(public $v = 0) {} }
#[Marker(Marker::TARGET_ALL | Marker::IS_REPEATABLE)] class Child extends Base {}
use N\Child as Alias;
#[Alias(1), Base(2)] #[Alias(3)] class Subject {}
$r = new \ReflectionClass(Subject::class); emit('all', attribute_view($r));
emit('exact', count($r->getAttributes(Child::class)));
emit('case', count($r->getAttributes('n\\child')));
emit('instanceof', count($r->getAttributes(Base::class, \ReflectionAttribute::IS_INSTANCEOF)));
attempt('flags', fn() => $r->getAttributes(null, 128));
$events = []; spl_autoload_register(function($n) use (&$events) { $events[] = $n; });
emit('exact-missing', count($r->getAttributes('Absent'))); emit('exact-loads', $events);
attempt('instanceof-missing', fn() => count($r->getAttributes('Absent', \ReflectionAttribute::IS_INSTANCEOF))); emit('instanceof-loads', $events);
