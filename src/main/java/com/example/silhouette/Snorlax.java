package com.example.silhouette;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * snorlax - a snowball that can't be thrown. Hit something with it (melee) and the hit lands normally, then the
 * target is frozen in place, "stopped in time", with the powdered-snow freezing effect, until it takes any damage
 * that isn't freezing damage.
 *
 * Nothing here uses potion effects. Players are held still with walk/fly speed, jump strength, gravity and a
 * position lock. Mobs are held still by switching their AI and gravity off.
 */
public final class Snorlax implements Listener, CommandExecutor, TabCompleter {

    /** Everything we need to put an entity back exactly how we found it. */
    private static final class Frozen {
        final LivingEntity entity;

        // mobs
        boolean hadAi;
        boolean hadGravity;

        // players
        float walkSpeed;
        float flySpeed;
        AttributeModifier jumpMod;
        AttributeModifier gravityMod;

        Frozen(LivingEntity entity) {
            this.entity = entity;
        }
    }

    private final JavaPlugin plugin;
    private final NamespacedKey itemKey;
    private final Map<UUID, Frozen> frozen = new HashMap<>();
    private BukkitTask task;

    public Snorlax(JavaPlugin plugin) {
        this.plugin = plugin;
        this.itemKey = SilhouettePlugin.key("snorlax");
    }

    // ------------------------------------------------------------------ lifecycle

    public void enable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);

        PluginCommand cmd = plugin.getCommand("snorlax");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }

        // Keeps the freezing overlay / freeze damage going. Freeze ticks drain by 2 every tick, so the target is
        // kept slightly above the maximum so the entity stays "fully frozen" (like standing in powdered snow).
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void disable() {
        if (task != null) task.cancel();
        for (Frozen f : new ArrayList<>(frozen.values())) {
            restore(f);
        }
        frozen.clear();
    }

    private void tick() {
        Iterator<Frozen> it = frozen.values().iterator();
        while (it.hasNext()) {
            LivingEntity e = it.next().entity;
            if (!e.isValid()) {
                it.remove(); // removed from the world, nothing left to restore
                continue;
            }
            e.setFreezeTicks(e.getMaxFreezeTicks() + 2);
        }
    }

    // ------------------------------------------------------------------ the item

    private ItemStack createItem() {
        ItemStack item = new ItemStack(Material.SNOWBALL);
        ItemMeta meta = item.getItemMeta();
        // Lowercase on purpose. Italics are turned off because custom names default to italic.
        meta.displayName(Component.text("snorlax").decoration(TextDecoration.ITALIC, false));
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isSnorlax(ItemStack item) {
        if (item == null || item.getType() != Material.SNOWBALL || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(itemKey, PersistentDataType.BYTE);
    }

    // ------------------------------------------------------------------ freeze / thaw

    private void freeze(LivingEntity target) {
        if (frozen.containsKey(target.getUniqueId())) return; // already frozen, tick() keeps it that way

        Frozen f = new Frozen(target);

        if (target instanceof Player p) {
            f.walkSpeed = p.getWalkSpeed();
            f.flySpeed = p.getFlySpeed();
            p.setWalkSpeed(0f);
            p.setFlySpeed(0f);
            f.jumpMod = addZeroModifier(p, Attribute.JUMP_STRENGTH, "snorlax_jump");
            f.gravityMod = addZeroModifier(p, Attribute.GRAVITY, "snorlax_gravity");
        } else {
            f.hadAi = target.hasAI();
            f.hadGravity = target.hasGravity();
            target.setAI(false);
            target.setGravity(false);
        }

        target.setVelocity(new Vector(0, 0, 0)); // stop any momentum
        target.setFreezeTicks(target.getMaxFreezeTicks() + 2);
        frozen.put(target.getUniqueId(), f);
    }

    private void thaw(Entity entity) {
        Frozen f = frozen.remove(entity.getUniqueId());
        if (f != null) restore(f);
    }

    private void restore(Frozen f) {
        LivingEntity e = f.entity;
        e.setFreezeTicks(0);

        if (e instanceof Player p) {
            p.setWalkSpeed(f.walkSpeed);
            p.setFlySpeed(f.flySpeed);
            removeModifier(p, Attribute.JUMP_STRENGTH, f.jumpMod);
            removeModifier(p, Attribute.GRAVITY, f.gravityMod);
        } else {
            e.setAI(f.hadAi);
            e.setGravity(f.hadGravity);
        }
    }

    /** Multiplies the attribute's final value by 0. Transient, so it can never get saved to the player's data. */
    private AttributeModifier addZeroModifier(Player p, Attribute attribute, String name) {
        AttributeInstance instance = p.getAttribute(attribute);
        if (instance == null) return null;
        AttributeModifier mod = new AttributeModifier(
                new NamespacedKey(plugin, name), -1.0, AttributeModifier.Operation.MULTIPLY_SCALAR_1);
        instance.addTransientModifier(mod);
        return mod;
    }

    private void removeModifier(Player p, Attribute attribute, AttributeModifier mod) {
        if (mod == null) return;
        AttributeInstance instance = p.getAttribute(attribute);
        if (instance != null) instance.removeModifier(mod);
    }

    // ------------------------------------------------------------------ events: hitting things

    /**
     * Hitting a player or mob with snorlax freezes it. The hit is NOT cancelled, so it deals damage and knockback
     * like any other hit. The freeze is applied one tick later, right after the hit: freezing inside the event
     * would let the damage from this very hit thaw the target again (see onDamage).
     * MONITOR + ignoreCancelled means only hits that actually went through (PvP/region protection respected)
     * freeze anything.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player attacker)) return;
        if (!(e.getEntity() instanceof LivingEntity victim)) return;
        if (!(victim instanceof Player || victim instanceof Mob)) return;
        if (!isSnorlax(attacker.getInventory().getItemInMainHand())) return;

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (victim.isValid() && !victim.isDead()) freeze(victim); // a killing blow has nothing to freeze
        });
    }

    /** Any damage that isn't freezing damage unfreezes the entity. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (e.getCause() == DamageCause.FREEZE) return;
        if (e.getDamage() <= 0) return; // e.g. a regular snowball hit does 0 damage
        thaw(e.getEntity());
    }

    // ------------------------------------------------------------------ events: keeping it from being thrown

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (isSnorlax(e.getItem())) {
            e.setUseItemInHand(Event.Result.DENY); // blocks still work, the item just doesn't do anything
        }
    }

    /** Backup for anything else that tries to launch it. */
    @EventHandler(ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent e) {
        if (e.getEntity() instanceof Snowball s && isSnorlax(s.getItem())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent e) {
        if (isSnorlax(e.getItem())) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ events: holding players in place

    /** Safety net for anything that still pushes a frozen player (water, pistons, air strafing). Looking around is allowed. */
    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (!frozen.containsKey(e.getPlayer().getUniqueId())) return;

        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null) return;
        if (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ()) return;

        Location locked = from.clone();
        locked.setYaw(to.getYaw());
        locked.setPitch(to.getPitch());
        e.setTo(locked);
    }

    // ------------------------------------------------------------------ events: cleanup

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        thaw(e.getEntity());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        thaw(e.getPlayer()); // so walk speed etc. can't stay zeroed on their save file
    }

    @EventHandler
    public void onUnload(EntitiesUnloadEvent e) {
        for (Entity entity : e.getEntities()) {
            thaw(entity); // so a mob can't get saved with AI switched off
        }
    }

    // ------------------------------------------------------------------ /snorlax

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Player target;
        if (args.length >= 1) {
            target = plugin.getServer().getPlayerExact(args[0]);
            if (target == null) {
                sender.sendMessage(Component.text("Player not found: " + args[0], NamedTextColor.RED));
                return true;
            }
        } else if (sender instanceof Player self) {
            target = self;
        } else {
            sender.sendMessage(Component.text("Usage: /snorlax <player>", NamedTextColor.RED));
            return true;
        }

        Map<Integer, ItemStack> leftover = target.getInventory().addItem(createItem());
        leftover.values().forEach(item -> target.getWorld().dropItemNaturally(target.getLocation(), item));

        sender.sendMessage(Component.text("Gave snorlax to " + target.getName() + ".", NamedTextColor.GRAY));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) return List.of();
        String prefix = args[0].toLowerCase();
        List<String> names = new ArrayList<>();
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (p.getName().toLowerCase().startsWith(prefix)) names.add(p.getName());
        }
        return names;
    }
}
