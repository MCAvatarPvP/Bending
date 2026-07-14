package com.projectkorra.projectkorra.ability.activation;

import com.jedk1.jedcore.JedCore;
import com.jedk1.jedcore.ability.airbending.*;
import com.jedk1.jedcore.ability.avatar.SpiritBeam;
import com.jedk1.jedcore.ability.avatar.elementsphere.ElementSphere;
import com.jedk1.jedcore.ability.earthbending.*;
import com.jedk1.jedcore.ability.earthbending.EarthKick;
import com.jedk1.jedcore.ability.earthbending.combo.Crevice;
import com.jedk1.jedcore.ability.earthbending.combo.MagmaBlast;
import com.jedk1.jedcore.ability.firebending.*;
import com.jedk1.jedcore.ability.passive.WallRun;
import com.jedk1.jedcore.ability.waterbending.*;
import com.jedk1.jedcore.ability.waterbending.combo.WaterFlow;
import com.jedk1.jedcore.ability.waterbending.combo.WaterGimbal;
import com.jedk1.jedcore.configuration.JedCoreConfig;
import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.util.ComboManager;
import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.util.ClickType;
import hackathonpack.HackathonPackListener;
import me.literka.ChiRework;
import me.literka.abilities.*;
import me.moros.hyperion.Hyperion;
import me.moros.hyperion.abilities.chiblocking.Smokescreen;
import me.moros.hyperion.abilities.earthbending.*;
import me.moros.hyperion.abilities.earthbending.EarthLine;
import me.moros.hyperion.abilities.firebending.Bolt;
import me.moros.hyperion.abilities.firebending.FlameRush;
import me.moros.hyperion.abilities.waterbending.IceBreath;
import me.moros.hyperion.abilities.waterbending.IceCrawl;
import me.moros.hyperion.abilities.waterbending.combo.IceDrill;
import me.simplicitee.project.addons.ProjectAddons;
import me.simplicitee.project.addons.ability.air.*;
import me.simplicitee.project.addons.ability.avatar.EnergyBeam;
import me.simplicitee.project.addons.ability.chi.FlyingKick;
import me.simplicitee.project.addons.ability.chi.NinjaStance;
import me.simplicitee.project.addons.ability.earth.*;
import me.simplicitee.project.addons.ability.fire.*;
import me.simplicitee.project.addons.ability.water.*;

import java.util.ArrayList;
import java.util.Collection;

import static com.projectkorra.projectkorra.ability.activation.AbilityActivationManager.*;

final class AddonAbilityActivationBootstrap {
    private AddonAbilityActivationBootstrap() {
    }

    static void registerDefaults() {
        registerJedCore();
        registerProjectAddons();
        registerChiRework();
        registerHackathonPack();
        registerToss();
        registerHyperion();
    }

    private static void registerHackathonPack() {
        CoreAbility.registerPluginAbilities(ProjectKorra.plugin, "hackathonpack");
        Platform.events().registerListener(new HackathonPackListener(), ProjectKorra.plugin);
    }

    private static void registerToss() {
        CoreAbility.registerPluginAbilities(ProjectKorra.plugin, "me.hiro");
    }

    private static void registerHyperion() {
        Hyperion.enable();

        register("Evade", ClickType.LEFT_CLICK, context -> created(new me.moros.hyperion.abilities.airbending.Evade(context.getPlayer())));
        register("Smokescreen", ClickType.LEFT_CLICK, context -> created(new Smokescreen(context.getPlayer())));

        register("EarthLine", ClickType.SHIFT_DOWN, context -> created(new EarthLine(context.getPlayer())));
        register("EarthLine", ClickType.LEFT_CLICK, context -> {
            EarthLine.shootLine(context.getPlayer());
            return true;
        });
        register("EarthShot", ClickType.SHIFT_DOWN, context -> created(new EarthShot(context.getPlayer())));
        register("EarthShot", ClickType.LEFT_CLICK, context -> {
            EarthShot.throwProjectile(context.getPlayer());
            return true;
        });
        register("LavaDisk", ClickType.SHIFT_DOWN, context -> created(new LavaDisk(context.getPlayer())));
        register("MetalCable", ClickType.LEFT_CLICK, context -> created(new MetalCable(context.getPlayer())));
        register("MetalCable", ClickType.SHIFT_DOWN, context -> {
            MetalCable.attemptDestroy(context.getPlayer());
            return true;
        });
        register("EarthGuard", ClickType.LEFT_CLICK, context -> created(new EarthGuard(context.getPlayer())));
        register("EarthGuard", ClickType.SHIFT_DOWN, context -> created(new EarthGuardWall(context.getPlayer())));
        register("EarthGlove", ClickType.LEFT_CLICK, context -> created(new EarthGlove(context.getPlayer())));
        register("EarthGlove", ClickType.SHIFT_DOWN, context -> {
            EarthGlove.attemptDestroy(context.getPlayer());
            return true;
        });

        register("Combustion", ClickType.SHIFT_DOWN, context -> created(new me.moros.hyperion.abilities.firebending.Combustion(context.getPlayer())));
        register("Combustion", ClickType.LEFT_CLICK, context -> {
            me.moros.hyperion.abilities.firebending.Combustion.attemptExplode(context.getPlayer());
            return true;
        });
        register("Bolt", ClickType.SHIFT_DOWN, context -> created(new Bolt(context.getPlayer())));
        register("FlameRush", ClickType.SHIFT_DOWN, context -> created(new FlameRush(context.getPlayer())));

        register("IceBreath", ClickType.SHIFT_DOWN, context -> created(new IceBreath(context.getPlayer())));
        register("IceCrawl", ClickType.SHIFT_DOWN, context -> created(new IceCrawl(context.getPlayer())));
        register("IceCrawl", ClickType.LEFT_CLICK, context -> {
            IceCrawl.shootLine(context.getPlayer());
            if (context.getPlayer().isSneaking()) {
                IceDrill.setClicked(context.getPlayer());
            }
            return true;
        });
    }

    private static void registerChiRework() {
        if (ChiRework.plugin == null) {
            new ChiRework().onEnable();
        } else {
            CoreAbility.registerPluginAbilities(ChiRework.plugin, "me.literka.abilities");
        }

        register("TwisterPunch", ClickType.LEFT_CLICK, context -> canStart(context, TwisterPunch.class) && created(new TwisterPunch(context.getPlayer())));
        register("StickyBomb", ClickType.LEFT_CLICK, context -> canStartIgnoringCooldown(context, StickyBomb.class, false) && created(new StickyBomb(context.getPlayer())));
        register("StickyBomb", ClickType.SHIFT_DOWN, AddonAbilityActivationBootstrap::detonateStickyBombs);
        register("RopeDart", ClickType.LEFT_CLICK, context -> canStart(context, RopeDart.class) && created(new RopeDart(context.getPlayer())));
        register("WhipKick", ClickType.LEFT_CLICK, context -> canStart(context, WhipKick.class) && created(new WhipKick(context.getPlayer())));
        register("AxeKick", ClickType.LEFT_CLICK, context -> canStart(context, AxeKick.class) && created(new AxeKick(context.getPlayer())));
        register("Deflect", ClickType.LEFT_CLICK, context -> canStart(context, Deflect.class) && created(new Deflect(context.getPlayer())));
        register("Evade", ClickType.LEFT_CLICK, context -> canStart(context, Evade.class) && created(new Evade(context.getPlayer())));
        register("Bola", ClickType.LEFT_CLICK, context -> canStart(context, Bola.class) && created(new Bola(context.getPlayer())));
        register("HighJump", ClickType.LEFT_CLICK, context -> canStart(context, HighJump.class, false) && created(new HighJump(context.getPlayer(), false)));
        register("Block", ClickType.SHIFT_DOWN, context -> canStart(context, Block.class) && created(new Block(context.getPlayer())));
        register("Counter", ClickType.SHIFT_DOWN, context -> {
            BendingPlayer bPlayer = context.getBendingPlayer();
            return bPlayer != null
                    && "Evade".equalsIgnoreCase(bPlayer.getBoundAbilityName())
                    && !bPlayer.isOnCooldown("Counter")
                    && !CoreAbility.hasAbility(context.getPlayer(), Counter.class)
                    && created(new Counter(context.getPlayer()));
        });
        register("HighJump", ClickType.SHIFT_DOWN, context -> canStart(context, HighJump.class, false) && created(new HighJump(context.getPlayer(), true)));
    }

    private static void registerJedCore() {
        if (JedCore.plugin == null) {
            new JedCore().onEnable();
        } else {
            CoreAbility.registerPluginAbilities(JedCore.plugin, "com.jedk1.jedcore.ability");
        }

        registerGlobal(ClickType.LEFT_CLICK, context -> {
            if (context.getBendingPlayer() != null && context.getBendingPlayer().isToggled()) {
                new WallRun(context.getPlayer());
                return CoreAbility.hasAbility(context.getPlayer(), WallRun.class);
            }
            return false;
        });
        registerGlobal(ClickType.LEFT_CLICK, context -> {
            if (!"FireShots".equalsIgnoreCase(context.getAbilityName()) || !CoreAbility.hasAbility(context.getPlayer(), FireShots.class)) {
                return false;
            }
            FireShots.fireShot(context.getPlayer());
            context.stopProcessing();
            context.cancelEvent();
            return true;
        });
        registerGlobal(ClickType.LEFT_CLICK, AddonAbilityActivationBootstrap::fireSkiPunchActivation);

        register("AirBlade", ClickType.LEFT_CLICK, context -> created(new AirBlade(context.getPlayer())));
        register("AirPunch", ClickType.LEFT_CLICK, context -> created(new AirPunch(context.getPlayer())));
        register("AirBreath", ClickType.SHIFT_DOWN, context -> created(new AirBreath(context.getPlayer())));
        register("AirGlide", ClickType.SHIFT_DOWN, context -> created(new AirGlide(context.getPlayer())));
        register("SonicBlast", ClickType.SHIFT_DOWN, context -> created(new SonicBlast(context.getPlayer())));

        register("EarthArmor", ClickType.LEFT_CLICK, context -> created(new MetalArmor(context.getPlayer())));
        register("EarthKick", ClickType.SHIFT_DOWN, context -> created(new EarthKick(context.getPlayer())));
        register("EarthLine", ClickType.SHIFT_DOWN, context -> com.jedk1.jedcore.ability.earthbending.EarthLine.prepareLine(context.getPlayer()));
        registerGlobal(ClickType.LEFT_CLICK, AddonAbilityActivationBootstrap::shootPreparedEarthLine);
        register("EarthLine", ClickType.LEFT_CLICK, context -> {
            if (!CoreAbility.hasAbility(context.getPlayer(), EarthLine.class)) {
                return false;
            }
            EarthLine.shootLine(context.getPlayer());
            return true;
        });
        register("EarthPillar", ClickType.SHIFT_DOWN, context -> created(new EarthPillar(context.getPlayer())));
        register("EarthShard", ClickType.SHIFT_DOWN, context -> created(new EarthShard(context.getPlayer())));
        register("EarthShard", ClickType.LEFT_CLICK, context -> {
            EarthShard.throwShard(context.getPlayer());
            return true;
        });
        register("EarthSurf", ClickType.LEFT_CLICK, context -> created(new EarthSurf(context.getPlayer())));
        register("Fissure", ClickType.LEFT_CLICK, context -> created(new Fissure(context.getPlayer())));
        register("Fissure", ClickType.SHIFT_DOWN, context -> {
            Fissure.performAction(context.getPlayer());
            return true;
        });
        register("LavaDisc", ClickType.SHIFT_DOWN, context -> created(new LavaDisc(context.getPlayer())));
        register("LavaFlux", ClickType.LEFT_CLICK, context -> created(new LavaFlux(context.getPlayer())));
        register("LavaThrow", ClickType.LEFT_CLICK, context -> created(new LavaThrow(context.getPlayer())));
        register("MagnetShield", ClickType.SHIFT_DOWN, context -> created(new MagnetShield(context.getPlayer())));
        register("MetalFragments", ClickType.SHIFT_DOWN, context -> created(new MetalFragments(context.getPlayer())));
        register("MetalFragments", ClickType.LEFT_CLICK, context -> {
            MetalFragments.shootFragment(context.getPlayer());
            return true;
        });
        register("MetalHook", ClickType.LEFT_CLICK, context -> created(new MetalHook(context.getPlayer())));
        register("MetalShred", ClickType.SHIFT_DOWN, context -> created(new MetalShred(context.getPlayer())));
        register("MetalShred", ClickType.LEFT_CLICK, context -> {
            MetalShred.extend(context.getPlayer());
            return true;
        });
        register("MudSurge", ClickType.SHIFT_DOWN, context -> created(new MudSurge(context.getPlayer())));
        register("MudSurge", ClickType.LEFT_CLICK, context -> {
            MudSurge.mudSurge(context.getPlayer());
            return true;
        });
        register("SandBlast", ClickType.SHIFT_DOWN, context -> created(new SandBlast(context.getPlayer())));
        register("SandBlast", ClickType.LEFT_CLICK, context -> {
            SandBlast.blastSand(context.getPlayer());
            return true;
        });
        register("Shockwave", ClickType.SHIFT_DOWN, context -> {
            Crevice.closeCrevice(context.getPlayer());
            return true;
        });
        register("LavaFlow", ClickType.LEFT_CLICK, context -> {
            MagmaBlast.performAction(context.getPlayer());
            return true;
        });

        register("Combustion", ClickType.SHIFT_DOWN, context -> created(new Combustion(context.getPlayer())));
        register("Combustion", ClickType.LEFT_CLICK, context -> {
            Combustion.combust(context.getPlayer());
            return true;
        });
        register("Discharge", ClickType.LEFT_CLICK, context -> created(new Discharge(context.getPlayer())));
        register("FireBall", ClickType.LEFT_CLICK, context -> created(new FireBall(context.getPlayer())));
        register("FireBreath", ClickType.SHIFT_DOWN, context -> created(new FireBreath(context.getPlayer())));
        register("FireComet", ClickType.SHIFT_DOWN, context -> created(new FireComet(context.getPlayer())));
        register("FireJet", ClickType.SHIFT_DOWN, AddonAbilityActivationBootstrap::fireSki);
        register("FirePunch", ClickType.LEFT_CLICK, context -> {
            if (!JedCoreConfig.getConfig(context.getBendingPlayer()).getBoolean("Abilities.Fire.FirePunch.ActivationOnPunch")) {
                return created(new FirePunch(context.getPlayer(), null));
            }
            return false;
        });
        register("FireShots", ClickType.SHIFT_DOWN, context -> created(new FireShots(context.getPlayer())));
        register("FireShots", ClickType.LEFT_CLICK, context -> {
            FireShots.fireShot(context.getPlayer());
            return true;
        });
        register("LightningBurst", ClickType.SHIFT_DOWN, context -> created(new LightningBurst(context.getPlayer())));

        register("Bloodbending", ClickType.SHIFT_DOWN, context -> created(new Bloodbending(context.getPlayer())));
        register("Bloodbending", ClickType.LEFT_CLICK, context -> {
            Bloodbending.launch(context.getPlayer());
            return true;
        });
        register("BloodPuppet", ClickType.SHIFT_DOWN, context -> created(new BloodPuppet(context.getPlayer())));
        register("BloodPuppet", ClickType.LEFT_CLICK, context -> {
            BloodPuppet.attack(context.getPlayer());
            return true;
        });
        register("FrostBreath", ClickType.SHIFT_DOWN, context -> created(new FrostBreath(context.getPlayer())));
        register("IceClaws", ClickType.SHIFT_DOWN, context -> created(new IceClaws(context.getPlayer())));
        register("IceClaws", ClickType.LEFT_CLICK, context -> {
            IceClaws.throwClaws(context.getPlayer());
            return true;
        });
        register("IceWall", ClickType.SHIFT_DOWN, context -> created(new IceWall(context.getPlayer())));
        register("Drain", ClickType.SHIFT_DOWN, context -> created(new Drain(context.getPlayer())));
        register("Drain", ClickType.LEFT_CLICK, context -> {
            Drain.fireBlast(context.getPlayer());
            return true;
        });
        register("WakeFishing", ClickType.SHIFT_DOWN, context -> created(new WakeFishing(context.getPlayer())));
        register("WaterManipulation", ClickType.LEFT_CLICK, context -> {
            WaterGimbal.prepareBlast(context.getPlayer());
            WaterFlow.freeze(context.getPlayer());
            return true;
        });

        register("ElementSphere", ClickType.LEFT_CLICK, context -> created(new ElementSphere(context.getPlayer())));
        register("SpiritBeam", ClickType.SHIFT_DOWN, context -> created(new SpiritBeam(context.getPlayer())));
        registerMulti("ElementSphere", ClickType.LEFT_CLICK, context -> created(new ElementSphere(context.getPlayer())));
    }

    private static void registerProjectAddons() {
        if (ProjectAddons.instance == null) {
            new ProjectAddons().onEnable();
        } else {
            CoreAbility.registerPluginAbilities(ProjectAddons.instance, "me.simplicitee.project.addons.ability");
        }

        registerProjectAddonsCombos();

        registerMulti("PlantArmor", ClickType.LEFT_CLICK, context -> {
            ComboManager.addComboAbility(context.getPlayer(), ClickType.LEFT_CLICK);
            return created(new PlantArmor(context.getPlayer(), ClickType.LEFT_CLICK));
        });
        registerMulti("PlantArmor", ClickType.SHIFT_DOWN, context -> {
            ComboManager.addComboAbility(context.getPlayer(), ClickType.SHIFT_DOWN);
            return created(new PlantArmor(context.getPlayer(), ClickType.SHIFT_DOWN));
        });
        registerMulti("BloodGrip", ClickType.LEFT_CLICK, context -> {
            ComboManager.addComboAbility(context.getPlayer(), ClickType.LEFT_CLICK);
            return created(new BloodGrip(context.getPlayer(), false));
        });

        register("FireDisc", ClickType.LEFT_CLICK, context -> canUseBound(context) && created(new FireDisc(context.getPlayer())));
        register("MagmaSlap", ClickType.LEFT_CLICK, context -> canUseBound(context) && created(new MagmaSlap(context.getPlayer())));
        register("Shrapnel", ClickType.LEFT_CLICK, context -> canUseBound(context) && (context.getPlayer().isSneaking() ? created(new ShrapnelBlast(context.getPlayer())) : created(new ShrapnelShot(context.getPlayer()))));
        register("NinjaStance", ClickType.LEFT_CLICK, context -> canUseBound(context) && created(new NinjaStance(context.getPlayer())));
        register("NinjaStance", ClickType.SHIFT_DOWN, AddonAbilityActivationBootstrap::beginNinjaStealth);
        register("LavaSurge", ClickType.SHIFT_DOWN, context -> canUseBound(context) && created(new LavaSurge(context.getPlayer())));
        register("LavaSurge", ClickType.LEFT_CLICK, AddonAbilityActivationBootstrap::shootLavaSurge);
        register("GaleGust", ClickType.LEFT_CLICK, context -> canUseBound(context) && created(new GaleGust(context.getPlayer())));
        register("Accretion", ClickType.SHIFT_DOWN, context -> canUseBound(context) && created(new Accretion(context.getPlayer())));
        register("Accretion", ClickType.LEFT_CLICK, AddonAbilityActivationBootstrap::shootAccretion);
        register("Crumble", ClickType.LEFT_CLICK, context -> canUseBound(context) && created(new Crumble(context.getPlayer(), ClickType.LEFT_CLICK)));
        register("Crumble", ClickType.SHIFT_DOWN, context -> canUseBound(context) && created(new Crumble(context.getPlayer(), ClickType.SHIFT_UP)));
        register("ArcSpark", ClickType.SHIFT_DOWN, context -> canUseBound(context) && created(new ArcSpark(context.getPlayer())));
        register("ArcSpark", ClickType.LEFT_CLICK, AddonAbilityActivationBootstrap::shootArcSpark);
        register("CombustBeam", ClickType.SHIFT_DOWN, context -> canUseBound(context) && created(new CombustBeam(context.getPlayer())));
        register("CombustBeam", ClickType.LEFT_CLICK, AddonAbilityActivationBootstrap::explodeCombustBeam);
        register("Jets", ClickType.LEFT_CLICK, AddonAbilityActivationBootstrap::clickJets);
        register("Bulwark", ClickType.SHIFT_DOWN, context -> canUseBound(context) && created(new Bulwark(context.getPlayer())));
        register("Bulwark", ClickType.LEFT_CLICK, AddonAbilityActivationBootstrap::clickBulwark);
        register("SonicWave", ClickType.LEFT_CLICK, context -> canUseBound(context) && created(new SonicWave(context.getPlayer())));
        register("ChargeBolt", ClickType.SHIFT_DOWN, context -> canUseBound(context) && created(new ChargeBolt(context.getPlayer())));
        register("ChargeBolt", ClickType.LEFT_CLICK, AddonAbilityActivationBootstrap::shootChargeBolt);
        register("IceBlast", ClickType.LEFT_CLICK, AddonAbilityActivationBootstrap::clickMistShards);
        register("Whip", ClickType.LEFT_CLICK, context -> canUseBound(context) && created(new Whip(context.getPlayer())));
        register("Whip", ClickType.SHIFT_DOWN, context -> {
            Whip.source(context.getPlayer());
            return true;
        });
        register("Whip", ClickType.SHIFT_UP, context -> {
            Whip.unsource(context.getPlayer());
            Whip whip = CoreAbility.getAbility(context.getPlayer(), Whip.class);
            if (whip != null) whip.remove();
            return true;
        });
        register("EarthKick", ClickType.SHIFT_DOWN, context -> canUseBound(context) && created(new me.simplicitee.project.addons.ability.earth.EarthKick(context.getPlayer())));
        register("EnergyBeam", ClickType.SHIFT_DOWN, context -> canUseBound(context) && created(new EnergyBeam(context.getPlayer())));
        register("Explode", ClickType.SHIFT_DOWN, context -> canUseBound(context) && created(new Explode(context.getPlayer())));
        register("QuickWeld", ClickType.SHIFT_DOWN, context -> canUseBound(context) && created(new QuickWeld(context.getPlayer(), context.getPlayer().getInventory().getItemInMainHand())));
        register("RazorLeaf", ClickType.SHIFT_DOWN, context -> canUseBound(context) && created(new RazorLeaf(context.getPlayer(), true)));
        register("PlantArmor", ClickType.SHIFT_DOWN, context -> canUseBound(context) && created(new PlantArmor(context.getPlayer(), ClickType.SHIFT_DOWN)));
        register("Zephyr", ClickType.SHIFT_DOWN, context -> canUseBound(context) && created(new Zephyr(context.getPlayer())));
        register("Dig", ClickType.SHIFT_DOWN, context -> canUseBound(context) && created(new Dig(context.getPlayer())));
        register("VocalMimicry", ClickType.SHIFT_DOWN, context -> canUseBound(context) && created(new VocalMimicry(context.getPlayer())));
        register("Deafen", ClickType.SHIFT_DOWN, context -> canUseBound(context) && created(new Deafen(context.getPlayer())));
        register("BloodGrip", ClickType.SHIFT_DOWN, context -> canUseBound(context) && created(new BloodGrip(context.getPlayer(), true)));
    }

    private static void registerProjectAddonsCombos() {
        ArrayList<ComboManager.AbilityInformation> flyingKick = new ArrayList<>();
        flyingKick.add(new ComboManager.AbilityInformation("SwiftKick", ClickType.SHIFT_DOWN));
        flyingKick.add(new ComboManager.AbilityInformation("SwiftKick", ClickType.LEFT_CLICK));
        ComboManager.getComboAbilities().put("FlyingKick", new ComboManager.ComboAbilityInfo("FlyingKick", flyingKick, FlyingKick.class));
    }

    private static boolean fireSki(final ActivationContext context) {
        final BendingPlayer bPlayer = context.getBendingPlayer();
        if (FireSki.isPunchActivated(bPlayer)) {
            FireSki ski = CoreAbility.getAbility(context.getPlayer(), FireSki.class);
            if (ski != null) {
                ski.remove();
                return true;
            }
            return false;
        }
        return created(new FireSki(context.getPlayer()));
    }

    private static boolean fireSkiPunchActivation(final ActivationContext context) {
        final BendingPlayer bPlayer = context.getBendingPlayer();
        if (bPlayer == null || !"FireJet".equalsIgnoreCase(context.getAbilityName())
                || !FireSki.isPunchActivated(bPlayer) || !context.getPlayer().isSneaking()) {
            return false;
        }

        final CoreAbility fireJet = CoreAbility.getAbility("FireJet");
        if (fireJet == null || !bPlayer.canBendIgnoreCooldowns(fireJet)) {
            return false;
        }

        final FireSki ski = new FireSki(context.getPlayer());
        context.stopProcessing();
        if (ski.isStarted() && !bPlayer.isOnCooldown("FireJet")) {
            context.cancelEvent();
        }
        return ski.isStarted();
    }

    private static boolean beginNinjaStealth(final ActivationContext context) {
        NinjaStance stance = CoreAbility.getAbility(context.getPlayer(), NinjaStance.class);
        if (stance != null) {
            stance.beginStealth();
            return true;
        }
        return false;
    }

    private static boolean detonateStickyBombs(final ActivationContext context) {
        final BendingPlayer bPlayer = context.getBendingPlayer();
        final CoreAbility stickyBomb = CoreAbility.getAbility(StickyBomb.class);
        if (bPlayer == null || stickyBomb == null || !bPlayer.canBendIgnoreCooldowns(stickyBomb)) {
            return false;
        }

        final Collection<StickyBomb> stickyBombs =
                CoreAbility.getAbilities(context.getPlayer(), StickyBomb.class);
        if (stickyBombs.isEmpty()) {
            return false;
        }
        for (final StickyBomb bomb : new ArrayList<>(stickyBombs)) {
            bomb.explode();
        }
        return true;
    }

    private static boolean shootPreparedEarthLine(final ActivationContext context) {
        final BendingPlayer bPlayer = context.getBendingPlayer();
        if (bPlayer == null || !"EarthLine".equalsIgnoreCase(bPlayer.getBoundAbilityName())
                || !CoreAbility.hasAbility(context.getPlayer(), EarthLine.class)) {
            return false;
        }
        EarthLine.shootLine(context.getPlayer());
        context.stopProcessing();
        return true;
    }

    private static boolean shootLavaSurge(final ActivationContext context) {
        final LavaSurge surge = CoreAbility.getAbility(context.getPlayer(), LavaSurge.class);
        if (surge != null) {
            surge.shoot();
            return true;
        }
        return ProjectAddons.instance != null
                && !ProjectAddons.instance.getConfig(context.getBendingPlayer()).getBoolean("Abilities.Earth.LavaSurge.NormalBehavior");
    }

    private static boolean shootAccretion(final ActivationContext context) {
        Accretion accretion = CoreAbility.getAbility(context.getPlayer(), Accretion.class);
        if (accretion != null) {
            accretion.shoot();
            return true;
        }
        return false;
    }

    private static boolean shootArcSpark(final ActivationContext context) {
        ArcSpark arc = CoreAbility.getAbility(context.getPlayer(), ArcSpark.class);
        if (arc != null) {
            arc.shoot();
            return true;
        }
        return false;
    }

    private static boolean explodeCombustBeam(final ActivationContext context) {
        CombustBeam beam = CoreAbility.getAbility(context.getPlayer(), CombustBeam.class);
        if (beam != null) {
            beam.explode();
            return true;
        }
        return false;
    }

    private static boolean clickJets(final ActivationContext context) {
        Jets jets = CoreAbility.getAbility(context.getPlayer(), Jets.class);
        if (jets != null) {
            jets.clickFunction();
            return true;
        }
        return created(new Jets(context.getPlayer()));
    }

    private static boolean clickBulwark(final ActivationContext context) {
        Bulwark bulwark = CoreAbility.getAbility(context.getPlayer(), Bulwark.class);
        if (bulwark != null) {
            bulwark.clickFunction();
            return true;
        }
        return false;
    }

    private static boolean shootChargeBolt(final ActivationContext context) {
        ChargeBolt bolt = CoreAbility.getAbility(context.getPlayer(), ChargeBolt.class);
        if (bolt != null) {
            bolt.bolt();
            return true;
        }
        return false;
    }

    private static boolean clickMistShards(final ActivationContext context) {
        MistShards shards = CoreAbility.getAbility(context.getPlayer(), MistShards.class);
        if (shards != null) {
            shards.clickFunction();
            return true;
        }
        return false;
    }

    private static boolean canStart(final ActivationContext context, final Class<? extends CoreAbility> type) {
        return canStart(context, type, true);
    }

    private static boolean canStart(final ActivationContext context, final Class<? extends CoreAbility> type, final boolean requireNoActive) {
        final BendingPlayer bPlayer = context.getBendingPlayer();
        final CoreAbility ability = CoreAbility.getAbility(type);
        return bPlayer != null
                && ability != null
                && bPlayer.canBend(ability)
                && (!requireNoActive || !CoreAbility.hasAbility(context.getPlayer(), type));
    }

    private static boolean canStartIgnoringCooldown(final ActivationContext context, final Class<? extends CoreAbility> type, final boolean requireNoActive) {
        final BendingPlayer bPlayer = context.getBendingPlayer();
        final CoreAbility ability = CoreAbility.getAbility(type);
        return bPlayer != null
                && ability != null
                && bPlayer.canBendIgnoreCooldowns(ability)
                && (!requireNoActive || !CoreAbility.hasAbility(context.getPlayer(), type));
    }

    private static boolean canUseBound(final ActivationContext context) {
        final BendingPlayer bPlayer = context.getBendingPlayer();
        final CoreAbility ability = context.getBoundAbility();
        return bPlayer != null && ability != null && bPlayer.canBend(ability);
    }

    private static boolean created(final CoreAbility ability) {
        return ability != null;
    }
}
