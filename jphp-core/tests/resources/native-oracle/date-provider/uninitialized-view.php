<?php
class ColdDate extends DateTime {public $tag='d';public function __construct(){}}
class ColdImmutable extends DateTimeImmutable {public $tag='i';public function __construct(){}}
class ColdZone extends DateTimeZone {public $tag='z';public function __construct(){}}
foreach(['date'=>new ColdDate(),'immutable'=>new ColdImmutable(),'zone'=>new ColdZone()]as$id=>$object){foreach(['cast','json','export','serialize']as$operation){try{$value=match($operation){'cast'=>(array)$object,'json'=>json_encode($object),'export'=>var_export($object,true),'serialize'=>$object->__serialize()};$error=null;}catch(Throwable$failure){$value=null;$error=[get_class($failure),$failure->getMessage()];}echo json_encode([$id,$operation,$value,$error],JSON_UNESCAPED_SLASHES),"\n";}}
