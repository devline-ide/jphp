<?php
function f(){return 1;} function bad(){return (void)f();}
echo "body-entered\n";
