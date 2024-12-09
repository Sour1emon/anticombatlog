package com.github.sour1emon.deadbody

import de.tr7zw.changeme.nbtapi.NBT
import org.bukkit.Bukkit
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.entity.Wolf
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class DeadBody : JavaPlugin(), Listener {

    private val lastCombatTime: MutableMap<UUID, Long> = ConcurrentHashMap()

    private val deadPlayers: MutableMap<UUID, ArmorStand> = ConcurrentHashMap()

    private val playerInventories: MutableMap<UUID, String> = ConcurrentHashMap()

    private fun playerCombatLogged(uniqueId: UUID): ArmorStand? {
        return deadPlayers.values.find { armorStand ->
            armorStand.hasMetadata("target_player") && armorStand.getMetadata("target_player")
                .any { it.asString() == uniqueId.toString() }
        }
    }

    override fun onEnable() {
        server.pluginManager.registerEvents(this, this)
    }

    override fun onDisable() {
        for (armorStand in deadPlayers.values) {
            armorStand.remove()
        }
        deadPlayers.clear()
    }

    @EventHandler
    fun onEntityDamageByEntity(event: EntityDamageByEntityEvent) {
        val damager = event.damageSource.causingEntity
        val entity = event.entity

        if (entity is Player && (damager is Player || (damager is Wolf && damager.owner is Player)) && damager != entity) {
            val playerUUID = entity.uniqueId
            lastCombatTime[playerUUID] = System.currentTimeMillis()
            logger.info("${entity.name} has been damaged by ${damager.name}.")
        }
    }

    @EventHandler
    fun onEntityDeath(event: EntityDeathEvent) {
        val entity = event.entity
        if (entity is ArmorStand && entity.hasMetadata("target_player")) {
            val playerUUID = UUID.fromString(entity.getMetadata("target_player")[0].asString())
            val playerDataFile = File(Bukkit.getWorld("world")!!.worldFolder, "playerdata/$playerUUID.dat")
            if (playerDataFile.exists()) {
                val nbtFile = NBT.getFileHandle(playerDataFile);
                val inventory = nbtFile.getCompoundList("Inventory")
                for (item in inventory) {
                    val itemStack = NBT.itemStackFromNBT(item);
                    if (itemStack != null) {
                        if (!itemStack.containsEnchantment(Enchantment.VANISHING_CURSE)) {
                            entity.world.dropItemNaturally(entity.location, itemStack)
                        }
                    }
                }
                nbtFile.removeKey("Inventory")
                nbtFile.setFloat("Health", 0.0F)
                nbtFile.save()
            }
            deadPlayers.remove(entity.uniqueId)
        }
    }

    @EventHandler
    fun onPlayerDeath(event: PlayerDeathEvent) {
        lastCombatTime[event.player.uniqueId] = 0
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val uuid = event.player.uniqueId;
        if (!lastCombatTime.containsKey(uuid)) {
            lastCombatTime[uuid] = 0
        }
        val combatLogged = playerCombatLogged(uuid);
        if (combatLogged != null) {
            combatLogged.remove();
            deadPlayers.remove(combatLogged.uniqueId)
        }
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val player = event.player
        if (event.reason == PlayerQuitEvent.QuitReason.DISCONNECTED) {
            if (System.currentTimeMillis() - lastCombatTime[player.uniqueId]!! < 15 * 1000) {
                val armorStand = player.world.spawnEntity(player.location, EntityType.ARMOR_STAND) as ArmorStand
                armorStand.setMetadata("combat_logged", FixedMetadataValue(this, true))
                armorStand.setMetadata("target_player", FixedMetadataValue(this, player.uniqueId))
                armorStand.equipment.helmet = player.inventory.helmet
                armorStand.equipment.chestplate = player.inventory.chestplate
                armorStand.equipment.leggings = player.inventory.leggings
                armorStand.equipment.boots = player.inventory.boots
                armorStand.equipment.setItemInMainHand(player.inventory.itemInMainHand)
                armorStand.equipment.setItemInOffHand(player.inventory.itemInOffHand)
                deadPlayers[armorStand.uniqueId] = armorStand
                logger.info("${player.name} has logged off while in combat")
            }
        }
    }
}
