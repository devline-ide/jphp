<?php
echo "inner-enter\n";
class DepReviewCollisionConsumer {}
echo 'inner-after=', json_encode(class_exists('DepReviewCollisionConsumer', false)), "\n";
