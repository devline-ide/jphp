<?php
date_default_timezone_set('UTC');
function dtrow($id,$value){echo json_encode([$id,$value],JSON_UNESCAPED_SLASHES|JSON_UNESCAPED_UNICODE),"\n";}
function dtprobe($id,$f){try{dtrow($id,$f());}catch(Throwable $e){dtrow($id,[get_class($e),$e->getMessage()]);}}
dtrow('internal-surface',[(new ReflectionClass(DateTime::class))->isInternal(),class_implements(DateTime::class),(new ReflectionClass(DateTimeImmutable::class))->isCloneable()]);
$offset=new DateTimeImmutable('2024-02-29 12:34:56.123456+05:30');
dtrow('micro-offset',[$offset->format('Y-m-d H:i:s.u P e U'),$offset->__serialize()]);
dtrow('abbreviation-state',(new DateTimeImmutable('2024-02-29 12:00:00 CET'))->__serialize());
dtrow('region-state',(new DateTimeImmutable('2024-02-29 12:00:00',new DateTimeZone('Europe/Berlin')))->__serialize());
$overflow=new DateTimeImmutable('2024-02-30');dtrow('date-overflow-warning',[$overflow->format('Y-m-d'),DateTimeImmutable::getLastErrors()]);
dtprobe('time-out-of-range',fn()=>new DateTimeImmutable('2024-01-01 25:00:00'));
dtrow('month-overflow',(new DateTimeImmutable('2024-01-31'))->add(new DateInterval('P1M'))->format('Y-m-d'));
$dst=new DateTimeImmutable('2024-03-30 12:00:00',new DateTimeZone('Europe/Berlin'));
dtrow('calendar-day-vs-elapsed-24h',[$dst->add(new DateInterval('P1D'))->format('Y-m-d H:i:s P'),$dst->add(new DateInterval('PT24H'))->format('Y-m-d H:i:s P')]);
dtrow('overlap-choice',(new DateTimeImmutable('2024-10-27 02:30:00',new DateTimeZone('Europe/Berlin')))->format('Y-m-d H:i:s P T'));
$mutable=new DateTime('2024-01-01');$same=$mutable->setDate(2024,2,30)->setTime(25,0);dtrow('mutable-normalization',[$same===$mutable,$mutable->format('Y-m-d H:i:s')]);
$copy=clone $mutable;$copy->modify('+1 day');dtrow('clone-independent',[$copy!==$mutable,$mutable->format('Y-m-d'),$copy->format('Y-m-d')]);
$immutable=new DateTimeImmutable('2024-01-01');$changed=$immutable->modify('+1 day');dtrow('immutable-new',[$immutable!==$changed,$immutable->format('Y-m-d'),$changed->format('Y-m-d')]);
dtrow('epoch-ignores-zone',(new DateTimeImmutable('@-0.500000',new DateTimeZone('Asia/Almaty')))->__serialize());
$d=new DateTimeImmutable('2024-02-29 12:34:56.123456+05:30');$wire=serialize($d);$restored=unserialize($wire);
dtrow('observability-and-wire',[get_object_vars($d),(array)$d,json_encode($d),$wire,get_class($restored),$restored->format('Y-m-d H:i:s.u P e')]);
$format=DateTimeImmutable::createFromFormat('!Y-m-d H:i:s.u','2024-02-30 12:34:56.000001');dtrow('format-overflow',[$format->format('Y-m-d H:i:s.u'),DateTimeImmutable::getLastErrors()]);
dtprobe('bad-format-nul',fn()=>DateTimeImmutable::createFromFormat('Y-m-d',"2024-01-01\0"));
$fixed=new DateTimeZone('+05:30');$region=new DateTimeZone('Europe/Berlin');dtrow('timezone-types',[$fixed->getName(),$fixed->getTransitions(0,1),$region->getTransitions(0,1),$region->getOffset(new DateTimeImmutable('2024-07-01 UTC'))]);
dtprobe('bad-zone',fn()=>new DateTimeZone('Not/A_Zone'));
$di=(new DateTimeImmutable('2024-03-30 12:00:00',new DateTimeZone('Europe/Berlin')))->diff(new DateTimeImmutable('2024-03-31 12:00:00',new DateTimeZone('Europe/Berlin')));dtrow('diff-fields',[$di->format('%a %d %h %i %s'),$di->invert,$di->days]);
