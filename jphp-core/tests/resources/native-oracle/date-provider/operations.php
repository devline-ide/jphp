<?php
date_default_timezone_set('UTC');
function row($id,$value){echo json_encode([$id,$value],JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES),"\n";}
$d=new DateTimeImmutable('2024-02-29 13:04:05.123456 UTC');
row('all-format-tokens',$d->format('d D j l N S w z W F m M n t L o Y y X x a A B g G h H i s u v e I O P p T Z c r U'));
row('format-raw-bytes',bin2hex($d->format(hex2bin('ff005c597c'))));
row('fixed-zone-format',(new DateTimeImmutable('2024-01-01 00:00:00+05:30'))->format('e T O P Z I'));
$interval=new DateInterval('P1DT2H3M4S');$interval->f=0.25;row('interval-fields',[$interval->format('%D %H %I %S %F %r%a'),$d->add($interval)->format('Y-m-d H:i:s.u')]);
$berlin=new DateTimeImmutable('2024-03-30 12:30:45',new DateTimeZone('Europe/Berlin'));
foreach(['+24 hours','+1 day','first day of next month']as$text)row('modify-'.$text,$berlin->modify($text)->format('Y-m-d H:i:s P'));
row('iso-overflow',$d->setISODate(2024,1,8)->format('Y-m-d H:i:s.u'));
row('setter-default-time',$d->setTime(2,3)->format('Y-m-d H:i:s.u'));
$m=DateTime::createFromImmutable($d);$i=DateTimeImmutable::createFromMutable($m);row('conversions',[get_class($m),get_class($i),$i==$m,$i===$d,$m->format('Y-m-d H:i:s.u e')]);
class DateChild extends DateTime {public string $tag='a';public function __clone(){$this->tag.='clone';}}
$a=new DateChild('2024-01-01');$copy=clone $a;row('subclass-clone',[$a->format('Y-m-d'),$copy->format('Y-m-d'),$a->tag,$copy->tag,get_class($copy)]);
class ImmutableChild extends DateTimeImmutable {public string $tag='b';public function __clone(){$this->tag.='clone';}}
$a=new ImmutableChild('2024-01-01');$copy=$a->modify('+1 day');row('immutable-user-clone-before-native-copy',[get_class($copy),$a->format('Y-m-d'),$copy->format('Y-m-d'),$copy->tag]);
$a=new DateTimeImmutable('2024-01-01');$a->date='shadow';row('native-view-versus-own',get_object_vars($a));row('native-view-cast',(array)$a);row('native-view-serialize',$a->__serialize());row('native-view-json',json_encode($a));
$zone=new DateTimeZone('Europe/Berlin');$z=unserialize(serialize($zone));row('timezone-wire',[$z->getName(),$z!==$zone,$z->__serialize()]);

class CloneReadDate extends DateTime {public array $events=[];public function __clone(){try{$this->format('Y');}catch(Throwable $error){$this->events[]=[get_class($error),$error->getMessage()];}}}
$read=new CloneReadDate('2024-06-07');$copy=clone $read;row('clone-native-state-after-callback',[$copy->events,$copy->format('Y-m-d')]);
class CloneThrowDate extends DateTime {public function __clone(){$GLOBALS['leaked_date_clone']=$this;throw new RuntimeException('clone-stopped');}}
$source=new CloneThrowDate('2024-06-07');try{$copy=clone $source;}catch(Throwable $error){row('clone-throw',[get_class($error),$error->getMessage()]);}
try{row('clone-throw-leaked-state',$GLOBALS['leaked_date_clone']->format('Y-m-d'));}catch(Throwable $error){row('clone-throw-leaked-state',[get_class($error),$error->getMessage()]);}
