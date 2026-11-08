package com.example.silhouette;

import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * division - a fishing rod that swaps places instead of fishing.
 *
 * Cast it: swap with the nearest player within 20 blocks, or, if you've marked something, swap with that
 * (anywhere in the world) instead. Left click any entity to mark it. A mark is used up by the next swap.
 * Both sides of a swap keep the other one's momentum.
 */
public final class Division implements Listener, CommandExecutor, TabCompleter {

    private static final double RANGE = 20.0;

    /** Players don't have a reliable server-side velocity, so their momentum is tracked from their movement. */
    private static final int MOMENTUM_MAX_AGE_TICKS = 3;

    private record Momentum(Vector velocity, int tick) {}

    private final JavaPlugin plugin;
    private final NamespacedKey itemKey;

    /** caster -> marked entity */
    private final Map<UUID, UUID> marks = new HashMap<>();
    private final Map<UUID, Momentum> momentum = new HashMap<>();

    public Division(JavaPlugin plugin) {
        this.plugin = plugin;
        this.itemKey = SilhouettePlugin.key("division");
    }

    public void enable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);

        PluginCommand cmd = plugin.getCommand("division");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
    }

    // ------------------------------------------------------------------ the item

    private ItemStack createItem() {
        ItemStack item = new ItemStack(Material.FISHING_ROD);
        ItemMeta meta = item.getItemMeta();
        // Lowercase on purpose. Italics are turned off because custom names default to italic.
        meta.displayName(Component.text("division").decoration(TextDecoration.ITALIC, false));
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isDivision(ItemStack item) {
        if (item == null || item.getType() != Material.FISHING_ROD || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(itemKey, PersistentDataType.BYTE);
    }

    // ------------------------------------------------------------------ marking (left click)

    /**
     * Fires for any entity a player swings at, including ones that never take a normal hit (wind charges,
     * boats, minecarts...). The attack is cancelled so marking something doesn't also damage or break it.
     */
    @EventHandler(ignoreCancelled = true)
    public void onMark(PrePlayerAttackEntityEvent e) {
        Player player = e.getPlayer();
        if (!isDivision(player.getInventory().getItemInMainHand())) return;

        Entity target = e.getAttacked();
        e.setCancelled(true);
        if (target.equals(player)) return;

        marks.put(player.getUniqueId(), target.getUniqueId());
        target.getWorld().playSound(target.getLocation(), Sound.BLOCK_CHAIN_PLACE, SoundCategory.PLAYERS, 1.0f, 1.0f);
    }

    // ------------------------------------------------------------------ casting (right click)

    @EventHandler(priority = EventPriority.HIGH)
    public void onCast(PlayerInteractEvent e) {
        Action action = e.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
        if (!isDivision(e.getItem())) return;

        Player player = e.getPlayer();

        // Right-clicking a chest, door, etc. should just use it, like a normal rod would.
        if (action == Action.RIGHT_CLICK_BLOCK && !player.isSneaking()
                && e.getClickedBlock() != null && e.getClickedBlock().getType().isInteractable()) {
            return;
        }

        e.setUseItemInHand(Event.Result.DENY); // no bobber, no fishing

        // If both hands hold a division, only swap once per click.
        if (e.getHand() == EquipmentSlot.OFF_HAND && isDivision(player.getInventory().getItemInMainHand())) return;

        swap(player);
    }

    private void swap(Player caster) {
        Entity target;

        UUID markId = marks.get(caster.getUniqueId());
        if (markId != null) {
            target = plugin.getServer().getEntity(markId); // works anywhere, as long as it's loaded
            if (target == null || !target.isValid()) {
                marks.remove(caster.getUniqueId());
                fail(caster, "Your mark is gone.");
                return;
            }
        } else {
            target = nearestPlayer(caster);
            if (target == null) {
                fail(caster, "Nobody within " + (int) RANGE + " blocks.");
                return;
            }
        }

        Location casterLoc = caster.getLocation();
        Location targetLoc = target.getLocation();
        Vector casterMomentum = momentumOf(caster);
        Vector targetMomentum = momentumOf(target);

        // Positions swap, but everyone keeps looking the way they were looking.
        Location casterDest = targetLoc.clone();
        casterDest.setYaw(casterLoc.getYaw());
        casterDest.setPitch(casterLoc.getPitch());

        Location targetDest = casterLoc.clone();
        targetDest.setYaw(targetLoc.getYaw());
        targetDest.setPitch(targetLoc.getPitch());

        if (!caster.teleport(casterDest)) {
            fail(caster, "Couldn't swap.");
            return;
        }
        if (!target.teleport(targetDest)) {
            caster.teleport(casterLoc); // put the caster back, the swap didn't happen
            fail(caster, "Couldn't swap.");
            return;
        }

        marks.remove(caster.getUniqueId()); // a mark is used up by the swap

        // Each side keeps the other's momentum. Applied now and again next tick so the teleport can't eat it.
        applyMomentum(caster, targetMomentum);
        applyMomentum(target, casterMomentum);
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            applyMomentum(caster, targetMomentum);
            applyMomentum(target, casterMomentum);
        });
    }

    private Player nearestPlayer(Player caster) {
        Player best = null;
        double bestDistSq = RANGE * RANGE;
        Location from = caster.getLocation();

        for (Player other : caster.getWorld().getPlayers()) {
            if (other.equals(caster) || other.getGameMode() == GameMode.SPECTATOR) continue;
            double distSq = other.getLocation().distanceSquared(from);
            if (distSq <= bestDistSq) {
                best = other;
                bestDistSq = distSq;
            }
        }
        return best;
    }

    private void fail(Player player, String message) {
        player.sendActionBar(Component.text(message, NamedTextColor.RED));
    }

    // ------------------------------------------------------------------ momentum

    private Vector momentumOf(Entity entity) {
        if (entity instanceof Player p) {
            Momentum m = momentum.get(p.getUniqueId());
            if (m == null || plugin.getServer().getCurrentTick() - m.tick() > MOMENTUM_MAX_AGE_TICKS) {
                return new Vector(0, 0, 0); // standing still
            }
            return m.velocity().clone();
        }
        return entity.getVelocity();
    }

    private void applyMomentum(Entity entity, Vector velocity) {
        if (entity.isValid()) entity.setVelocity(velocity.clone());
    }

    /** Blocks moved since the previous movement packet is roughly blocks per tick, which is what velocity uses. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location to = e.getTo();
        if (to == null) return;
        Vector delta = to.toVector().subtract(e.getFrom().toVector());
        momentum.put(e.getPlayer().getUniqueId(), new Momentum(delta, plugin.getServer().getCurrentTick()));
    }

    // ------------------------------------------------------------------ cleanup

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        marks.remove(id);
        momentum.remove(id);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        marks.remove(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ /division

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
            sender.sendMessage(Component.text("Usage: /division <player>", NamedTextColor.RED));
            return true;
        }

        Map<Integer, ItemStack> leftover = target.getInventory().addItem(createItem());
        leftover.values().forEach(item -> target.getWorld().dropItemNaturally(target.getLocation(), item));

        sender.sendMessage(Component.text("Gave division to " + target.getName() + ".", NamedTextColor.GRAY));
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
