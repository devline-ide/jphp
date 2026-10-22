<?php
class LegacyZeroArgReflection extends ReflectionClass {public function getConstants(){return ['legacy-value'=>1];}public function getReflectionConstants(){return ['legacy-ref'=>2];}}
$r=new LegacyZeroArgReflection(ProfileChild::class);echo $r->getConstants()['legacy-value'],':',$r->getReflectionConstants()['legacy-ref'],"\n";
