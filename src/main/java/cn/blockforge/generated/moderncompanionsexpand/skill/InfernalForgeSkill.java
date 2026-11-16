package cn.blockforge.generated.moderncompanionsexpand.skill;

import cn.blockforge.generated.moderncompanionsexpand.config.MceConfig;
import cn.blockforge.generated.moderncompanionsexpand.config.SkillPriorityConfig;
import cn.blockforge.generated.moderncompanionsexpand.util.AllyCheck;
import cn.blockforge.generated.moderncompanionsexpand.util.SafeGuard;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * 炼狱锻锤（cataclysm:infernal_forge）同伴技能：以自身为中心的地面冲击波，瞬发、无蓄力。
 *
 * 对齐灾变源码 Infernal_forge.useOn → EarthQuake（详见 docs/ANALYSIS_ROUND10_INFERNAL_FORGE.md）：
 * - 原版入口 useOn 绑死 Player（context.getPlayer() != null 且必须主手），同伴无法直接走；
 * - 服务端逻辑不依赖方块坐标（坐标只用于客户端粒子），所以不反射私有的 EarthQuake，
 *   而是用原版 API 复刻同款服务端分支（约 15 行 vanilla 调用），表现层白嫖灾变：
 *   · 音效 cataclysm:explosion（原版音量 1.5、随机音高 1/(rand*0.4+0.8)）；
 *   · 震屏 ScreenShake(30, 0.1f, 0, 30)（复用 CataclysmReflect.triggerScreenShake）；
 *   · 范围 getBoundingBox().inflate(4.0)（边长 9 的立方），排除施法者自己；
 *   · 伤害 = AttributeUtils.OriginDamage（反射，含物品 +13 攻击加成的攻击力属性）
 *     经 EnchantmentHelper.modifyDamage 附魔修正后 hurt()；
 *   · 命中后 (×0.5, ×1, +0.6) 向上弹飞——仅对非友军；
 * - 冷却：原版走 Player.getCooldowns()（同伴会 ClassCastException），改走我们的
 *   persistentData 冷却（SkillCooldownStore），默认 80 tick，配置 infernal_forge_cooldown_ticks；
 * - 按 r10 需求：不实现“生命值低于一半附加燃烧”（berserk）；
 * - 友军保护：AllyCheck.isAlly 在 hurt 之前过滤——玩家/其他同伴/主人链宠物不受伤也不被弹飞。
 *
 * 调度集成：chargeTicks = 0（瞬发），findTarget 要求目标在冲击波半径内（≤4.5 格），
 * 避免同伴对着远处目标空放浪费冷却。
 */
public final class InfernalForgeSkill implements CompanionSkill {

    public static final InfernalForgeSkill INSTANCE = new InfernalForgeSkill();

    /** 灾变源码 EarthQuake：double radius = 4.0D（边长 9 的立方） */
    public static final double SHOCKWAVE_RADIUS = 4.0D;
    /** 灾变源码：int numParticles = 80 的方块粒子环 */
    private static final int PARTICLE_COUNT = 80;
    /** findTarget 的半径容差：目标中心到同伴略超过 4 格仍算可打（目标碰撞箱有厚度） */
    private static final double TARGET_RANGE_GRACE = 0.5D;

    private InfernalForgeSkill() {}

    // ════════════════════════ CompanionSkill ════════════════════════

    @Override
    public String id() {
        return SkillRegistry.INFERNAL_FORGE_ID;
    }

    @Override
    public net.minecraft.resources.ResourceLocation requiredItem() {
        return CataclysmReflect.INFERNAL_FORGE_ITEM;
    }

    /** 锻锤是镐类武器，按原版习惯必须主手（原版 useOn 明确要求主手持握） */
    @Override
    public EquipmentSlot preferredSlot() {
        return EquipmentSlot.MAINHAND;
    }

    @Override
    public boolean enabled() {
        return MceConfig.infernalForgeEnabled();
    }

    @Override
    public int cooldownTicks() {
        return MceConfig.infernalForgeCooldownTicks();
    }

    @Override
    public int chargeTicks() {
        return 0; // 瞬发：原版就是对方块右击立即放，无蓄力
    }

    /** 冲击波必须打到目标才有意义：目标要在半径 + 容差内，否则不释放（防止空放浪费冷却） */
    @Override
    public LivingEntity findTarget(Mob caster) {
        LivingEntity target = CompanionSkill.super.findTarget(caster);
        if (target == null) {
            return null;
        }
        try {
            double limit = SHOCKWAVE_RADIUS + TARGET_RANGE_GRACE;
            if (caster.distanceToSqr(target) > limit * limit) {
                return null;
            }
            return target;
        } catch (Throwable t) {
            return target;
        }
    }

    @Override
    public boolean release(ServerLevel level, Mob caster, LivingEntity target, long now, boolean ignoreCooldown) {
        if (!ignoreCooldown && !SkillCooldownStore.isReady(caster, id(), now, cooldownTicks())) {
            SafeGuard.infoThrottled("mce_f_cooling_" + caster.getUUID(), now,
                    "Infernal Forge: " + IncineratorSkill.describe(caster) + " shockwave cooldown left "
                            + SkillCooldownStore.remainingTicks(caster, id(), now, cooldownTicks()) + " ticks, skipped this time");
            return false;
        }
        if (!CataclysmReflect.isCataclysmPresent()) {
            SafeGuard.warnThrottled("mce_f_nocate_" + level.dimension().location(), now,
                    "Infernal Forge: Cataclysm not detected (sounds / screen shake / damage basis all depend on Cataclysm), shockwave cancelled");
            return false;
        }

        ItemStack stack = caster.getItemBySlot(
                heldSlot(caster) == EquipmentSlot.OFFHAND ? EquipmentSlot.OFFHAND : EquipmentSlot.MAINHAND);
        if (stack.isEmpty()) {
            SafeGuard.warn("Infernal Forge: " + IncineratorSkill.describe(caster) + " no longer holding Infernal Forge, shockwave cancelled");
            return false;
        }

        // —— 释放表现（对齐灾变 EarthQuake 服务端分支顺序）：音效 → 粒子环 → 震屏 → 挥手 ——
        playExplosionSound(level, caster);
        spawnShockwaveParticles(level, caster);
        CataclysmReflect.triggerScreenShake(level, caster.position(), 30.0F, 0.1F, 0, 30);
        caster.swing(heldSlot(caster) == EquipmentSlot.OFFHAND
                ? net.minecraft.world.InteractionHand.OFF_HAND : net.minecraft.world.InteractionHand.MAIN_HAND, true);

        // —— 伤害结算：伤害源换成 mobAttack(同伴)（原版 playerAttack(player) 绑死 Player）——
        DamageSource source = level.damageSources().mobAttack(caster);
        float baseDamage = CataclysmReflect.originAttackDamage(caster, stack, fallbackAttackDamage(caster));

        List<net.minecraft.world.entity.Entity> candidates =
                level.getEntities(caster, caster.getBoundingBox().inflate(SHOCKWAVE_RADIUS));
        int hit = 0;
        int alliesSkipped = 0;
        for (net.minecraft.world.entity.Entity entity : candidates) {
            if (!(entity instanceof LivingEntity living) || !living.isAlive()) {
                continue;
            }
            // r10 友军保护（复用统一工具）：玩家/其他同伴/主人链宠物不受伤也不被弹飞
            if (AllyCheck.isAlly(caster, living)) {
                alliesSkipped++;
                continue;
            }
            try {
                float damage = EnchantmentHelper.modifyDamage(level, stack, living, source, baseDamage);
                if (living.hurt(source, damage)) {
                    // 灾变原版击飞公式：水平减半 + 向上弹 0.6
                    living.setDeltaMovement(living.getDeltaMovement()
                            .multiply(0.5D, 1.0D, 0.5D).add(0.0D, 0.6D, 0.0D));
                    living.hurtMarked = true;
                    hit++;
                }
            } catch (Throwable perEntity) {
                SafeGuard.warnThrottled("mce_f_hit_" + living.getId(), now,
                        "Infernal Forge: single-target resolution failed (that target skipped): "
                                + perEntity.getClass().getSimpleName() + " / " + perEntity.getMessage());
            }
        }

        // 冷却照常记录（原版右击就进冷却，与是否命中无关），防止逐 tick 空放刷屏
        SkillCooldownStore.markCast(caster, id(), now);
        ChargeStateStore.markSilence(caster, now + SkillPriorityConfig.silenceTicks());

        SafeGuard.info("Infernal Forge: " + IncineratorSkill.describe(caster) + " released a shockwave (radius " + SHOCKWAVE_RADIUS
                + " blocks, damage " + String.format("%.1f", baseDamage) + ", damage source "
                + (CataclysmReflect.isOriginDamageResolved() ? "Cataclysm OriginDamage reflection" : "direct attack-attribute read (degraded)")
                + "), hit " + hit + " targets, allies skipped" + alliesSkipped + " targets, cooldown "
                + cooldownTicks() + " ticks, global silence " + SkillPriorityConfig.silenceTicks() + " tick"
                + (ignoreCooldown ? " (command forced)" : ""));
        return hit > 0;
    }

    // ════════════════════════ 表现层 ════════════════════════

    /** 灾变原版音效 + 随机音高：1.0 / (rand * 0.4 + 0.8)，音量 1.5（EarthQuake L71） */
    private static void playExplosionSound(ServerLevel level, Mob caster) {
        try {
            SoundEvent explosion = CataclysmReflect.getExplosionSound();
            if (explosion == null) {
                SafeGuard.infoThrottled("mce_f_nosound_" + level.dimension().location(),
                        level.getGameTime(), "Infernal Forge: Cataclysm sound not found " + CataclysmReflect.EXPLOSION_SOUND
                                + ", this shockwave is silent (skill unaffected)");
                return;
            }
            float pitch = 1.0F / (level.getRandom().nextFloat() * 0.4F + 0.8F);
            caster.playSound(explosion, 1.5F, pitch);
        } catch (Throwable t) {
            SafeGuard.warnThrottled("mce_f_snd", level.getGameTime(),
                    "Infernal Forge: playing the sound failed (caught, skill unaffected): " + t.getClass().getSimpleName());
        }
    }

    /**
     * 方块粒子环：复刻灾变客户端 80 粒子环（半径 4、y+0.15、高斯速度）。
     * 原版方块取“被右击的方块”；同伴没有右击对象，改取脚下方块，脚下悬空则退化取自身所在格。
     */
    private static void spawnShockwaveParticles(ServerLevel level, Mob caster) {
        try {
            BlockPos below = caster.blockPosition().below();
            BlockState state = level.getBlockState(below);
            if (state.isAir()) {
                state = level.getBlockState(caster.blockPosition());
            }
            if (state.isAir()) {
                return; // 悬空/虚空里不放方块粒子，冲击波照常结算
            }
            BlockParticleOption option = new BlockParticleOption(ParticleTypes.BLOCK, state);
            for (int i = 0; i < PARTICLE_COUNT; i++) {
                double angle = (double) i / PARTICLE_COUNT * (Math.PI * 2.0D);
                double x = caster.getX() + SHOCKWAVE_RADIUS * Math.sin(angle);
                double y = caster.getY() + 0.15D;
                double z = caster.getZ() + SHOCKWAVE_RADIUS * Math.cos(angle);
                double vx = level.getRandom().nextGaussian() * 0.1D;
                double vy = level.getRandom().nextGaussian() * 0.1D;
                double vz = level.getRandom().nextGaussian() * 0.1D;
                // count=0：服务端把 (vx,vy,vz) 当作该粒子的初速度（原版同款“向外飞溅”的碎屑）
                level.sendParticles(option, x, y, z, 0, vx, vy, vz, 1.0D);
            }
        } catch (Throwable t) {
            SafeGuard.warnThrottled("mce_f_particle", level.getGameTime(),
                    "Infernal Forge: particle ring generation failed (caught, skill unaffected): " + t.getClass().getSimpleName());
        }
    }

    /** OriginDamage 反射层不可用时的退化值：同伴自己的攻击力属性（含物品加成） */
    private static float fallbackAttackDamage(Mob caster) {
        try {
            return (float) caster.getAttributeValue(Attributes.ATTACK_DAMAGE);
        } catch (Throwable t) {
            return 13.0F; // 物品攻击加成的原版数值，最后兜底
        }
    }
}