<?php
$GLOBALS['events'][]='enter-a';if(Fiber::getCurrent())Fiber::suspend('park');$GLOBALS['events'][]='exit-a';return 7;