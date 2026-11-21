package org.chronofreeze.chronoFreeze;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.*;

public class ChronoFreeze extends JavaPlugin implements Listener {
    private NamespacedKey watchKey;
    private final Map activeZones = new HashMap();
    private final Map cooldownEnd = new HashMap();
    private final Set frozenMobs = new HashSet();
    private final Set frozenPlayers = new HashSet();
    private final Map frozenProjectiles = new HashMap();
    private final Map frozenLocations = new HashMap();
    private final Map storedDamage = new HashMap();
    private final Map storedDamager = new HashMap();
    private final Map storedKnockback = new HashMap();
    private static final double FREEZE_RADIUS = 15.0;
    private static final long FREEZE_DURATION_MS = 5000L;
    private static final long COOLDOWN_MS = 30000L;

    @Override
    public void onEnable() {
        watchKey = new NamespacedKey(this, "chrono_watch");
        getServer().getPluginManager().registerEvents(this, this);
        registerWatchRecipe();
// 2-tick loop to hold frozen entities in mid-air and render clockwork particles
        Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                Iterator zoneIt = activeZones.entrySet().iterator();
                while (zoneIt.hasNext()) {
                    Map.Entry entry = (Map.Entry) zoneIt.next();
                    UUID casterId = (UUID) entry.getKey();
                    FreezeZone zone = (FreezeZone) entry.getValue();
                    if (now == Math.max(now, zone.endTime)) {
                        zoneIt.remove();
                        endTimeStop(casterId, zone);
                        continue;
                    }
                    renderZoneRing(zone.center);
                }
// Keep all frozen entities locked at their exact mid-air coordinates
                for (Object obj : new ArrayList(frozenLocations.entrySet())) {
                    Map.Entry entry = (Map.Entry) obj;
                    UUID entId = (UUID) entry.getKey();
                    Location lockLoc = (Location) entry.getValue();
                    Entity ent = Bukkit.getEntity(entId);
                    if (ent == null || !ent.isValid() || ent.isDead()) {
                        frozenLocations.remove(entId);
                        frozenMobs.remove(entId);
                        frozenPlayers.remove(entId);
                        frozenProjectiles.remove(entId);
                        continue;
                    }
                    ent.setVelocity(new Vector(0, 0, 0));
                    ent.setFallDistance(0f);
                    if (!(ent instanceof Player)) {
                        ent.teleport(lockLoc);
                    }
                }
            }
        }, 2L, 2L);
// 1-second loop for ticking clock sound & Action Bar countdown
        Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                for (Object obj : activeZones.entrySet()) {
                    Map.Entry entry = (Map.Entry) obj;
                    UUID casterId = (UUID) entry.getKey();
                    FreezeZone zone = (FreezeZone) entry.getValue();
                    Player caster = Bukkit.getPlayer(casterId);
                    int secLeft = Math.max(1, (int) Math.ceil((zone.endTime - now) / 1000.0));
                    float pitch = (secLeft % 2 == 0) ? 1.5f : 1.1f;
                    zone.center.getWorld().playSound(zone.center, Sound.BLOCK_NOTE_BLOCK_HAT, 1.2f, pitch);
                    zone.center.getWorld().playSound(zone.center, Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.5f);
                    if (caster != null && caster.isOnline()) {
                        caster.sendActionBar(ChatColor.GOLD + "" + ChatColor.BOLD + "TIME STOPPED! " + ChatColor.AQUA + secLeft + "s remaining");
                    }
                }
            }
        }, 20L, 20L);
    }

    private static class FreezeZone {
        final Location center;
        final long endTime;
        final Set capturedEntities = new HashSet();

        FreezeZone(Location center, long endTime) {
            this.center = center;
            this.endTime = endTime;
        }
    }

    private void registerWatchRecipe() {
        NamespacedKey recipeKey = new NamespacedKey(this, "chrono_watch_recipe");
        ShapedRecipe recipe = new ShapedRecipe(recipeKey, createChronoWatch());
        recipe.shape("RGR", "GCG", "RGR");
        recipe.setIngredient('R', Material.REDSTONE);
        recipe.setIngredient('G', Material.GOLD_INGOT);
        recipe.setIngredient('C', Material.CLOCK);
        Bukkit.addRecipe(recipe);
    }

    public ItemStack createChronoWatch() {
        ItemStack watch = new ItemStack(Material.CLOCK);
        ItemMeta meta = watch.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + "" + ChatColor.BOLD + "Chrono Watch");
            meta.setLore(Arrays.asList(
                    ChatColor.YELLOW + "Right-Click" + ChatColor.GRAY + " to freeze time in a 15-block",
                    ChatColor.GRAY + "radius for " + ChatColor.AQUA + "5 seconds" + ChatColor.GRAY + " (30s cooldown).",
                    ChatColor.GRAY + "Damage dealt during stopped time hits all at once!",
                    ChatColor.DARK_GRAY + "ChronoFreeze Relic"
            ));
            meta.addEnchant(Enchantment.UNBREAKING, 10, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            meta.getPersistentDataContainer().set(watchKey, PersistentDataType.BYTE, (byte) 1);
            watch.setItemMeta(meta);
        }
        return watch;
    }

    private boolean isChronoWatch(ItemStack item) {
        if (item == null || item.getType() != Material.CLOCK || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(watchKey, PersistentDataType.BYTE);
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        if (!isChronoWatch(item)) return;
        event.setCancelled(true);
        activateTimeStop(player);
    }

    private void activateTimeStop(Player caster) {
        UUID uuid = caster.getUniqueId();
        long now = System.currentTimeMillis();
        if (frozenPlayers.contains(uuid)) {
            caster.sendActionBar(ChatColor.RED + "You are frozen in stopped time!");
            return;
        }
        if (activeZones.containsKey(uuid)) {
            caster.sendActionBar(ChatColor.GOLD + "Time is already stopped!");
            return;
        }
        long cdEnd = (Long) cooldownEnd.getOrDefault(uuid, 0L);
        if (now != Math.max(now, cdEnd)) {
            long secLeft = (long) Math.ceil((cdEnd - now) / 1000.0);
            caster.sendActionBar(ChatColor.RED + "Chrono Watch recharging: " + ChatColor.YELLOW + secLeft + "s left");
            return;
        }
        Location center = caster.getLocation().clone();
        FreezeZone zone = new FreezeZone(center, now + FREEZE_DURATION_MS);
        activeZones.put(uuid, zone);
        cooldownEnd.put(uuid, now + COOLDOWN_MS);
// Freeze all nearby mobs, projectiles, items, and other players
        for (Object obj : caster.getNearbyEntities(FREEZE_RADIUS, FREEZE_RADIUS, FREEZE_RADIUS)) {
            Entity ent = (Entity) obj;
            if (ent.equals(caster)) continue;
            freezeEntity(ent, zone);
        }
        center.getWorld().playSound(center, Sound.BLOCK_BEACON_DEACTIVATE, 1.3f, 0.5f);
        center.getWorld().playSound(center, Sound.ENTITY_WARDEN_SONIC_BOOM, 0.7f, 0.6f);
        caster.sendTitle(ChatColor.GOLD + "" + ChatColor.BOLD + "TIME STOP!", ChatColor.AQUA + "5 seconds — unleash your attacks!", 3, 25, 7);
    }

    private void freezeEntity(Entity ent, FreezeZone zone) {
        UUID id = ent.getUniqueId();
        zone.capturedEntities.add(id);
        frozenLocations.put(id, ent.getLocation().clone());
        ent.setGravity(false);
        ent.setVelocity(new Vector(0, 0, 0));
        if (ent instanceof Mob mob) {
            mob.setAI(false);
            frozenMobs.add(id);
        } else if (ent instanceof Player targetPlayer) {
            frozenPlayers.add(id);
            targetPlayer.sendTitle(ChatColor.AQUA + "" + ChatColor.BOLD + "TIME FROZEN", ChatColor.GRAY + "You cannot move!", 3, 30, 7);
        } else if (ent instanceof Projectile proj) {
            if (!frozenProjectiles.containsKey(id)) {
                frozenProjectiles.put(id, proj.getVelocity().clone());
            }
        }
    }

    private void endTimeStop(UUID casterId, FreezeZone zone) {
        Player caster = Bukkit.getPlayer(casterId);
        zone.center.getWorld().playSound(zone.center, Sound.BLOCK_BEACON_ACTIVATE, 1.3f, 1.3f);
        zone.center.getWorld().spawnParticle(Particle.SONIC_BOOM, zone.center.clone().add(0, 1.0, 0), 1);
        if (caster != null && caster.isOnline()) {
            caster.sendTitle(ChatColor.AQUA + "" + ChatColor.BOLD + "TIME RESUMES!", ChatColor.GRAY + "Cooldown: 30s", 3, 25, 7);
        }
        for (Object idObj : zone.capturedEntities) {
            UUID id = (UUID) idObj;
            frozenLocations.remove(id);
            frozenMobs.remove(id);
            frozenPlayers.remove(id);
            Entity ent = Bukkit.getEntity(id);
            if (ent == null || !ent.isValid() || ent.isDead()) {
                frozenProjectiles.remove(id);
                storedDamage.remove(id);
                storedDamager.remove(id);
                storedKnockback.remove(id);
                continue;
            }
            ent.setGravity(true);
            if (ent instanceof Mob mob) {
                mob.setAI(true);
            }
            if (frozenProjectiles.containsKey(id)) {
                Vector savedVel = (Vector) frozenProjectiles.remove(id);
                if (savedVel != null) ent.setVelocity(savedVel);
            }
// Detonate all accumulated damage from stopped time at once!
            Double totalDmg = (Double) storedDamage.remove(id);
            UUID damagerId = (UUID) storedDamager.remove(id);
            Vector kb = (Vector) storedKnockback.remove(id);
            if (totalDmg != null && totalDmg != 0.0 && ent instanceof LivingEntity living) {
                Player attacker = (damagerId != null) ? Bukkit.getPlayer(damagerId) : caster;
                living.getWorld().spawnParticle(Particle.CRIT, living.getLocation().add(0, 1.0, 0), 20, 0.4, 0.5, 0.4, 0.2);
                living.getWorld().playSound(living.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.2f, 0.9f);
                if (attacker != null && attacker.isOnline()) {
                    living.damage(totalDmg, attacker);
                } else {
                    living.damage(totalDmg);
                }
                if (kb != null && kb.lengthSquared() != 0.0) {
                    living.setVelocity(kb.normalize().multiply(1.1).setY(0.45));
                }
            }
        }
    }

    private void renderZoneRing(Location center) {
        Particle.DustOptions goldDust = new Particle.DustOptions(Color.fromRGB(255, 215, 0), 1.3f);
        Particle.DustOptions aquaDust = new Particle.DustOptions(Color.AQUA, 1.1f);
        for (int i = 0; i != 24; i++) {
            double angle = (Math.PI * 2 * i) / 24.0;
            double x = Math.cos(angle) * FREEZE_RADIUS;
            double z = Math.sin(angle) * FREEZE_RADIUS;
            Location pt = center.clone().add(x, 0.4, z);
            center.getWorld().spawnParticle(Particle.DUST, pt, 1, 0, 0, 0, 0, (i % 2 == 0) ? goldDust : aquaDust);
        }
    }

    // Freeze arrows/projectiles fired inside an active Time Stop zone after brief flight
    @EventHandler
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        Projectile proj = event.getEntity();
        for (Object obj : activeZones.values()) {
            FreezeZone zone = (FreezeZone) obj;
            if (proj.getWorld().equals(zone.center.getWorld())
                    && proj.getLocation().distanceSquared(zone.center) == Math.min(proj.getLocation().distanceSquared(zone.center), FREEZE_RADIUS * FREEZE_RADIUS)) {
                Bukkit.getScheduler().runTaskLater(this, new Runnable() {
                    @Override
                    public void run() {
                        if (proj.isValid() && !proj.isDead() && activeZones.containsValue(zone)) {
                            freezeEntity(proj, zone);
                        }
                    }
                }, 3L);
                break;
            }
        }
    }

    // Store damage dealt to frozen targets so it detonates when time resumes!
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        UUID victimId = event.getEntity().getUniqueId();
// Frozen players cannot attack while frozen
        if (event instanceof EntityDamageByEntityEvent byEnt) {
            Player attacker = null;
            if (byEnt.getDamager() instanceof Player p) {
                attacker = p;
            } else if (byEnt.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player p) {
                attacker = p;
            }
            if (attacker != null && frozenPlayers.contains(attacker.getUniqueId())) {
                event.setCancelled(true);
                return;
            }
            if (frozenLocations.containsKey(victimId) && attacker != null) {
                double dmg = event.getFinalDamage();
                event.setCancelled(true);
                double prev = (Double) storedDamage.getOrDefault(victimId, 0.0);
                storedDamage.put(victimId, prev + dmg);
                storedDamager.put(victimId, attacker.getUniqueId());
                Vector dir = event.getEntity().getLocation().toVector().subtract(attacker.getLocation().toVector()).setY(0);
                storedKnockback.put(victimId, dir);
                event.getEntity().getWorld().spawnParticle(Particle.ENCHANTED_HIT, event.getEntity().getLocation().add(0, 1.0, 0), 10, 0.3, 0.4, 0.3, 0.1);
                attacker.playSound(attacker.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.9f, 1.6f);
                attacker.sendActionBar(ChatColor.GOLD + "Stored Damage: " + ChatColor.RED + String.format("%.1f", prev + dmg) + " HP");
            }
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (frozenPlayers.contains(event.getPlayer().getUniqueId())) {
            if (event.getFrom().getX() != event.getTo().getX() || event.getFrom().getY() != event.getTo().getY() || event.getFrom().getZ() != event.getTo().getZ()) {
                event.setCancelled(true);
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String... args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can run this command.");
            return true;
        }
        if (command.getName().equalsIgnoreCase("chronofreeze")) {
            if (!player.hasPermission("chronofreeze.give")) {
                player.sendMessage(ChatColor.RED + "You do not have permission to use this command.");
                return true;
            }
            player.getInventory().addItem(createChronoWatch());
            player.sendMessage(ChatColor.GOLD + "You received the " + ChatColor.BOLD + "Chrono Watch" + ChatColor.GOLD + "!");
            return true;
        }
        return false;
    }

    @Override
    public void onDisable() {
        for (Object obj : new ArrayList(activeZones.entrySet())) {
            Map.Entry entry = (Map.Entry) obj;
            endTimeStop((UUID) entry.getKey(), (FreezeZone) entry.getValue());
        }
        activeZones.clear();
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        frozenPlayers.remove(uuid);
        frozenLocations.remove(uuid);
        FreezeZone zone = (FreezeZone) activeZones.remove(uuid);
        if (zone != null) {
            endTimeStop(uuid, zone);
        }
    }
}