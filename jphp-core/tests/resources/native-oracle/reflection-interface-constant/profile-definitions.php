<?php
interface ProfileRootI {} interface ProfileSubI extends ProfileRootI {}
class ProfileBase implements ProfileSubI {public const A=1;} class ProfileChild extends ProfileBase {public const B=2;} class ProfileNoParent {}
