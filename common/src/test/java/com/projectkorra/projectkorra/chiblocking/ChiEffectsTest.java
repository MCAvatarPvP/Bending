package com.projectkorra.projectkorra.chiblocking;

import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.Particle;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.inventory.ItemStack;
import com.projectkorra.projectkorra.platform.mc.inventory.PlayerInventory;
import com.projectkorra.projectkorra.support.AbilityWorld;
import me.literka.abilities.DaggerThrow;
import org.junit.jupiter.api.Test;
import org.objenesis.ObjenesisStd;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChiEffectsTest {
    @Test void smokeBatchesTheSameGustsAtEachImpactPointForEachViewer() throws Exception {
        try (var ignored = new AbilityWorld()) {
            var viewers = new ArrayList<Player>();
            var world = new World() {
                @Override public List<Player> getPlayers() { return viewers; }
            };
            var calls = new ArrayList<Location>();
            var viewer = new Player() {
                @Override public World getWorld() { return world; }
                @Override public <T> void spawnParticle(Particle type, Location at, int count,
                        double x, double y, double z, double speed, T data, boolean force) {
                    assertEquals(Particle.SMALL_GUST, type);
                    assertEquals(32, count);
                    assertEquals(.5, x); assertEquals(.5, y); assertEquals(.5, z);
                    calls.add(at.clone());
                }
            };
            viewers.add(viewer);
            var impact = new Location(world, 12.5, 34.5, -8.5);
            Smokescreen.playEffect(impact);
            assertEquals(125, calls.size());
            assertEquals(4_000, calls.size() * 32);
            assertEquals(impact.clone().add(-2, 0, -2), calls.getFirst());
            assertTrue(calls.stream().allMatch(point -> point.getY() == impact.getY()
                    && Math.abs(point.getX() - impact.getX()) <= 2 && Math.abs(point.getZ() - impact.getZ()) <= 2));
        }
    }

    @Test void cancelledAbilityDamageDoesNotConsumeTheCastersNextPhysicalHit() throws Exception {
        try (var world = new AbilityWorld()) {
            var caster = world.player(1, 2, 3);
            var listener = new me.literka.ChiListener();
            var dart = new ObjenesisStd().newInstance(DaggerThrow.class);
            var damage = new com.projectkorra.projectkorra.event.AbilityDamageEntityEvent(
                    new com.projectkorra.projectkorra.platform.mc.entity.Entity(), caster, dart, 1, false);
            damage.setCancelled(true);
            listener.onAbilityHit(damage);
            var bomb = new com.projectkorra.projectkorra.platform.mc.entity.ShulkerBullet() {
                @Override public boolean hasMetadata(String key) { return key.equals("stickybomb"); }
            };
            var hit = new com.projectkorra.projectkorra.platform.mc.event.entity.EntityDamageByEntityEvent(caster, bomb,
                    com.projectkorra.projectkorra.platform.mc.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK, null, 1);
            listener.onEntityHit(hit);
            assertTrue(hit.isCancelled(), "the Chi listener still handled the subsequent attack against the bomb");
        }
    }

    @Test void daggerConsumesOneArrowFromTheActualSlotIncludingTheOffHand() throws Exception {
        try (var ignored = new AbilityWorld()) {
            var inventory = new PlayerInventory() {
                @Override public ItemStack getItem(int slot) {
                    var stack = super.getItem(slot);
                    return stack == null ? null : stack.clone();
                }
            };
            inventory.setContents(new ItemStack[41]);
            inventory.setItem(1, new ItemStack(Material.STONE, 3));
            var arrow = new ItemStack(Material.ARROW, 2);
            inventory.setItem(40, arrow);
            var owner = new Player() {
                @Override public PlayerInventory getInventory() { return inventory; }
            };
            var dagger = new ObjenesisStd().newInstance(DaggerThrow.class);
            var field = CoreAbility.class.getDeclaredField("player");
            field.setAccessible(true);
            field.set(dagger, owner);
            assertTrue(dagger.removeArrowFromInventory());
            assertEquals(1, inventory.getItem(40).getAmount());
            assertTrue(dagger.removeArrowFromInventory());
            assertNull(inventory.getItem(40));
            assertFalse(dagger.removeArrowFromInventory());
            assertEquals(3, inventory.getItem(1).getAmount());
        }
    }
}
