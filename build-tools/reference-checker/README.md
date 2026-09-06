# Wrapper reference equality check

Gradle enables the `WrapperReferenceEquality` javac plugin for Java compilation
in common, Bukkit, and Fabric, including tests and future source sets. It is a
build dependency only and is not shipped in the plugin or mod.

Compilation fails on `==` or `!=` when either operand is a common `Entity`,
`Block`, or `OfflinePlayer`, including player/entity subclasses and generic
bounds. Explicit casts to `Object` do not hide the comparison. Diagnostics point
to the expression in the source file.

Use `a.equals(b)` when `a` is non-null, or `Objects.equals(a, b)` when either value
can be null. To distinguish player login sessions, compare native `handle()`
references. Null checks, primitive comparisons, enums, and unrelated/native
object identity comparisons remain valid.

For an intentional identity fast path that subsequently checks value equality,
put `@SuppressWarnings("WrapperReferenceEquality")` on the method and explain
why it is safe. Suppressions also work on individual variable declarations;
class-wide suppressions and `@SuppressWarnings("all")` do not disable this rule.

The check uses compile-time types. It cannot identify a wrapper after it has
been stored in an `Object` variable or another unrelated interface on both
sides. Keep wrappers typed as their common API types.

IDE builds must delegate compilation to Gradle to use the same compiler checks.
Run the checker tests with `./gradlew :reference-checker:test`.
