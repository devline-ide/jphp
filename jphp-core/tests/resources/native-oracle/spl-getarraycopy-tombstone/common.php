<?php
error_reporting(0);
function gc_shared($id,$value){echo $id . '=' . base64_encode(serialize($value)) . "\n";}
class CopySharedPolicy {public $live=1;public $drop=2;public $null=null;}
$o=new CopySharedPolicy;$it=new ArrayIterator($o);unset($o->drop);$copy=$it->getArrayCopy();$copy['live']=9;gc_shared('object-copy-and-null',[$copy,$o->live,isset($o->drop)]);
$alias=5;$data=['reference'=>&$alias,'null'=>null,'nested'=>['n'=>1]];$it=new ArrayIterator($data);$copy=$it->getArrayCopy();$copy['reference']=8;$copy['nested']['n']=9;gc_shared('array-reference-and-cow',[$alias,$copy,$it->getArrayCopy(),$data]);
