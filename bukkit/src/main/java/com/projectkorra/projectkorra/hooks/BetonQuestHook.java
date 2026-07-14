package com.projectkorra.projectkorra.hooks;

import com.projectkorra.projectkorra.event.AbilityDamageEntityEvent;
import com.projectkorra.projectkorra.event.AbilityProgressEvent;
import com.projectkorra.projectkorra.event.ComboHelpStartEvent;
import com.projectkorra.projectkorra.event.ComboHelpStopEvent;
import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.event.EventHandler;
import org.betonquest.betonquest.api.BetonQuestApiService;
import org.betonquest.betonquest.api.QuestException;
import org.betonquest.betonquest.api.instruction.Instruction;
import org.betonquest.betonquest.api.profile.OnlineProfile;
import org.betonquest.betonquest.api.quest.objective.Objective;
import org.betonquest.betonquest.api.quest.objective.ObjectiveFactory;
import org.betonquest.betonquest.api.quest.objective.service.ObjectiveService;
import org.betonquest.betonquest.api.service.objective.ObjectiveRegistry;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
import java.util.Optional;

/**
 * BetonQuest objectives backed by ProjectKorra's loader-neutral event bus.
 */
public final class BetonQuestHook {
    private BetonQuestHook() {
    }

    public static void register(JavaPlugin plugin) {
        Plugin betonQuest = Bukkit.getPluginManager().getPlugin("BetonQuest");
        if (betonQuest == null || !betonQuest.isEnabled()) return;
        Optional<BetonQuestApiService> service = BetonQuestApiService.get();
        if (service.isEmpty()) {
            plugin.getLogger().warning("BetonQuest is enabled, but its API service is unavailable.");
            return;
        }

        ObjectiveRegistry registry = service.get().api(plugin).objectives().registry();
        register(registry, "projectkorraabilitydamage", Kind.DAMAGE);
        register(registry, "abilitydamageentityevent", Kind.DAMAGE);
        register(registry, "projectkorraabilitylocation", Kind.PROGRESS);
        register(registry, "abilitylocation", Kind.PROGRESS);
        register(registry, "projectkorracombohelpstart", Kind.COMBO_START);
        register(registry, "combohelpstartevent", Kind.COMBO_START);
        register(registry, "projectkorracombohelpstop", Kind.COMBO_STOP);
        register(registry, "combohelpstopevent", Kind.COMBO_STOP);
        Platform.events().registerListener(new BridgeListener());
        plugin.getLogger().info("Registered BetonQuest objectives for ProjectKorra events.");
    }

    private static void register(ObjectiveRegistry registry, String name, Kind kind) {
        registry.register(name, (ObjectiveFactory) (instruction, service) -> objective(instruction, service, kind));
    }

    private static Objective objective(Instruction instruction, ObjectiveService service, Kind kind) throws QuestException {
        Filters filters = Filters.parse(instruction);
        return new Objective() {
            {
                service.request(BridgeEvent.class).onlineHandler(this::handle).player(BridgeEvent::player).subscribe(true);
            }

            @Override
            public ObjectiveService getService() {
                return service;
            }

            private void handle(BridgeEvent event, OnlineProfile profile) {
                if (event.kind == kind && filters.matches(event)) service.complete(profile);
            }
        };
    }

    private static Player nativePlayer(com.projectkorra.projectkorra.platform.mc.entity.Player player) {
        return player != null && player.handle() instanceof Player nativePlayer ? nativePlayer : null;
    }

    private static String nativeEntityType(Entity entity) {
        if (entity == null) return null;
        return entity.handle() instanceof org.bukkit.entity.Entity nativeEntity
                ? nativeEntity.getType().name()
                : null;
    }

    private static Location nativeLocation(com.projectkorra.projectkorra.platform.mc.Location location) {
        if (location == null || location.getWorld() == null) return null;
        World world = Bukkit.getWorld(location.getWorld().getName());
        return world == null ? null : new Location(world, location.getX(), location.getY(), location.getZ());
    }

    private enum Kind {DAMAGE, PROGRESS, COMBO_START, COMBO_STOP}

    public static final class BridgeEvent extends Event {
        private static final HandlerList HANDLERS = new HandlerList();
        private final Kind kind;
        private final Player player;
        private final String ability;
        private final String entity;
        private final String reason;
        private final Location location;

        private BridgeEvent(Kind kind, Player player, String ability, String entity, String reason, Location location) {
            this.kind = kind;
            this.player = player;
            this.ability = ability;
            this.entity = entity;
            this.reason = reason;
            this.location = location;
        }

        public static HandlerList getHandlerList() {
            return HANDLERS;
        }

        public Player player() {
            return player;
        }

        @Override
        public HandlerList getHandlers() {
            return HANDLERS;
        }
    }

    public static final class BridgeListener {
        private static void fire(BridgeEvent event) {
            if (event.player != null) Bukkit.getPluginManager().callEvent(event);
        }

        @EventHandler
        public void damage(AbilityDamageEntityEvent event) {
            Player player = nativePlayer(event.getSource() != null ? event.getSource() : event.getAbility().getPlayer());
            String entity = nativeEntityType(event.getEntity());
            Location location = nativeLocation(event.getEntity() == null ? null : event.getEntity().getLocation());
            fire(new BridgeEvent(Kind.DAMAGE, player, event.getAbility().getName(), entity, null, location));
        }

        @EventHandler
        public void progress(AbilityProgressEvent event) {
            fire(new BridgeEvent(Kind.PROGRESS, nativePlayer(event.getAbility().getPlayer()), event.getAbility().getName(),
                    null, null, nativeLocation(event.getAbility().getLocation())));
        }

        @EventHandler
        public void comboStart(ComboHelpStartEvent event) {
            fire(new BridgeEvent(Kind.COMBO_START, nativePlayer(event.getPlayer()), event.getCombo().getName(),
                    null, null, null));
        }

        @EventHandler
        public void comboStop(ComboHelpStopEvent event) {
            fire(new BridgeEvent(Kind.COMBO_STOP, nativePlayer(event.getPlayer()),
                    event.getCombo() == null ? null : event.getCombo().getName(), null,
                    event.getReason() == null ? null : event.getReason().name(), null));
        }
    }

    private record Filters(String ability, String entity, String reason, String world,
                           Double x, Double y, Double z, double radius) {
        private static Filters parse(Instruction instruction) {
            String ability = null, entity = null, reason = null, world = null;
            Double x = null, y = null, z = null;
            double radius = 2.0;
            for (String part : instruction.getValueParts()) {
                int separator = part.indexOf(':');
                if (separator < 1 || separator == part.length() - 1) {
                    if (!part.isBlank()) ability = normalize(part);
                    continue;
                }
                String key = normalize(part.substring(0, separator));
                String raw = part.substring(separator + 1);
                String value = normalize(raw);
                switch (key) {
                    case "ability", "combo" -> ability = value;
                    case "entity" -> entity = value;
                    case "reason" -> reason = value;
                    case "world" -> world = value;
                    case "x" -> x = number(raw);
                    case "y" -> y = number(raw);
                    case "z" -> z = number(raw);
                    case "radius" -> {
                        Double parsed = number(raw);
                        if (parsed != null && parsed > 0) radius = parsed;
                    }
                }
            }
            return new Filters(ability, entity, reason, world, x, y, z, radius);
        }

        private static String normalize(String value) {
            return value == null ? null : value.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
        }

        private static Double number(String value) {
            try {
                return Double.parseDouble(value);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        private boolean matches(BridgeEvent event) {
            if (ability != null && !ability.equals(normalize(event.ability))) return false;
            if (entity != null && !entity.equals(normalize(event.entity))) return false;
            if (reason != null && !reason.equals(normalize(event.reason))) return false;
            if (world == null && x == null && y == null && z == null) return true;
            if (event.location == null || x == null || y == null || z == null) return false;
            if (world != null && !world.equals(normalize(event.location.getWorld().getName()))) return false;
            double dx = event.location.getX() - x, dy = event.location.getY() - y, dz = event.location.getZ() - z;
            return dx * dx + dy * dy + dz * dz <= radius * radius;
        }
    }
}
