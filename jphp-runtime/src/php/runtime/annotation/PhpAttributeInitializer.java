package php.runtime.annotation;
import java.lang.annotation.*;
/** Physical declaration-site certificate read without loading the generated class. */
@Retention(RetentionPolicy.CLASS) @Target(ElementType.METHOD)
public @interface PhpAttributeInitializer {String site();}
