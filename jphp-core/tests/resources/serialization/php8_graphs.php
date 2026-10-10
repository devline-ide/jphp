<?php
$output = '';
foreach ([null, false, true, 0, -1, 9223372036854775807, '', 'Привет', "a\0b", 0.0, -0.0, 1.5, 1.0e20, 1.0e-5, INF, -INF, NAN] as $value) {
    $output .= serialize($value) . "\n";
}
$x = 1; $y = 2;
$output .= serialize([&$x, &$x, &$y, &$y]) . "\n";
$x = null;
$output .= serialize([&$x, &$x]) . "\n";
$array = [1];
$output .= serialize([$array, $array]) . "\n";
$array = []; $array['self'] = &$array;
$output .= serialize($array) . "\n";
$object = new stdClass(); $object->self = $object;
$output .= serialize($object) . "\n";
$output .= serialize([$object, $object, 2, $object]) . "\n";
$output .= serialize([$object, &$object, &$object]) . "\n";
$first = new stdClass(); $second = new stdClass();
$first->other = $second; $second->other = $first;
$output .= serialize($first) . "\n";
$x = 3; $original = [&$x, &$x];
$restored = unserialize(serialize($original)); $restored[0] = 9;
$output .= serialize([$restored, $original, $x]) . "\n";
$original = [[1], [1]]; $restored = unserialize(serialize($original)); $restored[0][0] = 9;
$output .= serialize([$restored, $original]) . "\n";
$object = new stdClass(); $object->x = 1;
$restored = unserialize(serialize([$object, $object])); $restored[0]->x = 9;
$output .= serialize([$restored[0] === $restored[1], $restored[1]->x, $object->x]) . "\n";
$restored = unserialize('a:1:{s:4:"self";R:1;}'); $restored['x'] = 7;
$output .= serialize($restored['self']['x']) . "\n";
class GraphWake {
    public $other;
    public $name;
    public function __wakeup() { global $output; $output .= 'wake:' . $this->name . ':' . $this->other->name . "\n"; }
}
$first = new GraphWake(); $first->name = 'first';
$second = new GraphWake(); $second->name = 'second';
$first->other = $second; $second->other = $first;
$data = serialize($first); $output .= $data . "\n"; $restored = unserialize($data);
$output .= serialize($restored->other->other === $restored) . "\n";
class GraphModern {
    public $value;
    public function __sleep() { throw new Exception('sleep must not run'); }
    public function __wakeup() { throw new Exception('wakeup must not run'); }
    public function __serialize() { return ['saved' => $this->value, 'self' => $this]; }
    public function __unserialize($data) { $this->value = $data['saved'] + ($data['self'] === $this ? 1 : 100); }
}
$modern = new GraphModern(); $modern->value = 8;
$data = serialize($modern); $output .= $data . "\n";
$restored = unserialize($data); $output .= serialize($restored->value) . "\n";
class GraphPrivate {
    private $secret = 'hidden';
    protected $protected = 'safe';
    public $public = 'open';
    public function values() { return [$this->secret, $this->protected, $this->public]; }
}
$data = serialize(new GraphPrivate()); $output .= $data . "\n";
$output .= serialize(unserialize($data)->values()) . "\n";
$data = serialize($modern);
$output .= serialize(unserialize($data, ['allowed_classes' => false])) . "\n";
$output .= serialize(unserialize($data, ['allowed_classes' => ['GraphModern']])->value) . "\n";
$output .= serialize(unserialize('O:7:"Missing":1:{s:1:"x";i:3;}')) . "\n";
foreach (['', 'R:1;', 'r:1;', 'a:1:{i:0;r:1;}', 's:9:"hi";', 'a:2:{i:0;i:1;}', 'i:abc;', 'b:2;', 'O:8:"stdClass":1:{N;i:1;}'] as $bad) {
    $output .= serialize(@unserialize($bad)) . "\n";
}
$output .= serialize(@unserialize('a:1:{i:0;a:1:{i:0;i:1;}}', ['max_depth' => 1])) . "\n";
$output .= serialize(unserialize('a:1:{i:0;a:1:{i:0;i:1;}}', ['max_depth' => 0])) . "\n";
$output .= serialize(@unserialize('i:5;extra')) . "\n";
class GraphCustom implements Serializable {
    public $value;
    public function serialize() { return serialize($this->value); }
    public function unserialize($data) { $this->value = unserialize($data); }
}
$custom = new GraphCustom(); $custom->value = 'Привет';
$data = serialize($custom); $output .= $data . "\n";
$output .= serialize(unserialize($data)->value) . "\n";
class GraphCustomModern extends GraphCustom {
    public function __serialize() { return ['saved' => $this->value]; }
    public function __unserialize($data) { $this->value = $data['saved'] + 1; }
}
$custom = new GraphCustomModern(); $custom->value = 3;
$data = serialize($custom); $output .= $data . "\n";
$output .= serialize(unserialize($data)->value) . "\n";
foreach ([['max_depth' => -1], ['max_depth' => '1'], ['max_depth' => null], ['allowed_classes' => null], ['allowed_classes' => [1]]] as $options) {
    try { unserialize('N;', $options); $output .= "missed-error\n"; }
    catch (ValueError $e) { $output .= "ValueError\n"; }
    catch (TypeError $e) { $output .= "TypeError\n"; }
}
try { serialize(function() {}); $output .= "missed-error\n"; }
catch (Exception $e) { $output .= "closure-error\n"; }
foreach (['i:9223372036854775808;', 'i:-9223372036854775809;', 'i:+1;', 'd:Infinity;', 'd:NaN;', 'd:+INF;', 'd:-NAN;', 'd:1f;', 'd:0x1.0p1;', 'd: 1;', 'd:1.;', 'd:.5;'] as $data) {
    $output .= serialize(@unserialize($data)) . "\n";
}
class GraphStringable { public function __toString() { return 'i:7;'; } }
$output .= serialize(unserialize(new GraphStringable())) . "\n";
foreach ([[], new stdClass()] as $invalid) {
    try { unserialize($invalid); $output .= "missed-error\n"; }
    catch (TypeError $e) { $output .= "input-TypeError\n"; }
}
return $output;
