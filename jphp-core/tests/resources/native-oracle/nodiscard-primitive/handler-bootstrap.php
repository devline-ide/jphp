<?php
set_error_handler(function($type,$message){
    $GLOBALS['held'] =& $GLOBALS['r'];
    echo "handler\n";
    return true;
});
