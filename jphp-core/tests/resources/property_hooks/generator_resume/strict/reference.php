<?php
declare(strict_types=1);
class H { public $x=2 { &get { yield $this->x; echo "back:",$this->x,"\n"; } } } $h=new H; foreach($h->x as &$v) { echo "old:$v\n"; $v=9; } unset($v);
