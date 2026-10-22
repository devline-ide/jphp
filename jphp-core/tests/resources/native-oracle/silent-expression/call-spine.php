<?php
$events=[];error_reporting(E_ALL);
function mark($name,$value=1){$GLOBALS['events'][]=[$name,error_reporting()];return $value;}
function named($value){mark('named-body');return $value;}
class QuietTarget {function __construct(){mark('construct');}function call($value){mark('method');return $value;}function __get($key){mark('getter');return function($value){mark('getter-body');return $value;};}}
function receiver(){mark('receiver');return new QuietTarget;}
$results=[];
$results[]=@(function($value){mark('immediate-body');return $value;})(mark('immediate-arg',11));mark('after-immediate');
$callable=function($value){mark('variable-body');return $value;};$results[]=@$callable(mark('variable-arg',12));mark('after-variable');
$results[]=@named(mark('named-arg',13));mark('after-named');
$results[]=@receiver()->call(mark('method-arg',14));mark('after-method');
$results[]=@(receiver()->missing)(mark('getter-arg',15));mark('after-getter');
$results[]=@((function(){mark('callee-factory');return function($value){mark('factory-body');return $value;};})())(mark('factory-arg',16));mark('after-factory');
echo json_encode([$results,$events,error_reporting()]);
