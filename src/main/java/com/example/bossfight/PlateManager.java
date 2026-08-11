package com.example.bossfight;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Plakaların spawn edilmesi ve basıldığında arenayı tetiklemesinden sorumlu.
 */
public class PlateManager implements Listener {

    private final BossFightPlugin plugin;
    private final ArenaManager arenaManager;

    // Bu plugin tarafından spawn edilen aktif plaka konumları.
    private final Set<Location> activePlates = new HashSet<>();

    private BukkitTask spawnTask;

    // Ayarlar
    private double spawnChance;
    private int intervalSeconds;
    private int spawnRadius;
    private int regionChunkRadius;
    private int maxActivePlates;

    // Arena tetikleyicisi olarak kullanılan plaka türü.
    private static final Material PLATE_MATERIAL = Material.HEAVY_WEIGHTED_PRESSURE_PLATE;

    public PlateManager(BossFightPlugin plugin, ArenaManager arenaManager) {
        this.plugin = plugin;
        this.arenaManager = arenaManager;
        reloadSettings();
    }

    public void reloadSettings() {
        this.spawnChance = plugin.getConfig().getDouble("plate-spawn-chance", 15.0);
        this.intervalSeconds = plugin.getConfig().getInt("plate-spawn-interval-seconds", 60);
        this.spawnRadius = plugin.getConfig().getInt("plate-spawn-radius", 20);
        this.regionChunkRadius = plugin.getConfig().getInt("region-chunk-radius", 32);
        this.maxActivePlates = plugin.getConfig().getInt("max-active-plates", 3);
    }

    public void startSpawnTask() {
        long ticks = Math.max(1L, intervalSeconds) * 20L;
        this.spawnTask = Bukkit.getScheduler().runTaskTimer(plugin, this::trySpawnForAllPlayers, ticks, ticks);
    }

    public void stopSpawnTask() {
        if (spawnTask != null) {
            spawnTask.cancel();
            spawnTask = null;
        }
    }

    private void trySpawnForAllPlayers() {
        if (activePlates.size() >= maxActivePlates) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (activePlates.size() >= maxActivePlates) {
                break;
            }
            // %15 (config) şansı.
            if (ThreadLocalRandom.current().nextDouble() * 100.0 < spawnChance) {
                spawnPlateNear(player.getLocation());
            }
        }
    }

    /**
     * Verilen konumun etrafında, 32 chunkluk bölge içinde uygun bir yere plaka spawn eder.
     * @return spawn başarılıysa true.
     */
    public boolean spawnPlateNear(Location center) {
        if (activePlates.size() >= maxActivePlates) {
            return false;
        }
        World world = center.getWorld();
        if (world == null) {
            return false;
        }

        int regionBlockRadius = regionChunkRadius * 16;

        // 30 deneme ile uygun (üstü açık, katı zemin) bir yer bul.
        for (int attempt = 0; attempt < 30; attempt++) {
            int dx = ThreadLocalRandom.current().nextInt(-spawnRadius, spawnRadius + 1);
            int dz = ThreadLocalRandom.current().nextInt(-spawnRadius, spawnRadius + 1);

            // 32 chunkluk bölge sınırını aşma.
            if (Math.abs(dx) > regionBlockRadius || Math.abs(dz) > regionBlockRadius) {
                continue;
            }

            int x = center.getBlockX() + dx;
            int z = center.getBlockZ() + dz;
            int y = world.getHighestBlockYAt(x, z);

            Block ground = world.getBlockAt(x, y, z);
            Block above = world.getBlockAt(x, y + 1, z);

            if (ground.getType().isSolid() && above.getType().isAir()) {
                above.setType(PLATE_MATERIAL, false);
                activePlates.add(above.getLocation());
                return true;
            }
        }
        return false;
    }

    @EventHandler
    public void onStep(PlayerInteractEvent event) {
        if (event.getAction() != Action.PHYSICAL) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || block.getType() != PLATE_MATERIAL) {
            return;
        }
        Location plateLoc = block.getLocation();
        if (!activePlates.contains(plateLoc)) {
            return; // Bu plugin'e ait değil (normal plaka).
        }

        Player player = event.getPlayer();

        // Plakayı tüket ve kaldır.
        activePlates.remove(plateLoc);
        block.setType(Material.AIR, false);

        // Boss fight başlat (oyuncunun şu anki konumu = dönüş noktası).
        arenaManager.startFight(player, player.getLocation());
    }

    public void removePlate(Location loc) {
        if (activePlates.remove(loc)) {
            Block b = loc.getBlock();
            if (b.getType() == PLATE_MATERIAL) {
                b.setType(Material.AIR, false);
            }
        }
    }

    public void clearAllPlates() {
        for (Location loc : new HashSet<>(activePlates)) {
            Block b = loc.getBlock();
            if (b.getType() == PLATE_MATERIAL) {
                b.setType(Material.AIR, false);
            }
        }
        activePlates.clear();
    }
}
