<?php
declare(strict_types=1);
class H { public $x { get { yield $this; yield $this; } } } $h=new H; $w=WeakReference::create($h); $g=$h->x; unset($h); echo ($w->get()!==null?"retained":"lost"),"\n"; $g->rewind(); echo ($g->current()===$w->get()?"same":"wrong"),"\n"; $g->next(); echo ($g->current()===$w->get()?"same":"wrong"),"\n";
