package com.example.bossfight;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Arena, wave ve boss yönetimi.
 *
 * Kurallar:
 *  - Sadece iskelet ve zombi spawn olur (creeper YOK).
 *  - 3 wave sırayla; her wave temizlenince sonraki başlar.
 *  - 3. wave sonrası boss (büyük zombi) gelir.
 *  - Boss ölünce %50 şansla kılıç düşer.
 */
public class ArenaManager implements Listener {

    private final BossFightPlugin plugin;

    private Location arenaLocation;
    private List<Integer> waveCounts = new ArrayList<>();
    private double bossDropChance;
    private double bossHealth;
    private long teleportCooldownMs;
    private long fireballCooldownMs;
    private String bossName;
    private String swordName;
    private double swordDamage;
    private double armorDropChance;
    private String armorName;

    // Aktif fight durumu (aynı anda tek fight varsayımı; birden fazla için haritaya çevrilebilir).
    private ArenaSession session;

    // Bu plugin tarafından spawn edilen varlıkları işaretlemek için anahtarlar.
    private final org.bukkit.NamespacedKey keyArenaMob;
    private final org.bukkit.NamespacedKey keyBoss;
    private final org.bukkit.NamespacedKey keySword;
    private final org.bukkit.NamespacedKey keyArmor;
    private double armorSetReduction;

    public ArenaManager(BossFightPlugin plugin) {
        this.plugin = plugin;
        this.keyArenaMob = new org.bukkit.NamespacedKey(plugin, "arena_mob");
        this.keyBoss = new org.bukkit.NamespacedKey(plugin, "arena_boss");
        this.keySword = new org.bukkit.NamespacedKey(plugin, "ancient_sword");
        this.keyArmor = new org.bukkit.NamespacedKey(plugin, "ancient_armor");
        reloadArenaLocation();
    }

    public void reloadArenaLocation() {
        String worldName = plugin.getConfig().getString("arena.world", "world");
        World world = Bukkit.getWorld(worldName);
        double x = plugin.getConfig().getDouble("arena.x", 0.5);
        double y = plugin.getConfig().getDouble("arena.y", 100.0);
        double z = plugin.getConfig().getDouble("arena.z", 0.5);
        this.arenaLocation = (world != null) ? new Location(world, x, y, z) : null;

        this.waveCounts = plugin.getConfig().getIntegerList("waves");
        if (waveCounts.isEmpty()) {
            waveCounts = List.of(6, 8, 10);
        }
        this.bossDropChance = plugin.getConfig().getDouble("boss-drop-chance", 50.0);
        this.bossHealth = plugin.getConfig().getDouble("boss-health", 150.0);
        this.teleportCooldownMs = plugin.getConfig().getLong("boss-teleport-cooldown-seconds", 20) * 1000L;
        this.fireballCooldownMs = plugin.getConfig().getLong("boss-fireball-cooldown-seconds", 30) * 1000L;
        this.bossName = color(plugin.getConfig().getString("boss-name", "&cKadim Zombi"));
        this.swordName = color(plugin.getConfig().getString("sword-name", "&6Kadim Kılıç"));
        this.swordDamage = plugin.getConfig().getDouble("sword-damage", 200.0);
        this.armorDropChance = plugin.getConfig().getDouble("armor-drop-chance", 5.0);
        this.armorName = color(plugin.getConfig().getString("armor-name", "&5Kadim Zırh"));
        this.armorSetReduction = plugin.getConfig().getDouble("armor-set-damage-reduction", 0.92);
    }

    private static String color(String s) {
        return s == null ? "" : s.replace('&', '§');
    }

    /**
     * Oyuncu için boss fight başlatır.
     * Plakanın 1000 blok üstünde 32x32x32 cam kutu oluşturur ve fight orada geçer.
     * @param entryLocation oyuncunun plakaya bastığı yer (fight bitince buraya döner).
     */
    public void startFight(Player player, Location entryLocation) {
        if (session != null) {
            player.sendMessage("§cŞu anda başka bir boss fight sürüyor. Lütfen bekle.");
            return;
        }

        World world = entryLocation.getWorld();
        if (world == null) {
            player.sendMessage("§cArena oluşturulamadı.");
            return;
        }

        this.session = new ArenaSession(player.getUniqueId());
        this.session.entryLocation = entryLocation.clone();

        // Arena merkezi: plakanın 1000 blok üstü.
        Location boxCenter = entryLocation.clone();
        boxCenter.setY(Math.min(entryLocation.getY() + 1000, world.getMaxHeight() - 20));
        this.session.arenaCenter = boxCenter.clone();

        // 32x32x32 cam kutuyu inşa et (içi boş, 6 yüzü cam).
        buildGlassBox(boxCenter);

        // Oyuncuyu kutunun içine (zeminin biraz üstüne) ışınla.
        Location spawnIn = boxCenter.clone();
        spawnIn.setY(boxCenter.getY() - 15); // zemin, kutunun tabanına yakın
        player.teleport(spawnIn);
        player.sendMessage("§6Boss fight başlıyor! Hazır ol...");

        // Kısa gecikme sonra ilk wave.
        new BukkitRunnable() {
            @Override
            public void run() {
                startWave(0);
            }
        }.runTaskLater(plugin, 60L); // 3 sn
    }

    // Cam kutunun yarı boyutu (32x32x32 => merkezden 16 blok).
    private static final int BOX_HALF = 16;

    /**
     * Merkez etrafında 32x32x32 içi boş cam kutu inşa eder.
     * Kutu koordinatlarını session'a kaydeder (sonra temizlemek için).
     */
    private void buildGlassBox(Location center) {
        World world = center.getWorld();
        int cx = center.getBlockX();
        int cy = center.getBlockY();
        int cz = center.getBlockZ();

        int minX = cx - BOX_HALF, maxX = cx + BOX_HALF;
        int minY = cy - BOX_HALF, maxY = cy + BOX_HALF;
        int minZ = cz - BOX_HALF, maxZ = cz + BOX_HALF;

        session.boxMinX = minX; session.boxMaxX = maxX;
        session.boxMinY = minY; session.boxMaxY = maxY;
        session.boxMinZ = minZ; session.boxMaxZ = maxZ;
        session.boxWorldName = world.getName();

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    boolean isShell = (x == minX || x == maxX
                            || y == minY || y == maxY
                            || z == minZ || z == maxZ);
                    org.bukkit.block.Block block = world.getBlockAt(x, y, z);
                    if (isShell) {
                        block.setType(Material.GLASS, false);
                    } else {
                        block.setType(Material.AIR, false);
                    }
                }
            }
        }
    }

    /**
     * Cam kutuyu tamamen kaldırır (hepsini hava yapar).
     */
    private void removeGlassBox() {
        if (session == null || session.boxWorldName == null) {
            return;
        }
        World world = Bukkit.getWorld(session.boxWorldName);
        if (world == null) {
            return;
        }
        for (int x = session.boxMinX; x <= session.boxMaxX; x++) {
            for (int y = session.boxMinY; y <= session.boxMaxY; y++) {
                for (int z = session.boxMinZ; z <= session.boxMaxZ; z++) {
                    world.getBlockAt(x, y, z).setType(Material.AIR, false);
                }
            }
        }
    }

    private void startWave(int waveIndex) {
        if (session == null) {
            return;
        }
        if (waveIndex >= waveCounts.size()) {
            spawnBoss();
            return;
        }

        session.currentWave = waveIndex;
        session.aliveMobs.clear();

        int count = waveCounts.get(waveIndex);
        Player player = Bukkit.getPlayer(session.playerId);
        if (player != null) {
            player.sendMessage("§eWave " + (waveIndex + 1) + " / " + waveCounts.size() + " geliyor!");
        }

        World world = session.arenaCenter.getWorld();
        Location floor = session.arenaCenter.clone();
        floor.setY(session.arenaCenter.getY() - 15); // kutu zemini
        for (int i = 0; i < count; i++) {
            Location spawnLoc = randomAround(floor, 12);
            spawnLoc.setY(floor.getY()); // zeminde spawn
            // Sadece iskelet veya zombi (creeper yok).
            EntityType type = ThreadLocalRandom.current().nextBoolean()
                    ? EntityType.ZOMBIE : EntityType.SKELETON;

            LivingEntity mob = (LivingEntity) world.spawnEntity(spawnLoc, type);
            mob.getPersistentDataContainer().set(keyArenaMob, PersistentDataType.BYTE, (byte) 1);
            session.aliveMobs.add(mob.getUniqueId());
        }
    }

    private void spawnBoss() {
        if (session == null) {
            return;
        }
        Player player = Bukkit.getPlayer(session.playerId);
        if (player != null) {
            player.sendMessage("§4§lBOSS GELİYOR!");
        }

        World world = session.arenaCenter.getWorld();
        Location bossLoc = session.arenaCenter.clone();
        bossLoc.setY(session.arenaCenter.getY() - 15); // kutu zemini
        Zombie boss = (Zombie) world.spawnEntity(bossLoc, EntityType.ZOMBIE);

        boss.getPersistentDataContainer().set(keyArenaMob, PersistentDataType.BYTE, (byte) 1);
        boss.getPersistentDataContainer().set(keyBoss, PersistentDataType.BYTE, (byte) 1);

        // Boss'u "büyük zombi" yap.
        boss.setAdult();
        boss.setBaby(false);
        var scaleAttr = boss.getAttribute(Attribute.SCALE);
        if (scaleAttr != null) {
            scaleAttr.setBaseValue(1.6); // biraz büyük
        }
        var maxHealth = boss.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null) {
            maxHealth.setBaseValue(bossHealth);
            boss.setHealth(bossHealth);
        }
        boss.customName(legacyComponent(bossName));
        boss.setCustomNameVisible(true);
        boss.setRemoveWhenFarAway(false);

        session.bossId = boss.getUniqueId();
        session.bossSpawned = true;

        // Cooldownları boss spawn anına sıfırla: ilk ışınlanma 20 sn, ilk ateş topu 30 sn sonra.
        long now = System.currentTimeMillis();
        session.lastTeleport = now;
        session.lastFireball = now;

        startBossAbilities();
    }

    /**
     * Boss yetenek döngüsü:
     *  - En yakın oyuncunun arkasına ışınlanma (cooldown 20 sn)
     *  - Ateş topu fırlatma (cooldown 30 sn)
     * Her saniyede bir kontrol edilir.
     */
    private void startBossAbilities() {
        session.abilityTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (session == null || session.bossId == null || session.abilityTask == null) {
                    cancel();
                    return;
                }
                Entity bossEntity = Bukkit.getEntity(session.bossId);
                if (!(bossEntity instanceof Zombie boss) || boss.isDead() || !boss.isValid()) {
                    cancel();
                    return;
                }

                Player target = nearestPlayer(boss.getLocation());
                if (target == null) {
                    return;
                }

                long now = System.currentTimeMillis();

                // Işınlanma: cooldown (varsayılan 20 sn).
                if (now - session.lastTeleport >= teleportCooldownMs) {
                    teleportBehind(boss, target);
                    session.lastTeleport = now;
                }

                // Ateş topu: cooldown (varsayılan 30 sn).
                if (now - session.lastFireball >= fireballCooldownMs) {
                    shootFireball(boss, target);
                    session.lastFireball = now;
                }
            }
        }.runTaskTimer(plugin, 20L, 20L); // her 1 sn
    }

    /**
     * Bossu hedef oyuncunun tam arkasına ışınlar.
     */
    private void teleportBehind(Zombie boss, Player target) {
        Location tLoc = target.getLocation();
        org.bukkit.util.Vector behind = tLoc.getDirection().normalize().multiply(-2.0);
        Location dest = tLoc.clone().add(behind);
        // Gökyüzü arenası: zemin araması yok, oyuncunun Y'sinde kal.
        dest.setY(tLoc.getY());
        // Bossun hedefe bakmasını sağla.
        org.bukkit.util.Vector look = tLoc.toVector().subtract(dest.toVector());
        if (look.lengthSquared() > 0) {
            dest.setDirection(look);
        }
        boss.teleport(dest);
        boss.setTarget(target);
        World world = dest.getWorld();
        if (world != null) {
            world.playSound(dest, org.bukkit.Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 0.8f);
        }
    }

    /**
     * Bossun hedefe ateş topu fırlatmasını sağlar.
     */
    private void shootFireball(Zombie boss, Player target) {
        Location eye = boss.getEyeLocation();
        org.bukkit.util.Vector dir = target.getEyeLocation().toVector()
                .subtract(eye.toVector()).normalize();

        org.bukkit.entity.Fireball fireball =
                boss.launchProjectile(org.bukkit.entity.Fireball.class, dir.multiply(1.2));
        fireball.setShooter(boss);
        fireball.setYield(2.0f);          // patlama gücü
        fireball.setIsIncendiary(true);
        fireball.setDirection(dir);
        boss.getWorld().playSound(eye, org.bukkit.Sound.ENTITY_BLAZE_SHOOT, 1.0f, 1.0f);
    }

    /**
     * Verilen konuma en yakın oyuncuyu (aynı dünyada) bulur.
     */
    private Player nearestPlayer(Location from) {
        Player nearest = null;
        double best = Double.MAX_VALUE;
        for (Player p : from.getWorld().getPlayers()) {
            if (p.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
                continue;
            }
            double d = p.getLocation().distanceSquared(from);
            if (d < best) {
                best = d;
                nearest = p;
            }
        }
        return nearest;
    }

    /**
     * Arena çevresinde blok kırmayı engelle (arena kırılamasın).
     */
    @EventHandler
    public void onBlockBreak(org.bukkit.event.block.BlockBreakEvent event) {
        if (isInArenaZone(event.getBlock().getLocation()) && !event.getPlayer().isOp()) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§cArena bloklarını kıramazsın.");
        }
    }

    /**
     * Arena çevresinde blok koymayı engelle.
     */
    @EventHandler
    public void onBlockPlace(org.bukkit.event.block.BlockPlaceEvent event) {
        if (isInArenaZone(event.getBlock().getLocation()) && !event.getPlayer().isOp()) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§cArena içine blok koyamazsın.");
        }
    }

    /**
     * Patlamaların (ateş topu dahil) arena bloklarına zarar vermesini engelle.
     */
    @EventHandler
    public void onEntityExplode(org.bukkit.event.entity.EntityExplodeEvent event) {
        event.blockList().removeIf(block -> isInArenaZone(block.getLocation()));
    }

    @EventHandler
    public void onBlockExplode(org.bukkit.event.block.BlockExplodeEvent event) {
        event.blockList().removeIf(block -> isInArenaZone(block.getLocation()));
    }

    /**
     * Bir konumun korunan arena bölgesi içinde olup olmadığını döndürür.
     * Bölge: arena merkezinin çevresinde küresel bir alan (yarıçap 40 blok).
     */
    private boolean isInArenaZone(Location loc) {
        if (session == null || session.arenaCenter == null || session.arenaCenter.getWorld() == null) {
            return false;
        }
        if (loc.getWorld() == null || !loc.getWorld().equals(session.arenaCenter.getWorld())) {
            return false;
        }
        return loc.distanceSquared(session.arenaCenter) <= 40 * 40;
    }

    /**
     * Arena moblarının (zombi/iskelet) güneşte yanmasını engelle.
     */
    @EventHandler
    public void onCombust(org.bukkit.event.entity.EntityCombustEvent event) {
        if (event.getEntity().getPersistentDataContainer()
                .has(keyArenaMob, PersistentDataType.BYTE)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        if (session == null) {
            return;
        }
        LivingEntity dead = event.getEntity();
        UUID id = dead.getUniqueId();

        boolean isBoss = dead.getPersistentDataContainer()
                .has(keyBoss, PersistentDataType.BYTE);

        if (isBoss && id.equals(session.bossId)) {
            // Normal zombi droplarını temizle.
            event.getDrops().clear();
            event.setDroppedExp(0);

            Player player = Bukkit.getPlayer(session.playerId);
            Location entry = session.entryLocation;

            // Kılıç: config şansı (varsayılan %15).
            if (ThreadLocalRandom.current().nextDouble() * 100.0 < bossDropChance) {
                event.getDrops().add(createSword());
                if (player != null) {
                    player.sendMessage("§aBoss kılıcı düşürdü!");
                }
            }

            // Özel zırh: config şansı (varsayılan %5).
            if (ThreadLocalRandom.current().nextDouble() * 100.0 < armorDropChance) {
                for (ItemStack piece : createArmorSet()) {
                    event.getDrops().add(piece);
                }
                if (player != null) {
                    player.sendMessage("§5Boss özel zırhı düşürdü!");
                }
            }

            if (player != null) {
                player.sendMessage("§6§lBoss fight tamamlandı! Tebrikler.");
            }

            if (session.abilityTask != null) {
                session.abilityTask.cancel();
                session.abilityTask = null;
            }

            // Kalan arena moblarını temizle.
            World w = session.arenaCenter != null ? session.arenaCenter.getWorld() : null;
            if (w != null) {
                for (Entity e : w.getEntities()) {
                    if (e.getPersistentDataContainer().has(keyArenaMob, PersistentDataType.BYTE)) {
                        e.remove();
                    }
                }
            }

            // Cam kutuyu kaldır.
            removeGlassBox();

            // Oyuncuyu yatağına ışınla; yatak yoksa dünyanın spawn noktasına.
            if (player != null) {
                Location dest = player.getRespawnLocation();
                if (dest == null) {
                    World spawnWorld = (entry != null && entry.getWorld() != null)
                            ? entry.getWorld()
                            : Bukkit.getWorlds().get(0);
                    dest = spawnWorld.getSpawnLocation();
                }
                player.teleport(dest);
            }

            session = null;
            return;
        }

        // Normal arena mob'u öldü.
        if (session.aliveMobs.remove(id)) {
            if (session.aliveMobs.isEmpty() && !session.bossSpawned) {
                // Sonraki wave (veya boss).
                final int next = session.currentWave + 1;
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        startWave(next);
                    }
                }.runTaskLater(plugin, 40L); // 2 sn ara
            }
        }
    }

    /**
     * Arena aktifken creeper spawn'ını engelle (ekstra güvenlik; doğal spawn'lar için).
     * Sadece bu plugin sadece iskelet/zombi spawn ettiği için bu, doğal creeper'ları hedefler.
     */
    @EventHandler
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (session == null || session.arenaCenter == null) {
            return;
        }
        if (event.getEntityType() != EntityType.CREEPER) {
            return;
        }
        // Arena çevresindeyse creeper spawn'ını iptal et.
        if (event.getLocation().getWorld() != null
                && event.getLocation().getWorld().equals(session.arenaCenter.getWorld())
                && event.getLocation().distanceSquared(session.arenaCenter) <= 30 * 30) {
            event.setCancelled(true);
        }
    }

    private ItemStack createSword() {
        ItemStack sword = new ItemStack(Material.NETHERITE_SWORD);
        ItemMeta meta = sword.getItemMeta();
        if (meta != null) {
            meta.displayName(legacyComponent(swordName));

            // Gerçek enchant YOK ama büyülü parıltı görünsün (1.20.5+ Paper API).
            meta.setEnchantmentGlintOverride(true);
            meta.setUnbreakable(true);

            // Bu kılıcı işaretle; hasar event'inde tam olarak swordDamage uygulanır.
            meta.getPersistentDataContainer().set(keySword, PersistentDataType.BYTE, (byte) 1);

            sword.setItemMeta(meta);
        }
        return sword;
    }

    private boolean isAncientSword(ItemStack item) {
        if (item == null || item.getType() != Material.NETHERITE_SWORD) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer()
                .has(keySword, PersistentDataType.BYTE);
    }

    /**
     * Özel zırh seti (netherite tabanlı, yüksek koruma + tokluk).
     * Bu kılıcın (200 hasar) karşısında oyuncunun hayatta kalabilmesi için
     * yüksek armor, armor toughness ve Protection IV verilir.
     */
    private List<ItemStack> createArmorSet() {
        List<ItemStack> pieces = new ArrayList<>();
        pieces.add(createArmorPiece(Material.NETHERITE_HELMET, "Miğfer", 8.0, 4.0));
        pieces.add(createArmorPiece(Material.NETHERITE_CHESTPLATE, "Göğüslük", 16.0, 5.0));
        pieces.add(createArmorPiece(Material.NETHERITE_LEGGINGS, "Pantolon", 14.0, 5.0));
        pieces.add(createArmorPiece(Material.NETHERITE_BOOTS, "Bot", 8.0, 4.0));
        return pieces;
    }

    private ItemStack createArmorPiece(Material material, String slotLabel,
                                       double armorValue, double toughnessValue) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(legacyComponent(armorName + " §7- " + slotLabel));
            meta.setEnchantmentGlintOverride(true);
            meta.setUnbreakable(true);

            // Özel zırh işareti (survivability için kontrol edilir).
            meta.getPersistentDataContainer().set(keyArmor, PersistentDataType.BYTE, (byte) 1);

            // Yüksek koruma: Protection IV.
            meta.addEnchant(Enchantment.PROTECTION, 4, true);

            // Ekstra armor değeri.
            meta.addAttributeModifier(Attribute.ARMOR,
                    new org.bukkit.attribute.AttributeModifier(
                            new org.bukkit.NamespacedKey(plugin, "ancient_armor_" + slotLabel.toLowerCase()),
                            armorValue,
                            org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER,
                            org.bukkit.inventory.EquipmentSlotGroup.ARMOR
                    ));

            // Armor toughness: hasar azaltmayı ciddi artırır.
            meta.addAttributeModifier(Attribute.ARMOR_TOUGHNESS,
                    new org.bukkit.attribute.AttributeModifier(
                            new org.bukkit.NamespacedKey(plugin, "ancient_tough_" + slotLabel.toLowerCase()),
                            toughnessValue,
                            org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER,
                            org.bukkit.inventory.EquipmentSlotGroup.ARMOR
                    ));

            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * Bir zırh parçasının özel (Kadim) zırh olup olmadığını kontrol eder.
     */
    private boolean isAncientArmor(ItemStack item) {
        if (item == null) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer()
                .has(keyArmor, PersistentDataType.BYTE);
    }

    /**
     * Kılıç hasarı: saldırgan Kadim kılıç tutuyorsa hasarı tam swordDamage yap.
     * LOWEST önce çalışır ki zırh azaltması sonradan bu değeri işlesin.
     */
    @EventHandler(priority = org.bukkit.event.EventPriority.LOWEST)
    public void onSwordHit(org.bukkit.event.entity.EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) {
            return;
        }
        if (isAncientSword(attacker.getInventory().getItemInMainHand())) {
            event.setDamage(swordDamage);
        }
    }

    /**
     * Zırh survivability: kurban tam Kadim zırh seti giyiyorsa hasarı azalt.
     * HIGH önceliği ile kılıç hasarı set edildikten SONRA çalışır.
     */
    @EventHandler(priority = org.bukkit.event.EventPriority.HIGH)
    public void onPlayerDamage(org.bukkit.event.entity.EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        org.bukkit.inventory.PlayerInventory inv = player.getInventory();
        boolean fullSet = isAncientArmor(inv.getHelmet())
                && isAncientArmor(inv.getChestplate())
                && isAncientArmor(inv.getLeggings())
                && isAncientArmor(inv.getBoots());
        if (fullSet) {
            event.setDamage(event.getDamage() * (1.0 - armorSetReduction));
        }
    }

    private Component legacyComponent(String legacy) {
        // "§" (section) renk kodlu metni Component'e çevir.
        return LegacyComponentSerializer.legacySection().deserialize(legacy)
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false);
    }

    private Location randomAround(Location center, int radius) {
        double dx = ThreadLocalRandom.current().nextDouble(-radius, radius);
        double dz = ThreadLocalRandom.current().nextDouble(-radius, radius);
        // Gökyüzü arenası: zemini arama, aynı Y'de kal.
        return center.clone().add(dx, 0, dz);
    }

    public void cleanupAll() {
        if (session != null && session.abilityTask != null) {
            session.abilityTask.cancel();
            session.abilityTask = null;
        }
        if (session != null && session.arenaCenter != null && session.arenaCenter.getWorld() != null) {
            for (Entity e : session.arenaCenter.getWorld().getEntities()) {
                if (e.getPersistentDataContainer().has(keyArenaMob, PersistentDataType.BYTE)) {
                    e.remove();
                }
            }
            removeGlassBox();
        }
        session = null;
    }

    /**
     * Tek aktif fight'ın durumunu tutar.
     */
    private static final class ArenaSession {
        final UUID playerId;
        int currentWave = 0;
        boolean bossSpawned = false;
        UUID bossId;
        Location entryLocation;   // fight bitince dönülecek yer (plakaya basılan konum)
        Location arenaCenter;     // cam kutunun merkezi (fight burada geçer)
        // Cam kutu sınırları (temizlemek için).
        String boxWorldName;
        int boxMinX, boxMaxX, boxMinY, boxMaxY, boxMinZ, boxMaxZ;
        final List<UUID> aliveMobs = new ArrayList<>();

        // Boss yetenekleri.
        org.bukkit.scheduler.BukkitTask abilityTask;
        long lastTeleport = 0L;   // son ışınlanma zamanı (ms)
        long lastFireball = 0L;   // son ateş topu zamanı (ms)

        ArenaSession(UUID playerId) {
            this.playerId = playerId;
            // Boss spawn olunca yeteneklerin hemen değil, cooldown geçince gelmesi için
            // başlangıç zamanlarını "şimdi" yapıyoruz.
            long now = System.currentTimeMillis();
            this.lastTeleport = now;
            this.lastFireball = now;
        }
    }
}
