package cn.blockforge.generated.moderncompanionsexpand.skill;

import cn.blockforge.generated.moderncompanionsexpand.GeneratedMod;
import cn.blockforge.generated.moderncompanionsexpand.config.MceConfig;
import cn.blockforge.generated.moderncompanionsexpand.config.SkillPriorityConfig;
import cn.blockforge.generated.moderncompanionsexpand.event.ShadowInvulnGate;
import cn.blockforge.generated.moderncompanionsexpand.util.AllyCheck;
import cn.blockforge.generated.moderncompanionsexpand.util.SafeGuard;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;
import net.neoforged.neoforge.common.ItemAbilities;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * 绞肉锯（meat_shredder）同伴技能：贴脸站桩持续绞肉。
 *
 * 与前八把武器不同——它不需要反射实体、也不需要自研伤害循环：
 * 灾变真正的伤害逻辑在 Meat_Shredder.onUseTick(Level, LivingEntity, ItemStack, int)，
 * 参数收的就是 LivingEntity，且 LivingEntity.startUsingItem(hand) 是公有方法。
 * 同伴只要真的进入原版“使用物品”状态，引擎就会每 tick 自动跑原版 onUseTick——
 * 伤害公式（AttributeUtils.OriginDamage 主手/副手归一 + 力量 buff + Curios 饰品 +
 * EnchantmentHelper 附魔修正 ÷ 8.5）、穿方块判定（无方块检测）、命中粒子、
 * SHREDDER_LOOP 循环音效，100% 保真，零简化。
 * 本类只写“状态机外壳”：什么时候进、什么时候出、进出时的 AI/减速/友军处理。
 *
 * 生命周期（复用调度器三态，CHARGING = “正在绞肉”）：
 *   IDLE ──(扫描就绪：有目标 + 距离 ≤ 射程 + 只打敌对 + 预扫描清白)──▶ CHARGING（开始绞肉）
 *   CHARGING：引擎每 tick 自动跑原版 onUseTick；本类 tickCharge 只做
 *             面向目标 + 使用状态保活（读档后重进）+ 减速保活 + 盾牌互斥观察；
 *             受击不打断（interruptOnDamage=false，对齐原版按住右键被打不停）。
 *   退出（绞满 meat_shredder_max_duration_ticks / 目标死亡 / 距离 > 射程+0.5 / 换姿态 /
 *         被收回 / 读档后条件不成立）：stopUsingItem（引擎自动播 SHREDDER_END +
 *         清循环音效缓存）+ 移除减速 + 进冷却 meat_shredder_cooldown_ticks（默认 60）。
 *   退出后状态机回 IDLE，其他技能照常接上（clearState 统一恢复 AI）。
 *
 * 友军保护两层（拍板）：
 *   1. 预扫描挡开局：进绞肉前原版搜索盒内有友军（AllyCheck）或被动生物 → 退回普攻；
 *   2. 事件层 AllyDamageGuard 绞肉窗口兜底：绞肉是持续状态，友军可能中途走进扇区，
 *      原版循环又是对搜索盒内所有 LivingEntity 结算——窗口内 cataclysm:shredder
 *      伤害打向友军一律取消。
 *
 * 无敌帧副作用观察（拍板）：绞肉每次命中会把目标 invulnerableTime 重置为 20，
 * 期间队友的其他伤害会被原版 hurt() 的 lastHurt 分支吸收——观察日志在 AllyDamageGuard。
 *
 * r26 影子无敌帧（拍板）：上面的副作用不再只观察——绞肉期间由 ShadowInvulnGate 为目标
 * 维护“无绞肉污染时的无敌帧/lastHurt”影子账本（本类 beginCharge/tickCharge 调 onShredTick
 * 建账/刷新），非绞肉伤害按影子值拦/补/放行，收锯经 ShredAoeDeferral.clearShred 统一落回。
 * 绞肉自身的伤害路径零改动（闸门对 cataclysm:shredder 一律放行），DPS 不受影响，
 * 滚动实测见 rollingDpsSummary（/mce meat_shredder status）。
 *
 * /mce meat_shredder force：跳过预扫描与冷却，立即开始绞肉（进状态机，调度器接管收尾）。
 */
public final class MeatShredderSkill implements CompanionSkill {

    public static final MeatShredderSkill INSTANCE = new MeatShredderSkill();

    /** 原版 onUseTick 的视线射程（绞肉锯源码 range = 2.5，搜索盒沿视线延伸 + 1 格 padding） */
    private static final double VANILLA_LOOK_RANGE = 2.5D;
    private static final double VANILLA_BOX_PADDING = 1.0D;

    /** 绞肉期间挂在移动速度上的临时修饰：×0.2（ADD_MULTIPLIED_TOTAL -0.8 ≈ 原版输入×0.2） */
    private static final ResourceLocation SLOWDOWN_ID = ResourceLocation.fromNamespaceAndPath(
            GeneratedMod.MOD_ID, "meat_shredder_slowdown");
    private static final AttributeModifier SLOWDOWN_MODIFIER = new AttributeModifier(
            SLOWDOWN_ID, -0.8D, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);

    /** 当前绞肉用的手（持久化键：读档后重进使用状态要用同一只手） */
    private static final String KEY_SHRED_HAND = "mce_shred_hand";
    /** AllyDamageGuard 记录“这个目标最近被绞肉命中过”的时间戳键（无敌帧副作用观察用） */
    public static final String KEY_SHRED_HIT_AT = "mce_shred_hit_at";
    /** 无敌帧观察：绞肉命中后多久内打过来的伤害值得记录一次（60 tick 内无敌帧都还高） */
    private static final long INVULN_OBSERVE_TICKS = 60L;

    private MeatShredderSkill() {}

    // ════════════════════════ CompanionSkill ════════════════════════

    @Override
    public String id() {
        return SkillRegistry.MEAT_SHREDDER_ID;
    }

    @Override
    public ResourceLocation requiredItem() {
        return CataclysmReflect.MEAT_SHREDDER_ITEM;
    }

    @Override
    public boolean enabled() {
        return MceConfig.meatShredderEnabled();
    }

    @Override
    public int cooldownTicks() {
        return MceConfig.meatShredderCooldownTicks();
    }

    /** “蓄力时长” = 单次绞肉最长持续时间（进状态即开始绞肉，绞满即收锯） */
    @Override
    public int chargeTicks() {
        return MceConfig.meatShredderMaxDurationTicks();
    }

    /** 原版按住右键绞肉时被打也不会停——同伴版对齐（r23 拍板） */
    @Override
    public boolean interruptOnDamage() {
        return false;
    }

    /** 脱距打断用绞肉自己的退出距离（射程 + 0.5 迟滞），不用全局 32 格 */
    @Override
    public double breakDistanceOverride() {
        return MceConfig.meatShredderExitDistance();
    }

    // ════════════════════════ 进入：进入原版使用状态 ════════════════════════

    @Override
    public void beginCharge(ServerLevel level, Mob caster, LivingEntity target, long now) {
        try {
            EquipmentSlot slot = heldSlot(caster);
            InteractionHand hand = slot == EquipmentSlot.OFFHAND
                    ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
            CompoundTag tag = caster.getPersistentData();
            tag.putString(KEY_SHRED_HAND, hand == InteractionHand.OFF_HAND ? "OFF_HAND" : "MAIN_HAND");

            // r25 错峰：登记“这个目标正在被我绞肉”，其他同伴的 AoE 技能开始暂缓
            ShredAoeDeferral.registerShredTick(target, caster, now);
            // r26 影子无敌帧：为目标建立影子账本（绞肉开始时对实际无敌帧/lastHurt 拍快照）
            ShadowInvulnGate.onShredTick(target, now);

            // 关键一步：真的进入原版使用状态——之后引擎每 tick 自动调原版 onUseTick 结算伤害
            caster.startUsingItem(hand);
            applySlowdown(caster);
            playShredderStart(level, caster);

            boolean shield = isShieldLike(caster.getItemBySlot(EquipmentSlot.OFFHAND));
            SafeGuard.info("Meat Shredder: shredding started!" + IncineratorSkill.describe(caster)
                    + " (" + CompanionSkill.slotLabel(slot) + " held, target " + IncineratorSkill.describe(target)
                    + ", at most " + MceConfig.meatShredderMaxDurationTicks() + " ticks, cooldown "
                    + MceConfig.meatShredderCooldownTicks() + " tick"
                    + (shield ? ", off-hand shield exempted (meat_shredder_allow_with_shield=true)" : "")
                    + "); damage/enchantments/strength/block penetration all run through vanilla onUseTick, this mod recomputes nothing");
        } catch (Throwable t) {
            SafeGuard.error("Meat Shredder: entering the shredding state failed (caught, no game impact)", t);
        }
    }

    // ════════════════════════ 进行中：每 tick 维持 ════════════════════════

    @Override
    public void tickCharge(ServerLevel level, Mob caster, LivingEntity target, int progress, long now) {
        try {
            // —— r25 错峰：每 tick 刷新登记（绞肉者掉线/死亡等异常退出后，登记靠新鲜度宽限自动过期）——
            ShredAoeDeferral.registerShredTick(target, caster, now);
            // —— r26 影子无敌帧：每 tick 刷新影子账本新鲜度（首次由 beginCharge 建账）——
            ShadowInvulnGate.onShredTick(target, now);

            // —— 面向目标（NoAi 下 lookControl 不跑，手动转；原版命中判定沿视线射线，必须对准）——
            float yawDegrees = IncineratorSkill.yawToDegrees(caster, target);
            caster.setYRot(yawDegrees);
            caster.setXRot(0.0F);
            caster.setYHeadRot(yawDegrees);

            // —— 使用状态保活：原版“正在使用”是瞬时状态（存档不带走）。读档重进、
            //    被其它逻辑 stopUsingItem 时在这里重新进入，绞肉不断档 ——
            if (!caster.isUsingItem()) {
                InteractionHand hand = restoreHand(caster);
                caster.startUsingItem(hand);
                SafeGuard.infoThrottled("mce_ms_restart_" + caster.getUUID(), now,
                        "Meat Shredder: the vanilla use state was lost (reload/external interrupt), re-entered shredding ("
                                + progress + "/" + chargeTicks() + " tick)");
            } else {
                ItemStack used = caster.getItemInHand(caster.getUsedItemHand());
                boolean usingShredder = !used.isEmpty() && CataclysmReflect.MEAT_SHREDDER_ITEM.equals(
                        BuiltInRegistries.ITEM.getKey(used.getItem()));
                // —— 观察点（拍板）：绞肉期间副手盾被主模组举盾 AI 拉起 → 使用状态被抢，
                //    绞肉会“断断续续”（举盾→绞肉→举盾）。豁免默认开 = 绞肉优先：
                //    这里把状态抢回来并打限流日志，供用户判断要不要改配置 ——
                if (!usingShredder && isShieldLike(used)) {
                    SafeGuard.warnThrottled("mce_ms_shieldfight_" + caster.getUUID(), now,
                            "shredder watch: the off-hand shield's blocking AI stole the use state (" + IncineratorSkill.describe(caster)
                                    + " is blocking instead of shredding, the shredding got chopped into pieces). Re-entered shredding per the meat_shredder_allow_with_shield=true "
                                    + " exemption; to go back to 'blocking wins', set that config to false");
                    caster.stopUsingItem();
                    caster.startUsingItem(restoreHand(caster));
                }
            }

            // —— 减速保活：transient 修饰不随存档走，读档后也靠这里补挂 ——
            if (MceConfig.meatShredderSlowdown()) {
                applySlowdown(caster);
            }
        } catch (Throwable t) {
            SafeGuard.error("Meat Shredder: holding the shredding state failed (caught)", t);
        }
        observeShieldInterplay(caster, now);
    }

    /**
     * 观察点（拍板：观察“副手盾 + 绞肉”是否被举盾 AI 搞得断断续续）：
     * 绞肉期间同伴若同时处于举盾位（isBlocking），说明主模组的举盾 AI 在和绞肉抢状态，
     * 会出现“举盾→绞肉→举盾”的反复切换——打限流日志供用户观察。
     */
    private void observeShieldInterplay(Mob caster, long now) {
        try {
            ItemStack offhand = caster.getItemBySlot(EquipmentSlot.OFFHAND);
            if (!isShieldLike(offhand)) {
                return;
            }
            if (caster.isBlocking()) {
                SafeGuard.warnThrottled("mce_ms_shieldfight_" + caster.getUUID(), now,
                        "shredder watch: the off-hand shield's blocking state is present at the same time as shredding (" + IncineratorSkill.describe(caster)
                                + " current isBlocking=true). If shredding turns out choppy in practice, "
                                + "set meat_shredder_allow_with_shield to false (blocking wins, no automatic shredding)");
            }
        } catch (Throwable ignored) {
        }
    }

    // ════════════════════════ 退出：收锯 / 被打断 ════════════════════════

    @Override
    public void endCharge(ServerLevel level, Mob caster, long now) {
        // 调度器两条路都会走到这里：正常收锯（release 已先退出过，这里成为空操作）
        // 或被中断（目标死亡/超距/换姿态/读档安全退出）。退出动作统一，且退出即进冷却。
        // r25 错峰：被打断退出时状态机键还没清，从锁定目标读到 uuid 后解除登记
        String lockedTarget = ChargeStateStore.lockedTargetRawUuid(caster);
        exitShredding(caster, now);
        ShredAoeDeferral.clearShred(lockedTarget, now);
    }

    @Override
    public boolean release(ServerLevel level, Mob caster, LivingEntity target, long now, boolean ignoreCooldown) {
        try {
            boolean inState = ChargeStateStore.getState(caster) == ChargeStateStore.CHARGING
                    && SkillRegistry.MEAT_SHREDDER_ID.equals(ChargeStateStore.stateSkill(caster));
            if (inState) {
                // —— 正常收锯：绞满时长上限（调度器在 progress≥need 时调用）——
                exitShredding(caster, now);
                // r25 错峰：绞肉结束，立即解除登记，等待中的同伴即刻放行；r26 影子落回同走这里
                ShredAoeDeferral.clearShred(target.getUUID().toString(), now);
                SkillCooldownStore.markCast(caster, SkillRegistry.MEAT_SHREDDER_ID, now);
                ChargeStateStore.markSilence(caster, now + SkillPriorityConfig.silenceTicks());
                SafeGuard.info("Meat Shredder: shredder stopped!" + IncineratorSkill.describe(caster)
                        + " shredding ended, cooldown " + MceConfig.meatShredderCooldownTicks()
                        + " ticks (meat_shredder_cooldown_ticks), global silence "
                        + SkillPriorityConfig.silenceTicks() + " tick");
                return true;
            }

            // —— /mce force 路径：跳过预扫描与冷却，立即开始绞肉（状态机由调度器接管）——
            EquipmentSlot slot = heldSlot(caster);
            if (slot == null) {
                SafeGuard.warn("Meat Shredder: forced cast failed -- " + IncineratorSkill.describe(caster)
                        + " holds neither " + CataclysmReflect.MEAT_SHREDDER_ITEM
                        + " in main/off hand (run /mce meat_shredder equip first)");
                return false;
            }
            int cooldownTicks = MceConfig.meatShredderCooldownTicks();
            if (!ignoreCooldown && !SkillCooldownStore.isReady(
                    caster, SkillRegistry.MEAT_SHREDDER_ID, now, cooldownTicks)) {
                SafeGuard.infoThrottled("mce_ms_cooling_" + caster.getUUID(), now,
                        "Meat Shredder: " + IncineratorSkill.describe(caster) + " is still on cooldown ("
                                + SkillCooldownStore.remainingTicks(
                                        caster, SkillRegistry.MEAT_SHREDDER_ID, now, cooldownTicks)
                                + " ticks), skipped this time");
                return false;
            }
            ChargeStateStore.beginCharging(caster, SkillRegistry.MEAT_SHREDDER_ID, now, target);
            beginCharge(level, caster, target, now);
            SafeGuard.info("Meat Shredder: forcing the shredder to start (/mce force path, pre-scan skipped; the scheduler stops it after "
                    + MceConfig.meatShredderMaxDurationTicks() + " ticks)");
            return true;
        } catch (Throwable t) {
            SafeGuard.error("Meat Shredder: cast threw (caught)", t);
            exitShredding(caster, now); // 兜底：绝不让同伴留在“永久举锯”里
            return false;
        }
    }

    /** 退出动作统一：收锯（原版 releaseUsing 自动播 SHREDDER_END + 清循环音效缓存）+ 移除减速 + 进冷却 */
    private static void exitShredding(Mob caster, long now) {
        try {
            CompoundTag tag = caster.getPersistentData();
            boolean wasShredding = tag.contains(KEY_SHRED_HAND);
            if (caster.isUsingItem()) {
                caster.stopUsingItem();
            }
            removeSlowdown(caster);
            tag.remove(KEY_SHRED_HAND);
            if (wasShredding) {
                // 拍板：退出即进冷却（正常收锯与被打断退出同一条路；重复退出因键已清而跳过）
                SkillCooldownStore.markCast(caster, SkillRegistry.MEAT_SHREDDER_ID, now);
            }
        } catch (Throwable t) {
            SafeGuard.error("Meat Shredder: leaving the shredding state failed (caught)", t);
        }
    }

    // ════════════════════════ 触发门槛（findTarget 即门槛） ════════════════════════

    /**
     * 触发门槛全部前置在这里（调度器拿到非空 target 才会进入绞肉状态）：
     * 距离 ≤ 射程 → 副手盾门槛 → 只打敌对 → 预扫描（无友军、无被动生物）。
     * 每个门槛都有限流日志，扫描面板能看到“为什么没进绞肉”。
     */
    @Override
    public LivingEntity findTarget(Mob caster) {
        try {
            LivingEntity target = CompanionSkill.super.findTarget(caster);
            if (target == null) {
                return null;
            }
            long now = caster.level().getGameTime();

            // —— 门槛 1：距离 ≤ 射程（默认 3.0，对齐原版 2.5 视线 + 0.5 盒外扩）——
            double distance = caster.distanceTo(target);
            double range = MceConfig.meatShredderRange();
            if (distance > range) {
                SafeGuard.infoThrottled("mce_ms_far_" + caster.getUUID(), now,
                        "Meat Shredder: target distance " + String.format("%.1f", distance) + " blocks > range "
                                + range + " blocks (meat_shredder_range), falling back to normal attacks/other skills");
                return null;
            }

            // —— 门槛 2：副手盾牌（默认豁免，与断魂战戟一致；可配置关掉）——
            ItemStack offhand = caster.getItemBySlot(EquipmentSlot.OFFHAND);
            boolean shield = isShieldLike(offhand);
            if (shield && !MceConfig.meatShredderAllowWithShield()) {
                SafeGuard.infoThrottled("mce_ms_shield_" + caster.getUUID(), now,
                        "Meat Shredder: a shield is in the off hand and meat_shredder_allow_with_shield=false (vanilla blocking is exclusive), falling back to normal attacks");
                return null;
            }

            // —— 门槛 3：只打敌对目标（拍板“必须做”）——
            if (MceConfig.meatShredderHostileOnly() && !isAcceptableHostile(caster, target)) {
                SafeGuard.infoThrottled("mce_ms_hostile_" + caster.getUUID(), now,
                        "Meat Shredder: the target " + IncineratorSkill.describe(target)
                                + " is not hostile (Monster / currently attacking the companion / just hit by the companion), falling back to normal attacks -- passive mobs are not shredded on purpose");
                return null;
            }

            // —— 门槛 4：预扫描（照霆浪波浪/掣雷贴脸的做法）——
            if (!prescanClear(caster, target, now)) {
                return null;
            }
            return target;
        } catch (Throwable t) {
            SafeGuard.error("Meat Shredder: the trigger gate check failed (treated conservatively as 'do not enter shredding')", t);
            return null;
        }
    }

    /** 目标是不是“敌对”：Monster（含灾变/多数模组怪）、正在攻击同伴的实体、刚被同伴打过正在交战的实体 */
    private static boolean isAcceptableHostile(Mob caster, LivingEntity target) {
        try {
            if (AllyCheck.isAlly(caster, target)) {
                return false; // 友军永远是非法目标
            }
            if (target instanceof Monster) {
                return true;
            }
            if (target instanceof Mob tm && tm.getTarget() == caster) {
                return true; // 正在攻击同伴
            }
            return caster.getLastHurtByMob() == target; // 刚被同伴打过，已在交战
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 预扫描（进绞肉前一次性）：按原版 onUseTick 的搜索盒（自身碰撞盒沿视线延伸 2.5 + 四周 1 格）
     * 检查盒内其它生物——有友军（AllyCheck）或（hostile_only 开启时）非敌对生物 → 退回普攻。
     * 这是“只打敌对”的第一层：原版循环对搜索盒内所有 LivingEntity 结算，靠预扫描挡开局。
     */
    private static boolean prescanClear(Mob caster, LivingEntity target, long now) {
        try {
            Vec3 look = caster.getViewVector(1.0F);
            Vec3 lookOffset = look.scale(VANILLA_LOOK_RANGE);
            AABB searchArea = caster.getBoundingBox().expandTowards(lookOffset).inflate(VANILLA_BOX_PADDING);
            List<Entity> candidates = caster.level().getEntities(caster, searchArea);
            for (Entity entity : candidates) {
                if (!(entity instanceof LivingEntity other) || other == caster || other == target || !other.isAlive()) {
                    continue;
                }
                if (AllyCheck.isAlly(caster, other)) {
                    SafeGuard.infoThrottled("mce_ms_prescan_ally_" + caster.getUUID(), now,
                            "Meat Shredder: the pre-scan found the ally " + IncineratorSkill.describe(other)
                                    + " inside the shredding range (vanilla search box), falling back to normal attacks -- ally protection layer 1 in effect");
                    return false;
                }
                if (MceConfig.meatShredderHostileOnly() && !isAcceptableHostile(caster, other)) {
                    SafeGuard.infoThrottled("mce_ms_prescan_" + caster.getUUID(), now,
                            "Meat Shredder: the pre-scan found the passive mob " + IncineratorSkill.describe(other)
                                    + " inside the shredding range (vanilla search box, it could be hit), falling back to normal attacks -- 'hostile targets only' in effect");
                    return false;
                }
            }
            return true;
        } catch (Throwable t) {
            SafeGuard.error("Meat Shredder: the pre-scan threw (treated conservatively as 'do not enter shredding')", t);
            return false;
        }
    }

    // ════════════════════════ 工具 ════════════════════════

    /** 这件物品能不能当盾举（原版盾 + NeoForge SHIELD_BLOCK 能力，与灾变 use() 的判定同口径） */
    private static boolean isShieldLike(ItemStack stack) {
        try {
            if (stack.isEmpty()) {
                return false;
            }
            return stack.getItem() instanceof ShieldItem
                    || stack.canPerformAction(ItemAbilities.SHIELD_BLOCK);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 绞肉减速：transient 修饰（不随存档走，读档后由 tickCharge 补挂）；已挂则跳过 */
    private static void applySlowdown(Mob caster) {
        try {
            AttributeInstance speed = caster.getAttribute(Attributes.MOVEMENT_SPEED);
            if (speed != null && !speed.hasModifier(SLOWDOWN_ID)) {
                speed.addTransientModifier(SLOWDOWN_MODIFIER);
            }
        } catch (Throwable t) {
            SafeGuard.warn("Meat Shredder: adding the slowness modifier failed (shredding unaffected): " + t.getClass().getSimpleName());
        }
    }

    private static void removeSlowdown(Mob caster) {
        try {
            AttributeInstance speed = caster.getAttribute(Attributes.MOVEMENT_SPEED);
            if (speed != null) {
                speed.removeModifier(SLOWDOWN_ID);
            }
        } catch (Throwable t) {
            SafeGuard.warn("Meat Shredder: removing the slowness modifier failed (logic unaffected): " + t.getClass().getSimpleName());
        }
    }

    /** 读档后恢复绞肉用的手（persistentData 里存的手名；异常回退主手） */
    private static InteractionHand restoreHand(Mob caster) {
        try {
            String hand = caster.getPersistentData().getString(KEY_SHRED_HAND);
            return "OFF_HAND".equals(hand) ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        } catch (Throwable t) {
            return InteractionHand.MAIN_HAND;
        }
    }

    /** 绞肉开场音（灾变 use() 入口播的 SHREDDER_START；同伴不走 use()，由这里补播对齐表现） */
    private static void playShredderStart(ServerLevel level, Mob caster) {
        try {
            SoundEvent start = CataclysmReflect.getShredderStartSound();
            if (start != null) {
                level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                        start, SoundSource.PLAYERS, 1.5F, 1.0F);
            }
        } catch (Throwable ignored) {
        }
    }

    // ════════════════════════ r26 绞肉 DPS 实测（拍板：必须验证绞肉没被影子闸门削弱） ════════════════════════

    /** 滚动统计窗口：最近 200 tick = 10 秒（对齐拍板“统计 10 秒内的总伤害”） */
    private static final int DPS_WINDOW_TICKS = 200;
    /** 台账硬上限（防极端场景堆积；正常 10 秒最多 ~240 笔） */
    private static final int DPS_LEDGER_CAP = 5000;

    private record ShredDpsRecord(long tick, float amount) {}

    /** 绞肉伤害落地台账（全局滚动窗口；ShadowInvulnGate 在 LivingDamageEvent.Post 喂入） */
    private static final java.util.ArrayDeque<ShredDpsRecord> DPS_LEDGER = new java.util.ArrayDeque<>();

    /**
     * 记一笔“绞肉伤害实际生效”（Post 事件、只记真落地的净伤）。
     * 每 40 tick 打一条滚动汇总日志，方便不开命令、纯看日志就能核对 DPS。
     */
    public static void recordShredApplied(Mob shredder, float applied, long now) {
        try {
            synchronized (DPS_LEDGER) {
                DPS_LEDGER.addLast(new ShredDpsRecord(now, applied));
                while (!DPS_LEDGER.isEmpty()
                        && (now - DPS_LEDGER.peekFirst().tick() > DPS_WINDOW_TICKS
                            || DPS_LEDGER.size() > DPS_LEDGER_CAP)) {
                    DPS_LEDGER.removeFirst();
                }
            }
            if (SafeGuard.shouldLog("mce_ms_dps_" + shredder.getUUID(), now, 40L)) {
                SafeGuard.info("measured shredder DPS: " + IncineratorSkill.describe(shredder) + " -- "
                        + rollingDpsSummary(now));
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 最近 10 秒滚动总伤/秒率（/mce meat_shredder status 用；原版满速 1 点/tick ≈ 20 点/秒）。
     *
     * @param now 当前 gameTime（状态命令/落地事件各自传入，本类不反查服务端）
     */
    public static String rollingDpsSummary(long now) {
        try {
            float total = 0F;
            int hits = 0;
            synchronized (DPS_LEDGER) {
                for (ShredDpsRecord record : DPS_LEDGER) {
                    total += record.amount();
                    hits++;
                }
            }
            if (hits == 0) {
                return "most recent " + (DPS_WINDOW_TICKS / 20) + " seconds without a shredder hit record (" + DPS_WINDOW_TICKS
                        + " tick window; vanilla full rate should be about 20 points/second)";
            }
            double perSecond = total * 20.0D / DPS_WINDOW_TICKS;
            return "most recent " + (DPS_WINDOW_TICKS / 20) + " seconds (rolling " + DPS_WINDOW_TICKS
                    + " ticks) actually applied " + String.format("%.1f", total) + " points / " + hits
                    + " hits ~= " + String.format("%.1f", perSecond)
                    + " points/second (vanilla full rate 1 point/tick ~= 20 points/second; the shadow gate never touches shredder damage, this number should match r25)";
        } catch (Throwable t) {
            return "the DPS statistics threw (no game impact)";
        }
    }

    // ════════════════════════ 事件层接入点（AllyDamageGuard / 命令用） ════════════════════════

    /**
     * 绞肉拦截窗口：该同伴当前正处于“绞肉状态”（CHARGING 且技能 id 匹配，persistentData 判定，
     * 零成本直通）。窗口内原版 onUseTick 还在对搜索盒结算——友军伤害在事件层取消，
     * 同伴自己的非绞肉出手（普攻等）也一并取消。
     */
    public static boolean shredWindowActive(Entity caster) {
        try {
            return caster instanceof Mob
                    && ChargeStateStore.getState(caster) == ChargeStateStore.CHARGING
                    && SkillRegistry.MEAT_SHREDDER_ID.equals(ChargeStateStore.stateSkill(caster));
        } catch (Throwable t) {
            return false;
        }
    }

    /** 记录“这个目标刚被绞肉命中过”（无敌帧副作用观察；时间戳自过期，无需清理） */
    public static void recordShredHit(LivingEntity victim, long now) {
        try {
            victim.getPersistentData().putLong(KEY_SHRED_HIT_AT, now);
        } catch (Throwable ignored) {
        }
    }

    /** 这个目标最近（60 tick 内）被绞肉命中过吗（无敌帧副作用观察用） */
    public static boolean recentlyShredHit(LivingEntity victim, long now) {
        try {
            long last = victim.getPersistentData().getLong(KEY_SHRED_HIT_AT);
            return last > 0 && now - last <= INVULN_OBSERVE_TICKS;
        } catch (Throwable t) {
            return false;
        }
    }

    /** /mce reset 用：清绞肉易变键 + 摘减速 + 清错峰暂缓记录 + 必要时强制收锯（防止 reset 后“永久举锯”） */
    public static void clearVolatileState(Entity entity) {
        try {
            if (entity instanceof Mob mob) {
                removeSlowdown(mob);
                ShredAoeDeferral.clearEpisode(mob);
                ShadowInvulnGate.clearAll(mob); // r26：/mce reset 连影子账本一起清（不落回，按原版语义自然过期）
                // /mce reset 先于本调用清掉了状态机键，所以这里按“正在使用的东西是绞肉锯”判定
                if (mob.isUsingItem()) {
                    ItemStack used = mob.getItemInHand(mob.getUsedItemHand());
                    if (!used.isEmpty()
                            && CataclysmReflect.MEAT_SHREDDER_ITEM.equals(
                                    BuiltInRegistries.ITEM.getKey(used.getItem()))) {
                        mob.stopUsingItem();
                    }
                }
            }
            entity.getPersistentData().remove(KEY_SHRED_HAND);
        } catch (Throwable t) {
            SafeGuard.error("Meat Shredder: clearing volatile state failed", t);
        }
    }

    /** 日志用：当前绞肉进度（/mce skills show、状态自检用） */
    public static String progressText(Mob caster, long now) {
        try {
            if (!shredWindowActive(caster)) {
                return "not shredding";
            }
            int progress = (int) Math.max(0, now - ChargeStateStore.chargeStart(caster));
            return progress + "/" + MceConfig.meatShredderMaxDurationTicks()
                    + " ticks, hand=" + (restoreHand(caster) == InteractionHand.OFF_HAND ? "off hand" : "main hand");
        } catch (Throwable t) {
            return "unknown";
        }
    }
}
