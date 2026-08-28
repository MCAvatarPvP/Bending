package me.macieq;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.projectkorra.projectkorra.configuration.Config;
import com.projectkorra.projectkorra.configuration.PKConfigurationSection;
import me.macieq.utils.Pair;

public class MainConfig {
   private static final Map<String, Pair<Double, Integer>> abilityLavaDamage = new HashMap<>();

   public static void load() {
      Config config = FloorIsLava.plugin.getConfig();
      config.addDefault("Abilities.MagmaShot.Enabled", true);
      config.addDefault("Abilities.MagmaShot.Cooldown", 2000);
      config.addDefault("Abilities.MagmaShot.LaunchPower", 1);
      config.addDefault("Abilities.MagmaShot.Damage", 3);
      config.addDefault("Abilities.MagmaShot.LavaDamage", 0);
      config.addDefault("Abilities.MagmaShot.FireTicks", 0);
      config.addDefault("Abilities.MagmaShot.Hitbox", 1);
      config.addDefault("Abilities.MagmaShot.SourceRange", 10);
      config.addDefault("Abilities.MagmaShot.KeepSourceRange", 15);
      config.addDefault("Abilities.MagmaShot.Amount", 3);
      config.addDefault("Abilities.MagmaShot.TrailDuration", 300);
      config.addDefault("Abilities.LavaWave.Enabled", true);
      config.addDefault("Abilities.LavaWave.Cooldown", 2000);
      config.addDefault("Abilities.LavaWave.LaunchPower", 1);
      config.addDefault("Abilities.LavaWave.Damage", 2);
      config.addDefault("Abilities.LavaWave.LavaDamage", 0);
      config.addDefault("Abilities.LavaWave.FireTicks", 0);
      config.addDefault("Abilities.LavaWave.Knockback", 1);
      config.addDefault("Abilities.LavaWave.Hitbox", 1);
      config.addDefault("Abilities.LavaWave.Width", 40);
      config.addDefault("Abilities.LavaWave.Height", 30);
      config.addDefault("Abilities.LavaWave.SourceRange", 10);
      config.addDefault("Abilities.LavaWave.KeepSourceRange", 15);
      config.addDefault("Abilities.LavaWave.Amount", 5);
      config.addDefault("Abilities.LavaWave.TrailDuration", 300);
      config.addDefault("Abilities.LavaWave.AffectSelf", true);
      config.addDefault("Abilities.LavaWave.Multihits", false);
      config.addDefault("Abilities.LavaWave.RemoveOnHit", false);
      config.addDefault("Abilities.Eruption.Enabled", true);
      config.addDefault("Abilities.Eruption.Cooldown", 5000);
      config.addDefault("Abilities.Eruption.Damage", 2);
      config.addDefault("Abilities.Eruption.LavaDamage", 0);
      config.addDefault("Abilities.Eruption.FireTicks", 0);
      config.addDefault("Abilities.Eruption.Knockup", 0.7);
      config.addDefault("Abilities.Eruption.Hitbox", 1);
      config.addDefault("Abilities.Eruption.SourceRange", 10);
      config.addDefault("Abilities.Eruption.PoolCreationSpeed", 3);
      config.addDefault("Abilities.Eruption.PoolDuration", 10000);
      config.addDefault("Abilities.Eruption.TrailDuration", 750);
      config.addDefault("Abilities.Eruption.AffectSelf", true);
      config.addDefault("Abilities.Eruption.Multihits", false);
      config.addDefault("Abilities.Eruption.SmallEruption.ChargeTime", 1000);
      config.addDefault("Abilities.Eruption.SmallEruption.MagmaRadius", 3);
      config.addDefault("Abilities.Eruption.SmallEruption.LavaRadius", 1.8);
      config.addDefault("Abilities.Eruption.SmallEruption.LavaCenterRadius", 1.3);
      config.addDefault("Abilities.Eruption.SmallEruption.Height", 7);
      config.addDefault("Abilities.Eruption.BigEruption.ChargeTime", 2500);
      config.addDefault("Abilities.Eruption.BigEruption.MagmaRadius", 4);
      config.addDefault("Abilities.Eruption.BigEruption.LavaRadius", 2.4);
      config.addDefault("Abilities.Eruption.BigEruption.LavaCenterRadius", 1.8);
      config.addDefault("Abilities.Eruption.BigEruption.Height", 7);
      config.addDefault("Abilities.Eruption.OnLavaEruption.Radius", 1.8);
      config.addDefault("Abilities.Eruption.OnLavaEruption.CenterRadius", 1.3);
      config.addDefault("Abilities.Eruption.OnLavaEruption.Height", 7);
      config.addDefault("Abilities.LavaManipulation.Enabled", true);
      config.addDefault("Abilities.LavaManipulation.Cooldown", 3000);
      config.addDefault("Abilities.LavaManipulation.LaunchPower", 1);
      config.addDefault("Abilities.LavaManipulation.Damage", 2);
      config.addDefault("Abilities.LavaManipulation.LavaDamage", 0);
      config.addDefault("Abilities.LavaManipulation.LavaFireTicks", 0);
      config.addDefault("Abilities.LavaManipulation.Hitbox", 1);
      config.addDefault("Abilities.LavaManipulation.SourceRange", 15);
      config.addDefault("Abilities.LavaManipulation.Speed", 0.2);
      config.addDefault("Abilities.LavaManipulation.SneakSpeed", 0.4);
      config.addDefault("Abilities.LavaManipulation.Length", 5);
      config.addDefault("Abilities.LavaManipulation.HoldDistance", 3);
      config.addDefault("Abilities.LavaManipulation.MaxHoldDistance", 25);
      config.addDefault("Abilities.LavaManipulation.MeltRadius", 2);
      config.addDefault("Abilities.LavaManipulation.MeltTime", 1000);
      config.addDefault("Abilities.LavaManipulation.MeltDuration", 10000);
      config.addDefault("Abilities.VolcanicFlow.Enabled", true);
      config.addDefault("Abilities.VolcanicFlow.Cooldown", 2000);
      config.addDefault("Abilities.VolcanicFlow.SourceRange", 10);
      config.addDefault("Abilities.VolcanicFlow.Speed", 0.4);
      config.addDefault("Abilities.VolcanicFlow.Radius", 3);
      config.addDefault("Abilities.VolcanicFlow.MaxHeightDifference", 3);
      config.addDefault("Abilities.VolcanicFlow.FlowDuration", 4000);
      config.addDefault("Abilities.VolcanicFlow.MagmaDuration", 1000);
      config.addDefault("Abilities.VolcanicFlow.LavaDuration", 5000);
      config.addDefault("Abilities.VolcanicFlow.RequiresLavaSource", true);
      config.addDefault("Abilities.VolcanicFlow.RequiresSneaking", true);
      config.addDefault("Abilities.VolcanicFlow.LavaDamage", 0);
      config.addDefault("Abilities.VolcanicFlow.LavaFireTicks", 0);
      config.addDefault("Abilities.LavaMortar.Enabled", true);
      config.addDefault("Abilities.LavaMortar.Cooldown", 2000);
      config.addDefault("Abilities.LavaMortar.LaunchPower", 1.1);
      config.addDefault("Abilities.LavaMortar.Gravity", 0.05);
      config.addDefault("Abilities.LavaMortar.MinDamage", 1);
      config.addDefault("Abilities.LavaMortar.MaxDamage", 3);
      config.addDefault("Abilities.LavaMortar.LavaDamage", 0);
      config.addDefault("Abilities.LavaMortar.FireTicks", 0);
      config.addDefault("Abilities.LavaMortar.SourceRadius", 5);
      config.addDefault("Abilities.LavaMortar.SourceSpeed", 0.2);
      config.addDefault("Abilities.LavaMortar.ManipulationSpeed", 0.1);
      config.addDefault("Abilities.LavaMortar.MaxRadius", (double)1.5F);
      config.addDefault("Abilities.LavaMortar.SourceInterval", 1000);
      config.addDefault("Abilities.LavaMortar.RadiusGainPerSource", (double)0.375F);
      config.addDefault("Abilities.LavaMortar.MinPoolRadius", 2);
      config.addDefault("Abilities.LavaMortar.MaxPoolRadius", 4);
      config.addDefault("Abilities.LavaMortar.MagmaDuration", 500);
      config.addDefault("Abilities.LavaMortar.LavaDuration", 5000);
      List<String> defaultExample = List.of("start here");
      config.addDefault("LavaDamage", defaultExample);
      if (!config.isSet("LavaDamage")) {
         config.set("LavaDamage", defaultExample);
      }

      loadLavaDamage(config);
      config.options().copyDefaults(true);
      config.save();
   }

   public static PKConfigurationSection getConfig() {
      return FloorIsLava.plugin.getConfig();
   }

   private static void loadLavaDamage(Config config) {
      abilityLavaDamage.clear();

      for(String s : config.getStringList("LavaDamage")) {
         String[] args = s.split(", ");
         if (args.length >= 3 && !args[0].isEmpty() && !args[1].isEmpty() && !args[2].isEmpty()) {
            String ability = args[0].toLowerCase();

            try {
               double damage = Double.parseDouble(args[1]);
               int fireticks = Integer.parseInt(args[2]);
               abilityLavaDamage.putIfAbsent(ability, Pair.of(damage, fireticks));
            } catch (NumberFormatException var8) {
               FloorIsLava.log.warning("Wrong LavaDamage format in config.yml");
            }
         }
      }

   }

   public static Pair<Double, Integer> getPair(String ability) {
      return abilityLavaDamage.get(ability.toLowerCase());
   }
}
