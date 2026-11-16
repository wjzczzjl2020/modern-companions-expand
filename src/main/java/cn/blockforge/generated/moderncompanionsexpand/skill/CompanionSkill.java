package cn.blockforge.generated.moderncompanionsexpand.skill;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;

/**
 * 同伴主动技能的统一契约。调度器（SkillTickHandler）只认这个接口，
 * 新技能接入 = 写一个实现类 + 注册进 SkillRegistry + 在优先级表加一行 id。
 *
 * 生命周期（由调度器驱动，实现类不要自己挂 tick 事件）：
 *   IDLE ─(chargeTicks>0)→ beginCharge → CHARGING：每 tick tickCharge，进度满 → release
 *        ─(chargeTicks==0)→ 当场 release
 *   release 成功 → 记冷却 + 全局沉默；activeTicks>0 → ACTIVE 窗口每 tick tickActive
 */
public interface CompanionSkill {

    /** 技能 id（=灾变物品 id 去掉命名空间，也是优先级表里的那一行） */
    String id();

    /** 触发需要主手持有的物品（纯注册表 ID 比对，编译期零依赖灾变） */
    ResourceLocation requiredItem();

    /** 技能开关（读 TOML，实时生效） */
    boolean enabled();

    /** 该技能冷却 tick（读 TOML） */
    int cooldownTicks();

    /** 蓄力需要多少 tick；0 = 就绪即放（壁垒） */
    int chargeTicks();

    /** 释放成功后的“进行窗口”tick（壁垒冲锋 8 tick）；0 = 无窗口 */
    default int activeTicks() {
        return 0;
    }

    /** 蓄力/进行中“被打一次”是否打断（r23 钩子）。炎葬等默认 true；绞肉锯 false = 原版按住右键被打也不停 */
    default boolean interruptOnDamage() {
        return true;
    }

    /** 覆盖蓄力期的“脱距打断”距离（>0 生效）；默认 -1 = 用全局 charge_break_distance（32 格）。绞肉锯用 射程+0.5 */
    default double breakDistanceOverride() {
        return -1.0D;
    }

    /** 纯释放：技能的全部表现 + 记冷却都在这一步完成。
     *  @param ignoreCooldown 命令强制施放时为 true
     *  @return 是否放出来了（true 才会进入沉默/窗口） */
    boolean release(ServerLevel level, Mob caster, LivingEntity target, long now, boolean ignoreCooldown);

    /** 进入蓄力时调一次（起手姿势等表现） */
    default void beginCharge(ServerLevel level, Mob caster, LivingEntity target, long now) {
    }

    /** 蓄力期间每 tick 调（面向目标、粒子、进度音效）。progress = 已蓄的 tick 数 */
    default void tickCharge(ServerLevel level, Mob caster, LivingEntity target, int progress, long now) {
    }

    /** 蓄力被中断时调（收尾表现，如停止持握姿势）。状态清理由调度器统一做 */
    default void endCharge(ServerLevel level, Mob caster, long now) {
    }

    /** 进入冲锋窗口时调一次（壁垒：真正写附件 + 施加冲量） */
    default void beginActive(ServerLevel level, Mob caster, long now) {
    }

    /** 窗口期间每 tick 调（壁垒：驱动灾变 ChargeAttachment.tick）。返回 false 可提前结束窗口 */
    default boolean tickActive(ServerLevel level, Mob caster, long now) {
        return true;
    }

    /** 本技能物品“应该”装备到哪个手（/mce equip 用；壁垒这类盾系物品按原版习惯是副手） */
    default EquipmentSlot preferredSlot() {
        return EquipmentSlot.MAINHAND;
    }

    /**
     * 主手、副手依次检查，返回第一个持有本技能物品的槽位；两手都没有 → null。
     * （r6 事故根因：旧版只查主手，副手的火焰壁垒永远扫描不到。任何异常按“没持有”处理，不崩。）
     */
    default EquipmentSlot heldSlot(Mob mob) {
        try {
            EquipmentSlot[] slots = {EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND};
            for (EquipmentSlot slot : slots) {
                ItemStack stack = mob.getItemBySlot(slot);
                if (!stack.isEmpty() && requiredItem().equals(BuiltInRegistries.ITEM.getKey(stack.getItem()))) {
                    return slot;
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 是否持有本技能物品（主手或副手任一即可，具体槽位问 heldSlot） */
    default boolean holdsItem(Mob mob) {
        return heldSlot(mob) != null;
    }

    /** 槽位中文短名（日志/命令回显用） */
    static String slotLabel(EquipmentSlot slot) {
        if (slot == EquipmentSlot.OFFHAND) {
            return "off hand";
        }
        if (slot == EquipmentSlot.MAINHAND) {
            return "main hand";
        }
        return "none";
    }

    /** 找合法攻击目标（AI 目标 + 存活 + 同维度 + 距离兜底） */
    default LivingEntity findTarget(Mob caster) {
        LivingEntity target = caster.getTarget();
        if (target == null || target == caster || !target.isAlive() || target.isRemoved()) {
            return null;
        }
        if (target.level() != caster.level()) {
            return null;
        }
        if (caster.distanceToSqr(target) > 48.0D * 48.0D) {
            return null;
        }
        return target;
    }

    /** 本技能当前还差多少 tick 冷却（status 展示用） */
    default long remainingCooldown(Mob caster, long now) {
        return SkillCooldownStore.remainingTicks(caster, id(), now, cooldownTicks());
    }
}
