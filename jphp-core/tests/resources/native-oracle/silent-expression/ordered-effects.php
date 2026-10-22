<?php
error_reporting(E_ALL);$events=[];
function mark($name,$value=1){$GLOBALS['events'][]=[$name,error_reporting()];return$value;}
class OrderedThing {public $name;function __construct($name){$this->name=$name;mark($name);}}
$call=mark('call-left-1',1)+@mark('call-right-1',2)+mark('call-left-2',3)+@mark('call-right-2',4);mark('after-calls');
$object=(new OrderedThing('new-left'))==@(new OrderedThing('new-right'));mark('after-new');
$array=[mark('array-left',5)]+@([mark('array-right',6)]);mark('after-array');
set_error_handler(function($type,$message){mark('capture-warning-'.$type);return true;});
$closure=(function()use($undefinedCapture){})===@(mark('closure-right',null));restore_error_handler();mark('after-closure');
$false=false&&(mark('skipped-false-left')+@mark('skipped-false-right'));
$true=true||(mark('skipped-true-left')+@mark('skipped-true-right'));
$and=true&&@(function(){return mark('active-and');})();
$or=false||@(function(){return mark('active-or');})();mark('after-short-circuit');
$nullsafe=mark('nullsafe-receiver',null)?->missing(mark('skipped-nullsafe-argument'))+@mark('nullsafe-rhs',9);mark('after-nullsafe');
echo json_encode([$call,$object,$array,$closure,$false,$true,$and,$or,$nullsafe,$events,error_reporting()]);
