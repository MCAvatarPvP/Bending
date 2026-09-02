package com.projectkorra.projectkorra.fabric.client.prediction.impl;

import com.jedk1.jedcore.ability.passive.WallRun;
import com.projectkorra.projectkorra.BendingManager;
import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.Manager;
import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.BendingManager.TempElementsRunnable;
import com.projectkorra.projectkorra.Element.SubElement;
import com.projectkorra.projectkorra.ability.Ability;
import com.projectkorra.projectkorra.ability.AirAbility;
import com.projectkorra.projectkorra.ability.ComboAbility;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.EarthAbility;
import com.projectkorra.projectkorra.ability.ElementalAbility;
import com.projectkorra.projectkorra.ability.FireAbility;
import com.projectkorra.projectkorra.ability.WaterAbility;
import com.projectkorra.projectkorra.ability.activation.AbilityActivationManager;
import com.projectkorra.projectkorra.ability.activation.AbilityActivationManager.TrackingResult;
import com.projectkorra.projectkorra.ability.util.CollisionInitializer;
import com.projectkorra.projectkorra.ability.util.CollisionManager;
import com.projectkorra.projectkorra.ability.util.ComboManager;
import com.projectkorra.projectkorra.ability.util.EmbeddedAddonBootstrap;
import com.projectkorra.projectkorra.ability.util.MultiAbilityManager;
import com.projectkorra.projectkorra.ability.util.PassiveManager;
import com.projectkorra.projectkorra.ability.util.ComboManager.AbilityInformation;
import com.projectkorra.projectkorra.airbending.AirBlast;
import com.projectkorra.projectkorra.airbending.AirGlider;
import com.projectkorra.projectkorra.chiblocking.util.ChiblockingManager;
import com.projectkorra.projectkorra.earthbending.EarthSmash;
import com.projectkorra.projectkorra.earthbending.RaiseEarth;
import com.projectkorra.projectkorra.earthbending.RaiseEarthWall;
import com.projectkorra.projectkorra.earthbending.EarthTunnel;
import com.projectkorra.projectkorra.earthbending.EarthSmash.PredictionBlock;
import com.projectkorra.projectkorra.earthbending.EarthSmash.PredictionTransfer;
import com.projectkorra.projectkorra.earthbending.util.EarthbendingManager;
import com.projectkorra.projectkorra.fabric.client.prediction.action.ClientNativeActionCorrelation;
import com.projectkorra.projectkorra.fabric.client.prediction.block.ClientDirectBlockAuthority;
import com.projectkorra.projectkorra.fabric.client.prediction.block.ClientBlockVisualOverlay;
import com.projectkorra.projectkorra.fabric.client.prediction.block.ClientTempBlockAuthority;
import com.projectkorra.projectkorra.fabric.client.prediction.config.ClientPredictionConfig;
import com.projectkorra.projectkorra.fabric.client.prediction.entity.ClientEntityReconciliation;
import com.projectkorra.projectkorra.fabric.client.prediction.entity.EarthShardFallingCollisionPolicy;
import com.projectkorra.projectkorra.fabric.client.prediction.effect.ClientSoundAuthority;
import com.projectkorra.projectkorra.fabric.client.prediction.movement.ClientVelocityAuthority;
import com.projectkorra.projectkorra.fabric.client.prediction.state.ClientPlayerStateAuthority;
import com.projectkorra.projectkorra.fabric.client.prediction.state.PredictionCooldownAuthority;
import com.projectkorra.projectkorra.fabric.prediction.protocol.PredictionPayloads.AbilityRemoved;
import com.projectkorra.projectkorra.fabric.prediction.protocol.PredictionPayloads.AbilityStateOwner;
import com.projectkorra.projectkorra.fabric.prediction.protocol.PredictionPayloads.AbilityTransfer;
import com.projectkorra.projectkorra.fabric.prediction.protocol.PredictionPayloads.AirGliderState;
import com.projectkorra.projectkorra.fabric.prediction.protocol.PredictionPayloads.ConfigEntry;
import com.projectkorra.projectkorra.fabric.prediction.protocol.PredictionPayloads.DirectBlockReceipt;
import com.projectkorra.projectkorra.fabric.prediction.protocol.PredictionPayloads.InputKind;
import com.projectkorra.projectkorra.fabric.prediction.protocol.PredictionPayloads.GlidingStateOwner;
import com.projectkorra.projectkorra.fabric.prediction.protocol.PredictionPayloads.NativeAction;
import com.projectkorra.projectkorra.fabric.prediction.protocol.PredictionPayloads.PlayerCosmetics;
import com.projectkorra.projectkorra.fabric.prediction.protocol.PredictionPayloads.TempBlockBatch;
import com.projectkorra.projectkorra.fabric.prediction.protocol.PredictionPayloads.TempFallingBlockPrepare;
import com.projectkorra.projectkorra.fabric.prediction.protocol.PredictionPayloads.TempFallingBlockReceipt;
import com.projectkorra.projectkorra.fabric.prediction.protocol.PredictionPayloads.VelocityOwner;
import com.projectkorra.projectkorra.fabric.prediction.protocol.PredictionPayloads.VelocityOwnerV2;
import com.projectkorra.projectkorra.firebending.FireBlastCharged;
import com.projectkorra.projectkorra.firebending.util.FirebendingManager;
import com.projectkorra.projectkorra.listener.CommonInputHandler;
import com.projectkorra.projectkorra.listener.CommonPlayerListenerCore;
import com.projectkorra.projectkorra.listener.CommonInputHandler.SlotResult;
import com.projectkorra.projectkorra.listener.CommonPlayerListenerCore.MovementResult;
import com.projectkorra.projectkorra.object.CosmeticColor;
import com.projectkorra.projectkorra.object.EarthCosmetic;
import com.projectkorra.projectkorra.object.WaterCosmetic;
import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.fabric.FabricClientPredictionPlatform;
import com.projectkorra.projectkorra.platform.fabric.FabricMC;
import com.projectkorra.projectkorra.platform.fabric.FabricPredictionMC;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.block.BlockFace;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.block.data.Levelled;
import com.projectkorra.projectkorra.platform.mc.block.data.Snowable;
import com.projectkorra.projectkorra.platform.mc.block.data.type.Fire;
import com.projectkorra.projectkorra.platform.mc.block.data.type.Snow;
import com.projectkorra.projectkorra.platform.mc.entity.FallingBlock;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.prediction.action.AbilityExecutionContext;
import com.projectkorra.projectkorra.prediction.action.AbilityRemovalSync;
import com.projectkorra.projectkorra.prediction.action.PredictionActionSeed;
import com.projectkorra.projectkorra.prediction.action.PredictionDeterminism;
import com.projectkorra.projectkorra.prediction.authority.RegionProtectionAuthority;
import com.projectkorra.projectkorra.prediction.authority.RegionProtectionAuthority.Snapshot;
import com.projectkorra.projectkorra.prediction.block.TempBlockSync;
import com.projectkorra.projectkorra.prediction.block.TempFallingBlockSync;
import com.projectkorra.projectkorra.prediction.hit.PredictedContactSync;
import com.projectkorra.projectkorra.prediction.hit.HitRegistrationPolicy;
import com.projectkorra.projectkorra.prediction.state.CooldownSync;
import com.projectkorra.projectkorra.prediction.state.GlidingStateSync;
import com.projectkorra.projectkorra.prediction.state.PredictionStateOrdering;
import com.projectkorra.projectkorra.prediction.state.CooldownSync.Listener;
import com.projectkorra.projectkorra.util.BlockSource;
import com.projectkorra.projectkorra.util.ClickType;
import com.projectkorra.projectkorra.util.Cooldown;
import com.projectkorra.projectkorra.util.CooldownDisplayHandler;
import com.projectkorra.projectkorra.util.FallHandler;
import com.projectkorra.projectkorra.util.FlightHandler;
import com.projectkorra.projectkorra.util.RegenHandler;
import com.projectkorra.projectkorra.util.RevertChecker;
import com.projectkorra.projectkorra.util.TempBlock;
import com.projectkorra.projectkorra.util.TempFallingBlock;
import com.projectkorra.projectkorra.waterbending.blood.Bloodbending;
import com.projectkorra.projectkorra.waterbending.passive.FastSwim;
import com.projectkorra.projectkorra.waterbending.util.WaterbendingManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.function.Supplier;
import java.util.logging.Level;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.entity.MovementType;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.network.packet.s2c.play.ExperienceBarUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerAbilitiesS2CPacket;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import com.projectkorra.projectkorra.fabric.client.ExactPredictionRuntime;

public abstract class ExactPredictionState
        implements Listener,
        GlidingStateSync.Listener,
        com.projectkorra.projectkorra.prediction.block.TempFallingBlockSync.Listener,
        com.projectkorra.projectkorra.prediction.hit.PredictedContactSync.Listener {
    protected static final ThreadLocal<Long> INPUT_ACTION = new ThreadLocal<>();
    protected static final ThreadLocal<Long> INPUT_EVENT_POSE = new ThreadLocal<>();
    protected static final Set<String> PERSISTENT_FLIGHT_ABILITIES = Set.of(
            "airscooter", "airspout", "waterspout", "sandspout", "airglider", "firejet", "flight");
    protected static final boolean DEBUG = Boolean.parseBoolean(System.getProperty("projectkorra.prediction.debug", "false"));
    protected static final int MATERIAL_STATE_CACHE_LIMIT = 4_096;
    protected static final Map<String, BlockState> MATERIAL_STATE_CACHE =
            new LinkedHashMap<>(256, 0.75F, true) {
                @Override
                protected boolean removeEldestEntry(final Map.Entry<String, BlockState> eldest) {
                    return size() > MATERIAL_STATE_CACHE_LIMIT;
                }
            };
    protected final Map<Long, Action> actions = new LinkedHashMap<>();
    protected final Map<CoreAbility, Long> abilityActions = new IdentityHashMap<>();
    protected final Map<CoreAbility, Long> abilityCreationActions = new IdentityHashMap<>();
    protected final Map<CoreAbility, Set<Long>> abilityTransitionActions = new IdentityHashMap<>();
    protected final Set<CoreAbility> authoritativelyEstablishedAbilities = Collections.newSetFromMap(new IdentityHashMap<>());
    protected final List<String> abilityRemovalHistory = new ArrayList<>();
    protected final ClientNativeActionCorrelation nativeActions = new ClientNativeActionCorrelation();
    protected Set<String> authoritativeFlightAbilities = Set.of();
    protected long authoritativeFlightSequence = -1L;
    protected Set<String> grantedPermissions = Set.of();
    protected final ClientVelocityAuthority velocityAuthority = new ClientVelocityAuthority(
            ExactPredictionState::debug
    );
    protected final ClientSoundAuthority soundAuthority = new ClientSoundAuthority();
    protected final ClientEntityReconciliation entityReconciliation = new ClientEntityReconciliation(
            ExactPredictionState::materialState
    );
    protected final ClientBlockVisualOverlay blockVisualOverlay = new ClientBlockVisualOverlay();
    protected final ClientDirectBlockAuthority directBlockAuthority;
    protected final ClientTempBlockAuthority tempBlockAuthority;
    protected final ClientPlayerStateAuthority playerStateAuthority =
            new ClientPlayerStateAuthority(ExactPredictionState::debug);
    protected final PredictionCooldownAuthority cooldownAuthority = new PredictionCooldownAuthority();
    protected FabricClientPredictionPlatform platform;
    protected BendingManager bendingManager;
    protected BendingPlayer bendingPlayer;
    protected long tick;
    protected boolean ready;
    protected boolean initializing;
    protected boolean managersStarted;
    protected boolean commonRuntimeInstalled;
    protected boolean discardingWorldState;
    protected String lastStartFailure = "";

    protected ExactPredictionState() {
        this.directBlockAuthority = new ClientDirectBlockAuthority(
                new ClientDirectBlockAuthority.Context() {
                    @Override
                    public long currentAction() {
                        return ExactPredictionState.this.currentAction();
                    }

                    @Override
                    public long tick() {
                        return ExactPredictionState.this.tick;
                    }

                    @Override
                    public String inputAbility(long actionSequence) {
                        Action action = ExactPredictionState.this.actions.get(actionSequence);
                        return action == null ? "" : action.inputAbility;
                    }

                    @Override
                    public void markMutation(long actionSequence, String ability, int ordinal) {
                        Action action = ExactPredictionState.this.actions.get(actionSequence);
                        if (action != null) {
                            action.directBlockOrdinals.put(ability, ordinal);
                        }
                    }

                    @Override
                    public boolean hasAction(long actionSequence) {
                        return ExactPredictionState.this.actions.containsKey(actionSequence);
                    }

                    @Override
                    public boolean hasActiveAbility(long actionSequence, String abilityName) {
                        for (Entry<CoreAbility, Long> entry : ExactPredictionState.this.abilityActions.entrySet()) {
                            CoreAbility ability = entry.getKey();
                            if (entry.getValue() == actionSequence && ability != null && !ability.isRemoved() && ability.getName().equalsIgnoreCase(abilityName)) {
                                return true;
                            }
                        }

                        return false;
                    }

                    @Override
                    public boolean sameActiveAbilityLifecycle(
                            long actionSequence, long creationActionSequence,
                            String abilityName) {
                        for (Entry<CoreAbility, Long> entry
                                : ExactPredictionState.this.abilityActions.entrySet()) {
                            CoreAbility ability = entry.getKey();
                            if (entry.getValue() != actionSequence || ability == null
                                    || ability.isRemoved()
                                    || !ability.getName().equalsIgnoreCase(abilityName)) continue;
                            final long creation = ExactPredictionState.this
                                    .abilityCreationActions.getOrDefault(
                                    ability, ability.getPredictionActionSequence());
                            if (creation == creationActionSequence) return true;
                        }
                        return false;
                    }

                    @Override
                    public int confirmationTicks(long actionSequence) {
                        return ExactPredictionState.this.blockConfirmationTicks(actionSequence);
                    }
                },
                ExactPredictionState::materialState,
                this.blockVisualOverlay,
                ExactPredictionState::debug
        );
        this.tempBlockAuthority = new ClientTempBlockAuthority(
                new ClientTempBlockAuthority.Context() {
                    @Override
                    public boolean ready() {
                        return ExactPredictionState.this.ready;
                    }

                    @Override
                    public long tick() {
                        return ExactPredictionState.this.tick;
                    }

                    @Override
                    public long currentAction() {
                        return ExactPredictionState.this.currentAction();
                    }

                    @Override
                    public long actionForAbility(CoreAbility ability) {
                        return ExactPredictionState.this.abilityActions.getOrDefault(ability, 0L);
                    }

                    @Override
                    public String inputAbility(long actionSequence) {
                        Action action = ExactPredictionState.this.actions.get(actionSequence);
                        return action == null ? "" : action.inputAbility;
                    }

                    @Override
                    public int nextTempBlockOrdinal(long actionSequence) {
                        Action action = ExactPredictionState.this.actions.get(actionSequence);
                        return action == null ? 0 : ++action.tempBlockOrdinal;
                    }

                    @Override
                    public long localActionSequence(long paperSequence) {
                        return ExactPredictionState.this.localActionSequence(paperSequence);
                    }

                    @Override
                    public int confirmationTicks(long actionSequence) {
                        return ExactPredictionState.this.blockConfirmationTicks(actionSequence);
                    }
                },
                this.directBlockAuthority,
                this.blockVisualOverlay,
                ExactPredictionState::materialState,
                ExactPredictionState::debug
        );
    }


    protected static boolean finite(Vec3d value) {
        return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }

    protected static BlockState materialState(String materialName) {
        final String cacheKey = materialName == null ? "" : materialName;
        synchronized (MATERIAL_STATE_CACHE) {
            final BlockState cached = MATERIAL_STATE_CACHE.get(cacheKey);
            if (cached != null) return cached;
        }
        final BlockState decoded = decodeMaterialState(cacheKey);
        synchronized (MATERIAL_STATE_CACHE) {
            MATERIAL_STATE_CACHE.put(cacheKey, decoded);
        }
        return decoded;
    }

    protected static BlockState decodeMaterialState(final String materialName) {
        if (materialName != null && !materialName.contains(";")) {
            return FabricMC.blockState(materialName);
        }

        String[] fields = materialName == null ? new String[0] : materialName.trim().split(";");

        Material material;
        try {
            String key = fields.length == 0 ? "" : fields[0];
            int namespace = key.indexOf(58);
            if (namespace >= 0) {
                key = key.substring(namespace + 1);
            }

            material = key.isBlank() ? Material.AIR : Material.valueOf(key.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            material = Material.AIR;
        }

        BlockData data = material.createBlockData();

        for (int i = 1; i < fields.length; i++) {
            int separator = fields[i].indexOf(61);
            if (separator > 0) {
                String name = fields[i].substring(0, separator);
                String value = fields[i].substring(separator + 1);

                try {
                    if (data instanceof Levelled levelled) {
                        if (name.equals("level")) {
                            levelled.setLevel(Integer.parseInt(value));
                        } else if (name.equals("waterlogged")) {
                            levelled.setWaterlogged(value.equals("1"));
                        }
                    } else if (data instanceof Fire fire && name.equals("faces") && !value.isBlank()) {
                        for (String face : value.split(",")) {
                            fire.setFace(BlockFace.valueOf(face), true);
                        }
                    } else if (data instanceof Snow snow && name.equals("layers")) {
                        snow.setLayers(Math.max(1, Math.min(8, Integer.parseInt(value))));
                    } else if (data instanceof Snowable snowable && name.equals("snowy")) {
                        snowable.setSnowy(value.equals("1"));
                    }
                } catch (IllegalArgumentException var16) {
                }
            }
        }

        return FabricMC.blockState(data);
    }

    protected static boolean matchesWorld(String clientWorld, String serverWorld) {
        if (serverWorld == null || serverWorld.isBlank()) {
            return false;
        } else {
            return clientWorld.equals(serverWorld)
                    ? true
                    : serverWorld.indexOf(58) < 0 && ("minecraft:overworld".equals(clientWorld) || "overworld".equals(clientWorld));
        }
    }

    protected static boolean close(Vec3d first, Vec3d second, double tolerance) {
        return first.squaredDistanceTo(second) <= tolerance * tolerance;
    }


    protected static void debug(String message) {
        if (DEBUG) {
            System.out.println("[ProjectKorraPrediction] " + message);
        }
    }


    protected static final class Action {
        final long sequence;
        final long createdTick;
        final Vec3d origin;
        final float yaw;
        final float pitch;
        final double eyeHeight;
        final String inputAbility;
        final InputKind kind;
        final int selectedSlot;
        final long deterministicSeed;
        final Set<CoreAbility> abilities = Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<Entity> spawned = Collections.newSetFromMap(new IdentityHashMap<>());
        final Map<CoreAbility, Long> previousAbilityActions = new IdentityHashMap<>();
        final Map<Integer, Integer> velocityOrdinals = new HashMap<>();
        final Map<Integer, Integer> abilityStateOrdinals = new HashMap<>();
        final Map<Integer, Integer> glidingStateOrdinals = new HashMap<>();
        final Map<String, Integer> directBlockOrdinals = new HashMap<>();
        final Set<UUID> claimedTargets = new HashSet<>();
        int tempFallingBlockOrdinal;
        int tempBlockOrdinal;
        boolean reconciled;
        boolean nativeConfirmed;
        boolean locallyPredicted;
        boolean executed;
        boolean inputHandled;
        boolean comboRecorded;
        boolean cooldownActiveAtInput;
        boolean recoveredFromAuthority;
        AbilityInformation comboInput;
        int blockConfirmationTicks = 40;

        protected Action(
                long sequence, long createdTick, Vec3d origin, float yaw, float pitch, double eyeHeight, String inputAbility, InputKind kind, int selectedSlot
        ) {
            this.sequence = sequence;
            this.createdTick = createdTick;
            this.origin = origin;
            this.yaw = yaw;
            this.pitch = pitch;
            this.eyeHeight = eyeHeight;
            this.inputAbility = inputAbility == null ? "" : inputAbility;
            this.kind = kind;
            this.selectedSlot = selectedSlot;
            this.deterministicSeed = PredictionActionSeed.from(
                    kind == null ? "" : kind.name(), selectedSlot, this.inputAbility, origin.x, origin.y, origin.z, yaw, pitch
            );
        }

        protected ClientNativeActionCorrelation.Candidate correlationCandidate() {
            return new ClientNativeActionCorrelation.Candidate(sequence, kind, selectedSlot,
                    inputAbility, origin.x, origin.y, origin.z, yaw, pitch);
        }
    }


    protected abstract long currentAction();
    protected abstract int blockConfirmationTicks(long actionSequence);
    protected abstract long localActionSequence(long paperSequence);
    protected abstract void stop0(MinecraftClient client);
    protected abstract void reconcileAuthoritativeCooldowns(Map<String, Long> cooldowns);
    protected abstract String airBlastStamina();
    protected abstract void abortFailedLocalInput(Action action);
    protected abstract boolean hasLivePredictedVelocityWriter(int entityId);
    protected abstract void forceRemoveAbility(CoreAbility ability);
    protected abstract String currentAbilityName();
    protected abstract String activeAirBlastSummary();
    protected abstract void recordAbilityRemoval(AbilityRemoved removed, String resolution,
                                                 List<CoreAbility> matching);
}
