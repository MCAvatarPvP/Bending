package me.literka;

import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.configuration.Config;
import com.projectkorra.projectkorra.configuration.PKConfiguration;
import com.projectkorra.projectkorra.platform.mc.event.HandlerList;
import com.projectkorra.projectkorra.platform.mc.plugin.java.JavaPlugin;
import me.literka.util.Utils;

import java.io.File;
import java.util.List;

public class ChiRework implements JavaPlugin {

    public static ChiRework plugin;
    public static Config config;
    public static String name = "ChiRework";
    public static String version = "1.0";
    public static String authors = "Literka (Code), Rakion (Concept), Magikas (Concept)";
    public static Element MODERN_CHI;

    public static PKConfiguration config() {
        return config.get();
    }

    @Override
    public void onEnable() {
        MODERN_CHI = new Element("MartialArts", Element.ElementType.NO_SUFFIX);
        plugin = this;
        config = new Config(new File("ChiRework", "chi-rework.yml"));
        setupConfig();

        CoreAbility.registerPluginAbilities(this, "me.literka.abilities");
        getServer().getPluginManager().registerEvents(new ChiListener(), this);
        getServer().getScheduler().scheduleSyncRepeatingTask(this, Utils::tick, 0, 1);
    }

    @Override
    public void onDisable() {
        HandlerList.unregisterAll(this);
    }

    public void setupConfig() {
        PKConfiguration c = config();

        c.addDefault("Chat.Colors.Air", "GOLD");
        c.addDefault("General.IgnoreCooldownWhenAbility", List.of("SomeAbility", "WallRun"));
        c.addDefault("General.AllowHitsOnBlock", false);

        c.addDefault("Abilities.Uppercut.Enabled", true);
        c.addDefault("Abilities.Uppercut.Cooldown", 1000);
        c.addDefault("Abilities.Uppercut.SwingCooldown", 1000);
        c.addDefault("Abilities.Uppercut.BlockDuration", 1500);
        c.addDefault("Abilities.Uppercut.BlockChance", 20.0);
        c.addDefault("Abilities.Uppercut.Damage", 2);
        c.addDefault("Abilities.Uppercut.Height", 7.5625);
        c.addDefault("Abilities.Uppercut.AnimationSpeed", 0.3);
        c.addDefault("Abilities.Uppercut.FallDamage", false);
        c.addDefault("Abilities.Uppercut.AllowFlyingKickFusion", true);
        c.addDefault("Abilities.Uppercut.LagCompensation.Enabled", true);
        c.addDefault("Abilities.Uppercut.LagCompensation.SyncForBothParties", true);
        c.addDefault("Abilities.Uppercut.LagCompensation.CompensateSelfVelocity", true);
        c.addDefault("Abilities.Uppercut.LagCompensation.IncludeTargetPing", true);
        c.addDefault("Abilities.Uppercut.LagCompensation.PingPerTick", 100.0);
        c.addDefault("Abilities.Uppercut.LagCompensation.MaxCompensationTicks", 3);

        c.addDefault("Abilities.HeelCrash.Enabled", true);
        c.addDefault("Abilities.HeelCrash.Cooldown", 1000);
        c.addDefault("Abilities.HeelCrash.BlockDuration", 1000);
        c.addDefault("Abilities.HeelCrash.BlockChance", 20.0);
        c.addDefault("Abilities.HeelCrash.SwingCooldown", 1000);
        c.addDefault("Abilities.HeelCrash.HitDamage", 2);
        c.addDefault("Abilities.HeelCrash.ExplosionDamage", 2);
        c.addDefault("Abilities.HeelCrash.ExplosionRadius", 2);
        c.addDefault("Abilities.HeelCrash.ExplosionBlastPower", 5);
        c.addDefault("Abilities.HeelCrash.HeightPush", -2);
        c.addDefault("Abilities.HeelCrash.ForwardPush", 0.5);
        c.addDefault("Abilities.HeelCrash.RequiredGroundDistance", 4);

        c.addDefault("Abilities.TwisterPunch.Enabled", true);
        c.addDefault("Abilities.TwisterPunch.Cooldown", 1000);
        c.addDefault("Abilities.TwisterPunch.BlockDuration", 1000);
        c.addDefault("Abilities.TwisterPunch.BlockChance", 25.0);
        c.addDefault("Abilities.TwisterPunch.FallDamage", false);
        c.addDefault("Abilities.TwisterPunch.Damage", 2);
        c.addDefault("Abilities.TwisterPunch.Speed", 4);
        c.addDefault("Abilities.TwisterPunch.SpinRadius", 1.5);
        c.addDefault("Abilities.TwisterPunch.SpinRadiusEnd", 0.7);
        c.addDefault("Abilities.TwisterPunch.SpinRadiusSteps", 6);
        c.addDefault("Abilities.TwisterPunch.SpinStep", 10);
        c.addDefault("Abilities.TwisterPunch.HitRadiusHorizontal", 0.7);
        c.addDefault("Abilities.TwisterPunch.HitRadiusVertical", 1);
        c.addDefault("Abilities.TwisterPunch.RequiredGroundDistance.Self", 4);
        c.addDefault("Abilities.TwisterPunch.RequiredGroundDistance.Others", 4);
        c.addDefault("Abilities.TwisterPunch.ExplosionDamage", 2);
        c.addDefault("Abilities.TwisterPunch.ExplosionRadius", 2);
        c.addDefault("Abilities.TwisterPunch.ExplosionBlastPower", 5);
        c.addDefault("Abilities.TwisterPunch.Push.Self", 0.5);
        c.addDefault("Abilities.TwisterPunch.Push.Others", 2);

        c.addDefault("Abilities.AxeKick.Enabled", true);
        c.addDefault("Abilities.AxeKick.Cooldown", 1000);
        c.addDefault("Abilities.AxeKick.BlockDuration", 1000);
        c.addDefault("Abilities.AxeKick.BlockChance", 20.0);
        c.addDefault("Abilities.AxeKick.Damage", 2);
        c.addDefault("Abilities.AxeKick.RadiusHorizontal", 0.2);
        c.addDefault("Abilities.AxeKick.RadiusVertical", 0.2);
        c.addDefault("Abilities.AxeKick.Length", 4);
        c.addDefault("Abilities.AxeKick.Angle", 70);
        c.addDefault("Abilities.AxeKick.AngleStep", 5);
        c.addDefault("Abilities.AxeKick.Speed", 2);
        c.addDefault("Abilities.AxeKick.Push.Self", 0.5);
        c.addDefault("Abilities.AxeKick.Push.Others", 0.5);
        c.addDefault("Abilities.AxeKick.FallDamage", false);

        c.addDefault("Abilities.RopeDart.Enabled", true);
        c.addDefault("Abilities.RopeDart.Cooldown", 1000);
        c.addDefault("Abilities.RopeDart.ShootPower", 2);
        c.addDefault("Abilities.RopeDart.PullSpeed", 1);
        c.addDefault("Abilities.RopeDart.PullSpeedOthers", 1);
        c.addDefault("Abilities.RopeDart.MaxRange", 25);
        c.addDefault("Abilities.RopeDart.MaxRangeRemove", 30);
        c.addDefault("Abilities.RopeDart.MinRangeRemove", 1.3);
        c.addDefault("Abilities.RopeDart.PullYourselfToEnemy", false);
        c.addDefault("Abilities.RopeDart.CancelOnHit", false);
        c.addDefault("Abilities.RopeDart.CancellingAbilities", List.of("WhipKick", "FlyingKick", "HighJump", "StickyBomb"));
        c.addDefault("Abilities.RopeDart.CancelVelocityAbilities", List.of("AirSwipe", "AirBreath", "AirBlast"));
        c.addDefault("Abilities.RopeDart.OldCooldown", 1000);
        c.addDefault("Abilities.RopeDart.OldShootPower", 2);
        c.addDefault("Abilities.RopeDart.OldPullSpeed", 1);
        c.addDefault("Abilities.RopeDart.OldPullSpeedOthers", 1);
        c.addDefault("Abilities.RopeDart.OldMaxRange", 25);
        c.addDefault("Abilities.RopeDart.OldMaxRangeRemove", 30);
        c.addDefault("Abilities.RopeDart.OldMinRangeRemove", 1.3);
        c.addDefault("Abilities.RopeDart.OldPullYourselfToEnemy", false);
        c.addDefault("Abilities.RopeDart.OldCancelOnHit", false);
        c.addDefault("Abilities.RopeDart.OldCancellingAbilities", List.of("WhipKick", "FlyingKick", "HighJump", "StickyBomb"));
        c.addDefault("Abilities.RopeDart.OldCancelVelocityAbilities", List.of("AirSwipe", "AirBreath", "AirBlast"));

        c.addDefault("Abilities.StickyBomb.Enabled", true);
        c.addDefault("Abilities.StickyBomb.Cooldown", 1000);
        c.addDefault("Abilities.StickyBomb.Duration", 10000);
        c.addDefault("Abilities.StickyBomb.ThrowDelay", 250);
        c.addDefault("Abilities.StickyBomb.Speed", 1);
        c.addDefault("Abilities.StickyBomb.Radius", 3);
        c.addDefault("Abilities.StickyBomb.Limit", 2);
        c.addDefault("Abilities.StickyBomb.ExplosionPush", 2);
        c.addDefault("Abilities.StickyBomb.Damage.Self", 2);
        c.addDefault("Abilities.StickyBomb.Damage.Others", 2);
        c.addDefault("Abilities.StickyBomb.Damage.Sticked.Self", 2);
        c.addDefault("Abilities.StickyBomb.Damage.Sticked.Others", 2);
        c.addDefault("Abilities.StickyBomb.FallDamage", false);
        c.addDefault("Abilities.StickyBomb.CancelVelocityAbilities", List.of("AirSwipe", "AirBreath", "AirBlast"));

        c.addDefault("Abilities.WhipKick.Enabled", true);
        c.addDefault("Abilities.WhipKick.Damage", 3);
        c.addDefault("Abilities.WhipKick.Range", 8);
        c.addDefault("Abilities.WhipKick.Speed", 1.3);
        c.addDefault("Abilities.WhipKick.Cooldown", 1000);
        c.addDefault("Abilities.WhipKick.BlockDuration", 1000);
        c.addDefault("Abilities.WhipKick.BlockChance", 20.0);
        c.addDefault("Abilities.WhipKick.FallDamage", false);
        c.addDefault("Abilities.WhipKick.PushOthers", 1);
        c.addDefault("Abilities.WhipKick.StopVelocityInEnd", true);
        c.addDefault("Abilities.WhipKick.OnlyOnGround", false);

        c.addDefault("Abilities.Counter.Enabled", true);
        c.addDefault("Abilities.Counter.Cooldown", 1000);
        c.addDefault("Abilities.Counter.Duration", 1000);
        c.addDefault("Abilities.Counter.StunDuration", 1000);
        c.addDefault("Abilities.Counter.ActiveMessage", "&#FFC02BCounter is active");
        c.addDefault("Abilities.Counter.HitMessage", "&#FFC02B*Stunned by Counter*");

        c.addDefault("Abilities.Block.Enabled", true);
        c.addDefault("Abilities.Block.Cooldown", 1000);
        c.addDefault("Abilities.Block.Duration", 1000);
        c.addDefault("Abilities.Block.DamageMultiplier", 0.5);
        c.addDefault("Abilities.Block.CannotBlock", List.of("FireBlastCharged", "EarthSmash", "Torrent"));

        c.addDefault("Abilities.Deflect.Enabled", true);
        c.addDefault("Abilities.Deflect.Cooldown", 1000);
        c.addDefault("Abilities.Deflect.Duration", 1000);
        c.addDefault("Abilities.Deflect.Power", 0.1);

        c.addDefault("Abilities.Evade.Enabled", true);
        c.addDefault("Abilities.Evade.Cooldown", 1000);
        c.addDefault("Abilities.Evade.MaxDuration", 2000);
        c.addDefault("Abilities.Evade.IgnoreAbilities", List.of("FirstAbil", "SecondAbil"));
        c.addDefault("Abilities.Evade.WarningMessage", "&#FFC02BEvade active");
        c.addDefault("Abilities.Evade.SuccessMessage", "&#FFC02BEvaded");
        c.addDefault("Abilities.Evade.FailMessage", "&#FFC02BFailed to Evade");

        c.addDefault("Abilities.Gouge.Enabled", true);
        c.addDefault("Abilities.Gouge.Cooldown", 1000);
        c.addDefault("Abilities.Gouge.SwingCooldown", 1000);
        c.addDefault("Abilities.Gouge.Duration", 1000);
        c.addDefault("Abilities.Gouge.MinHitDistance", 2);
        c.addDefault("Abilities.Gouge.Damage", 2);

        c.addDefault("Abilities.Headbutt.Enabled", true);
        c.addDefault("Abilities.Headbutt.Cooldown", 1000);
        c.addDefault("Abilities.Headbutt.SwingCooldown", 1000);
        c.addDefault("Abilities.Headbutt.Speed", 2);
        c.addDefault("Abilities.Headbutt.Damage.Self", 2);
        c.addDefault("Abilities.Headbutt.Damage.Others", 2);
        c.addDefault("Abilities.Headbutt.BlockChance.Self", 100.0);
        c.addDefault("Abilities.Headbutt.BlockChance.Others", 100.0);
        c.addDefault("Abilities.Headbutt.BlockDuration.Self", 1000);
        c.addDefault("Abilities.Headbutt.BlockDuration.Others", 1000);
        c.addDefault("Abilities.Headbutt.Stun.Self", 1000);
        c.addDefault("Abilities.Headbutt.Stun.Others", 1000);
        c.addDefault("Abilities.Headbutt.Message.Self", "&#FFC02B*Stunned for {current_stun_time}*");
        c.addDefault("Abilities.Headbutt.Message.Others", "&#FFC02B*Stunned for {current_stun_time}*");

        c.addDefault("Abilities.QuickStrike.Enabled", true);
        c.addDefault("Abilities.QuickStrike.Damage", 2);
        c.addDefault("Abilities.QuickStrike.Cooldown", 3000);
        c.addDefault("Abilities.QuickStrike.SwingCooldown", 1000);
        c.addDefault("Abilities.QuickStrike.BlockChance", 10.0);
        c.addDefault("Abilities.QuickStrike.BlockDuration", 1000);

        c.addDefault("Abilities.RapidPunch.Enabled", true);
        c.addDefault("Abilities.RapidPunch.Damage", 1);
        c.addDefault("Abilities.RapidPunch.Cooldown", 6000);
        c.addDefault("Abilities.RapidPunch.SwingCooldown", 1000);
        c.addDefault("Abilities.RapidPunch.Punches", 3);
        c.addDefault("Abilities.RapidPunch.Interval", 100);
        c.addDefault("Abilities.RapidPunch.BlockChance", 25.0);
        c.addDefault("Abilities.RapidPunch.BlockDuration", 1000);
        c.addDefault("Abilities.RapidPunch.FallDamage", false);
        c.addDefault("Abilities.RapidPunch.Push.Horizontal", 0.6);
        c.addDefault("Abilities.RapidPunch.Push.Vertical", 0.2);

        c.addDefault("Abilities.SwiftKick.Enabled", true);
        c.addDefault("Abilities.SwiftKick.Damage", 1);
        c.addDefault("Abilities.SwiftKick.Cooldown", 6000);
        c.addDefault("Abilities.SwiftKick.SwingCooldown", 1000);
        c.addDefault("Abilities.SwiftKick.BlockChance", 15.0);
        c.addDefault("Abilities.SwiftKick.BlockDuration", 1000);
        c.addDefault("Abilities.SwiftKick.FallDamage", false);
        c.addDefault("Abilities.SwiftKick.Push.Self", 0.6);
        c.addDefault("Abilities.SwiftKick.Push.Others", 0.6);

        c.addDefault("Abilities.Backstab.Enabled", true);
        c.addDefault("Abilities.Backstab.Damage", 1);
        c.addDefault("Abilities.Backstab.Cooldown", 6000);
        c.addDefault("Abilities.Backstab.SwingCooldown", 1000);
        c.addDefault("Abilities.Backstab.BlockChance", 100.0);
        c.addDefault("Abilities.Backstab.BlockDuration", 1000);
        c.addDefault("Abilities.Backstab.MaxActivationAngle", 90);

        c.addDefault("Abilities.DaggerThrow.Enabled", true);
        c.addDefault("Abilities.DaggerThrow.Cooldown", 3000);
        c.addDefault("Abilities.DaggerThrow.MaxDaggers.Enabled", true);
        c.addDefault("Abilities.DaggerThrow.MaxDaggers.Amount", 6);
        c.addDefault("Abilities.DaggerThrow.Damage", 1.0);
        c.addDefault("Abilities.DaggerThrow.Push", 0.1);
        c.addDefault("Abilities.DaggerThrow.ParticleTrail", true);
        c.addDefault("Abilities.DaggerThrow.CollisionRadius", 0.5);
        c.addDefault("Abilities.DaggerThrow.RequiresArrows", false);
        c.addDefault("Abilities.DaggerThrow.Interactions.WaterSpout.Enabled", true);
        c.addDefault("Abilities.DaggerThrow.Interactions.WaterSpout.Cooldown", 1000);
        c.addDefault("Abilities.DaggerThrow.Interactions.WaterSpout.HitsRequired", 1);
        c.addDefault("Abilities.DaggerThrow.Interactions.AirSpout.Enabled", true);
        c.addDefault("Abilities.DaggerThrow.Interactions.AirSpout.Cooldown", 1000);
        c.addDefault("Abilities.DaggerThrow.Interactions.AirSpout.HitsRequired", 1);

        c.addDefault("Abilities.Bola.Enabled", true);
        c.addDefault("Abilities.Bola.Cooldown", 6000);
        c.addDefault("Abilities.Bola.Range", 20);
        c.addDefault("Abilities.Bola.Radius", 1.5);
        c.addDefault("Abilities.Bola.BlockAbilities.AirBlast", 1000);

        c.addDefault("Abilities.HighJump.Enabled", true);
        c.addDefault("Abilities.HighJump.ShowParticles", true);
        c.addDefault("Abilities.HighJump.Jump.Enabled", true);
        c.addDefault("Abilities.HighJump.Jump.Cooldown", 3000);
        c.addDefault("Abilities.HighJump.Jump.Height", 1.0);
        c.addDefault("Abilities.HighJump.Jump.ExtinguishFireTick", false);
        c.addDefault("Abilities.HighJump.DoubleJump.Enabled", true);
        c.addDefault("Abilities.HighJump.DoubleJump.Cooldown", 2000);
        c.addDefault("Abilities.HighJump.DoubleJump.Height", 1.0);
        c.addDefault("Abilities.HighJump.DoubleJump.ExtinguishFireTick", false);
        c.addDefault("Abilities.HighJump.Lunge.Enabled", true);
        c.addDefault("Abilities.HighJump.Lunge.Cooldown", 5000);
        c.addDefault("Abilities.HighJump.Lunge.Height", 1.0);
        c.addDefault("Abilities.HighJump.Lunge.Distance", 2.0);
        c.addDefault("Abilities.HighJump.Lunge.ExtinguishFireTick", false);
        c.addDefault("Abilities.HighJump.Evade.Enabled", true);
        c.addDefault("Abilities.HighJump.Evade.Cooldown", 5000);
        c.addDefault("Abilities.HighJump.Evade.Height", 1.0);
        c.addDefault("Abilities.HighJump.Evade.Distance", 2.0);
        c.addDefault("Abilities.HighJump.Evade.ExtinguishFireTick", false);

        c.addDefault("Language.Uppercut.Description", "");
        c.addDefault("Language.Uppercut.Instructions", "");

        c.addDefault("Language.HeelCrash.Description", "");
        c.addDefault("Language.HeelCrash.Instructions", "");

        c.addDefault("Language.TwisterPunch.Description", "");
        c.addDefault("Language.TwisterPunch.Instructions", "");

        c.addDefault("Language.AxeKick.Description", "Execute a dynamic arc kick, dealing damage to your opponent while propelling both you and the enemy into the air.");
        c.addDefault("Language.AxeKick.Instructions", "");

        c.addDefault("Language.RopeDart.Description", "");
        c.addDefault("Language.RopeDart.Instructions", "");

        c.addDefault("Language.StickyBomb.Description", "");
        c.addDefault("Language.StickyBomb.Instructions", "");

        c.addDefault("Language.WhipKick.Description", "");
        c.addDefault("Language.WhipKick.Instructions", "");

        c.addDefault("Language.Counter.Description", "");
        c.addDefault("Language.Counter.Instructions", "");

        c.addDefault("Language.Block.Description", "");
        c.addDefault("Language.Block.Instructions", "");

        c.addDefault("Language.Deflect.Description", "");
        c.addDefault("Language.Deflect.Instructions", "");

        c.addDefault("Language.Evade.Description", "");
        c.addDefault("Language.Evade.Instructions", "");

        c.addDefault("Language.Gouge.Description", "");
        c.addDefault("Language.Gouge.Instructions", "");

        c.addDefault("Language.Headbutt.Description", "");
        c.addDefault("Language.Headbutt.Instructions", "");

        c.addDefault("Language.QuickStrike.Description", "");
        c.addDefault("Language.QuickStrike.Instructions", "");

        c.addDefault("Language.RapidPunch.Description", "");
        c.addDefault("Language.RapidPunch.Instructions", "");

        c.addDefault("Language.SwiftKick.Description", "");
        c.addDefault("Language.SwiftKick.Instructions", "");

        c.addDefault("Language.Backstab.Description", "Exploit vulnerability by striking the enemy's back for devastating damage. Requires precise positioning and timing. Turn the tide of battle with this stealthy and lethal ability.");
        c.addDefault("Language.Backstab.Instructions", "");

        c.addDefault("Language.DaggerThrow.Description", "Unleash swift and deadly precision as you hurl a flurry of razor-sharp daggers at your foes");
        c.addDefault("Language.DaggerThrow.Instructions", "");

        c.addDefault("Language.Bola.Description", "");
        c.addDefault("Language.Bola.Instructions", "");

        c.addDefault("Language.HighJump.Description", "Chiblockers are skilled acrobats and this HighJump Replacement satisfies those abilities! Now, you can lunge forward, lunge backwards, activate a double jump, or use the classic HighJump if you so desire!");
        c.addDefault("Language.HighJump.Instructions", "Left-Click: Jump up. Tap-Shift: Lunge backwards. Spint+Click: Lunge Forwards. Tap-Shift in the air: Double Jump");

        config.save();
    }
}
