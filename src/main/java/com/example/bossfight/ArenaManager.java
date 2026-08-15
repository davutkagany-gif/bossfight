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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private double healAmount;
    private long healCooldownMs;
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
    private final org.bukkit.NamespacedKey keyBlazeWand;
    private double armorSetReduction;
    private double blazeHealth;
    private double blazeDropChance;
    private String blazeName;
    private String wandName;
    private double wandDamage;
    private long wandCooldownMs;

    public ArenaManager(BossFightPlugin plugin) {
        this.plugin = plugin;
        this.keyArenaMob = new org.bukkit.NamespacedKey(plugin, "arena_mob");
        this.keyBoss = new org.bukkit.NamespacedKey(plugin, "arena_boss");
        this.keySword = new org.bukkit.NamespacedKey(plugin, "ancient_sword");
        this.keyArmor = new org.bukkit.NamespacedKey(plugin, "ancient_armor");
        this.keyBlazeWand = new org.bukkit.NamespacedKey(plugin, "blaze_wand");
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
        this.healAmount = plugin.getConfig().getDouble("boss-heal-amount", 500.0);
        this.healCooldownMs = plugin.getConfig().getLong("boss-heal-cooldown-seconds", 20) * 1000L;
        this.bossName = color(plugin.getConfig().getString("boss-name", "&cKadim Zombi"));
        this.swordName = color(plugin.getConfig().getString("sword-name", "&6Kadim Kılıç"));
        this.swordDamage = plugin.getConfig().getDouble("sword-damage", 200.0);
        this.armorDropChance = plugin.getConfig().getDouble("armor-drop-chance", 5.0);
        this.armorName = color(plugin.getConfig().getString("armor-name", "&5Kadim Zırh"));
        this.armorSetReduction = plugin.getConfig().getDouble("armor-set-damage-reduction", 0.60);
        this.blazeHealth = plugin.getConfig().getDouble("blaze-health", 800.0);
        this.blazeDropChance = plugin.getConfig().getDouble("blaze-wand-drop-chance", 1.0);
        this.blazeName = color(plugin.getConfig().getString("blaze-name", "&6Dev Blaze"));
        this.wandName = color(plugin.getConfig().getString("wand-name", "&cSonsuz Alev Asası"));
        this.wandDamage = plugin.getConfig().getDouble("wand-damage", 30.0);
        this.wandCooldownMs = plugin.getConfig().getLong("wand-cooldown-ms", 1000);
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
        // Zaten bir fight varsa: katılmayı dene.
        if (session != null) {
            if (session.joinLocked) {
                player.sendMessage("§cBoss çıktı, artık katılamazsın. Bir sonrakini bekle.");
                return;
            }
            if (session.participants.contains(player.getUniqueId())) {
                player.sendMessage("§eZaten bu dövüştesin.");
                return;
            }
            // Katıl: aynı arenaya ışınla.
            session.participants.add(player.getUniqueId());
            Location joinLoc = session.arenaCenter.clone();
            joinLoc.setY(session.arenaCenter.getY() - 15);
            player.teleport(joinLoc);
            player.sendMessage("§aBoss dövüşüne katıldın!");
            broadcastToParticipants("§e" + player.getName() + " dövüşe katıldı!");
            return;
        }

        World world = entryLocation.getWorld();
        if (world == null) {
            player.sendMessage("§cArena oluşturulamadı.");
            return;
        }

        this.session = new ArenaSession(player.getUniqueId());
        this.session.participants.add(player.getUniqueId());
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
        broadcastToParticipants("§eWave " + (waveIndex + 1) + " / " + waveCounts.size() + " geliyor!");

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

    /**
     * Tüm katılımcılara mesaj gönderir.
     */
    private void broadcastToParticipants(String msg) {
        if (session == null) {
            return;
        }
        for (UUID id : session.participants) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.sendMessage(msg);
            }
        }
    }

    private void spawnBoss() {
        if (session == null) {
            return;
        }
        // Boss geldi: artık yeni katılım yok.
        session.joinLocked = true;
        broadcastToParticipants("§4§lBOSS GELİYOR!");

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
            // Not: MAX_HEALTH attribute'unun oyun içi üst sınırı vardır.
            // Önce baz değeri ayarla, sonra o attribute'un izin verdiği
            // gerçek max değere göre canı doldur (exception önlenir).
            maxHealth.setBaseValue(bossHealth);
            double effectiveMax = maxHealth.getValue();
            boss.setHealth(effectiveMax);
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
        session.lastHeal = now;

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

                // Can yenileme: can %50'nin altına inince, 20 sn cooldown ile heal et.
                var maxHealthAttr = boss.getAttribute(Attribute.MAX_HEALTH);
                if (maxHealthAttr != null) {
                    double maxHp = maxHealthAttr.getValue();
                    double curHp = boss.getHealth();
                    if (curHp < maxHp * 0.5 && now - session.lastHeal >= healCooldownMs) {
                        double newHp = Math.min(maxHp, curHp + healAmount);
                        boss.setHealth(newHp);
                        session.lastHeal = now;
                        boss.getWorld().playSound(boss.getLocation(),
                                org.bukkit.Sound.ENTITY_WITHER_SPAWN, 0.6f, 1.5f);
                        broadcastToParticipants("§cBoss canını yeniledi!");
                    }
                }

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
            // Boss'un kendi düşürdüğü item'ları event'ten temizle (elle vereceğiz).
            event.getDrops().clear();
            event.setDroppedExp(0);

            // Katılımcı listesini topla (online olanlar).
            List<Player> onlineParticipants = new ArrayList<>();
            for (UUID pid : session.participants) {
                Player p = Bukkit.getPlayer(pid);
                if (p != null) {
                    onlineParticipants.add(p);
                }
            }

            if (session.isBlaze) {
                // BLAZE boss: %1 şansla Sonsuz Alev Asası, rastgele katılımcıya.
                if (!onlineParticipants.isEmpty()
                        && ThreadLocalRandom.current().nextDouble() * 100.0 < blazeDropChance) {
                    Player lucky = onlineParticipants.get(
                            ThreadLocalRandom.current().nextInt(onlineParticipants.size()));
                    lucky.getInventory().addItem(createBlazeWand());
                    broadcastToParticipants("§6Dev Blaze asasını düşürdü! §e→ " + lucky.getName());
                }
                broadcastToParticipants("§6§lBlaze boss yenildi! Tebrikler.");
            } else {
                // ZOMBI boss: kılıç %15, zırh %5.
                if (!onlineParticipants.isEmpty()
                        && ThreadLocalRandom.current().nextDouble() * 100.0 < bossDropChance) {
                    Player lucky = onlineParticipants.get(
                            ThreadLocalRandom.current().nextInt(onlineParticipants.size()));
                    lucky.getInventory().addItem(createSword());
                    broadcastToParticipants("§aBoss kılıcı düşürdü! §e→ " + lucky.getName());
                }
                if (!onlineParticipants.isEmpty()
                        && ThreadLocalRandom.current().nextDouble() * 100.0 < armorDropChance) {
                    Player lucky = onlineParticipants.get(
                            ThreadLocalRandom.current().nextInt(onlineParticipants.size()));
                    for (ItemStack piece : createArmorSet()) {
                        lucky.getInventory().addItem(piece);
                    }
                    broadcastToParticipants("§5Boss özel zırhı düşürdü! §e→ " + lucky.getName());
                }
                broadcastToParticipants("§6§lBoss fight tamamlandı! Tebrikler.");
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

            // TÜM katılımcıları yatağına (yoksa dünya spawn'ına) ışınla.
            for (Player p : onlineParticipants) {
                Location dest = p.getRespawnLocation();
                if (dest == null) {
                    dest = Bukkit.getWorlds().get(0).getSpawnLocation();
                }
                p.teleport(dest);
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

    public ItemStack createSword() {
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
    public List<ItemStack> createArmorSet() {
        List<ItemStack> pieces = new ArrayList<>();
        pieces.add(createArmorPiece(Material.NETHERITE_HELMET, "Miğfer", "helmet", 8.0, 4.0));
        pieces.add(createArmorPiece(Material.NETHERITE_CHESTPLATE, "Göğüslük", "chest", 16.0, 5.0));
        pieces.add(createArmorPiece(Material.NETHERITE_LEGGINGS, "Pantolon", "legs", 14.0, 5.0));
        pieces.add(createArmorPiece(Material.NETHERITE_BOOTS, "Bot", "boots", 8.0, 4.0));
        return pieces;
    }

    private ItemStack createArmorPiece(Material material, String slotLabel, String keyName,
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
                            new org.bukkit.NamespacedKey(plugin, "ancient_armor_" + keyName),
                            armorValue,
                            org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER,
                            org.bukkit.inventory.EquipmentSlotGroup.ARMOR
                    ));

            // Armor toughness: hasar azaltmayı ciddi artırır.
            meta.addAttributeModifier(Attribute.ARMOR_TOUGHNESS,
                    new org.bukkit.attribute.AttributeModifier(
                            new org.bukkit.NamespacedKey(plugin, "ancient_tough_" + keyName),
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
     * Arena katılımcıları birbirine vuramaz (friendly fire kapalı).
     */
    @EventHandler(priority = org.bukkit.event.EventPriority.LOWEST)
    public void onFriendlyFire(org.bukkit.event.entity.EntityDamageByEntityEvent event) {
        if (session == null) {
            return;
        }
        if (event.getDamager() instanceof Player attacker
                && event.getEntity() instanceof Player victim) {
            if (session.participants.contains(attacker.getUniqueId())
                    && session.participants.contains(victim.getUniqueId())) {
                event.setCancelled(true);
            }
        }
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

    // ============================================================
    //  BLAZE BOSS (nether temalı, ayrı fight)
    // ============================================================

    /**
     * Blaze boss fight başlatır (altın plaka tetikler).
     * Plakanın 1000 blok üstünde netherrack zeminli, cam duvarlı arena kurar.
     */
    public void startBlazeFight(Player player, Location entryLocation) {
        if (session != null) {
            if (session.joinLocked) {
                player.sendMessage("§cBoss çıktı, artık katılamazsın.");
                return;
            }
            if (session.participants.contains(player.getUniqueId())) {
                player.sendMessage("§eZaten bu dövüştesin.");
                return;
            }
            session.participants.add(player.getUniqueId());
            Location jl = session.arenaCenter.clone();
            jl.setY(session.arenaCenter.getY() - 15);
            player.teleport(jl);
            player.sendMessage("§aBoss dövüşüne katıldın!");
            broadcastToParticipants("§e" + player.getName() + " dövüşe katıldı!");
            return;
        }

        World world = entryLocation.getWorld();
        if (world == null) {
            player.sendMessage("§cArena oluşturulamadı.");
            return;
        }

        this.session = new ArenaSession(player.getUniqueId());
        this.session.isBlaze = true;
        this.session.participants.add(player.getUniqueId());
        this.session.entryLocation = entryLocation.clone();

        Location boxCenter = entryLocation.clone();
        boxCenter.setY(Math.min(entryLocation.getY() + 1000, world.getMaxHeight() - 20));
        this.session.arenaCenter = boxCenter.clone();

        buildNetherArena(boxCenter);

        Location spawnIn = boxCenter.clone();
        spawnIn.setY(boxCenter.getY() - 15);
        player.teleport(spawnIn);
        player.sendMessage("§6Blaze boss dövüşü başlıyor! Hazır ol...");

        // Blaze fight'ta wave yok, direkt boss (biraz gecikmeyle).
        new BukkitRunnable() {
            @Override
            public void run() {
                spawnBlazeBoss();
            }
        }.runTaskLater(plugin, 60L);
    }

    /**
     * Netherrack zeminli, cam duvarlı/tavanlı 32x32x32 arena.
     */
    private void buildNetherArena(Location center) {
        World world = center.getWorld();
        int cx = center.getBlockX(), cy = center.getBlockY(), cz = center.getBlockZ();
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
                    org.bukkit.block.Block block = world.getBlockAt(x, y, z);
                    if (y == minY) {
                        block.setType(Material.NETHERRACK, false); // zemin netherrack
                    } else if (x == minX || x == maxX || y == maxY || z == minZ || z == maxZ) {
                        block.setType(Material.GLASS, false); // duvar/tavan cam
                    } else {
                        block.setType(Material.AIR, false);
                    }
                }
            }
        }
    }

    private void spawnBlazeBoss() {
        if (session == null) {
            return;
        }
        session.joinLocked = true;
        broadcastToParticipants("§4§lDEV BLAZE GELİYOR!");

        World world = session.arenaCenter.getWorld();
        Location loc = session.arenaCenter.clone();
        loc.setY(session.arenaCenter.getY() - 13); // zeminin biraz üstü
        org.bukkit.entity.Blaze boss = (org.bukkit.entity.Blaze) world.spawnEntity(loc, EntityType.BLAZE);

        boss.getPersistentDataContainer().set(keyArenaMob, PersistentDataType.BYTE, (byte) 1);
        boss.getPersistentDataContainer().set(keyBoss, PersistentDataType.BYTE, (byte) 1);

        var scaleAttr = boss.getAttribute(Attribute.SCALE);
        if (scaleAttr != null) {
            scaleAttr.setBaseValue(3.0); // büyük
        }
        var maxHealth = boss.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null) {
            maxHealth.setBaseValue(blazeHealth);
            boss.setHealth(maxHealth.getValue());
        }
        boss.customName(legacyComponent(blazeName));
        boss.setCustomNameVisible(true);
        boss.setRemoveWhenFarAway(false);

        session.bossId = boss.getUniqueId();
        session.bossSpawned = true;

        long now = System.currentTimeMillis();
        session.lastFireball = now;
        session.lastRise = now;
        session.lastRain = now;

        startBlazeAbilities();
    }

    /**
     * Blaze boss yetenek döngüsü:
     *  - Ateş topu (2 sn)
     *  - Havaya yükselip patlama + yer alev alması (10 sn)
     *  - Ateş yağmuru (15 sn)
     */
    private void startBlazeAbilities() {
        session.abilityTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (session == null || session.bossId == null || session.abilityTask == null) {
                    cancel();
                    return;
                }
                Entity be = Bukkit.getEntity(session.bossId);
                if (!(be instanceof org.bukkit.entity.Blaze boss) || boss.isDead() || !boss.isValid()) {
                    cancel();
                    return;
                }
                Player target = nearestPlayer(boss.getLocation());
                if (target == null) {
                    return;
                }
                long now = System.currentTimeMillis();

                // Ateş topu her 2 sn.
                if (now - session.lastFireball >= 2000) {
                    shootFireball2(boss, target);
                    session.lastFireball = now;
                }

                // Havaya yükselip patlama her 10 sn.
                if (now - session.lastRise >= 10000) {
                    riseAndExplode(boss);
                    session.lastRise = now;
                }

                // Ateş yağmuru her 15 sn.
                if (now - session.lastRain >= 15000) {
                    fireRain();
                    session.lastRain = now;
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    /** Blaze'in hedefe ateş topu atması. */
    private void shootFireball2(org.bukkit.entity.Blaze boss, Player target) {
        Location eye = boss.getEyeLocation();
        org.bukkit.util.Vector dir = target.getEyeLocation().toVector()
                .subtract(eye.toVector()).normalize();
        org.bukkit.entity.SmallFireball fb =
                boss.launchProjectile(org.bukkit.entity.SmallFireball.class, dir.multiply(1.2));
        fb.setShooter(boss);
        fb.setIsIncendiary(true);
        boss.getWorld().playSound(eye, org.bukkit.Sound.ENTITY_BLAZE_SHOOT, 1.0f, 0.8f);
    }

    /** Blaze yavaşça yükselir, sonra patlar ve etraf alev alır. */
    private void riseAndExplode(org.bukkit.entity.Blaze boss) {
        broadcastToParticipants("§6Dev Blaze havaya yükseliyor...");
        // 3 saniye boyunca yukarı it.
        new BukkitRunnable() {
            int ticks = 0;
            @Override
            public void run() {
                if (boss.isDead() || !boss.isValid() || session == null) {
                    cancel();
                    return;
                }
                boss.setVelocity(new org.bukkit.util.Vector(0, 0.4, 0));
                ticks += 5;
                if (ticks >= 60) { // 3 sn
                    cancel();
                    // Patla: yer alev alsın.
                    Location c = boss.getLocation();
                    boss.getWorld().createExplosion(c, 0f, false, false); // hasarsız görsel patlama
                    boss.getWorld().playSound(c, org.bukkit.Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 0.7f);
                    spreadFireOnFloor();
                }
            }
        }.runTaskTimer(plugin, 0L, 5L);
    }

    /** Arena zemininde rastgele noktaları ateşe verir. */
    private void spreadFireOnFloor() {
        if (session == null || session.arenaCenter == null) {
            return;
        }
        World world = session.arenaCenter.getWorld();
        int floorY = session.boxMinY + 1;
        for (int i = 0; i < 40; i++) {
            int x = ThreadLocalRandom.current().nextInt(session.boxMinX + 1, session.boxMaxX);
            int z = ThreadLocalRandom.current().nextInt(session.boxMinZ + 1, session.boxMaxZ);
            org.bukkit.block.Block b = world.getBlockAt(x, floorY, z);
            if (b.getType() == Material.AIR) {
                b.setType(Material.FIRE, false);
            }
        }
    }

    /** Ateş yağmuru: tavandan aşağı ateş topları düşer. */
    private void fireRain() {
        if (session == null || session.arenaCenter == null) {
            return;
        }
        broadcastToParticipants("§cAteş yağmuru!");
        World world = session.arenaCenter.getWorld();
        int topY = session.boxMaxY - 1;
        for (int i = 0; i < 15; i++) {
            int x = ThreadLocalRandom.current().nextInt(session.boxMinX + 1, session.boxMaxX);
            int z = ThreadLocalRandom.current().nextInt(session.boxMinZ + 1, session.boxMaxZ);
            Location spawn = new Location(world, x + 0.5, topY, z + 0.5);
            org.bukkit.entity.SmallFireball fb = (org.bukkit.entity.SmallFireball)
                    world.spawnEntity(spawn, EntityType.SMALL_FIREBALL);
            fb.setDirection(new org.bukkit.util.Vector(0, -1, 0));
            fb.setIsIncendiary(true);
        }
    }

    /** Sonsuz Alev Asası oluşturur. */
    public ItemStack createBlazeWand() {
        ItemStack wand = new ItemStack(Material.BLAZE_ROD);
        ItemMeta meta = wand.getItemMeta();
        if (meta != null) {
            meta.displayName(legacyComponent(wandName));
            meta.setEnchantmentGlintOverride(true);
            meta.setUnbreakable(true);
            meta.getPersistentDataContainer().set(keyBlazeWand, PersistentDataType.BYTE, (byte) 1);
            wand.setItemMeta(meta);
        }
        return wand;
    }

    private boolean isBlazeWand(ItemStack item) {
        if (item == null || item.getType() != Material.BLAZE_ROD) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer()
                .has(keyBlazeWand, PersistentDataType.BYTE);
    }

    // Asa cooldown takibi (oyuncu bazında).
    private final Map<UUID, Long> wandCooldowns = new HashMap<>();

    /** Asayla sağ tık: ateş topu at (cooldown 1 sn). */
    @EventHandler
    public void onWandUse(org.bukkit.event.player.PlayerInteractEvent event) {
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_AIR
                && event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        if (!isBlazeWand(player.getInventory().getItemInMainHand())) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = wandCooldowns.get(player.getUniqueId());
        if (last != null && now - last < wandCooldownMs) {
            return; // cooldown
        }
        wandCooldowns.put(player.getUniqueId(), now);

        org.bukkit.util.Vector dir = player.getEyeLocation().getDirection().normalize();
        org.bukkit.entity.SmallFireball fb =
                player.launchProjectile(org.bukkit.entity.SmallFireball.class, dir.multiply(1.5));
        fb.setShooter(player);
        fb.setIsIncendiary(true);
        fb.getPersistentDataContainer().set(keyBlazeWand, PersistentDataType.BYTE, (byte) 1);
        player.getWorld().playSound(player.getLocation(),
                org.bukkit.Sound.ENTITY_BLAZE_SHOOT, 1.0f, 1.2f);
    }

    /** Asadan çıkan ateş topunun hasarını 30 yap ve hedefi yak. */
    @EventHandler
    public void onWandFireballHit(org.bukkit.event.entity.EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof org.bukkit.entity.SmallFireball fb)) {
            return;
        }
        if (fb.getPersistentDataContainer().has(keyBlazeWand, PersistentDataType.BYTE)) {
            event.setDamage(wandDamage);
            if (event.getEntity() instanceof LivingEntity le) {
                le.setFireTicks(100); // 5 sn yansın
            }
        }
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
        final UUID playerId;      // fight'ı başlatan
        final java.util.Set<UUID> participants = new java.util.HashSet<>(); // tüm katılımcılar
        boolean joinLocked = false; // boss gelince true olur, yeni katılım engellenir
        int currentWave = 0;
        boolean bossSpawned = false;
        boolean isBlaze = false;  // bu fight blaze boss fight'ı mı
        long lastRise = 0L;       // blaze: son havaya yükselme
        long lastRain = 0L;       // blaze: son ateş yağmuru
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
        long lastHeal = 0L;       // son can yenileme zamanı (ms)

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
