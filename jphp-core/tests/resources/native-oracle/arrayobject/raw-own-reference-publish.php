<?php
require __DIR__.'/common.php';
class PublishTypedAO extends ArrayObject {public int $x=7;}
row('raw-restore-future-write-guard',function(){$a=new PublishTypedAO;$a->__unserialize([0,[],['x'=>'bad'],null]);$raw=$a->x;try{$a->x=[];}catch(Throwable $e){$failure=[get_class($e),$e->getMessage()];}$a->x=9;return [$raw,$failure,$a->x];});
row('raw-restore-old-alias-detached',function(){$a=new PublishTypedAO;$ref=&$a->x;$a->__unserialize([0,[],['x'=>'bad'],null]);try{$ref='old';$out=['accepted',$ref];}catch(Throwable $e){$out=[get_class($e),$e->getMessage(),$ref];}return [$out,$a->x];});
row('raw-restore-new-reference-guard',function(){$a=new PublishTypedAO;$value=5;$a->__unserialize([0,[],['x'=>&$value],null]);try{$value='bad';$out=['accepted'];}catch(Throwable $e){$out=[get_class($e),$e->getMessage()];}return [$out,$value,$a->x];});
row('raw-restore-other-owner-stays-guarded',function(){$a=new PublishTypedAO;$other=new PublishTypedAO;$ref=&$a->x;$other->x=&$ref;$a->__unserialize([0,[],['x'=>'bad'],null]);try{$ref='bad';$out=['accepted'];}catch(Throwable $e){$out=[get_class($e),$e->getMessage()];}return [$out,$ref,$a->x,$other->x];});
row('nested-modern-wire-byte-preservation',function(){$a=new ArrayObject(['text'=>'ёж😀']);$b=new ArrayObject($a);return bin2hex($b->serialize());});
row('invalid-byte-wire-hex',function(){$a=new ArrayObject(["\xFF"=>"a\0\x80"]);$wire=$a->serialize();$b=new ArrayObject;$b->unserialize($wire);$copy=$b->getArrayCopy();$key=null;foreach($copy as $candidateKey=>$ignored){$key=$candidateKey;break;}return [bin2hex($wire),bin2hex($key),bin2hex($copy[$key])];});
class PublishWireAO extends ArrayObject {public $alias;public $object;}
row('backing-ownprops-shared-cell-object',function(){$value=3;$object=(object)['n'=>4];$a=new PublishWireAO(['cell'=>&$value,'object'=>$object]);$a->alias=&$value;$a->object=$object;$wire=$a->serialize();$b=new PublishWireAO;$b->unserialize($wire);$b->alias=9;$copy=$b->getArrayCopy();return [bin2hex($wire),$copy['cell'],$b->alias,$copy['object']===$b->object];});
