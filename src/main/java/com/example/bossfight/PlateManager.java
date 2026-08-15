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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Plakaların spawn edilmesi ve basıldığında ilgili boss fight'ı tetiklemesi.
 *
 * İki plaka tipi:
 *  - AĞIR (demir) basınç plakası  -> Zombi boss (zindan)
 *  - HAFİF (altın) basınç plakası -> Blaze boss (nether)
 */
public class PlateManager implements Listener {

    private final BossFightPlugin plugin;
    private final ArenaManager arenaManager;

    // Aktif plaka konumu -> boss tipi ("zombie" / "blaze").
    private final Map<Location, String> activePlates = new HashMap<>();

    private BukkitTask spawnTask;

    // Ayarlar
    private double spawnChance;
    private int intervalSeconds;
    private int spawnRadius;
    private int regionChunkRadius;
    private int maxActivePlates;

    // Plaka türleri.
    private static final Material ZOMBIE_PLATE = Material.HEAVY_WEIGHTED_PRESSURE_PLATE; // demir
    private static final Material BLAZE_PLATE = Material.LIGHT_WEIGHTED_PRESSURE_PLATE;  // altın

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
            if (ThreadLocalRandom.current().nextDouble() * 100.0 < spawnChance) {
                // Rastgele boss tipi seç.
                String type = ThreadLocalRandom.current().nextBoolean() ? "zombie" : "blaze";
                spawnPlateNear(player.getLocation(), type);
            }
        }
    }

    /**
     * Verilen konumun etrafında, belirtilen boss tipinde plaka spawn eder.
     */
    public boolean spawnPlateNear(Location center, String bossType) {
        if (activePlates.size() >= maxActivePlates) {
            return false;
        }
        World world = center.getWorld();
        if (world == null) {
            return false;
        }

        Material plateMat = bossType.equals("blaze") ? BLAZE_PLATE : ZOMBIE_PLATE;
        int regionBlockRadius = regionChunkRadius * 16;

        for (int attempt = 0; attempt < 30; attempt++) {
            int dx = ThreadLocalRandom.current().nextInt(-spawnRadius, spawnRadius + 1);
            int dz = ThreadLocalRandom.current().nextInt(-spawnRadius, spawnRadius + 1);

            if (Math.abs(dx) > regionBlockRadius || Math.abs(dz) > regionBlockRadius) {
                continue;
            }

            int x = center.getBlockX() + dx;
            int z = center.getBlockZ() + dz;
            int y = world.getHighestBlockYAt(x, z);

            Block ground = world.getBlockAt(x, y, z);
            Block above = world.getBlockAt(x, y + 1, z);

            if (ground.getType().isSolid() && above.getType().isAir()) {
                above.setType(plateMat, false);
                activePlates.put(above.getLocation(), bossType);
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
        if (block == null) {
            return;
        }
        Material t = block.getType();
        if (t != ZOMBIE_PLATE && t != BLAZE_PLATE) {
            return;
        }
        Location plateLoc = block.getLocation();
        String bossType = activePlates.get(plateLoc);
        if (bossType == null) {
            return; // Bu plugin'e ait değil.
        }

        Player player = event.getPlayer();

        // Plakayı fight boyunca BIRAKMA — herkes basıp katılabilsin.
        // Fight bitince ArenaManager plakayı kaldıracak.
        if (bossType.equals("blaze")) {
            arenaManager.startBlazeFight(player, player.getLocation());
        } else {
            arenaManager.startFight(player, player.getLocation());
        }
        arenaManager.registerPlate(plateLoc);
    }

    public void clearAllPlates() {
        for (Location loc : new HashSet<>(activePlates.keySet())) {
            Block b = loc.getBlock();
            if (b.getType() == ZOMBIE_PLATE || b.getType() == BLAZE_PLATE) {
                b.setType(Material.AIR, false);
            }
        }
        activePlates.clear();
    }
}
