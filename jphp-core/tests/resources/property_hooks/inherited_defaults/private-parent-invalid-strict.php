<?php
declare(strict_types=1);
class BaseDefault {private int $x=1;} class ChildDefault extends BaseDefault {public int $x=3 {get=>42;}}
