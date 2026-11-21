package com.humanmace;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class HumanMace extends JavaPlugin implements Listener, TabExecutor {

    /** The enchants a human mace can wear. */
    private enum HE {
        DENSITY("density", "Density", Enchantment.DENSITY),
        BREACH("breach", "Breach", Enchantment.BREACH),
        WIND_BURST("wind_burst", "Wind Burst", Enchantment.WIND_BURST),
        SHARPNESS("sharpness", "Sharpness", Enchantment.SHARPNESS),
        FIRE_ASPECT("fire_aspect", "Fire Aspect", Enchantment.FIRE_ASPECT);

        final String id;
        final String display;
        final Enchantment enchantment;

        HE(String id, String display, Enchantment enchantment) {
            this.id = id;
            this.display = display;
            this.enchantment = enchantment;
        }

        /** Density and Breach can't be worn together. */
        HE opposite() {
            if (this == DENSITY) return BREACH;
            if (this == BREACH) return DENSITY;
            return null;
        }

        static HE of(Enchantment e) {
            for (HE he : values()) {
                if (he.enchantment.equals(e)) return he;
            }
            return null;
        }
    }

    /** Where a wind burst launch started, so landing back there doesn't hurt (like the real mace). */
    private record Impact(long time, double y) {}

    private NamespacedKey tokenKey;
    private NamespacedKey maceKey;
    private final Map<HE, NamespacedKey> enchKeys = new EnumMap<>(HE.class);
    private final Map<UUID, Impact> impacts = new HashMap<>();

    @Override
    public void onEnable() {
        tokenKey = new NamespacedKey(this, "token");
        maceKey = new NamespacedKey(this, "human_mace");
        for (HE he : HE.values()) {
            enchKeys.put(he, new NamespacedKey(this, "ench_" + he.id));
        }
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("enchants").setExecutor(this);
        getCommand("humanmace").setExecutor(this);

        // clean up stale wind burst landing records
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            long now = System.currentTimeMillis();
            impacts.entrySet().removeIf(en -> {
                Player p = Bukkit.getPlayer(en.getKey());
                return p == null || (now - en.getValue().time() > 750 && p.isOnGround());
            });
        }, 10L, 10L);
    }

    // ------------------------------------------------------------------ state

    private boolean isHumanMace(Player p) {
        return p.getPersistentDataContainer().has(maceKey, PersistentDataType.BYTE);
    }

    private int level(Player p, HE he) {
        Integer v = p.getPersistentDataContainer().get(enchKeys.get(he), PersistentDataType.INTEGER);
        return v == null ? 0 : v;
    }

    private void setLevel(Player p, HE he, int level) {
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        if (level <= 0) pdc.remove(enchKeys.get(he));
        else pdc.set(enchKeys.get(he), PersistentDataType.INTEGER, level);
    }

    private void clearAll(Player p) {
        p.getPersistentDataContainer().remove(maceKey);
        for (HE he : HE.values()) setLevel(p, he, 0);
    }

    // ------------------------------------------------------------------ token

    private ItemStack makeToken() {
        ItemStack it = new ItemStack(Material.NETHER_STAR);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text("Human Mace Token", NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(Component.text("Right-click to become a human mace", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)));
        m.getPersistentDataContainer().set(tokenKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    private boolean isToken(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return false;
        return it.getItemMeta().getPersistentDataContainer().has(tokenKey, PersistentDataType.BYTE);
    }

    private void giveItem(Player p, ItemStack item) {
        Map<Integer, ItemStack> left = p.getInventory().addItem(item);
        for (ItemStack l : left.values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), l);
        }
    }

    private void consumeOne(Player p, EquipmentSlot hand) {
        ItemStack it = p.getInventory().getItem(hand);
        if (it == null) return;
        if (it.getAmount() <= 1) p.getInventory().setItem(hand, null);
        else it.setAmount(it.getAmount() - 1);
    }

    private void useToken(Player p, EquipmentSlot hand) {
        if (isHumanMace(p)) {
            p.sendActionBar(Component.text("You're already a human mace!", NamedTextColor.RED));
            return;
        }
        consumeOne(p, hand);
        p.getPersistentDataContainer().set(maceKey, PersistentDataType.BYTE, (byte) 1);
        p.sendMessage(Component.text("You are now a human mace! Fall from a height to smash. "
                + "Right-click Density, Wind Burst, Sharpness, Fire Aspect or Breach books to enchant yourself, "
                + "and use /enchants to see them.", NamedTextColor.GOLD));
        World w = p.getWorld();
        w.playSound(p.getLocation(), "minecraft:item.mace.smash_air", 1.0f, 1.0f);
        w.spawnParticle(Particle.CLOUD, p.getLocation().add(0, 1, 0), 30, 0.4, 0.6, 0.4, 0.05);
    }

    // ------------------------------------------------------------------ books

    private boolean applyBook(Player p, EquipmentSlot hand, ItemStack book) {
        if (!(book.getItemMeta() instanceof EnchantmentStorageMeta meta)) return false;

        boolean handled = false;
        boolean consumed = false;
        List<ItemStack> returns = new ArrayList<>();

        for (Map.Entry<Enchantment, Integer> en : meta.getStoredEnchants().entrySet()) {
            HE he = HE.of(en.getKey());
            if (he == null) continue;
            handled = true;

            int lvl = en.getValue();
            int cur = level(p, he);
            HE other = he.opposite();

            if (other != null && level(p, other) > 0) {
                // Density <-> Breach swap: give the old book back at its old level
                int oldLvl = level(p, other);
                setLevel(p, other, 0);
                setLevel(p, he, lvl);
                returns.add(makeBook(other, oldLvl));
                consumed = true;
                p.sendMessage(Component.text("Applied " + he.display + " " + roman(lvl) + ". "
                        + other.display + " " + roman(oldLvl) + " was returned as a book.", NamedTextColor.AQUA));
            } else if (lvl > cur) {
                setLevel(p, he, lvl);
                consumed = true;
                p.sendMessage(Component.text("Applied " + he.display + " " + roman(lvl) + ".", NamedTextColor.AQUA));
            } else {
                p.sendMessage(Component.text("You already have " + he.display + " " + roman(cur)
                        + " (that book isn't an upgrade).", NamedTextColor.RED));
            }
        }

        if (consumed) {
            consumeOne(p, hand);
            for (ItemStack r : returns) giveItem(p, r);
            p.playSound(p.getLocation(), "minecraft:block.enchantment_table.use", 1.0f, 1.0f);
        }
        return handled;
    }

    private ItemStack makeBook(HE he, int level) {
        ItemStack b = new ItemStack(Material.ENCHANTED_BOOK);
        EnchantmentStorageMeta m = (EnchantmentStorageMeta) b.getItemMeta();
        m.addStoredEnchant(he.enchantment, level, true);
        b.setItemMeta(m);
        return b;
    }

    // ------------------------------------------------------------------ events

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = e.getItem();
        EquipmentSlot hand = e.getHand();
        if (item == null || hand == null) return;
        Player p = e.getPlayer();

        if (isToken(item)) {
            e.setCancelled(true);
            useToken(p, hand);
            return;
        }

        if (item.getType() == Material.ENCHANTED_BOOK && isHumanMace(p)) {
            // let people still open anvils, chests, etc. with a book in hand
            if (a == Action.RIGHT_CLICK_BLOCK && !p.isSneaking()) {
                Block b = e.getClickedBlock();
                if (b != null && b.getType().isInteractable()) return;
            }
            if (applyBook(p, hand, item)) e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (e.getCause() != DamageCause.ENTITY_ATTACK) return;
        if (!(e.getDamager() instanceof Player p) || !isHumanMace(p)) return;

        // a human mace can't also swing a normal mace
        if (p.getInventory().getItemInMainHand().getType() == Material.MACE) {
            e.setCancelled(true);
            p.sendActionBar(Component.text("You can't use a mace while you are a human mace!", NamedTextColor.RED));
            return;
        }
        if (!(e.getEntity() instanceof LivingEntity victim)) return;

        double damage = e.getDamage();

        // Sharpness: flat +2.5
        if (level(p, HE.SHARPNESS) > 0) damage += 2.5;

        // Mace smash: same maths as the real mace
        float fall = p.getFallDistance();
        boolean smash = fall > 1.5f && !p.isGliding();
        if (smash) {
            damage += smashBonus(fall) + 0.5 * level(p, HE.DENSITY) * fall;
        }

        // Breach: works on every hit, falling or not
        int breach = level(p, HE.BREACH);
        if (breach > 0) damage = breachDamage(victim, damage, breach);

        e.setDamage(damage);

        // Fire Aspect: works on every hit, falling or not
        int fire = level(p, HE.FIRE_ASPECT);
        if (fire > 0) victim.setFireTicks(Math.max(victim.getFireTicks(), 80 * fire));

        if (smash) {
            final Location targetLoc = victim.getLocation();
            final boolean ground = victim.isOnGround();
            final int wind = level(p, HE.WIND_BURST);
            Bukkit.getScheduler().runTask(this, () -> postSmash(p, victim, targetLoc, ground, fall, wind));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFall(EntityDamageEvent e) {
        if (e.getCause() != DamageCause.FALL || !(e.getEntity() instanceof Player p)) return;
        Impact imp = impacts.remove(p.getUniqueId());
        if (imp == null) return;
        // after a wind burst launch only the drop below the launch point counts
        double dist = Math.min(p.getFallDistance(), imp.y() - p.getLocation().getY());
        double wanted = dist - 3.0;
        if (wanted <= 0) e.setCancelled(true);
        else e.setDamage(Math.min(e.getDamage(), wanted));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        impacts.remove(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ mace maths

    private static double smashBonus(double h) {
        if (h <= 3.0) return 4.0 * h;
        if (h <= 8.0) return 12.0 + 2.0 * (h - 3.0);
        return 22.0 + (h - 8.0);
    }

    /** Breach: each level makes the victim's armor 15% less effective. */
    private static double breachDamage(LivingEntity victim, double dmg, int level) {
        double armor = attr(victim, Attribute.ARMOR);
        double tough = attr(victim, Attribute.ARMOR_TOUGHNESS);
        if (armor <= 0) return dmg;
        double h = Math.min(Math.max(armor - dmg / (2.0 + tough / 4.0), armor * 0.2), 20.0) / 25.0;
        double hb = h * Math.max(0.0, 1.0 - 0.15 * level);
        return dmg * (1.0 - hb) / (1.0 - h);
    }

    private static double attr(LivingEntity e, Attribute a) {
        AttributeInstance i = e.getAttribute(a);
        return i == null ? 0.0 : i.getValue();
    }

    private void postSmash(Player p, LivingEntity victim, Location tl, boolean ground, float fall, int wind) {
        if (!p.isOnline() || p.isDead()) return;
        World w = tl.getWorld();

        // sound + dust
        String sound = !ground ? "minecraft:item.mace.smash_air"
                : fall > 5 ? "minecraft:item.mace.smash_ground_heavy" : "minecraft:item.mace.smash_ground";
        w.playSound(tl, sound, 1.0f, 1.0f);
        w.spawnParticle(Particle.CLOUD, tl, 20, 0.5, 0.1, 0.5, 0.05);

        // knock back everything standing near the target
        for (Entity en : w.getNearbyEntities(tl, 3.5, 3.5, 3.5)) {
            if (!(en instanceof LivingEntity le) || le instanceof ArmorStand) continue;
            if (le.equals(p) || le.equals(victim)) continue;
            if (le instanceof Tameable t && t.getOwner() != null
                    && t.getOwner().getUniqueId().equals(p.getUniqueId())) continue;

            Vector dir = le.getLocation().toVector().subtract(tl.toVector());
            double dist = dir.length();
            if (dist > 3.5) continue;
            dir.setY(0);
            if (dir.lengthSquared() < 1.0E-4) continue;
            double power = (3.5 - dist) * 0.7 * (fall > 5 ? 2.0 : 1.0);
            if (power <= 0) continue;
            dir.normalize().multiply(power);
            le.setVelocity(le.getVelocity().add(new Vector(dir.getX(), 0.7, dir.getZ())));
        }

        // the smash cancels your fall; Wind Burst launches you back up
        p.setFallDistance(0f);
        Vector v = p.getVelocity();
        if (wind > 0) {
            double vy = Math.min(1.2 + 0.3 * (wind - 1), 3.0);
            p.setVelocity(v.setY(vy));
            impacts.put(p.getUniqueId(), new Impact(System.currentTimeMillis(), p.getLocation().getY()));
            w.playSound(p.getLocation(), "minecraft:entity.wind_charge.wind_burst", 1.0f, 1.0f);
            w.spawnParticle(Particle.CLOUD, p.getLocation(), 30, 0.4, 0.1, 0.4, 0.05);
        } else {
            p.setVelocity(v.setY(0.01));
        }
    }

    // ------------------------------------------------------------------ commands

    private static String roman(int n) {
        String[] r = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return n >= 1 && n <= 10 ? r[n] : String.valueOf(n);
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (c.getName().equalsIgnoreCase("enchants")) {
            if (!(s instanceof Player p)) {
                s.sendMessage("Only players can use this.");
                return true;
            }
            if (!isHumanMace(p)) {
                p.sendMessage(Component.text("You're not a human mace.", NamedTextColor.RED));
                return true;
            }
            List<HE> active = new ArrayList<>();
            for (HE he : HE.values()) {
                if (level(p, he) > 0) active.add(he);
            }
            if (active.isEmpty()) {
                p.sendMessage(Component.text("You're a human mace with no enchants yet.", NamedTextColor.GRAY));
                return true;
            }
            p.sendMessage(Component.text("Your human mace enchants:", NamedTextColor.GOLD));
            for (HE he : active) {
                p.sendMessage(Component.text(" - ", NamedTextColor.DARK_GRAY)
                        .append(Component.text(he.display + " " + roman(level(p, he)), NamedTextColor.AQUA)));
            }
            return true;
        }

        // /humanmace <give|remove> [player]
        if (a.length == 0) {
            s.sendMessage(Component.text("Usage: /humanmace <give|remove> [player]", NamedTextColor.RED));
            return true;
        }
        Player target = a.length > 1 ? Bukkit.getPlayerExact(a[1]) : (s instanceof Player pl ? pl : null);
        if (target == null) {
            s.sendMessage(Component.text("Player not found.", NamedTextColor.RED));
            return true;
        }
        switch (a[0].toLowerCase()) {
            case "give" -> {
                giveItem(target, makeToken());
                s.sendMessage(Component.text("Gave a Human Mace Token to " + target.getName() + ".", NamedTextColor.GREEN));
            }
            case "remove" -> {
                clearAll(target);
                s.sendMessage(Component.text(target.getName() + " is no longer a human mace.", NamedTextColor.GREEN));
            }
            default -> s.sendMessage(Component.text("Usage: /humanmace <give|remove> [player]", NamedTextColor.RED));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        if (!c.getName().equalsIgnoreCase("humanmace")) return List.of();
        if (a.length == 1) return List.of("give", "remove");
        if (a.length == 2) return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        return List.of();
    }
}
