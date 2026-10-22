<?php
error_reporting(0);
function gc_out($id,$value){echo$id,'=',base64_encode(serialize($value)),"\n";}
class CopyMissingSlots {public int $uninitialized;public ?int $null=null;public int $drop=2;private int $private=7;protected int $protected=8;}
$o=new CopyMissingSlots;$it=new ArrayIterator($o);unset($o->drop);gc_out('missing-null-and-hidden',$it->getArrayCopy());
class CopyGetterMutation {public int $first {get{unset($this->drop);$this->late=4;return 7;}}public int $drop=2;}
$o=new CopyGetterMutation;$it=new ArrayIterator($o);$value=$o->first;gc_out('getter-removes-declared',[$value,$it->getArrayCopy()]);
class CopyTypedReferences {public int $number=2;public array $items=['n'=>1];public readonly int $fixed;function __construct(){$this->fixed=3;}}
$o=new CopyTypedReferences;$alias=&$o->number;$it=new ArrayIterator($o);$copy=$it->getArrayCopy();$copy['number']=7;$copy['items']['n']=9;$copy['fixed']=30;$error=null;
try{$copy['number']='bad';}catch(TypeError $e){$error=get_class($e);}
gc_out('actual-reference-and-value-cow',[$alias,$o->number,$o->items,$o->fixed,$copy,$error,$it->getArrayCopy()]);
