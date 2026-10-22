<?php
date_default_timezone_set('UTC');
set_error_handler(static function($n,$m){return true;});
class ReviewDateExport extends DateTime {public $tag='v';protected $hidden=2;private $secret=3;}
class ReviewZoneExport extends DateTimeZone {public $tag='z';protected $hidden=4;}
$date=new ReviewDateExport('2024-01-01');
$zone=new ReviewZoneExport('UTC');
foreach(['date'=>$date,'zone'=>$zone] as $name=>$object){
    try{$backing=(new ArrayObject($object))->getArrayCopy();}catch(Throwable $error){$backing=[get_class($error),$error->getMessage()];}
    echo json_encode([$name,array_keys((array)$object),json_encode($object),$object->__serialize(),$backing],JSON_UNESCAPED_SLASHES),"\n";
}
