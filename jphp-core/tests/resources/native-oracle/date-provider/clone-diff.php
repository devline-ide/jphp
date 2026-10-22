<?php
date_default_timezone_set('UTC');
function row($id,$value){echo json_encode([$id,$value],JSON_UNESCAPED_SLASHES),"\n";}
class EmptySource extends DateTime {public array $events=[];public function __clone(){$this->events[]='cloned';}}
$source=(new ReflectionClass(EmptySource::class))->newInstanceWithoutConstructor();
try{$copy=clone $source;row('uninitialized-clone-object',[get_class($copy),$copy->events]);try{row('uninitialized-clone-value',$copy->format('Y-m-d'));}catch(Throwable $error){row('uninitialized-clone-value',[get_class($error),$error->getMessage()]);}}catch(Throwable $error){row('uninitialized-clone-object',[get_class($error),$error->getMessage()]);}
class MutatingSource extends DateTime {public function __clone(){$GLOBALS['source_date']->modify('+1 day');}}
$source=new MutatingSource('2024-01-01');$GLOBALS['source_date']=$source;$copy=clone $source;row('copy-after-source-mutation',[$source->format('Y-m-d'),$copy->format('Y-m-d')]);
foreach([
 ['month-end','2024-01-31 00:00:00 UTC','2024-03-01 00:00:00 UTC'],
 ['reverse-month-end','2024-03-01 00:00:00 UTC','2024-01-31 00:00:00 UTC'],
 ['mixed-zones','2024-03-30 12:00:00 UTC','2024-04-01 12:00:00 Europe/Berlin'],
 ['dst-civil','2024-03-30 12:00:00 Europe/Berlin','2024-04-01 11:00:00 Europe/Berlin'],
 ['negative-micros','2024-01-01 00:00:00.900000 UTC','2024-01-01 00:00:00.100000 UTC']
]as[$id,$a,$b]){$left=new DateTimeImmutable($a);$right=new DateTimeImmutable($b);$diff=$left->diff($right);row($id,[$diff->format('%R%Y %M %D %H %I %S %F %a'),$diff->invert,$diff->days,$diff->f]);}
