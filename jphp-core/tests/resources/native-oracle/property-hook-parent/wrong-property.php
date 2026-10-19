<?php
class P{public int $x=2{get=>$this->x;} public int $y=3{get=>$this->y;}}class C extends P{public int $x{get=>parent::$y::get();}}echo(new C)->x;
