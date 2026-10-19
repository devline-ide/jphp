<?php
require __DIR__.'/helper.php';
#[DelayedTargetValidation,NoDiscard] class InvalidTarget{}
attempt('newInstance',fn()=>(new ReflectionClass('InvalidTarget'))->getAttributes('NoDiscard')[0]->newInstance());
