<?php
date_default_timezone_set('UTC');
function row($id,$value){echo json_encode([$id,$value],JSON_UNESCAPED_SLASHES),"\n";}
row('initial-default',date_default_timezone_get());
row('set-default',[date_default_timezone_set('Asia/Almaty'),date_default_timezone_get(),(new DateTimeImmutable('2024-01-01'))->format('e P')]);
$events=[];set_error_handler(function($code,$message)use(&$events){$events[]=[$code,$message];return true;});$result=date_default_timezone_set('Not/A_Zone');restore_error_handler();
row('failed-default-preserves',[$result,date_default_timezone_get(),$events]);
date_default_timezone_set('UTC');
foreach(['!Y-m-d','Y-m-d|','U','!U','U.u']as$format){$input=$format==='U.u'?'0.500000':($format==='U'||$format==='!U'?'0':'2024-02-29');$d=DateTimeImmutable::createFromFormat($format,$input);row('format-'.$format,[$d->format('Y-m-d H:i:s.u e'),DateTimeImmutable::getLastErrors()]);}
$bad=DateTimeImmutable::createFromFormat('!Y-m-d','bad');row('format-failure',[$bad,DateTimeImmutable::getLastErrors()]);
