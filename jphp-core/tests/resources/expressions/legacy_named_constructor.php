<?php
class LegacyNamedConstructor {public $p=4;public function LegacyNamedConstructor(&$p){$this->p=$p;$p=9;}}
class LegacyNamedConstructorChild extends LegacyNamedConstructor {}
$x=3;$y=5;$a=new LegacyNamedConstructor($x);$b=new LegacyNamedConstructorChild($y);
return [[$a->p,$x],[$b->p,$y]];
