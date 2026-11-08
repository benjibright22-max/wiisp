package com.example.silhouette;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
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
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Dilation - a clock that slows time around whoever uses it.
 *
 * Right click: three rings of black and white particles leave the user one after another and grow out to a
 * 5 block radius (a trident sound for each ring). The field follows the user. For 20 seconds from the click
 * the user can't take damage. At the 20 second mark the rings close back in the same way, again with a trident
 * sound for each ring.
 *
 * Anyone inside the field (players and mobs, never the user) is slowed for 10 seconds. The 10 seconds are
 * refreshed on every tick they spend inside and only count down once they're outside.
 *
 * Players really do run slower: they're sent a "ticking state" packet (through ProtocolLib, which this plugin
 * already depends on) that drops their own client's tick rate, the same thing /tick rate does for everyone.
 * Their movement, falling, jumping, animations and particles all run at that rate, and it's set back to the
 * server's rate when the slow ends. Their attack speed is cut as well, because attack cooldowns are counted
 * by the server and would otherwise still recharge at full speed.
 *
 * Mobs have no client, so they get the closest thing: movement speed, gravity and jump strength scaled down.
 */
public final class Dilation implements Listener, CommandExecutor, TabCompleter {

    private static final double RADIUS = 5.0;

    private static final int RING_COUNT = 3;
    /** Ticks between one ring leaving and the next. */
    private static final int RING_STAGGER_TICKS = 8;
    /** Ticks one ring takes to go from the user out to the full radius (or back in). */
    private static final int RING_TRAVEL_TICKS = 24;
    /** The closing starts 20 seconds after the click, and the user is invincible until then. */
    private static final int ACTIVE_TICKS = 20 * 20;
    private static final int END_TICKS = ACTIVE_TICKS + (RING_COUNT - 1) * RING_STAGGER_TICKS + RING_TRAVEL_TICKS;

    private static final int SLOW_TICKS = 10 * 20;

    /** Heights (above the user's feet) of the three rings. */
    private static final double[] RING_HEIGHTS = {0.15, 1.0, 1.85};

    private static final Particle.DustOptions BLACK = new Particle.DustOptions(Color.fromRGB(8, 8, 8), 1.6f);
    private static final Particle.DustOptions WHITE = new Particle.DustOptions(Color.WHITE, 1.6f);

    /** A slowed player's client runs at this fraction of the server's tick rate (never below 1 tick/s). */
    private static final float PLAYER_TICK_FACTOR = 0.2f;

    /**
     * Players: their client tick rate already slows movement, falling, jumping and mining, so only attack speed
     * is touched. Cutting those attributes too would stack on top and slow them down twice over.
     * Each value is added to the attribute as a multiplier: -0.8 means "x0.2".
     */
    private static final Map<Attribute, Double> PLAYER_ATTRIBUTES = Map.of(
            Attribute.ATTACK_SPEED, -0.8);

    /**
     * Mobs: gravity is cut as much as speed so falling looks slow-motion, and jumping is cut less so a jump
     * still gets off the ground (with weak gravity a full-strength jump would send them flying).
     */
    private static final Map<Attribute, Double> MOB_ATTRIBUTES = Map.of(
            Attribute.MOVEMENT_SPEED, -0.8,
            Attribute.GRAVITY, -0.8,
            Attribute.JUMP_STRENGTH, -0.6);

    /** A running Dilation. {@code age} is the number of ticks since the click. */
    private static final class Field {
        final Player owner;
        int age;

        Field(Player owner) {
            this.owner = owner;
        }
    }

    /** Something currently slowed, and what we added to it so it can be put back exactly. */
    private static final class Slowed {
        final LivingEntity entity;
        final Map<Attribute, AttributeModifier> modifiers = new LinkedHashMap<>();
        int ticksLeft = SLOW_TICKS;

        Slowed(LivingEntity entity) {
            this.entity = entity;
        }
    }

    private final JavaPlugin plugin;
    private final ProtocolManager protocol;
    private final NamespacedKey itemKey;
    private final NamespacedKey modifierKey;

    private final Map<UUID, Field> fields = new LinkedHashMap<>();
    private final Map<UUID, Slowed> slowed = new HashMap<>();
    private BukkitTask task;

    public Dilation(JavaPlugin plugin) {
        this.plugin = plugin;
        this.protocol = ProtocolLibrary.getProtocolManager();
        this.itemKey = SilhouettePlugin.key("dilation");
        this.modifierKey = new NamespacedKey(plugin, "dilation_slow"); // transient, never saved
    }

    // ------------------------------------------------------------------ lifecycle

    public void enable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);

        PluginCommand cmd = plugin.getCommand("dilation");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }

        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void disable() {
        if (task != null) task.cancel();
        for (Slowed s : new ArrayList<>(slowed.values())) {
            restore(s);
        }
        slowed.clear();
        fields.clear();
    }

    // ------------------------------------------------------------------ the item

    private ItemStack createItem() {
        ItemStack item = new ItemStack(Material.CLOCK);
        ItemMeta meta = item.getItemMeta();
        // Italics are turned off because custom names default to italic.
        meta.displayName(Component.text("Dilation").decoration(TextDecoration.ITALIC, false));
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isDilation(ItemStack item) {
        if (item == null || item.getType() != Material.CLOCK || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(itemKey, PersistentDataType.BYTE);
    }

    // ------------------------------------------------------------------ activating (right click)

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        Action action = e.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
        if (!isDilation(e.getItem())) return;

        Player player = e.getPlayer();

        // Right-clicking a chest, door, etc. should just use it.
        if (action == Action.RIGHT_CLICK_BLOCK && !player.isSneaking()
                && e.getClickedBlock() != null && e.getClickedBlock().getType().isInteractable()) {
            return;
        }

        // If both hands hold a Dilation, only act once per click.
        if (e.getHand() == EquipmentSlot.OFF_HAND && isDilation(player.getInventory().getItemInMainHand())) return;

        if (fields.containsKey(player.getUniqueId())) {
            player.sendActionBar(Component.text("Dilation is already active.", NamedTextColor.RED));
            return;
        }
        fields.put(player.getUniqueId(), new Field(player));
    }

    // ------------------------------------------------------------------ invincibility

    /** The user can't take damage from the click until the rings start closing. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player player)) return;
        Field field = fields.get(player.getUniqueId());
        if (field != null && field.age < ACTIVE_TICKS) {
            e.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ the main loop

    private void tick() {
        Set<UUID> inside = new HashSet<>();

        Iterator<Field> fieldIt = fields.values().iterator();
        while (fieldIt.hasNext()) {
            Field field = fieldIt.next();
            Player owner = field.owner;
            if (!owner.isOnline() || owner.isDead() || field.age >= END_TICKS) {
                fieldIt.remove();
                continue;
            }
            tickField(field, inside);
            field.age++;
        }

        // Everyone slowed counts down, except those still in a field, who are topped back up.
        Iterator<Slowed> slowedIt = slowed.values().iterator();
        while (slowedIt.hasNext()) {
            Slowed s = slowedIt.next();
            if (!s.entity.isValid()) {
                slowedIt.remove(); // gone from the world, nothing left to restore
                continue;
            }
            if (inside.contains(s.entity.getUniqueId())) {
                s.ticksLeft = SLOW_TICKS;
            } else if (--s.ticksLeft <= 0) {
                slowedIt.remove();
                restore(s);
            }
        }
    }

    private void tickField(Field field, Set<UUID> inside) {
        Player owner = field.owner;
        World world = owner.getWorld();
        Location center = owner.getLocation();
        int age = field.age;

        double reach = 0; // the biggest ring is how far the slow reaches right now
        for (int ring = 0; ring < RING_COUNT; ring++) {
            // One trident sound each time a ring sets off, going out and coming back in.
            if (age == ring * RING_STAGGER_TICKS) {
                world.playSound(center, Sound.ITEM_TRIDENT_RIPTIDE_2, SoundCategory.PLAYERS, 1.0f, 1.0f);
            } else if (age == ACTIVE_TICKS + ring * RING_STAGGER_TICKS) {
                world.playSound(center, Sound.ITEM_TRIDENT_RIPTIDE_2, SoundCategory.PLAYERS, 1.0f, 0.8f);
            }

            double radius = ringRadius(age, ring);
            reach = Math.max(reach, radius);
            if (age % 2 == 0 && radius > 0.05) {
                drawRing(world, center, radius, RING_HEIGHTS[ring], age, ring);
            }
        }

        if (reach <= 0.05) return;

        double reachSq = reach * reach;
        for (Entity entity : world.getNearbyEntities(center, reach, reach, reach)) {
            if (!(entity instanceof LivingEntity target) || target.equals(owner)) continue;
            if (!(target instanceof Player || target instanceof Mob)) continue;
            if (target instanceof Player p && p.getGameMode() == GameMode.SPECTATOR) continue;
            if (target.isDead()) continue;
            if (target.getLocation().distanceSquared(center) > reachSq) continue;

            inside.add(target.getUniqueId());
            slow(target);
        }
    }

    /** Ring {@code ring} grows 0 -> RADIUS after its start time, holds, then shrinks back after the closing time. */
    private static double ringRadius(int age, int ring) {
        int closing = age - (ACTIVE_TICKS + ring * RING_STAGGER_TICKS);
        if (closing >= 0) {
            return RADIUS * (1.0 - Math.min(1.0, closing / (double) RING_TRAVEL_TICKS));
        }
        int opening = age - ring * RING_STAGGER_TICKS;
        return RADIUS * Math.min(1.0, Math.max(0, opening) / (double) RING_TRAVEL_TICKS);
    }

    private void drawRing(World world, Location center, double radius, double height, int age, int ring) {
        int points = Math.max(8, (int) Math.ceil(2 * Math.PI * radius / 0.6));
        // Rings turn slowly, in alternating directions.
        double phase = age * 0.06 * (ring % 2 == 0 ? 1 : -1);
        for (int i = 0; i < points; i++) {
            double angle = 2 * Math.PI * i / points + phase;
            double x = center.getX() + Math.cos(angle) * radius;
            double z = center.getZ() + Math.sin(angle) * radius;
            world.spawnParticle(Particle.DUST, x, center.getY() + height, z, 1, 0, 0, 0, 0, i % 2 == 0 ? BLACK : WHITE);
        }
    }

    // ------------------------------------------------------------------ slowing / restoring

    /** Slows the entity if it isn't already. Topping the timer back up is done in tick(). */
    private void slow(LivingEntity target) {
        if (slowed.containsKey(target.getUniqueId())) return;

        Slowed s = new Slowed(target);
        if (target instanceof Player player) {
            sendTickRate(player, Math.max(1.0f, Bukkit.getServerTickManager().getTickRate() * PLAYER_TICK_FACTOR), false);
        }
        Map<Attribute, Double> attributes = target instanceof Player ? PLAYER_ATTRIBUTES : MOB_ATTRIBUTES;
        for (Map.Entry<Attribute, Double> entry : attributes.entrySet()) {
            AttributeInstance instance = target.getAttribute(entry.getKey());
            if (instance == null) continue; // e.g. mobs have no attack speed or block break speed
            AttributeModifier mod = new AttributeModifier(modifierKey, entry.getValue(),
                    AttributeModifier.Operation.MULTIPLY_SCALAR_1);
            instance.addTransientModifier(mod); // transient, so it can never get saved to the entity's data
            s.modifiers.put(entry.getKey(), mod);
        }
        slowed.put(target.getUniqueId(), s);
    }

    private void unslow(Entity entity) {
        Slowed s = slowed.remove(entity.getUniqueId());
        if (s != null) restore(s);
    }

    private void restore(Slowed s) {
        if (s.entity instanceof Player player) {
            // Back to whatever the server is actually running at (this respects a global /tick rate or freeze).
            sendTickRate(player, Bukkit.getServerTickManager().getTickRate(), Bukkit.getServerTickManager().isFrozen());
        }
        for (Map.Entry<Attribute, AttributeModifier> entry : s.modifiers.entrySet()) {
            AttributeInstance instance = s.entity.getAttribute(entry.getKey());
            if (instance != null) instance.removeModifier(entry.getValue());
        }
    }

    /** Sets one player's client tick rate, using the same packet the server sends everyone for /tick rate. */
    private void sendTickRate(Player player, float tickRate, boolean frozen) {
        try {
            PacketContainer packet = protocol.createPacket(PacketType.Play.Server.TICKING_STATE);
            packet.getFloat().write(0, tickRate);
            packet.getBooleans().write(0, frozen);
            protocol.sendServerPacket(player, packet);
        } catch (Exception ex) {
            // e.g. the player is mid-disconnect; nothing to restore on their side then.
            plugin.getLogger().fine("Could not send tick rate to " + player.getName() + ": " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------------ cleanup

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        unslow(e.getEntity());
        if (e.getEntity() instanceof Player p) fields.remove(p.getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        unslow(p);
        fields.remove(p.getUniqueId());
    }

    @EventHandler
    public void onUnload(EntitiesUnloadEvent e) {
        for (Entity entity : e.getEntities()) {
            unslow(entity);
        }
    }

    // ------------------------------------------------------------------ /dilation

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
            sender.sendMessage(Component.text("Usage: /dilation <player>", NamedTextColor.RED));
            return true;
        }

        Map<Integer, ItemStack> leftover = target.getInventory().addItem(createItem());
        leftover.values().forEach(item -> target.getWorld().dropItemNaturally(target.getLocation(), item));

        sender.sendMessage(Component.text("Gave Dilation to " + target.getName() + ".", NamedTextColor.GRAY));
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
