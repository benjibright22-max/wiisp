package com.example.silhouette;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.reflect.StructureModifier;
import com.comphenix.protocol.wrappers.EnumWrappers.ItemSlot;
import com.comphenix.protocol.wrappers.Pair;
import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Silhouette - a chainmail chestplate that grants "true" invisibility.
 *
 * True invisibility = the Invisibility effect (no particles) PLUS stripping every
 * equipment slot (armor, main hand, off hand) out of the packets other players receive,
 * so nothing floats around in the air where the wearer is standing.
 */
public final class SilhouettePlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    /** Entity IDs of players currently wearing Silhouette. Read from netty threads, so it must be concurrent. */
    private final Set<Integer> active = ConcurrentHashMap.newKeySet();

    private NamespacedKey itemKey;
    private ProtocolManager protocol;
    private Snorlax snorlax;
    private Dilation dilation;

    /**
     * Item tags keep the old "silhouette" namespace on purpose. The plugin is now called WispSMP, and
     * new NamespacedKey(plugin, ...) would switch to the "wispsmp" namespace, which would stop every
     * item that was already given out from being recognised.
     */
    static NamespacedKey key(String name) {
        return Objects.requireNonNull(NamespacedKey.fromString("silhouette:" + name));
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onEnable() {
        itemKey = key("silhouette");
        protocol = ProtocolLibrary.getProtocolManager();

        registerEquipmentFilter();
        getServer().getPluginManager().registerEvents(this, this);

        PluginCommand cmd = getCommand("silhouette");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }

        snorlax = new Snorlax(this);
        snorlax.enable();

        new Division(this).enable();
        new Index(this).enable();

        dilation = new Dilation(this);
        dilation.enable();

        // Safety net: catches anything the events miss (milk buckets, /give, creative inventory, etc.)
        getServer().getScheduler().runTaskTimer(this, () -> {
            for (Player p : getServer().getOnlinePlayers()) {
                update(p, true);
            }
        }, 20L, 10L);

        // Handles /reload
        for (Player p : getServer().getOnlinePlayers()) {
            update(p, false);
        }
    }

    @Override
    public void onDisable() {
        if (snorlax != null) snorlax.disable();
        if (dilation != null) dilation.disable();
        for (Player p : getServer().getOnlinePlayers()) {
            if (active.contains(p.getEntityId())) {
                deactivate(p, false);
            }
        }
        active.clear();
    }

    // ------------------------------------------------------------------ the item

    private ItemStack createItem() {
        ItemStack item = new ItemStack(Material.CHAINMAIL_CHESTPLATE);
        ItemMeta meta = item.getItemMeta();
        // GRAY is the light gray (§7). Italics are turned off because custom names default to italic.
        meta.displayName(Component.text("Silhouette", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isSilhouette(ItemStack item) {
        if (item == null || item.getType() != Material.CHAINMAIL_CHESTPLATE || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(itemKey, PersistentDataType.BYTE);
    }

    // ------------------------------------------------------------------ equip / unequip logic

    private void update(Player p, boolean sound) {
        if (p.isDead()) return;

        boolean wearing = isSilhouette(p.getInventory().getChestplate());
        boolean isActive = active.contains(p.getEntityId());

        if (wearing && !isActive) {
            activate(p, sound);
        } else if (!wearing && isActive) {
            deactivate(p, sound);
        } else if (wearing) {
            ensureEffect(p); // e.g. they drank milk
        }
    }

    private void activate(Player p, boolean sound) {
        active.add(p.getEntityId()); // add first so any packet sent from here on is filtered
        ensureEffect(p);
        resendEquipment(p);          // make everyone who can already see them drop the armor/weapons
        if (sound) playSound(p, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE);
    }

    private void deactivate(Player p, boolean sound) {
        active.remove(p.getEntityId());
        removeOurEffect(p);
        resendEquipment(p);          // show their gear again
        if (sound) playSound(p, Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE);
    }

    private PotionEffect trueInvisibility() {
        // infinite duration, amplifier 0, ambient=false, particles=false, icon=true
        return new PotionEffect(PotionEffectType.INVISIBILITY, PotionEffect.INFINITE_DURATION, 0, false, false, true);
    }

    private void ensureEffect(Player p) {
        PotionEffect cur = p.getPotionEffect(PotionEffectType.INVISIBILITY);
        if (cur == null || cur.getDuration() != PotionEffect.INFINITE_DURATION || cur.hasParticles()) {
            p.addPotionEffect(trueInvisibility());
        }
    }

    /** Only removes the invisibility if it's ours (infinite) so we don't wipe a real potion. */
    private void removeOurEffect(Player p) {
        PotionEffect cur = p.getPotionEffect(PotionEffectType.INVISIBILITY);
        if (cur != null && cur.getDuration() == PotionEffect.INFINITE_DURATION) {
            p.removePotionEffect(PotionEffectType.INVISIBILITY);
        }
    }

    private void playSound(Player p, Sound sound) {
        p.getWorld().playSound(p.getLocation(), sound, SoundCategory.PLAYERS, 1.0f, 1.0f);
    }

    // ------------------------------------------------------------------ packet magic

    /** Replaces every item in outgoing equipment packets for Silhouette wearers with air. */
    private void registerEquipmentFilter() {
        protocol.addPacketListener(new PacketAdapter(this, ListenerPriority.HIGH, PacketType.Play.Server.ENTITY_EQUIPMENT) {
            @Override
            public void onPacketSending(PacketEvent event) {
                if (event.isPlayerTemporary()) return;

                PacketContainer original = event.getPacket();
                int entityId = original.getIntegers().read(0);
                if (!active.contains(entityId)) return;

                // The wearer's own client already knows their inventory - leave their view alone.
                if (event.getPlayer().getEntityId() == entityId) return;

                // Packets can be shared between viewers, so never mutate the original.
                PacketContainer copy = original.deepClone();
                StructureModifier<List<Pair<ItemSlot, ItemStack>>> modifier = copy.getSlotStackPairLists();
                List<Pair<ItemSlot, ItemStack>> emptied = new ArrayList<>();
                for (Pair<ItemSlot, ItemStack> pair : modifier.read(0)) {
                    emptied.add(new Pair<>(pair.getFirst(), new ItemStack(Material.AIR)));
                }
                modifier.write(0, emptied);
                event.setPacket(copy);
            }
        });
    }

    /** Re-sends the player's real equipment to everyone tracking them (the filter decides what's actually shown). */
    private void resendEquipment(Player p) {
        PlayerInventory inv = p.getInventory();

        List<Pair<ItemSlot, ItemStack>> pairs = new ArrayList<>();
        pairs.add(new Pair<>(ItemSlot.MAINHAND, orAir(inv.getItemInMainHand())));
        pairs.add(new Pair<>(ItemSlot.OFFHAND, orAir(inv.getItemInOffHand())));
        pairs.add(new Pair<>(ItemSlot.HEAD, orAir(inv.getHelmet())));
        pairs.add(new Pair<>(ItemSlot.CHEST, orAir(inv.getChestplate())));
        pairs.add(new Pair<>(ItemSlot.LEGS, orAir(inv.getLeggings())));
        pairs.add(new Pair<>(ItemSlot.FEET, orAir(inv.getBoots())));

        PacketContainer packet = new PacketContainer(PacketType.Play.Server.ENTITY_EQUIPMENT);
        packet.getIntegers().write(0, p.getEntityId());
        packet.getSlotStackPairLists().write(0, pairs);

        for (Player viewer : p.getTrackedPlayers()) {
            try {
                protocol.sendServerPacket(viewer, packet);
            } catch (Exception ex) {
                getLogger().warning("Could not update equipment for " + viewer.getName() + ": " + ex.getMessage());
            }
        }
    }

    private static ItemStack orAir(ItemStack item) {
        return item == null ? new ItemStack(Material.AIR) : item;
    }

    // ------------------------------------------------------------------ events

    @EventHandler
    public void onArmorChange(PlayerArmorChangeEvent e) {
        if (e.getSlotType() != PlayerArmorChangeEvent.SlotType.CHEST) return;
        update(e.getPlayer(), true);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        update(e.getPlayer(), false);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        active.remove(p.getEntityId());
        removeOurEffect(p); // so it can't linger on the player's save file
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getPlayer();
        active.remove(p.getEntityId()); // respawning gives a new entity ID
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        getServer().getScheduler().runTask(this, () -> update(p, false));
    }

    // ------------------------------------------------------------------ /silhouette

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Player target;
        if (args.length >= 1) {
            target = getServer().getPlayerExact(args[0]);
            if (target == null) {
                sender.sendMessage(Component.text("Player not found: " + args[0], NamedTextColor.RED));
                return true;
            }
        } else if (sender instanceof Player self) {
            target = self;
        } else {
            sender.sendMessage(Component.text("Usage: /silhouette <player>", NamedTextColor.RED));
            return true;
        }

        Map<Integer, ItemStack> leftover = target.getInventory().addItem(createItem());
        leftover.values().forEach(item -> target.getWorld().dropItemNaturally(target.getLocation(), item));

        sender.sendMessage(Component.text("Gave Silhouette to " + target.getName() + ".", NamedTextColor.GRAY));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) return List.of();
        String prefix = args[0].toLowerCase();
        List<String> names = new ArrayList<>();
        for (Player p : getServer().getOnlinePlayers()) {
            if (p.getName().toLowerCase().startsWith(prefix)) names.add(p.getName());
        }
        return names;
    }
}
