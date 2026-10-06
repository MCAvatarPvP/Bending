package com.projectkorra.projectkorra.ability.activation;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.ability.*;
import com.projectkorra.projectkorra.ability.util.MultiAbilityManager;
import com.projectkorra.projectkorra.platform.mc.GameMode;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.util.ClickType;
import com.projectkorra.projectkorra.prediction.rollback.RollbackClock;
import com.projectkorra.projectkorra.prediction.rollback.RollbackLiveOwnership;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class AbilityActivationManager {
    private static Map<String, EnumMap<ClickType, List<ActivationHandler>>> HANDLERS = new ConcurrentHashMap<>();
    private static Map<String, EnumMap<ClickType, List<ActivationHandler>>> MULTI_HANDLERS = new ConcurrentHashMap<>();
    private static EnumMap<ClickType, List<ActivationHandler>> GLOBAL_HANDLERS = new EnumMap<>(ClickType.class);
    private static Map<Class<?>, Boolean> DISCOVERED = new ConcurrentHashMap<>();
    private static final ThreadLocal<ArrayDeque<TrackingFrame>> HANDLED_TRACKING =
            ThreadLocal.withInitial(ArrayDeque::new);

    private static ArrayDeque<TrackingFrame> PRIVATE_TRACKING;

    /** Captured with the common graph, including handler receivers and their shared aliases. */
    public static final class RollbackRegistry {
        private final Map<String, EnumMap<ClickType, List<ActivationHandler>>> handlers = HANDLERS;
        private final Map<String, EnumMap<ClickType, List<ActivationHandler>>> multi = MULTI_HANDLERS;
        private final EnumMap<ClickType, List<ActivationHandler>> global = GLOBAL_HANDLERS;
        private final Map<Class<?>, Boolean> discovered = DISCOVERED;
        private final ArrayDeque<TrackingFrame> tracking = new ArrayDeque<>();
        private RollbackRegistry() {
            if (!trackingStack().isEmpty()) throw new IllegalStateException("Activation capture requires an input boundary");
        }
        public void validate() {
            if (!tracking.isEmpty()) throw new IllegalStateException("Imported activation tracking is not at an input boundary");
            for (var group : handlers.values()) validateHandlers(group);
            for (var group : multi.values()) validateHandlers(group);
            validateHandlers(global);
            for (var type : discovered.keySet()) Objects.requireNonNull(type, "discovered activation type");
        }
        private static void validateHandlers(Map<ClickType, List<ActivationHandler>> groups) {
            groups.forEach((click, entries) -> {
                Objects.requireNonNull(click, "activation click");
                for (var handler : entries) {
                    Objects.requireNonNull(handler, "activation handler");
                    if (handler instanceof com.projectkorra.projectkorra.prediction.rollback.RollbackCallback portable) portable.validateActivation();
                    if (handler instanceof AnnotatedHandler annotated) annotated.resolve();
                }
            });
        }
        public void install() {
            if (!com.projectkorra.projectkorra.prediction.rollback.RollbackDomain.active())
                throw new IllegalStateException("Activation import requires a private domain");
            validate();
            HANDLERS = handlers; MULTI_HANDLERS = multi; GLOBAL_HANDLERS = global; DISCOVERED = discovered;
            PRIVATE_TRACKING = tracking;
        }
    }

    public static RollbackRegistry captureRollbackRegistry() { return new RollbackRegistry(); }
    public static List<java.lang.reflect.Field> rollbackFields() {
        return com.projectkorra.projectkorra.prediction.rollback.RollbackStateGraph.staticFields(AbilityActivationManager.class,
                field -> Set.of("HANDLERS", "MULTI_HANDLERS", "GLOBAL_HANDLERS", "DISCOVERED", "PRIVATE_TRACKING").contains(field.getName()));
    }
    private static ArrayDeque<TrackingFrame> trackingStack() {
        return com.projectkorra.projectkorra.prediction.rollback.RollbackDomain.active() && PRIVATE_TRACKING != null
                ? PRIVATE_TRACKING : HANDLED_TRACKING.get();
    }

    private AbilityActivationManager() {
    }

    public static void beginTracking() {
        trackingStack().push(new TrackingFrame());
    }

    public static boolean finishTracking() {
        return finishTrackingResult().handled();
    }

    public static TrackingResult finishTrackingResult() {
        final ArrayDeque<TrackingFrame> stack = trackingStack();
        final TrackingFrame frame = stack.isEmpty() ? null : stack.pop();
        if (stack.isEmpty() && stack != PRIVATE_TRACKING) HANDLED_TRACKING.remove();
        return frame == null
                ? new TrackingResult(false, List.of())
                : new TrackingResult(frame.handled, List.copyOf(frame.affectedAbilities));
    }

    public static void markHandled() {
        markHandled(null);
    }

    public static void markHandled(final CoreAbility affectedAbility) {
        final ArrayDeque<TrackingFrame> stack = trackingStack();
        if (!stack.isEmpty()) {
            final TrackingFrame frame = stack.peek();
            frame.handled = true;
            if (affectedAbility != null) frame.affectedAbilities.add(affectedAbility);
        }
    }

    public record TrackingResult(boolean handled, List<CoreAbility> affectedAbilities) {
    }

    private static final class TrackingFrame {
        private boolean handled;
        private final Set<CoreAbility> affectedAbilities =
                Collections.newSetFromMap(new IdentityHashMap<>());
    }

    public static void reload() {
        HANDLERS.clear();
        MULTI_HANDLERS.clear();
        GLOBAL_HANDLERS.clear();
        DISCOVERED.clear();
        CoreAbilityActivationBootstrap.registerDefaults();
        AddonAbilityActivationBootstrap.registerDefaults();
        discoverRegisteredAbilities();
    }

    public static void discoverRegisteredAbilities() {
        for (final CoreAbility ability : CoreAbility.getAbilities()) {
            discover(ability);
        }
    }

    public static void discover(final CoreAbility ability) {
        if (ability == null || DISCOVERED.putIfAbsent(ability.getClass(), Boolean.TRUE) != null) {
            return;
        }

        if (ability instanceof DynamicActivationAbility dynamic) {
            final Collection<ClickType> types = dynamic.getActivationTypes();
            if (types != null) {
                for (final ClickType type : types) {
                    register(ability.getName(), type, dynamic::activate);
                }
            }
        }

        for (final Method method : ability.getClass().getDeclaredMethods()) {
            final ActivationMethod activation = method.getAnnotation(ActivationMethod.class);
            if (activation == null) {
                continue;
            }
            method.setAccessible(true);
            final ActivationHandler handler = new AnnotatedHandler(ability, method);
            register(ability.getName(), handler, activation.value());
            for (final String alias : activation.aliases()) {
                register(alias, handler, activation.value());
            }
        }
    }

    public static void register(final String abilityName, final ActivationHandler handler, final ClickType... clickTypes) {
        if (clickTypes == null) {
            return;
        }
        for (final ClickType clickType : clickTypes) {
            register(abilityName, clickType, handler);
        }
    }

    public static void register(final String abilityName, final ClickType clickType, final ActivationHandler handler) {
        if (abilityName == null || clickType == null || handler == null) {
            return;
        }
        HANDLERS.computeIfAbsent(normalize(abilityName), ignored -> new EnumMap<>(ClickType.class))
                .computeIfAbsent(clickType, ignored -> new ArrayList<>())
                .add(handler);
    }

    public static void registerGlobal(final ClickType clickType, final ActivationHandler handler) {
        if (clickType == null || handler == null) {
            return;
        }
        GLOBAL_HANDLERS.computeIfAbsent(clickType, ignored -> new ArrayList<>()).add(handler);
    }

    public static void registerMulti(final String abilityName, final ClickType clickType, final ActivationHandler handler) {
        if (abilityName == null || clickType == null || handler == null) {
            return;
        }
        MULTI_HANDLERS.computeIfAbsent(normalize(abilityName), ignored -> new EnumMap<>(ClickType.class))
                .computeIfAbsent(clickType, ignored -> new ArrayList<>())
                .add(handler);
    }

    public static ActivationContext newContext(final Player player, final ClickType clickType) {
        return new ActivationContext(player, clickType);
    }

    public static boolean dispatch(final Player player, final ClickType clickType) {
        return dispatch(new ActivationContext(player, clickType));
    }

    public static boolean dispatch(final ActivationContext context) {
        if (context.getPlayer() == null || context.getPlayer().getGameMode() == GameMode.SPECTATOR) {
            return false;
        }
        boolean handled = dispatchGlobal(context);
        if (context.shouldStopProcessing()) {
            return handled;
        }

        return handled | dispatchBoundAbility(context);
    }

    public static boolean dispatchGlobal(final ActivationContext context) {
        return runGlobalHandlers(context);
    }

    public static boolean dispatchBoundAbility(final ActivationContext context) {
        if (!canActivateBoundAbility(context)) {
            return false;
        }
        return runHandlers(context);
    }

    public static boolean dispatchMultiAbility(final ActivationContext context) {
        final Player player = context.getPlayer();
        if (player == null || !MultiAbilityManager.hasMultiAbilityBound(player)) {
            return false;
        }
        final String abilityName = MultiAbilityManager.getBoundMultiAbility(player);
        if (abilityName == null) {
            return false;
        }
        return runHandlers(MULTI_HANDLERS, context.withAbilityName(abilityName));
    }

    private static boolean runGlobalHandlers(final ActivationContext context) {
        final List<ActivationHandler> handlers = GLOBAL_HANDLERS.get(context.getClickType());
        if (handlers == null || handlers.isEmpty()) {
            return false;
        }
        return runHandlers(handlers, context);
    }

    private static boolean runHandlers(final ActivationContext context) {
        final String abilityName = context.getAbilityName();
        if (abilityName == null) {
            return false;
        }
        return runHandlers(HANDLERS, context);
    }

    private static boolean runHandlers(final Map<String, EnumMap<ClickType, List<ActivationHandler>>> handlerMap, final ActivationContext context) {
        final String abilityName = context.getAbilityName();
        if (abilityName == null) {
            return false;
        }
        final EnumMap<ClickType, List<ActivationHandler>> handlersByClick = handlerMap.get(normalize(abilityName));
        if (handlersByClick == null) {
            return false;
        }
        final List<ActivationHandler> handlers = handlersByClick.get(context.getClickType());
        if (handlers == null || handlers.isEmpty()) {
            return false;
        }
        return runHandlers(handlers, context);
    }

    private static boolean runHandlers(final List<ActivationHandler> handlers, final ActivationContext context) {
        boolean handled = false;
        for (final ActivationHandler handler : handlers) {
            if (context.getPlayer() != null && RollbackLiveOwnership.blocks(context.getPlayer().getUniqueId())) break;
            try {
                final boolean activated = handler.activate(context);
                handled |= activated;
                if (activated) markHandled();
            } catch (final Throwable throwable) {
                // A handler can have already mutated the provisional world. Continuing
                // would finalize a partial action instead of aborting the failed step.
                if (RollbackClock.active() || com.projectkorra.projectkorra.prediction.rollback.RollbackDomain.active()) {
                    if (throwable instanceof Error error) throw error;
                    throw new IllegalStateException("Failed rollback activation: " + context.getAbilityName()
                            + " / " + context.getClickType(), throwable);
                }
                ProjectKorra.log.warning("Failed to activate " + context.getAbilityName() + " for " + context.getClickType() + ": " + throwable.getMessage());
                throwable.printStackTrace();
            }
            if (context.shouldStopProcessing()) {
                break;
            }
        }
        return handled;
    }

    private static boolean canActivateBoundAbility(final ActivationContext context) {
        final Player player = context.getPlayer();
        final BendingPlayer bPlayer = context.getBendingPlayer();
        final CoreAbility ability = context.getBoundAbility();

        if (player == null || bPlayer == null || ability == null || context.getAbilityName() == null) {
            return false;
        }
        if (!bPlayer.canBendIgnoreCooldowns(ability)) {
            return false;
        }
        if (!isElementToggled(bPlayer, ability)) {
            return false;
        }
        return !requiresWeaponCheck(ability) || bPlayer.canCurrentlyBendWithWeapons();
    }

    private static boolean requiresWeaponCheck(final CoreAbility ability) {
        return !(ability instanceof AvatarAbility);
    }

    private static boolean isElementToggled(final BendingPlayer bPlayer, final CoreAbility ability) {
        if (ability instanceof AirAbility) {
            return bPlayer.isElementToggled(Element.AIR);
        }
        if (ability instanceof WaterAbility) {
            return bPlayer.isElementToggled(Element.WATER);
        }
        if (ability instanceof EarthAbility) {
            return bPlayer.isElementToggled(Element.EARTH);
        }
        if (ability instanceof FireAbility) {
            return bPlayer.isElementToggled(Element.FIRE);
        }
        if (ability instanceof ChiAbility) {
            return bPlayer.isElementToggled(Element.CHI);
        }
        return true;
    }

    /** Method identity is portable; reflection objects and live receiver references are not. */
    public static final class AnnotatedHandler implements ActivationHandler {
        private final CoreAbility ability;
        private final String methodName;
        private final Class<?>[] parameterTypes;

        private AnnotatedHandler(CoreAbility ability, Method method) {
            this.ability = ability;
            this.methodName = method.getName();
            this.parameterTypes = method.getParameterTypes();
        }

        private Method resolve() {
            try {
                Method method = ability.getClass().getDeclaredMethod(methodName, parameterTypes);
                if (!method.isAnnotationPresent(ActivationMethod.class))
                    throw new IllegalArgumentException("Activation descriptor requires @ActivationMethod");
                method.setAccessible(true);
                return method;
            } catch (ReflectiveOperationException exception) {
                throw new IllegalArgumentException("Missing annotated activation method", exception);
            }
        }

        @Override public boolean activate(ActivationContext context) {
            return invokeAnnotatedActivation(ability, resolve(), context);
        }
    }

    private static boolean invokeAnnotatedActivation(final CoreAbility ability, final Method method, final ActivationContext context) {
        try {
            final Object target = Modifier.isStatic(method.getModifiers()) ? null : ability;
            final Object result;
            if (method.getParameterCount() == 0) {
                result = method.invoke(target);
            } else if (method.getParameterCount() == 1) {
                final Class<?> parameterType = method.getParameterTypes()[0];
                if (ActivationContext.class.isAssignableFrom(parameterType)) {
                    result = method.invoke(target, context);
                } else if (Player.class.isAssignableFrom(parameterType)) {
                    result = method.invoke(target, context.getPlayer());
                } else {
                    throw new IllegalArgumentException("Unsupported @ActivationMethod parameter: " + parameterType.getName());
                }
            } else {
                throw new IllegalArgumentException("@ActivationMethod supports at most one parameter");
            }
            return !(result instanceof Boolean) || (Boolean) result;
        } catch (final ReflectiveOperationException exception) {
            throw new RuntimeException(exception);
        }
    }

    private static String normalize(final String abilityName) {
        return abilityName.toLowerCase(Locale.ROOT);
    }
}
