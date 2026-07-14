package com.projectkorra.projectkorra.ability.activation;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.airbending.*;
import com.projectkorra.projectkorra.airbending.combo.AirSlash;
import com.projectkorra.projectkorra.airbending.flight.FlightMultiAbility;
import com.projectkorra.projectkorra.avatar.AvatarState;
import com.projectkorra.projectkorra.board.BendingBoardManager;
import com.projectkorra.projectkorra.chiblocking.AcrobatStance;
import com.projectkorra.projectkorra.chiblocking.HighJump;
import com.projectkorra.projectkorra.chiblocking.Smokescreen;
import com.projectkorra.projectkorra.chiblocking.WarriorStance;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.earthbending.*;
import com.projectkorra.projectkorra.earthbending.lava.LavaFlow;
import com.projectkorra.projectkorra.earthbending.lava.LavaSurge;
import com.projectkorra.projectkorra.earthbending.metal.Extraction;
import com.projectkorra.projectkorra.earthbending.metal.MetalClips;
import com.projectkorra.projectkorra.firebending.*;
import com.projectkorra.projectkorra.firebending.combustion.Combustion;
import com.projectkorra.projectkorra.firebending.lightning.Lightning;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.util.ChatUtil;
import com.projectkorra.projectkorra.util.ClickType;
import com.projectkorra.projectkorra.waterbending.*;
import com.projectkorra.projectkorra.waterbending.blood.Bloodbending;
import com.projectkorra.projectkorra.waterbending.combo.IceBullet;
import com.projectkorra.projectkorra.waterbending.healing.HealingWaters;
import com.projectkorra.projectkorra.waterbending.ice.IceBlast;
import com.projectkorra.projectkorra.waterbending.ice.IceSpikeBlast;
import com.projectkorra.projectkorra.waterbending.ice.PhaseChange;
import com.projectkorra.projectkorra.waterbending.multiabilities.WaterArms;

import static com.projectkorra.projectkorra.ability.activation.AbilityActivationManager.*;

final class CoreAbilityActivationBootstrap {
    private CoreAbilityActivationBootstrap() {
    }

    static void registerDefaults() {
        registerGlobal(ClickType.LEFT_CLICK, CoreAbilityActivationBootstrap::leftClickIceBullet);
        registerGlobal(ClickType.RIGHT_CLICK, CoreAbilityActivationBootstrap::rightClickIceBullet);
        registerGlobal(ClickType.RIGHT_CLICK_BLOCK, CoreAbilityActivationBootstrap::rightClickIceBullet);
        registerGlobal(ClickType.LEFT_CLICK, CoreAbilityActivationBootstrap::leftClickPreparedEarthBlast);
        registerGlobal(ClickType.LEFT_CLICK, CoreAbilityActivationBootstrap::leftClickActiveTorrent);

        registerAir();
        registerWater();
        registerEarth();
        registerFire();
        registerChi();
        registerAvatar();
        registerMultiAbilities();
    }

    private static void registerAir() {
        register("Tornado", ClickType.SHIFT_DOWN, context -> created(new Tornado(context.getPlayer())));
        register("AirBlast", ClickType.SHIFT_DOWN, context -> created(new AirBlast(context.getPlayer())));
        register("AirBurst", ClickType.SHIFT_DOWN, context -> created(new AirBurst(context.getPlayer(), false)));
        register("AirSuction", ClickType.SHIFT_DOWN, context -> created(new AirSuction(context.getPlayer())));
        register("AirSwipe", ClickType.SHIFT_DOWN, context -> created(new AirSwipe(context.getPlayer(), true)));
        register("AirShield", ClickType.SHIFT_DOWN, context -> created(new AirShield(context.getPlayer())));
        register("Suffocate", ClickType.SHIFT_DOWN, context -> created(new Suffocate(context.getPlayer())));

        register("AirBlast", ClickType.LEFT_CLICK, context -> {
            AirBlast.shoot(context.getPlayer());
            return true;
        });
        register("AirSuction", ClickType.LEFT_CLICK, context -> {
            AirSuction.shoot(context.getPlayer());
            return true;
        });
        register("AirBurst", ClickType.LEFT_CLICK, context -> {
            AirBurst.coneBurst(context.getPlayer());
            return true;
        });
        register("AirScooter", ClickType.LEFT_CLICK, context -> {
            if (!context.getBoolean("canRideScooter", true)) {
                return false;
            }
            return created(new AirScooter(context.getPlayer()));
        });
        register("AirSpout", ClickType.LEFT_CLICK, context -> created(new AirSpout(context.getPlayer())));
        register("AirSlash", ClickType.LEFT_CLICK, context -> created(new AirSlash(context.getPlayer())));
        register("AirSwipe", ClickType.LEFT_CLICK, context -> created(new AirSwipe(context.getPlayer())));
        register("Flight", ClickType.LEFT_CLICK, context -> {
            created(new FlightMultiAbility(context.getPlayer()));
            context.stopProcessing();
            return true;
        });
    }

    private static void registerWater() {
        register("Bloodbending", ClickType.SHIFT_DOWN, context -> created(new Bloodbending(context.getPlayer())));
        register("IceBlast", ClickType.SHIFT_DOWN, context -> created(new IceBlast(context.getPlayer())));
        register("IceSpike", ClickType.SHIFT_DOWN, context -> created(new IceSpikeBlast(context.getPlayer())));
        register("OctopusForm", ClickType.SHIFT_DOWN, context -> {
            OctopusForm.form(context.getPlayer());
            return true;
        });
        register("PhaseChange", ClickType.SHIFT_DOWN, context -> phaseChange(context.getPlayer(), PhaseChange.PhaseChangeType.MELT));
        register("WaterManipulation", ClickType.SHIFT_DOWN, context -> created(new WaterManipulation(context.getPlayer())));
        register("WaterBubble", ClickType.SHIFT_DOWN, context -> created(new WaterBubble(context.getPlayer(), true)));
        register("Surge", ClickType.SHIFT_DOWN, context -> {
            SurgeWall.form(context.getPlayer());
            return true;
        });
        register("Torrent", ClickType.SHIFT_DOWN, context -> {
            Torrent.create(context.getPlayer());
            return true;
        });
        register("WaterArms", ClickType.SHIFT_DOWN, context -> created(new WaterArms(context.getPlayer())));
        register("HealingWaters", ClickType.SHIFT_DOWN, context -> created(new HealingWaters(context.getPlayer())));

        register("Bloodbending", ClickType.LEFT_CLICK, context -> {
            Bloodbending.launch(context.getPlayer());
            return true;
        });
        register("IceBlast", ClickType.LEFT_CLICK, context -> {
            IceBlast.activate(context.getPlayer());
            return true;
        });
        register("IceSpike", ClickType.LEFT_CLICK, context -> {
            IceSpikeBlast.activate(context.getPlayer());
            return true;
        });
        register("OctopusForm", ClickType.LEFT_CLICK, context -> created(new OctopusForm(context.getPlayer())));
        register("PhaseChange", ClickType.LEFT_CLICK, context -> phaseChange(context.getPlayer(), PhaseChange.PhaseChangeType.FREEZE));
        register("WaterBubble", ClickType.LEFT_CLICK, context -> created(new WaterBubble(context.getPlayer(), false)));
        register("WaterSpout", ClickType.LEFT_CLICK, CoreAbilityActivationBootstrap::toggleWaterSpout);
        register("WaterManipulation", ClickType.LEFT_CLICK, context -> {
            WaterManipulation.moveWater(context.getPlayer());
            return true;
        });
        register("Surge", ClickType.LEFT_CLICK, context -> created(new SurgeWall(context.getPlayer())));
        register("Torrent", ClickType.LEFT_CLICK, context -> created(new Torrent(context.getPlayer())));
        register("HealingWaters", ClickType.RIGHT_CLICK_ENTITY, CoreAbilityActivationBootstrap::rightClickHealingWaters);
    }

    private static void registerEarth() {
        register("Catapult", ClickType.SHIFT_DOWN, context -> created(new Catapult(context.getPlayer(), true)));
        register("EarthBlast", ClickType.SHIFT_DOWN, context -> created(new EarthBlast(context.getPlayer())));
        register("EarthArmor", ClickType.SHIFT_DOWN, context -> created(new EarthArmor(context.getPlayer())));
        register("RaiseEarth", ClickType.SHIFT_DOWN, context -> created(new RaiseEarthWall(context.getPlayer())));
        register("Collapse", ClickType.SHIFT_DOWN, context -> created(new CollapseWall(context.getPlayer())));
        register("OldEarthGrab", ClickType.SHIFT_DOWN, context -> created(new OldEarthGrab(context.getPlayer(), OldEarthGrab.Type.SELF)));
        register("Shockwave", ClickType.SHIFT_DOWN, context -> created(new Shockwave(context.getPlayer(), false)));
        register("EarthTunnel", ClickType.SHIFT_DOWN, context -> created(new EarthTunnel(context.getPlayer())));
        register("Tremorsense", ClickType.SHIFT_DOWN, CoreAbilityActivationBootstrap::toggleTremorsense);
        register("Extraction", ClickType.SHIFT_DOWN, context -> created(new Extraction(context.getPlayer())));
        register("LavaFlow", ClickType.SHIFT_DOWN, context -> created(new LavaFlow(context.getPlayer(), LavaFlow.AbilityType.SHIFT)));
        register("EarthSmash", ClickType.SHIFT_DOWN, context -> created(new EarthSmash(context.getPlayer(), ClickType.SHIFT_DOWN)));
        register("MetalClips", ClickType.SHIFT_DOWN, CoreAbilityActivationBootstrap::shiftMetalClips);
        register("EarthGrab", ClickType.SHIFT_DOWN, context -> created(new EarthGrab(context.getPlayer(), EarthGrab.GrabMode.DRAG)));

        register("Catapult", ClickType.LEFT_CLICK, context -> created(new Catapult(context.getPlayer(), false)));
        register("EarthBlast", ClickType.LEFT_CLICK, context -> {
            EarthBlast.throwEarth(context.getPlayer());
            return true;
        });
        register("RaiseEarth", ClickType.LEFT_CLICK, context -> created(new RaiseEarth(context.getPlayer())));
        register("RaiseEarth", ClickType.RIGHT_CLICK_BLOCK, context -> created(new RaiseEarth(context.getPlayer())));
        register("Collapse", ClickType.LEFT_CLICK, context -> created(new Collapse(context.getPlayer())));
        register("OldEarthGrab", ClickType.LEFT_CLICK, context -> created(new OldEarthGrab(context.getPlayer(), OldEarthGrab.Type.OTHERS)));
        register("Shockwave", ClickType.LEFT_CLICK, context -> {
            Shockwave.coneShockwave(context.getPlayer());
            return true;
        });
        register("EarthArmor", ClickType.LEFT_CLICK, CoreAbilityActivationBootstrap::clickEarthArmor);
        register("Tremorsense", ClickType.LEFT_CLICK, context -> created(new Tremorsense(context.getPlayer(), true)));
        register("MetalClips", ClickType.LEFT_CLICK, CoreAbilityActivationBootstrap::clickMetalClips);
        register("LavaSurge", ClickType.LEFT_CLICK, CoreAbilityActivationBootstrap::clickLavaSurge);
        register("LavaFlow", ClickType.LEFT_CLICK, context -> created(new LavaFlow(context.getPlayer(), LavaFlow.AbilityType.CLICK)));
        register("EarthSmash", ClickType.LEFT_CLICK, context -> created(new EarthSmash(context.getPlayer(), ClickType.LEFT_CLICK)));
        register("EarthGrab", ClickType.LEFT_CLICK, context -> created(new EarthGrab(context.getPlayer(), EarthGrab.GrabMode.PROJECTING)));
        register("EarthSmash", ClickType.RIGHT_CLICK_BLOCK, context -> created(new EarthSmash(context.getPlayer(), ClickType.RIGHT_CLICK)));
    }

    private static void registerFire() {
        register("Blaze", ClickType.SHIFT_DOWN, context -> created(new BlazeRing(context.getPlayer())));
        register("FireBlast", ClickType.SHIFT_DOWN, context -> created(new FireBlastCharged(context.getPlayer())));
        register("HeatControl", ClickType.SHIFT_DOWN, context -> created(new HeatControl(context.getPlayer(), HeatControl.HeatControlType.COOK)));
        register("FireBurst", ClickType.SHIFT_DOWN, context -> created(new FireBurst(context.getPlayer())));
        register("FireShield", ClickType.SHIFT_DOWN, context -> created(new FireShield(context.getPlayer(), true)));
        register("Lightning", ClickType.SHIFT_DOWN, context -> created(new Lightning(context.getPlayer())));
        //register("Combustion", ClickType.SHIFT_DOWN, context -> created(new Combustion(context.getPlayer())));
        register("FireManipulation", ClickType.SHIFT_DOWN, context -> created(new FireManipulation(context.getPlayer(), FireManipulation.FireManipulationType.SHIFT)));

        register("Blaze", ClickType.LEFT_CLICK, context -> created(new Blaze(context.getPlayer())));
        register("FireBlast", ClickType.LEFT_CLICK, context -> created(new FireBlast(context.getPlayer())));
        register("FireJet", ClickType.LEFT_CLICK, context -> created(new FireJet(context.getPlayer())));
        register("HeatControl", ClickType.LEFT_CLICK, context -> created(new HeatControl(context.getPlayer(), HeatControl.HeatControlType.MELT)));
        register("Illumination", ClickType.LEFT_CLICK, CoreAbilityActivationBootstrap::clickIllumination);
        register("FireBurst", ClickType.LEFT_CLICK, context -> {
            FireBurst.coneBurst(context.getPlayer());
            return true;
        });
        register("FireShield", ClickType.LEFT_CLICK, context -> created(new FireShield(context.getPlayer())));
        register("WallOfFire", ClickType.LEFT_CLICK, context -> created(new WallOfFire(context.getPlayer())));
        register("Combustion", ClickType.LEFT_CLICK, context -> {
            Combustion.explode(context.getPlayer());
            return true;
        });
        register("FireManipulation", ClickType.LEFT_CLICK, CoreAbilityActivationBootstrap::clickFireManipulation);
    }

    private static void registerChi() {
        register("HighJump", ClickType.LEFT_CLICK, context -> created(new HighJump(context.getPlayer())));
        register("Smokescreen", ClickType.LEFT_CLICK, context -> created(new Smokescreen(context.getPlayer())));
        register("WarriorStance", ClickType.LEFT_CLICK, context -> created(new WarriorStance(context.getPlayer())));
        register("AcrobatStance", ClickType.LEFT_CLICK, context -> created(new AcrobatStance(context.getPlayer())));
    }

    private static void registerAvatar() {
        register("AvatarState", ClickType.LEFT_CLICK, context -> {
            final Player player = context.getPlayer();
            created(new AvatarState(player));
            ChatUtil.displayMovePreview(player);
            BendingBoardManager.updateAllSlots(player);
            return true;
        });
    }

    private static boolean leftClickPreparedEarthBlast(final ActivationContext context) {
        final Player player = context.getPlayer();
        if (player == null || !"EarthBlast".equalsIgnoreCase(context.getAbilityName())) {
            return false;
        }
        for (final EarthBlast blast : CoreAbility.getAbilities(player, EarthBlast.class)) {
            if (!blast.isProgressing()) {
                EarthBlast.throwEarth(player);
                context.stopProcessing();
                context.cancelEvent();
                return true;
            }
        }
        return false;
    }

    private static boolean leftClickActiveTorrent(final ActivationContext context) {
        final Player player = context.getPlayer();
        if (player == null || !"Torrent".equalsIgnoreCase(context.getAbilityName()) || !CoreAbility.hasAbility(player, Torrent.class)) {
            return false;
        }
        new Torrent(player);
        context.stopProcessing();
        context.cancelEvent();
        return true;
    }

    private static boolean toggleWaterSpout(final ActivationContext context) {
        final boolean removingExisting = CoreAbility.hasAbility(context.getPlayer(), WaterSpout.class);
        final WaterSpout result = new WaterSpout(context.getPlayer());
        // Removing an existing spout is a complete state transition even
        // though the constructor correctly does not start a replacement.
        return removingExisting || created(result);
    }

    private static void registerMultiAbilities() {
        registerMulti("WaterArms", ClickType.LEFT_CLICK, context -> created(new WaterArms(context.getPlayer())));
        registerMulti("Flight", ClickType.LEFT_CLICK, context -> created(new FlightMultiAbility(context.getPlayer())));
    }

    private static boolean leftClickIceBullet(final ActivationContext context) {
        if (CoreAbility.hasAbility(context.getPlayer(), IceBullet.class)) {
            CoreAbility.getAbility(context.getPlayer(), IceBullet.class).doLeftClick();
            return true;
        }
        return false;
    }

    private static boolean rightClickIceBullet(final ActivationContext context) {
        if (CoreAbility.hasAbility(context.getPlayer(), IceBullet.class)) {
            CoreAbility.getAbility(context.getPlayer(), IceBullet.class).doRightClick();
            return true;
        }
        return false;
    }

    private static boolean rightClickHealingWaters(final ActivationContext context) {
        final HealingWaters instance = CoreAbility.getAbility(context.getPlayer(), HealingWaters.class);
        if (instance != null && instance.charged) {
            instance.click();
            context.cancelEvent();
            return true;
        }
        return false;
    }

    private static boolean phaseChange(final Player player, final PhaseChange.PhaseChangeType type) {
        if (!CoreAbility.hasAbility(player, PhaseChange.class)) {
            return created(new PhaseChange(player, type));
        }
        CoreAbility.getAbility(player, PhaseChange.class).startNewType(type);
        return true;
    }

    private static boolean toggleTremorsense(final ActivationContext context) {
        final Player player = context.getPlayer();
        final BendingPlayer bPlayer = context.getBendingPlayer();
        bPlayer.toggleTremorSense();
        ChatUtil.displayMovePreview(player);
        BendingBoardManager.updateAllSlots(player);
        return true;
    }

    private static boolean shiftMetalClips(final ActivationContext context) {
        final Player player = context.getPlayer();
        final MetalClips clips = CoreAbility.getAbility(player, MetalClips.class);
        if (clips != null) {
            if (clips.getTargetEntity() == null) {
                clips.setMagnetized(true);
            } else {
                clips.setControlling(true);
            }
        } else {
            new MetalClips(player, 1);
        }
        return true;
    }

    private static boolean clickEarthArmor(final ActivationContext context) {
        final EarthArmor armor = CoreAbility.getAbility(context.getPlayer(), EarthArmor.class);
        if (armor != null && armor.isFormed()) {
            armor.click();
            return true;
        }
        return false;
    }

    private static boolean clickMetalClips(final ActivationContext context) {
        final Player player = context.getPlayer();
        final MetalClips clips = CoreAbility.getAbility(player, MetalClips.class);
        if (clips == null) {
            new MetalClips(player, 0);
        } else if (clips.getMetalClipsCount() < (player.hasPermission("bending.ability.MetalClips.4clips") ? 4 : 3)) {
            clips.shootMetal();
        } else if (clips.getMetalClipsCount() == 4 && clips.isCanUse4Clips()) {
            clips.crush();
        }
        return true;
    }

    private static boolean clickLavaSurge(final ActivationContext context) {
        final LavaSurge surge = CoreAbility.getAbility(context.getPlayer(), LavaSurge.class);
        if (surge != null) {
            surge.launch();
            return true;
        }
        return false;
    }

    private static boolean clickIllumination(final ActivationContext context) {
        final Player player = context.getPlayer();
        if (ConfigManager.defaultConfig.get().getBoolean("Abilities.Fire.Illumination.Passive")) {
            context.getBendingPlayer().toggleIllumination();
            ChatUtil.displayMovePreview(player);
            BendingBoardManager.updateAllSlots(player);
        } else {
            new Illumination(player);
        }
        return true;
    }

    private static boolean clickFireManipulation(final ActivationContext context) {
        final Player player = context.getPlayer();
        if (CoreAbility.hasAbility(player, FireManipulation.class)) {
            final FireManipulation fireManip = CoreAbility.getAbility(player, FireManipulation.class);
            if (fireManip.getFireManipulationType() == FireManipulation.FireManipulationType.SHIFT) {
                fireManip.click();
                return true;
            }
            return false;
        }
        new FireManipulation(player, FireManipulation.FireManipulationType.CLICK);
        return true;
    }

    private static boolean created(final CoreAbility ability) {
        return ability != null && !ability.isRemoved();
    }
}
