<?php
class ReenterThrow { public function __toString(){ echo 'i'; throw new Exception('boom'); } }
$o=new ReenterThrow; $ref=&$o; $array=['value'=>$o]; $holder=new stdClass; $holder->value=$o;
echo 'a'; ob_start(); echo 'p'; try { print($ref); } catch(Exception $e) { echo '|'.$e->getMessage().'|'; } $buffer=ob_get_clean(); echo '['.$buffer.']'; try { echo $array['value']; } catch(Exception $e) { echo '|'.$e->getMessage().'|'; } try { print($holder->value); } catch(Exception $e) { echo '|'.$e->getMessage().'|'; } echo 'z';
