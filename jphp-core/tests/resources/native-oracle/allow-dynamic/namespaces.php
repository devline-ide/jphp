<?php
namespace ADPUser;
function emit($tag,$value){echo $tag.'='.bin2hex(serialize($value))."\n";}
set_error_handler(function($severity,$message){emit('warning',[$severity,$message]);return true;});
#[\Attribute] class AllowDynamicProperties {}
#[AllowDynamicProperties] class Fake {}
use \AllowDynamicProperties as Dynamic;
#[Dynamic] class Real {}
$fake=new Fake;$fake->x=1;emit('fake',$fake->x);$real=new Real;$real->x=2;emit('real',$real->x);
