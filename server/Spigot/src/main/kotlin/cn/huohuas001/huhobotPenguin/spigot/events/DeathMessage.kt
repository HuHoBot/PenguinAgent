package cn.huohuas001.huhobotPenguin.spigot.events

import org.bukkit.entity.Entity
import org.bukkit.entity.Projectile
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.PlayerDeathEvent

/**
 * 把 Bukkit 死亡事件转成中文播报文案。
 *
 * 原版 [PlayerDeathEvent.getDeathMessage] 取自服务端语言包，默认是英文，
 * 直接转发到 QQ 群可读性很差，因此这里按击杀者与死亡原因自行拼装中文描述。
 */
object DeathMessage {

    /**
     * 击杀者名称：玩家击杀取玩家名，投射物取发射者。
     *
     * @return 环境死亡（摔落、岩浆等）时返回 null
     */
    fun killerOf(event: PlayerDeathEvent): String? {
        val victim = event.entity.name
        val killer = event.entity.killer
        if (killer != null && !killer.name.equals(victim, ignoreCase = true)) {
            return killer.name
        }
        val damager = (event.entity.lastDamageCause as? EntityDamageByEntityEvent)?.damager
        val sourceName = damager?.let { sourceName(it) }
        return sourceName?.takeIf { it.isNotBlank() && !it.equals(victim, ignoreCase = true) }
    }

    /**
     * 中文死亡描述，**不含死者名**，可直接拼在 {name} 之后。
     *
     * 例如「被苦力怕杀死了」「从高处摔落而死」。
     */
    fun describe(event: PlayerDeathEvent): String {
        val killer = killerOf(event)
        if (killer != null) return "被${killer}杀死了"

        return when (event.entity.lastDamageCause?.cause) {
            EntityDamageEvent.DamageCause.FALL -> "从高处摔落而死"
            EntityDamageEvent.DamageCause.LAVA -> "掉进了岩浆"
            EntityDamageEvent.DamageCause.FIRE,
            EntityDamageEvent.DamageCause.FIRE_TICK,
            EntityDamageEvent.DamageCause.MELTING -> "被火烧死了"
            EntityDamageEvent.DamageCause.HOT_FLOOR -> "被岩浆烫死了"
            EntityDamageEvent.DamageCause.DROWNING -> "溺水而死了"
            EntityDamageEvent.DamageCause.SUFFOCATION -> "窒息而死了"
            EntityDamageEvent.DamageCause.VOID -> "掉进了虚空"
            EntityDamageEvent.DamageCause.LIGHTNING -> "被闪电劈死了"
            EntityDamageEvent.DamageCause.BLOCK_EXPLOSION,
            EntityDamageEvent.DamageCause.ENTITY_EXPLOSION -> "被炸死了"
            EntityDamageEvent.DamageCause.POISON,
            EntityDamageEvent.DamageCause.WITHER,
            EntityDamageEvent.DamageCause.MAGIC -> "中毒而死了"
            EntityDamageEvent.DamageCause.STARVATION -> "饿死了"
            EntityDamageEvent.DamageCause.FALLING_BLOCK -> "被掉落的方块砸死了"
            EntityDamageEvent.DamageCause.THORNS -> "被荆棘刺死了"
            EntityDamageEvent.DamageCause.FLY_INTO_WALL -> "撞墙死了"
            else -> "死了"
        }
    }

    private fun sourceName(damager: Entity): String? = when (damager) {
        is Projectile -> (damager.shooter as? Entity)?.name
        else -> damager.name
    }
}