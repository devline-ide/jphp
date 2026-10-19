<?php
require __DIR__."/helper.php";
#[Attribute(0)] class ZeroMarker {}
#[ZeroMarker] function sample() {}
$a=(new ReflectionFunction('sample'))->getAttributes()[0];emit('metadata',[$a->getName(),$a->getTarget()]);attempt('instance',fn()=>get_class($a->newInstance()));
