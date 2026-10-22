<?php
function probe($id,$name){try{echo json_encode([$id,defined($name)],JSON_THROW_ON_ERROR),"\n";}catch(Throwable $e){echo json_encode([$id,get_class($e),$e->getMessage()],JSON_THROW_ON_ERROR),"\n";}}
class Many {const GOOD=1; const BAD=UNRELATED_MISSING; private const SECRET=PRIVATE_MISSING;}
probe('good-with-other-unresolved','Many::GOOD');
probe('missing-with-other-unresolved','Many::MISSING');
probe('inaccessible-unresolved','Many::SECRET');
trait Direct {const X=1;}
probe('trait-public','Direct::X');
enum Single {case A;}
probe('enum-case','Single::A');
$seen=[];spl_autoload_register(function($n)use(&$seen){$seen[]=$n;});
probe('unknown-pseudo','NotLoaded::class');probe('unknown-empty','NotLoaded::');
echo json_encode(['autoload',$seen],JSON_THROW_ON_ERROR),"\n";
