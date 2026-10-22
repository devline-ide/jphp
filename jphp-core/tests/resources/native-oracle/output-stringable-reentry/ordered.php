<?php
class ReenterValue { public function __toString(){ echo 'i'; return 'v'; } }
$o=new ReenterValue; $ref=&$o; $array=['value'=>$o]; $holder=new stdClass; $holder->value=$o;
echo 'a'; $r=print($ref); echo '|'.$r.'|'; print($array['value']); echo '|'; print($holder->value); ob_start(); print($ref); echo $array['value']; $buffer=ob_get_clean(); echo '['.$buffer.']z';
