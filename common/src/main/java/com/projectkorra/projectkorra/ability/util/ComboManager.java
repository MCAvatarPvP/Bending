package com.projectkorra.projectkorra.ability.util;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.Element.SubElement;
import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.ability.ComboAbility;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.activation.AbilityActivationManager;
import com.projectkorra.projectkorra.event.ComboHelpCompleteEvent;
import com.projectkorra.projectkorra.event.ComboHelpInputEvent;
import com.projectkorra.projectkorra.event.ComboHelpStartEvent;
import com.projectkorra.projectkorra.event.ComboHelpStopEvent;
import com.projectkorra.projectkorra.event.ComboHelpStopEvent.Reason;
import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.mc.ChatColor;
import com.projectkorra.projectkorra.platform.mc.Sound;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.scheduler.BukkitRunnable;
import com.projectkorra.projectkorra.util.ChatUtil;
import com.projectkorra.projectkorra.util.ClickType;
import com.projectkorra.projectkorra.util.ReflectionHandler;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ComboManager {
    private static final long CLEANUP_DELAY = 20 * 60;
    private static final int MAX_COMBO_HELP_VISIBLE_LENGTH = 72;
    private static final Map<String, ArrayList<AbilityInformation>> RECENTLY_USED = new ConcurrentHashMap<>();
    private static final HashMap<String, ComboAbilityInfo> COMBO_ABILITIES = new HashMap<>();
    private static final HashMap<String, String> AUTHORS = new HashMap<>();
    private static final HashMap<String, String> DESCRIPTIONS = new HashMap<>();
    private static final HashMap<String, String> INSTRUCTIONS = new HashMap<>();
    private static final HashMap<UUID, Set<ClickType>> SCHEDULED_COMBO_ABILITY = new HashMap<>();
    private static final Map<UUID, ComboHelpSession> COMBO_HELP_SESSIONS = new ConcurrentHashMap<>();

    public ComboManager() {
        COMBO_ABILITIES.clear();
        DESCRIPTIONS.clear();
        INSTRUCTIONS.clear();

		/*if (ConfigManager.defaultConfig.get().getBoolean("Abilities.Earth.EarthDome.Enabled")) {
			final ArrayList<AbilityInformation> earthDomeOthers = new ArrayList<>();
			earthDomeOthers.add(new AbilityInformation("RaiseEarth", ClickType.RIGHT_CLICK_BLOCK));
			earthDomeOthers.add(new AbilityInformation("Shockwave", ClickType.LEFT_CLICK));
			COMBO_ABILITIES.put("EarthDomeOthers", new ComboAbilityInfo("EarthDomeOthers", earthDomeOthers, EarthDomeOthers.class));
		}*/

        startCleanupTask();
    }

    public static void scheduleComboAbility(final Player player, final ClickType type) {
        SCHEDULED_COMBO_ABILITY.computeIfAbsent(player.getUniqueId(), uuid -> new HashSet<>()).add(type);
    }

    public static void addComboAbilityIfValid(final Player player, final ClickType type) {
        Set<ClickType> types = SCHEDULED_COMBO_ABILITY.get(player.getUniqueId());
        if (types == null || !types.contains(type)) {
            return;
        }

        addComboAbility(player, type);
        types.remove(type);
    }

    public static void addComboAbility(final Player player, final ClickType type) {
        final BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(player);
        if (bPlayer == null) {
            return;
        }

        final String abilityName = bPlayer.getBoundAbilityName();
        if (abilityName == null) {
            return;
        }

        final AbilityInformation info = new AbilityInformation(abilityName, type, System.currentTimeMillis());
        addRecentAbility(player, info);
        handleComboHelpInput(player, info);

        final ComboAbilityInfo comboAbil = checkForValidCombo(player);
        if (comboAbil == null) {
            return;
        } else if (!player.hasPermission("bending.ability." + comboAbil.getName())) {
            return;
        }

        Object created = null;
        if (comboAbil.getComboType() instanceof Class) {
            final Class<?> clazz = (Class<?>) comboAbil.getComboType();
            try {
                created = ReflectionHandler.instantiateObject(clazz, player);
            } catch (final Exception e) {
                e.printStackTrace();
            }
        } else {
            if (comboAbil.getComboType() instanceof ComboAbility) {
                created = ((ComboAbility) comboAbil.getComboType()).createNewComboInstance(player);
            }
        }

        // A combo is created by the final bound-ability input, so its runtime
        // name normally differs from that input (for example Blaze:SHIFT_UP
        // creates FireWheel). Report the successfully started instance to the
        // activation tracker; prediction reconciliation must not reject it as
        // an unrelated/unhandled input and immediately remove it.
        if (created instanceof CoreAbility ability && ability.isStarted() && !ability.isRemoved()) {
            AbilityActivationManager.markHandled();
        }
    }

    /**
     * Adds an {@link AbilityInformation} to the player's
     * {@link ComboManager#RECENTLY_USED recentlyUsedAbilities}.
     *
     * @param player The player to add the AbilityInformation for
     * @param info   The AbilityInformation to add
     */
    public static void addRecentAbility(final Player player, final AbilityInformation info) {
        ArrayList<AbilityInformation> list;
        final String name = player.getName();
        if (RECENTLY_USED.containsKey(name)) {
            list = RECENTLY_USED.get(name);
        } else {
            list = new ArrayList<AbilityInformation>();
        }

        list.add(info);
        RECENTLY_USED.put(name, list);
    }

    /**
     * Removes a recent combo combination
     *
     * @param player The player to remove
     * @param type   The type of combo to remove
     */
    public static void removeRecentType(final Player player, ClickType type) {
        if (RECENTLY_USED.containsKey(player.getName())) {
            ArrayList<AbilityInformation> list = RECENTLY_USED.get(player.getName());

            if (list.size() > 0) {
                AbilityInformation last = list.get(list.size() - 1);
                if (last.getTime() > System.currentTimeMillis() - 50 && last.getClickType() == type) { //If the ability was within the last tick
                    list.remove(last);
                }
            }
        }
    }

    /**
     * Checks if a Player's {@link ComboManager#RECENTLY_USED
     * recentlyUsedAbilities} contains a valid set of moves to perform any
     * combos. If it does, it returns the valid combo.
     *
     * @param player The player for whom to check if a valid combo has been
     *               performed
     * @return The ComboAbility of the combo that has been performed, or null if
     * no valid combo was found
     */
    public static ComboAbilityInfo checkForValidCombo(final Player player) {
        final ArrayList<AbilityInformation> playerCombo = getRecentlyUsedAbilities(player, 8);
        for (final String ability : COMBO_ABILITIES.keySet()) {
            final ComboAbilityInfo customAbility = COMBO_ABILITIES.get(ability);
            final ArrayList<AbilityInformation> abilityCombo = customAbility.getAbilities();
            final int size = abilityCombo.size();

            if (playerCombo.size() < size) {
                continue;
            }

            boolean isValid = true;
            for (int i = 1; i <= size; i++) {
                final AbilityInformation playerInfo = playerCombo.get(playerCombo.size() - i);
                final AbilityInformation comboInfo = abilityCombo.get(abilityCombo.size() - i);
                if (playerInfo.getAbilityName().equals(comboInfo.getAbilityName()) && playerInfo.getClickType() == ClickType.LEFT_CLICK_ENTITY && comboInfo.getClickType() == ClickType.LEFT_CLICK) {
                    continue;
                } else if (!playerInfo.equalsWithoutTime(comboInfo)) {
                    isValid = false;
                    break;
                }
            }

            if (isValid) {
                return customAbility;
            }
        }

        return null;
    }

    public static void cleanupOldCombos() {
        RECENTLY_USED.clear();
    }

    /**
     * Gets the player's most recently used abilities, up to a maximum of 10.
     *
     * @param player The player to get recent abilities for
     * @param amount The amount of recent abilities to get, starting from most
     *               recent and getting older
     * @return An ArrayList of the player's recently
     * used abilities
     */
    public static ArrayList<AbilityInformation> getRecentlyUsedAbilities(final Player player, final int amount) {
        final String name = player.getName();
        if (!RECENTLY_USED.containsKey(name)) {
            return new ArrayList<AbilityInformation>();
        }

        final ArrayList<AbilityInformation> list = RECENTLY_USED.get(name);
        if (list.size() < amount) {
            return new ArrayList<AbilityInformation>(list);
        }

        final ArrayList<AbilityInformation> tempList = new ArrayList<AbilityInformation>();
        for (int i = 0; i < amount; i++) {
            tempList.add(0, list.get(list.size() - 1 - i));
        }

        return tempList;
    }

    /**
     * Gets all of the combos for a given element.
     *
     * @param element The element to get combos for
     * @return An ArrayList of the combos for that element
     */
    public static ArrayList<String> getCombosForElement(final Element element) {
        final ArrayList<String> list = new ArrayList<String>();
        for (final String comboab : COMBO_ABILITIES.keySet()) {
            final CoreAbility coreAbil = CoreAbility.getAbility(comboab);
            if (coreAbil == null) {
                continue;
            }

            Element abilElement = coreAbil.getElement();
            if (abilElement instanceof SubElement) {
                abilElement = ((SubElement) abilElement).getParentElement();
            }

            if (abilElement == element) {
                list.add(comboab);
            }
        }

        Collections.sort(list);
        return list;
    }

    public static void startCleanupTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                cleanupOldCombos();
            }
        }.runTaskTimer(ProjectKorra.plugin, 0, CLEANUP_DELAY);
    }

    public static long getCleanupDelay() {
        return CLEANUP_DELAY;
    }

    public static HashMap<String, ComboAbilityInfo> getComboAbilities() {
        return COMBO_ABILITIES;
    }

    public static ComboAbilityInfo getComboAbility(final String name) {
        for (final Map.Entry<String, ComboAbilityInfo> entry : COMBO_ABILITIES.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    public static HashMap<String, String> getAuthors() {
        return AUTHORS;
    }

    public static HashMap<String, String> getDescriptions() {
        return DESCRIPTIONS;
    }

    public static HashMap<String, String> getInstructions() {
        return INSTRUCTIONS;
    }

    public static void registerCombos() {
        for (CoreAbility ability : CoreAbility.getAbilities()) {
            if (ability instanceof ComboAbility && ability.isEnabled()) {
                final ComboAbility combo = (ComboAbility) ability;
                try { //Using a try catch here because we can run addon code. And that may crash/stop the loop
                    ArrayList<AbilityInformation> combination = combo.getCombination();
                    if (combination != null) {
                        ComboManager.getComboAbilities().put(ability.getName(), new ComboManager.ComboAbilityInfo(ability.getName(), combination, combo));
                        ComboManager.getDescriptions().put(ability.getName(), ability.getDescription());
                        ComboManager.getInstructions().put(ability.getName(), ability.getInstructions());
                    }
                } catch (Error | Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public static boolean startComboHelp(final Player player, final ComboAbilityInfo combo) {
        final ComboHelpStartEvent event = new ComboHelpStartEvent(player, combo);
        Platform.events().call(event);
        if (event.isCancelled()) {
            return false;
        }

        stopComboHelp(player, Reason.REPLACED);
        final ComboHelpSession session = new ComboHelpSession(player, combo);
        COMBO_HELP_SESSIONS.put(player.getUniqueId(), session);
        session.start();
        return true;
    }

    public static void stopComboHelp(final Player player) {
        stopComboHelp(player, Reason.MANUAL);
    }

    private static void stopComboHelp(final Player player, final Reason reason) {
        final ComboHelpSession session = COMBO_HELP_SESSIONS.remove(player.getUniqueId());
        if (session != null) {
            session.stop();
            Platform.events().call(new ComboHelpStopEvent(player, session.combo, reason));
        }
    }

    public static boolean isUsingComboHelp(final Player player) {
        return COMBO_HELP_SESSIONS.containsKey(player.getUniqueId());
    }

    private static void handleComboHelpInput(final Player player, final AbilityInformation info) {
        final ComboHelpSession session = COMBO_HELP_SESSIONS.get(player.getUniqueId());
        if (session != null) {
            session.handleInput(info);
        }
    }

    private static String describeAbilityInformation(final AbilityInformation info) {
        return info.getAbilityName() + " (" + describeClickType(info.getClickType()) + ")";
    }

    private static String describeClickType(final ClickType clickType) {
        switch (clickType) {
            case LEFT_CLICK:
                return "Left Click";
            case LEFT_CLICK_ENTITY:
                return "Left Click Entity";
            case RIGHT_CLICK:
                return "Right Click";
            case RIGHT_CLICK_ENTITY:
                return "Right Click Entity";
            case RIGHT_CLICK_BLOCK:
                return "Right Click Block";
            case SHIFT_DOWN:
                return "Shift Down";
            case SHIFT_UP:
                return "Shift Up";
            case OFFHAND_TRIGGER:
                return "Offhand";
            default:
                return clickType.name();
        }
    }

    /**
     * Contains information on an ability used in a combo.
     *
     * @author kingbirdy
     *
     */
    public static class AbilityInformation {
        private String abilityName;
        private ClickType clickType;
        private long time;

        public AbilityInformation(final String name, final ClickType type) {
            this(name, type, 0);
        }

        public AbilityInformation(final String name, final ClickType type, final long time) {
            this.abilityName = name;
            this.clickType = type;
            this.time = time;
        }

        /**
         * Compares if two {@link AbilityInformation}'s are equal without
         * respect to {@link AbilityInformation#time time}.
         *
         * @param info The AbilityInformation to compare against
         * @return True if they are equal without respect to time
         */
        public boolean equalsWithoutTime(final AbilityInformation info) {
            return this.getAbilityName().equals(info.getAbilityName()) && this.getClickType().equals(info.getClickType());
        }

        /**
         * Gets the name of the ability.
         *
         * @return The name of the ability.
         */
        public String getAbilityName() {
            return this.abilityName;
        }

        public void setAbilityName(final String abilityName) {
            this.abilityName = abilityName;
        }

        /**
         * Gets the {@link ClickType} of the {@link AbilityInformation}.
         *
         * @return The ClickType
         */
        public ClickType getClickType() {
            return this.clickType;
        }

        public void setClickType(final ClickType clickType) {
            this.clickType = clickType;
        }

        public long getTime() {
            return this.time;
        }

        public void setTime(final long time) {
            this.time = time;
        }

        @Override
        public String toString() {
            return this.abilityName + " " + this.clickType + " " + this.time;
        }
    }

    public static class ComboAbilityInfo {
        private String name;
        private ArrayList<AbilityInformation> abilities;
        private Object comboType;

        public ComboAbilityInfo(final String name, final ArrayList<AbilityInformation> abilities, final Object comboType) {
            this.name = name;
            this.abilities = abilities;
            this.comboType = comboType;
        }

        public ArrayList<AbilityInformation> getAbilities() {
            return this.abilities;
        }

        public void setAbilities(final ArrayList<AbilityInformation> abilities) {
            this.abilities = abilities;
        }

        public Object getComboType() {
            return this.comboType;
        }

        public void setComboType(final Object comboType) {
            this.comboType = comboType;
        }

        public String getName() {
            return this.name;
        }

        public void setName(final String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return this.name;
        }
    }

    private static class ComboHelpSession {
        private final Player player;
        private final ComboAbilityInfo combo;
        private int progress;
        private String status = ChatColor.YELLOW + "Ready";
        private int taskId = -1;

        private ComboHelpSession(final Player player, final ComboAbilityInfo combo) {
            this.player = player;
            this.combo = combo;
        }

        private void start() {
            this.taskId = Platform.scheduler().scheduleRepeating(() -> {
                if (!this.player.isOnline() || COMBO_HELP_SESSIONS.get(this.player.getUniqueId()) != this) {
                    this.stop();
                    COMBO_HELP_SESSIONS.remove(this.player.getUniqueId(), this);
                    Platform.events().call(new ComboHelpStopEvent(this.player, this.combo, Reason.PLAYER_OFFLINE));
                    return;
                }
                ChatUtil.sendActionBar(this.getActionBarMessage(), this.player);
            }, 0L, 10L);
        }

        private void stop() {
            if (this.taskId != -1) {
                Platform.scheduler().cancelTask(this.taskId);
                this.taskId = -1;
            }
        }

        private void handleInput(final AbilityInformation info) {
            final ArrayList<AbilityInformation> sequence = this.combo.getAbilities();
            if (sequence.isEmpty()) {
                return;
            }

            final int previousProgress = this.progress;
            this.progress = this.findMatchingPrefixLength();
            if (this.progress == sequence.size()) {
                this.status = ChatColor.GREEN + "Completed: " + this.combo.getName();
                Platform.events().call(new ComboHelpInputEvent(this.player, this.combo, info, ComboHelpInputEvent.Result.COMPLETED, this.progress));
                Platform.events().call(new ComboHelpCompleteEvent(this.player, this.combo));
                this.player.playSound(this.player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0F, 1.0F);
                stopComboHelp(this.player, Reason.COMPLETED);
                return;
            }

            if (this.progress > 0 || previousProgress == sequence.size()) {
                this.status = ChatColor.GREEN + "Correct";
                Platform.events().call(new ComboHelpInputEvent(this.player, this.combo, info, ComboHelpInputEvent.Result.CORRECT, this.progress));
                this.player.playSound(this.player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1.0F, 1.0F);
            } else {
                this.status = ChatColor.RED + "Wrong";
                Platform.events().call(new ComboHelpInputEvent(this.player, this.combo, info, ComboHelpInputEvent.Result.WRONG, this.progress));
                this.player.playSound(this.player.getLocation(), Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO, 1.0F, 1.0F);
            }
        }

        private boolean matches(final AbilityInformation actual, final AbilityInformation expected) {
            if (actual.getAbilityName().equals(expected.getAbilityName())
                    && actual.getClickType() == ClickType.LEFT_CLICK_ENTITY
                    && expected.getClickType() == ClickType.LEFT_CLICK) {
                return true;
            }
            return actual.equalsWithoutTime(expected);
        }

        private int findMatchingPrefixLength() {
            final ArrayList<AbilityInformation> sequence = this.combo.getAbilities();
            final ArrayList<AbilityInformation> recent = getRecentlyUsedAbilities(this.player, sequence.size());
            final int maxLength = Math.min(sequence.size(), recent.size());
            for (int length = maxLength; length > 0; length--) {
                boolean matches = true;
                for (int i = 0; i < length; i++) {
                    final AbilityInformation actual = recent.get(recent.size() - length + i);
                    final AbilityInformation expected = sequence.get(i);
                    if (!this.matches(actual, expected)) {
                        matches = false;
                        break;
                    }
                }
                if (matches) {
                    return length;
                }
            }
            return 0;
        }

        private String getActionBarMessage() {
            return this.status + ChatColor.DARK_GRAY + " | " + this.getReferenceSequence();
        }

        private String getReferenceSequence() {
            final ArrayList<AbilityInformation> sequence = this.combo.getAbilities();
            final StringBuilder builder = new StringBuilder(ChatColor.GOLD + this.combo.getName() + ChatColor.GRAY + ": ");
            final String visibleStatus = ChatColor.stripColor(this.status);
            final int maxReferenceLength = MAX_COMBO_HELP_VISIBLE_LENGTH - (visibleStatus == null ? 0 : visibleStatus.length()) - 3;
            int visibleLength = this.combo.getName().length() + 2;
            boolean appendedStep = false;
            for (int i = this.progress; i < sequence.size(); i++) {
                final AbilityInformation info = sequence.get(i);
                int repeats = 1;
                while (i + repeats < sequence.size() && info.equalsWithoutTime(sequence.get(i + repeats))) {
                    repeats++;
                }

                final String step = describeAbilityInformation(info) + (repeats > 1 ? "x" + repeats : "");
                final int separatorLength = appendedStep ? 3 : 0;
                if (visibleLength + separatorLength + step.length() > maxReferenceLength) {
                    if (appendedStep) {
                        builder.append(ChatColor.DARK_GRAY).append(" > ");
                    }
                    builder.append(ChatColor.WHITE).append("...");
                    break;
                }

                if (appendedStep) {
                    builder.append(ChatColor.DARK_GRAY).append(" > ");
                }

                builder.append(ChatColor.WHITE).append(step);
                visibleLength += separatorLength + step.length();
                appendedStep = true;
                i += repeats - 1;
            }
            return builder.toString();
        }
    }
}
