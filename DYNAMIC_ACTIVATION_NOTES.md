# Dynamic ability activation changes

This project now routes normal ability inputs through `AbilityActivationManager` instead of hard-coding ability construction in `PKListener`.

## Main changes

- `PKListener` now records inputs, keeps the existing movement/combo/safety checks, and dispatches to `AbilityActivationManager` for:
  - `ClickType.RIGHT_CLICK` / `RIGHT_CLICK_BLOCK` / `RIGHT_CLICK_ENTITY`
  - `ClickType.SHIFT_DOWN`
  - `ClickType.LEFT_CLICK`
- Existing ProjectKorra core behavior was moved into `CoreAbilityActivationBootstrap` so the old behavior is preserved outside the listener.
- New abilities no longer need to be added to `PKListener`. They can either:
  1. implement `DynamicActivationAbility`, or
  2. add an `@ActivationMethod` method.

## Example: interface-based activation

```java
public final class ExampleAbility extends AirAbility implements DynamicActivationAbility {
    public ExampleAbility() {}

    public ExampleAbility(Player player) {
        super(player);
        start();
    }

    @Override
    public Collection<ClickType> getActivationTypes() {
        return List.of(ClickType.LEFT_CLICK, ClickType.SHIFT_DOWN);
    }

    @Override
    public boolean activate(ActivationContext context) {
        new ExampleAbility(context.getPlayer());
        return true;
    }
}
```

## Example: annotation-based activation

```java
@ActivationMethod(ClickType.LEFT_CLICK)
public static boolean activateLeftClick(ActivationContext context) {
    new ExampleAbility(context.getPlayer());
    return true;
}
```

`AbilityActivationManager.reload()` is called by the `PKListener` constructor, which re-registers built-in handlers and discovers activation declarations from registered `CoreAbility` descriptors.
