package com.projectkorra.projectkorra.ability.activation;

import com.projectkorra.projectkorra.util.ClickType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as an ability activation entry point. This is intentionally
 * reflection-friendly so new abilities can be activated by declaring the input
 * they listen for instead of editing PKListener.
 * <p>
 * Supported method signatures:
 * boolean method(ActivationContext context)
 * void method(ActivationContext context)
 * boolean method(Player player)
 * void method(Player player)
 * boolean method()
 * void method()
 * <p>
 * Static and instance methods are both supported. For instance methods, the
 * descriptor instance registered by CoreAbility is used.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ActivationMethod {
    ClickType[] value();

    String[] aliases() default {};
}
