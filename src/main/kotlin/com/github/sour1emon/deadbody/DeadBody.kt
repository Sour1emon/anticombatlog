package com.github.sour1emon.deadbody

import de.tr7zw.changeme.nbtapi.NBT
import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.npc.NPC
import net.citizensnpcs.trait.SkinTrait
import org.bukkit.Bukkit
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.EntityType
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Wolf
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.server.ServerLoadEvent
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class DeadBody : JavaPlugin(), Listener {

    private val lastCombatTime: MutableMap<UUID, Long> = ConcurrentHashMap()

    private val deadPlayers: MutableMap<UUID, NPC> = ConcurrentHashMap()

    private val COMBAT_TIME_SECONDS = 15

    private val DEAD_BODY_DESPAWN_TIME = 60

    private fun playerCombatLogged(uniqueId: UUID): NPC? {
        return deadPlayers.values.find { npc ->
            npc.entity.hasMetadata("target_player") && npc.entity.getMetadata("target_player")
                .any { it.asString() == uniqueId.toString() }
        }
    }

    override fun onEnable() {
        server.pluginManager.registerEvents(this, this)
    }

    override fun onDisable() {
        for (npc in deadPlayers.values) {
            npc.destroy()
        }
        deadPlayers.clear()
    }

    @EventHandler
    fun onServerLoad(event: ServerLoadEvent) {
        CitizensAPI.getNPCRegistry().forEach { npc: NPC? ->
            run {
                npc?.destroy()
            }
        }
    }

    @EventHandler
    fun onEntityDamageByEntity(event: EntityDamageByEntityEvent) {
        val damager = event.damageSource.causingEntity
        val entity = event.entity

        if (damager != null) {
            var damagerUUID = damager.uniqueId
            if (damager is Wolf && damager.owner is Player) {
                damagerUUID = (damager.owner as Player).uniqueId
            }

            if (entity is Player && (damager is Player || (damager is Wolf && damager.owner is Player)) && damager != entity) {
                val playerUUID = entity.uniqueId
                if (!entity.hasMetadata("NPC")) {
                    lastCombatTime[playerUUID] = System.currentTimeMillis()
                }
                if (!damager.hasMetadata("NPC")) {
                    lastCombatTime[damagerUUID] = System.currentTimeMillis()
                }
                logger.info("${entity.name} has been damaged by ${damager.name}.")
            }
        }
    }

    @EventHandler
    fun onEntityDeath(event: EntityDeathEvent) {
        val entity = event.entity
        if (entity is Player && entity.hasMetadata("target_player")) {
            val playerUUID = UUID.fromString(entity.getMetadata("target_player")[0].asString())
            val playerDataFile = File(Bukkit.getWorld("world")!!.worldFolder, "playerdata/$playerUUID.dat")
            if (playerDataFile.exists()) {
                val nbtFile = NBT.getFileHandle(playerDataFile)
                val inventory = nbtFile.getCompoundList("Inventory")
                for (item in inventory) {
                    val itemStack = NBT.itemStackFromNBT(item)
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
            deadPlayers[entity.uniqueId]?.destroy()
            deadPlayers.remove(entity.uniqueId)
        }
    }

    @EventHandler
    fun onPlayerDeath(event: PlayerDeathEvent) {
        lastCombatTime[event.player.uniqueId] = 0
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val uuid = event.player.uniqueId
        if (!lastCombatTime.containsKey(uuid)) {
            lastCombatTime[uuid] = 0
        }
        val combatLogged = playerCombatLogged(uuid)
        if (combatLogged != null) {
            combatLogged.destroy()
            deadPlayers.remove(combatLogged.uniqueId)
        }
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val player = event.player
        if (event.reason == PlayerQuitEvent.QuitReason.DISCONNECTED) {
            logger.info("${player.name} has not been in combat for ${System.currentTimeMillis() - lastCombatTime[player.uniqueId]!!} ms")
            if (System.currentTimeMillis() - lastCombatTime[player.uniqueId]!! < COMBAT_TIME_SECONDS * 1000) {
                val npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.PLAYER, "${player.name} (Combat Logged)")
                npc.getOrAddTrait(SkinTrait::class.java).skinName = player.name
                npc.isProtected = false
                npc.spawn(player.location)
                val npcEntity = npc.entity as LivingEntity
                npcEntity.health = player.health
                npcEntity.setMetadata("combat_logged", FixedMetadataValue(this, true))
                npcEntity.setMetadata("target_player", FixedMetadataValue(this, player.uniqueId))
                npcEntity.equipment?.helmet = player.inventory.helmet
                npcEntity.equipment?.chestplate = player.inventory.chestplate
                npcEntity.equipment?.leggings = player.inventory.leggings
                npcEntity.equipment?.boots = player.inventory.boots
                npcEntity.equipment?.setItemInMainHand(player.inventory.itemInMainHand)
                npcEntity.equipment?.setItemInOffHand(player.inventory.itemInOffHand)
                deadPlayers[npc.uniqueId] = npc
                logger.info("${player.name} has logged off while in combat")
                server.scheduler.runTaskLater(this, RemoveDeadBodyTask(npc), (DEAD_BODY_DESPAWN_TIME * 20).toLong())
            }
        }
    }

    class RemoveDeadBodyTask(private val npc: NPC) : Runnable {
        override fun run() {
            npc.destroy()
        }
    }
}
