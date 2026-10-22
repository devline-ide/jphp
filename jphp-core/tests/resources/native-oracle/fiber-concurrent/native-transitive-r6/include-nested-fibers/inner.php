<?php
$GLOBALS['events'][]='inner-start';Fiber::suspend('inner-park');$GLOBALS['events'][]='inner-end';return 6;