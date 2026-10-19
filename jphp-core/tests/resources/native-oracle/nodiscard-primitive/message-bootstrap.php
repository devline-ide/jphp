<?php
class RetainingMessage {
    function __construct(){
        $GLOBALS['held'] =& $GLOBALS['r'];
        echo "message\n";
    }
    function __toString(){return "retained";}
}
set_error_handler(function($type,$message){echo "handler\n";return true;});
