<?php
class H { public $x=4 { get { yield $this->x; $this->x+=3; yield $this->x; } set { $this->x=$value; } } } $h=new H; foreach($h->x as $v) echo "$v\n"; $h->x=20; foreach($h->x as $v) echo "$v\n";
