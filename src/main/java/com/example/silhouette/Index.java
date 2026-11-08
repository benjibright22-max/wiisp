package com.example.silhouette;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Index - a black bundle with orange lettering that messes with other players' inventories.
 *
 * Right click a player: scrambles their inventory (hotbar + storage, not armor). 2 minute cooldown.
 * Shift + right click a player: opens a GUI of their inventory. Take one stack and it moves from them to you,
 * then goes back to them 5 minutes later. 15 minute cooldown, counted from the moment something is stolen.
 *
 * A stolen item is a "loan": it is tagged, can't be dropped, stored away or used up, and the original is
 * kept on file so the owner always gets it back, even across restarts or while they're offline.
 */
public final class Index implements Listener, CommandExecutor, TabCompleter {

    private static final long SCRAMBLE_COOLDOWN_MS = 2 * 60_000L;
    private static final long STEAL_COOLDOWN_MS = 15 * 60_000L;
    private static final long RETURN_AFTER_MS = 5 * 60_000L;

    private static final TextColor ORANGE = TextColor.color(0xFF8C00);

    /** Hotbar + storage: inventory slots 0-35. Armor and the off hand are left alone. */
    private static final int MAIN_SLOTS = 36;

    /** Marks the GUI so clicks in it can be recognised (and cancelled). */
    private static final class StealGui implements InventoryHolder {
        final UUID victim;
        Inventory inventory;

        StealGui(UUID victim) {
            this.victim = victim;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    /** An item that is out on loan. {@code original} is exactly what the owner had, and what they get back. */
    private record Theft(UUID id, UUID victim, UUID thief, long returnAt, int slot, ItemStack original) {}

    /** An item waiting for its owner to be online (and alive) to receive it. */
    private record Delivery(UUID id, int slot, ItemStack item) {}

    private final JavaPlugin plugin;
    private final NamespacedKey itemKey;
    private final NamespacedKey loanKey;
    private final File dataFile;

    private final Map<UUID, Long> scrambleReady = new HashMap<>();
    private final Map<UUID, Long> stealReady = new HashMap<>();
    private final Map<UUID, Theft> thefts = new LinkedHashMap<>();
    private final Map<UUID, List<Delivery>> deliveries = new HashMap<>();

    public Index(JavaPlugin plugin) {
        this.plugin = plugin;
        this.itemKey = SilhouettePlugin.key("index");
        this.loanKey = SilhouettePlugin.key("index_loan");
        this.dataFile = new File(plugin.getDataFolder(), "index-data.yml");
    }

    // ------------------------------------------------------------------ lifecycle

    public void enable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);

        PluginCommand cmd = plugin.getCommand("index");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }

        load();
    }

    // ------------------------------------------------------------------ the item

    private ItemStack createItem() {
        ItemStack item = new ItemStack(Material.BLACK_BUNDLE);
        ItemMeta meta = item.getItemMeta();
        // Italics are turned off because custom names default to italic.
        meta.displayName(Component.text("Index", ORANGE).decoration(TextDecoration.ITALIC, false));
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isIndex(ItemStack item) {
        if (item == null || item.getType() != Material.BLACK_BUNDLE || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(itemKey, PersistentDataType.BYTE);
    }

    // ------------------------------------------------------------------ right click: scramble / steal

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onUse(PlayerInteractEntityEvent e) {
        Player user = e.getPlayer();
        PlayerInventory inv = user.getInventory();
        ItemStack held = e.getHand() == EquipmentSlot.HAND ? inv.getItemInMainHand() : inv.getItemInOffHand();
        if (!isIndex(held)) return;
        if (!(e.getRightClicked() instanceof Player target) || target.equals(user)) return;
        if (target.getGameMode() == GameMode.SPECTATOR) return;

        e.setCancelled(true);

        // If both hands hold an Index, only act once per click.
        if (e.getHand() == EquipmentSlot.OFF_HAND && isIndex(inv.getItemInMainHand())) return;

        if (user.isSneaking()) {
            openSteal(user, target);
        } else {
            scramble(user, target);
        }
    }

    private void scramble(Player user, Player target) {
        long left = remaining(scrambleReady, user);
        if (left > 0) {
            fail(user, "Index is recharging: " + format(left));
            return;
        }

        PlayerInventory inv = target.getInventory();
        List<ItemStack> items = new ArrayList<>(MAIN_SLOTS);
        for (int i = 0; i < MAIN_SLOTS; i++) {
            items.add(inv.getItem(i)); // empty slots are null, and get shuffled too so items can land in them
        }
        Collections.shuffle(items);
        for (int i = 0; i < MAIN_SLOTS; i++) {
            inv.setItem(i, items.get(i));
        }
        target.updateInventory();

        scrambleReady.put(user.getUniqueId(), System.currentTimeMillis() + SCRAMBLE_COOLDOWN_MS);
        target.getWorld().playSound(target.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, SoundCategory.PLAYERS, 1.0f, 1.2f);
    }

    private void openSteal(Player thief, Player victim) {
        long left = remaining(stealReady, thief);
        if (left > 0) {
            fail(thief, "Index can't steal for another " + format(left));
            return;
        }

        StealGui gui = new StealGui(victim.getUniqueId());
        gui.inventory = Bukkit.createInventory(gui, MAIN_SLOTS, Component.text("Index: " + victim.getName(), ORANGE));

        // Same layout as the player's own inventory: storage on top, hotbar underneath.
        PlayerInventory theirs = victim.getInventory();
        for (int g = 0; g < MAIN_SLOTS; g++) {
            ItemStack item = theirs.getItem(toInvSlot(g));
            if (item != null && !item.getType().isAir()) {
                gui.inventory.setItem(g, item.clone());
            }
        }
        thief.openInventory(gui.inventory);
    }

    /** GUI slots 0-26 are inventory slots 9-35 (storage), GUI slots 27-35 are inventory slots 0-8 (hotbar). */
    private static int toInvSlot(int guiSlot) {
        return guiSlot < 27 ? guiSlot + 9 : guiSlot - 27;
    }

    private void steal(Player thief, StealGui gui, int guiSlot) {
        ItemStack shown = gui.inventory.getItem(guiSlot);
        if (shown == null || shown.getType().isAir()) return;

        Player victim = Bukkit.getPlayer(gui.victim);
        if (victim == null) {
            fail(thief, "They left.");
            closeLater(thief);
            return;
        }

        long left = remaining(stealReady, thief);
        if (left > 0) {
            fail(thief, "Index can't steal for another " + format(left));
            closeLater(thief);
            return;
        }

        int slot = toInvSlot(guiSlot);
        ItemStack real = victim.getInventory().getItem(slot);
        if (real == null || !real.equals(shown)) {
            fail(thief, "That item moved.");
            closeLater(thief);
            return;
        }
        if (isLoaned(real)) {
            fail(thief, "That item is already on loan.");
            return;
        }
        if (thief.getInventory().firstEmpty() == -1) {
            fail(thief, "Your inventory is full.");
            return;
        }

        UUID id = UUID.randomUUID();
        long now = System.currentTimeMillis();
        ItemStack original = real.clone();
        Theft theft = new Theft(id, victim.getUniqueId(), thief.getUniqueId(), now + RETURN_AFTER_MS, slot, original);

        victim.getInventory().setItem(slot, null);
        thief.getInventory().addItem(loan(original, id, victim.getName()));

        thefts.put(id, theft);
        stealReady.put(thief.getUniqueId(), now + STEAL_COOLDOWN_MS);
        schedule(theft);
        save();

        thief.getWorld().playSound(thief.getLocation(), Sound.ENTITY_ITEM_PICKUP, SoundCategory.PLAYERS, 1.0f, 0.8f);
        thief.sendActionBar(Component.text("Stole " + describe(original) + " from " + victim.getName()
                + ". It goes back in 5 minutes.", ORANGE));
        closeLater(thief);
    }

    // ------------------------------------------------------------------ loans: tagging, returning

    /** The stolen copy: same item, tagged with the theft id, with a lore line saying where it came from. */
    private ItemStack loan(ItemStack source, UUID theftId, String from) {
        ItemStack copy = source.clone();
        ItemMeta meta = copy.getItemMeta();
        meta.getPersistentDataContainer().set(loanKey, PersistentDataType.STRING, theftId.toString());
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        lore.add(Component.text("Stolen from " + from, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        copy.setItemMeta(meta);
        return copy;
    }

    private UUID loanId(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return null;
        String raw = item.getItemMeta().getPersistentDataContainer().get(loanKey, PersistentDataType.STRING);
        if (raw == null) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private boolean isLoaned(ItemStack item) {
        return loanId(item) != null;
    }

    private void schedule(Theft theft) {
        long ticks = Math.max(1L, (theft.returnAt() - System.currentTimeMillis()) / 50L);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> resolve(theft.id()), ticks);
    }

    /** Takes the loaned copy back from the thief (if they're online) and hands the original to the owner. */
    private void resolve(UUID theftId) {
        Theft theft = thefts.remove(theftId);
        if (theft == null) return; // already resolved, e.g. the thief died

        Player thief = Bukkit.getPlayer(theft.thief());
        if (thief != null) {
            strip(thief, id -> id.equals(theftId));
        }
        // If the thief is offline, the leftover copy is removed when they log back in (see onJoin).

        Player owner = Bukkit.getPlayer(theft.victim());
        if (owner != null && !owner.isDead()) {
            give(owner, theft.slot(), theft.original());
        } else {
            deliveries.computeIfAbsent(theft.victim(), k -> new ArrayList<>())
                    .add(new Delivery(theft.id(), theft.slot(), theft.original()));
        }
        save();
    }

    /** Back into the slot it came from if that's free, otherwise anywhere it fits, otherwise on the ground. */
    private void give(Player owner, int slot, ItemStack item) {
        PlayerInventory inv = owner.getInventory();
        if (slot >= 0 && slot < MAIN_SLOTS && inv.getItem(slot) == null) {
            inv.setItem(slot, item.clone());
        } else {
            inv.addItem(item.clone()).values()
                    .forEach(left -> owner.getWorld().dropItemNaturally(owner.getLocation(), left));
        }
    }

    private void deliverPending(Player owner) {
        List<Delivery> list = deliveries.remove(owner.getUniqueId());
        if (list == null) return;
        for (Delivery d : list) {
            give(owner, d.slot(), d.item());
        }
        save();
    }

    /** Removes loaned items whose theft id matches from a player's inventory (armor and off hand included) and cursor. */
    private void strip(Player p, Predicate<UUID> shouldRemove) {
        PlayerInventory inv = p.getInventory();
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length; i++) {
            UUID id = loanId(contents[i]);
            if (id != null && shouldRemove.test(id)) {
                inv.setItem(i, null);
            }
        }
        UUID cursorId = loanId(p.getItemOnCursor());
        if (cursorId != null && shouldRemove.test(cursorId)) {
            p.setItemOnCursor(null);
        }
    }

    /** Anything still tagged after its loan ended (thief was offline, item was in a crafting grid...) is deleted. */
    private void stripExpired(Player p) {
        strip(p, id -> !thefts.containsKey(id));
    }

    // ------------------------------------------------------------------ the GUI + keeping loans with the thief

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent e) {
        if (e.getInventory().getHolder() instanceof StealGui gui) {
            e.setCancelled(true);
            if (!(e.getWhoClicked() instanceof Player thief)) return;
            int slot = e.getRawSlot();
            if (slot < 0 || slot >= MAIN_SLOTS) return; // clicks in the thief's own inventory do nothing
            steal(thief, gui, slot);
            return;
        }

        ItemStack current = e.getCurrentItem();
        ItemStack cursor = e.getCursor();
        boolean currentLoaned = isLoaned(current);
        boolean cursorLoaned = isLoaned(cursor);
        if (!currentLoaned && !cursorLoaned && e.getClick() != ClickType.NUMBER_KEY) return;

        // Tucking a loaned item into a bundle would hide it from the return.
        if ((currentLoaned && isBundle(cursor)) || (cursorLoaned && isBundle(current))) {
            e.setCancelled(true);
            return;
        }

        Inventory top = e.getView().getTopInventory();
        if (top.getType() == InventoryType.CRAFTING) return; // just their own inventory is open

        boolean clickedTop = e.getRawSlot() >= 0 && e.getRawSlot() < top.getSize();

        if (currentLoaned && e.isShiftClick() && !clickedTop) {
            e.setCancelled(true); // shift-click into a chest, shulker, etc.
        } else if (cursorLoaned && clickedTop) {
            e.setCancelled(true); // put down in a chest, shulker, etc.
        } else if (e.getClick() == ClickType.NUMBER_KEY && clickedTop && e.getHotbarButton() >= 0
                && isLoaned(e.getWhoClicked().getInventory().getItem(e.getHotbarButton()))) {
            e.setCancelled(true); // hotbar-key swap into a chest, shulker, etc.
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof StealGui) {
            e.setCancelled(true);
            return;
        }
        if (!isLoaned(e.getOldCursor())) return;

        Inventory top = e.getView().getTopInventory();
        if (top.getType() == InventoryType.CRAFTING) return;
        for (int raw : e.getRawSlots()) {
            if (raw < top.getSize()) {
                e.setCancelled(true);
                return;
            }
        }
    }

    private static boolean isBundle(ItemStack item) {
        return item != null && item.getType().name().endsWith("BUNDLE");
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (isLoaned(e.getItemDrop().getItemStack())) e.setCancelled(true);
    }

    /** Item frames, armor stands, allays, horses... anything that could take the item off the thief. */
    @EventHandler(ignoreCancelled = true)
    public void onStash(PlayerInteractEntityEvent e) {
        if (e.getRightClicked() instanceof Player) return;
        PlayerInventory inv = e.getPlayer().getInventory();
        ItemStack held = e.getHand() == EquipmentSlot.HAND ? inv.getItemInMainHand() : inv.getItemInOffHand();
        if (isLoaned(held)) e.setCancelled(true);
    }

    /** Placing or eating a loan would use it up, and the owner still gets the original back. */
    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (isLoaned(e.getItemInHand())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent e) {
        if (isLoaned(e.getItem())) e.setCancelled(true);
    }

    /** If the thief dies, the loan goes straight back to its owner instead of dropping. */
    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        if (e.getKeepInventory()) return; // it stays in their inventory and is taken back on schedule

        List<UUID> ids = new ArrayList<>();
        Iterator<ItemStack> it = e.getDrops().iterator();
        while (it.hasNext()) {
            UUID id = loanId(it.next());
            if (id != null) {
                it.remove();
                ids.add(id);
            }
        }
        for (UUID id : ids) {
            resolve(id);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        plugin.getServer().getScheduler().runTask(plugin, () -> stripExpired(p));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        stripExpired(e.getPlayer());
        deliverPending(e.getPlayer());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        plugin.getServer().getScheduler().runTask(plugin, () -> deliverPending(p));
    }

    // ------------------------------------------------------------------ persistence

    private void save() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (Theft t : thefts.values()) {
            String path = "thefts." + t.id();
            cfg.set(path + ".victim", t.victim().toString());
            cfg.set(path + ".thief", t.thief().toString());
            cfg.set(path + ".returnAt", t.returnAt());
            cfg.set(path + ".slot", t.slot());
            cfg.set(path + ".item", t.original());
        }
        for (Map.Entry<UUID, List<Delivery>> entry : deliveries.entrySet()) {
            for (Delivery d : entry.getValue()) {
                String path = "deliveries." + entry.getKey() + "." + d.id();
                cfg.set(path + ".slot", d.slot());
                cfg.set(path + ".item", d.item());
            }
        }
        try {
            plugin.getDataFolder().mkdirs();
            cfg.save(dataFile);
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not save " + dataFile.getName() + ": " + ex.getMessage());
        }
    }

    private void load() {
        if (!dataFile.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(dataFile);

        ConfigurationSection theftSection = cfg.getConfigurationSection("thefts");
        if (theftSection != null) {
            for (String key : theftSection.getKeys(false)) {
                ConfigurationSection s = theftSection.getConfigurationSection(key);
                if (s == null) continue;
                try {
                    ItemStack item = s.getItemStack("item");
                    if (item == null) continue;
                    Theft theft = new Theft(UUID.fromString(key), UUID.fromString(s.getString("victim", "")),
                            UUID.fromString(s.getString("thief", "")), s.getLong("returnAt"), s.getInt("slot"), item);
                    thefts.put(theft.id(), theft);
                    schedule(theft);
                } catch (IllegalArgumentException ex) {
                    plugin.getLogger().warning("Skipping unreadable Index loan " + key);
                }
            }
        }

        ConfigurationSection deliverySection = cfg.getConfigurationSection("deliveries");
        if (deliverySection != null) {
            for (String owner : deliverySection.getKeys(false)) {
                ConfigurationSection perOwner = deliverySection.getConfigurationSection(owner);
                if (perOwner == null) continue;
                for (String key : perOwner.getKeys(false)) {
                    ConfigurationSection s = perOwner.getConfigurationSection(key);
                    if (s == null) continue;
                    try {
                        ItemStack item = s.getItemStack("item");
                        if (item == null) continue;
                        deliveries.computeIfAbsent(UUID.fromString(owner), k -> new ArrayList<>())
                                .add(new Delivery(UUID.fromString(key), s.getInt("slot"), item));
                    } catch (IllegalArgumentException ex) {
                        plugin.getLogger().warning("Skipping unreadable Index delivery " + key);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static long remaining(Map<UUID, Long> readyAt, Player p) {
        Long at = readyAt.get(p.getUniqueId());
        return at == null ? 0L : Math.max(0L, at - System.currentTimeMillis());
    }

    private static String format(long ms) {
        long seconds = (ms + 999) / 1000;
        return seconds >= 60 ? (seconds / 60) + "m " + (seconds % 60) + "s" : seconds + "s";
    }

    private static String describe(ItemStack item) {
        String name = item.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return item.getAmount() > 1 ? item.getAmount() + "x " + name : name;
    }

    private void fail(Player player, String message) {
        player.sendActionBar(Component.text(message, NamedTextColor.RED));
    }

    private void closeLater(Player player) {
        plugin.getServer().getScheduler().runTask(plugin, () -> player.closeInventory());
    }

    // ------------------------------------------------------------------ /index

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
            sender.sendMessage(Component.text("Usage: /index <player>", NamedTextColor.RED));
            return true;
        }

        Map<Integer, ItemStack> leftover = target.getInventory().addItem(createItem());
        leftover.values().forEach(item -> target.getWorld().dropItemNaturally(target.getLocation(), item));

        sender.sendMessage(Component.text("Gave Index to " + target.getName() + ".", NamedTextColor.GRAY));
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
