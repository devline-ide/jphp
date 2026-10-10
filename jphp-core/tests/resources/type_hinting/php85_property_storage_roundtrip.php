<?php
class PSTRound {}
function pstr_event($s,$m){global $pstr_events;$pstr_events[]=[$s,$m];}
function pstr_duplicate($field){$o=new PSTRound;set_error_handler(function($s,$m)use($o,$field){pstr_event($s,$m);set_error_handler(function($s,$m){pstr_event($s,$m);return true;});try{$o->$field=4;}finally{restore_error_handler();}return true;});try{$o->$field=1;}finally{restore_error_handler();}return $o;}
$pstr_events=[];$o=pstr_duplicate('x');$a=(array)$o;$round=serialize((object)$a);$n=pstr_duplicate('0');$numeric=[serialize($n),serialize((array)$n),serialize(get_object_vars($n))];set_error_handler(function($s,$m){pstr_event($s,$m);return true;});try{$o->{'0'}=8;}finally{restore_error_handler();}$mixed=[serialize((array)$o),serialize(get_object_vars($o))];$rows=[[$round,$numeric,$mixed,$pstr_events]];
$pstr_events=[];$o=pstr_duplicate('x');$a=(array)$o;$rows[]=[[reset($a),next($a),prev($a),end($a),prev($a),current($a),key($a),next($a),next($a),prev($a),current($a)],$pstr_events];
return $rows;
