<?php
require __DIR__."/helper.php";
#[Attribute(1<<20)] class InvalidMarker {}
#[InvalidMarker] function sample() {}
emit('body','entered');attempt('instance',fn()=>get_class((new ReflectionFunction('sample'))->getAttributes()[0]->newInstance()));
