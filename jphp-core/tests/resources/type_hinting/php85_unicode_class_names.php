<?php
namespace UCN;
class Éclair { const LABEL = "upper"; }
class éclair { const LABEL = "lower"; }
class Child extends Éclair {}
interface ΣContract {}
interface σContract {}
class ImplementsUpper implements ΣContract {}
class Holder { public Éclair $item; }
class VarianceParent { public function item(): Éclair { return new Éclair(); } }
class VarianceChild extends VarianceParent { public function item(): Child { return new Child(); } }
class ÉFailure extends \Exception {}
class éFailure extends \Exception {}
function upper(Éclair $value): Éclair { return $value; }
$a = new ÉCLAIR(); $b = new éCLAIR(); $c = new Child();
echo get_class($a), '|', get_class($b), '|', Éclair::LABEL, '|', éclair::LABEL, "\n";
echo (int)($a instanceof Éclair), (int)($a instanceof éclair), (int)($c instanceof Éclair), (int)($c instanceof éclair), "\n";
$name = 'UCN\\éclair'; echo (int)($b instanceof $name), (int)($a instanceof $name), "\n";
echo (int)class_exists('UCN\\ÉCLAIR', false), (int)class_exists('UCN\\éCLAIR', false), "\n";
echo (int)is_a($a, 'UCN\\éclair'), (int)is_subclass_of($c, 'UCN\\Éclair'), (int)is_subclass_of($c, 'UCN\\éclair'), "\n";
$i = new ImplementsUpper(); echo (int)($i instanceof ΣContract), (int)($i instanceof σContract), "\n";
$holder = new Holder(); $holder->item = $a;
try { $holder->item = $b; } catch (\TypeError $e) { echo "property-error|"; }
echo get_class($holder->item), "\n";
try { upper($b); } catch (\TypeError $e) { echo "argument-error\n"; }
echo get_class((new VarianceChild())->item()), "\n";
try { throw new ÉFailure(); } catch (éFailure $e) { echo "wrong-catch\n"; } catch (ÉFailure $e) { echo "right-catch\n"; }
\spl_autoload_register(function ($name) { echo "autoload:", $name, "\n"; });
echo (int)class_exists('UCN\\ÉMissing'), (int)class_exists('UCN\\éMissing'), "\n";
