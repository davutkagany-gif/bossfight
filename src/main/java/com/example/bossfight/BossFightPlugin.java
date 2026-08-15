package com.example.bossfight;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

/**
 * BossFight ana sınıfı.
 *
 * Akış:
 *  1) 32 chunkluk bölge içinde %15 şansla bir basınç plakası (arena tetikleyici) spawn olur.
 *  2) Oyuncu plakaya basınca arena koordinatına ışınlanır ve boss fight başlar.
 *  3) 3 wave: sadece iskelet ve zombi (creeper yok). Her wave temizlenince sonraki başlar.
 *  4) 3. wave sonrası boss (büyük zombi) gelir. Boss ölünce %50 şansla kılıç düşer.
 */
public final class BossFightPlugin extends JavaPlugin {

    private PlateManager plateManager;
    private ArenaManager arenaManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.arenaManager = new ArenaManager(this);
        this.plateManager = new PlateManager(this, arenaManager);

        Bukkit.getPluginManager().registerEvents(plateManager, this);
        Bukkit.getPluginManager().registerEvents(arenaManager, this);

        plateManager.startSpawnTask();

        getLogger().info("BossFight etkinleştirildi.");
    }

    @Override
    public void onDisable() {
        if (plateManager != null) {
            plateManager.stopSpawnTask();
            plateManager.clearAllPlates();
        }
        if (arenaManager != null) {
            arenaManager.cleanupAll();
        }
        getLogger().info("BossFight devre dışı bırakıldı.");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!command.getName().equalsIgnoreCase("bossfight")) {
            return false;
        }
        if (!sender.hasPermission("bossfight.admin")) {
            sender.sendMessage("§cBunun için yetkin yok.");
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage("§eKullanım: /bossfight <spawnplate|setarena|reload>");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "spawnplate" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("§cBu komutu oyuncu olarak kullan.");
                    return true;
                }
                // İkinci argüman: "zombie" (varsayılan) veya "blaze".
                String type = (args.length >= 2 && args[1].equalsIgnoreCase("blaze"))
                        ? "blaze" : "zombie";
                boolean ok = plateManager.spawnPlateNear(player.getLocation(), type);
                sender.sendMessage(ok
                        ? "§a" + (type.equals("blaze") ? "Blaze" : "Zombi") + " plakası spawn edildi."
                        : "§cUygun yer bulunamadı ya da limit dolu.");
            }
            case "setarena" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("§cBu komutu oyuncu olarak kullan.");
                    return true;
                }
                Location loc = player.getLocation();
                getConfig().set("arena.world", loc.getWorld().getName());
                getConfig().set("arena.x", loc.getX());
                getConfig().set("arena.y", loc.getY());
                getConfig().set("arena.z", loc.getZ());
                saveConfig();
                arenaManager.reloadArenaLocation();
                sender.sendMessage("§aArena koordinatı bulunduğun yere ayarlandı.");
            }
            case "reload" -> {
                reloadConfig();
                plateManager.reloadSettings();
                arenaManager.reloadArenaLocation();
                sender.sendMessage("§aConfig yeniden yüklendi.");
            }
            case "sword" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("§cBu komutu oyuncu olarak kullan.");
                    return true;
                }
                player.getInventory().addItem(arenaManager.createSword());
                sender.sendMessage("§aKadim Kılıç envanterine eklendi.");
            }
            case "armor" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("§cBu komutu oyuncu olarak kullan.");
                    return true;
                }
                for (var piece : arenaManager.createArmorSet()) {
                    player.getInventory().addItem(piece);
                }
                sender.sendMessage("§aKadim Zırh seti envanterine eklendi.");
            }
            case "wand" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("§cBu komutu oyuncu olarak kullan.");
                    return true;
                }
                player.getInventory().addItem(arenaManager.createBlazeWand());
                sender.sendMessage("§aSonsuz Alev Asası envanterine eklendi.");
            }
            default -> sender.sendMessage("§eKullanım: /bossfight <spawnplate [zombie|blaze]|setarena|reload|sword|armor|wand>");
        }
        return true;
    }

    public ArenaManager getArenaManager() {
        return arenaManager;
    }
}
