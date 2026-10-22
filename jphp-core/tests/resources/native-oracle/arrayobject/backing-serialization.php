<?php
require __DIR__.'/common.php';
row('backing-object-wire',function(){$a=new ArrayObject(['x'=>1]);$b=new ArrayObject($a);$v=$b->__serialize();return [get_debug_type($v[1]),$v[0],$b->serialize()];});
row('backing-iterator-wire',function(){$a=new ArrayIterator(['x'=>1]);$b=new ArrayObject($a);$v=$b->__serialize();return [get_debug_type($v[1]),$v[0],$b->serialize()];});
row('self-properties',function(){$a=new ArrayObject(['x'=>1]);$a->__construct($a);return [$a->getFlags(),$a->__serialize(),$a->getArrayCopy()];});
