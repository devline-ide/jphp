<?php
error_reporting(0);
function numeric_copy_row($id,$o){$it=new ArrayIterator($o);$copy=$it->getArrayCopy();$error=null;try{$copy[]='tail';}catch(Throwable $e){$error=[get_class($e),$e->getMessage()];}echo $id . '=' . base64_encode(serialize([$copy,array_keys($copy),$error])) . "\n";}
$o=new stdClass;$o->{'9'}='nine';$o->{'2'}='two';unset($o->{'9'});numeric_copy_row('numeric-property-remove',$o);
$o=new stdClass;$o->{'-3'}='negative';numeric_copy_row('negative-property',$o);
$o=new stdClass;$o->{'9223372036854775807'}='large';unset($o->{'9223372036854775807'});$o->{'1'}='one';numeric_copy_row('large-property-history',$o);
$o=(object)[9=>'nine',2=>'two'];unset($o->{'9'});numeric_copy_row('array-cast-positive-history',$o);
$o=(object)[-3=>'negative'];numeric_copy_row('array-cast-negative-history',$o);
$o=(object)[9223372036854775807=>'large',1=>'one'];unset($o->{'9223372036854775807'});numeric_copy_row('array-cast-large-history',$o);
