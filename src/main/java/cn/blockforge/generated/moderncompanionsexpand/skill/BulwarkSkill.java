package cn.blockforge.generated.moderncompanionsexpand.skill;

import cn.blockforge.generated.moderncompanionsexpand.config.MceConfig;
import cn.blockforge.generated.moderncompanionsexpand.config.SkillPriorityConfig;
import cn.blockforge.generated.moderncompanionsexpand.util.AllyCheck;
import cn.blockforge.generated.moderncompanionsexpand.util.SafeGuard;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * 火焰壁垒（bulwark_of_the_flame）同伴技能：朝当前攻击目标冲锋。
 *
 * 对齐灾变源码 Bulwark_of_the_flame.releaseUsing（t 固定满蓄力 4，按确认第 2 点）：
 * - 冲量：方向 = 施法者朝向目标后的视线公式，大小 f5 = 3.0 * (t/6) = 2.0；
 *   push(f1, 0, f3)，落地时额外上抬 1.2/2 格；
 * - 判定窗口：timer = t*2 = 8 tick（确认第 5 点：窗口期间不做蓄力中断判定）；
 * - 命中伤害 0.6 * (t*2) = 4.8 点，来源 mobProjectile(施法者, 施法者)；
 *   命中范围 AABB 膨胀 3.5 / 0.75 / 3.5 小范围 AoE；
 * - 击退 (dx, dz) = 冲量 * 0.1，撞墙追加 +1 伤害并眩晕 50 tick；
 * - 冷却不走原版 ItemCooldowns（那里对 Player 硬转型，同伴会 ClassCastException），
 *   走我们的 persistentData 冷却，默认 200 tick（10 秒），
 *   配置项 bulwark_cooldown_ticks（按用户补充要求）。
 *
 * 复用策略（按分析文档 1.4）：
 * - 方案 A：反射调用灾变自己的 ChargeAttachment（setter 写入 + 我们每 tick 驱动它的 tick()），
 *   命中、AoE、击退、撞墙眩晕、音效全部走灾变原版代码；
 * - 方案 B：附件层不可用时，自建同款 AABB 判定 + 反射构造 Wall_Watcher_Entity 盯目标；
 * - 方案 C：连 Wall_Watcher 都造不出来时，直接伤害 + 原版击退（无撞墙眩晕），日志说明；
 * - 不调用灾变的 releaseUsing：第一行 isShiftKeyDown() 同伴永远过不去，
 *   尾部 ((Player)…).getCooldowns() 还会当场抛 ClassCastException（整段绕过）。
 *
 * 友军安全（r9 修正）：原版 ChargeAttachment 只按 isAlliedTo 过滤，而该判定只认
 * 计分板同队/主人链——测试环境的“命令装备出来的施法者 + 玩家/别的同伴”全部漏网，
 * Wall_Watcher 每 tick 的 setMotion 就是把人钉着飞的元凶（主模组友伤只吞伤害不吞位移）。
 * 现在三层拦截：方案 A 命中同 tick 把友军从 Wall_Watcher 观察名单摘除
 * （CataclysmReflect.stripAlliesFromWatchers）；灾变自己调的 hurt() 由事件层
 * （AllyDamageGuard，仅壁垒 ACTIVE 窗口）兜底取消；方案 B/C 在源头用统一
 * AllyCheck.isAlly 判定，友军既不结算伤害也不进击退名单。
 * 配置 bulwark_friendly_knockback=true 可整体回到灾变原版行为。
 */
public final class BulwarkSkill implements CompanionSkill {

    public static final BulwarkSkill INSTANCE = new BulwarkSkill();

    /** 确认第 2 点：同伴没有“按住 1~4 tick”这层概念，固定满蓄力 */
    private static final int CHARGE_T = 4;
    /** 窗口 tick 数 = t*2（= 8，确认第 5 点） */
    private static final int WINDOW_TICKS = CHARGE_T * 2;
    private static final float DAMAGE_PER_EFFECTIVE_CHARGE = 0.6F;

    private BulwarkSkill() {}

    // ════════════════════════ CompanionSkill ════════════════════════

    @Override
    public String id() {
        return SkillRegistry.BULWARK_ID;
    }

    @Override
    public net.minecraft.resources.ResourceLocation requiredItem() {
        return CataclysmReflect.BULWARK_ITEM;
    }

    /** 壁垒是盾系物品：原版习惯拿在副手，调度器扫描主手/副手都能识别（r6 只扫主手是事故根因） */
    @Override
    public net.minecraft.world.entity.EquipmentSlot preferredSlot() {
        return net.minecraft.world.entity.EquipmentSlot.OFFHAND;
    }

    @Override
    public boolean enabled() {
        return MceConfig.bulwarkEnabled();
    }

    @Override
    public int cooldownTicks() {
        return MceConfig.bulwarkCooldownTicks();
    }

    @Override
    public int chargeTicks() {
        return 0; // 瞬时释放：优先级就绪 → 当场冲锋
    }

    @Override
    public int activeTicks() {
        return WINDOW_TICKS;
    }

    @Override
    public boolean release(ServerLevel level, Mob caster, LivingEntity target, long now, boolean ignoreCooldown) {
        if (!ignoreCooldown && !SkillCooldownStore.isReady(caster, id(), now, cooldownTicks())) {
            SafeGuard.infoThrottled("mce_b_cooling_" + caster.getUUID(), now,
                    "Bulwark of the Flame: " + IncineratorSkill.describe(caster) + " charge cooldown remaining "
                            + SkillCooldownStore.remainingTicks(caster, id(), now, cooldownTicks()) + " ticks, skipped this time");
            return false;
        }
        if (!CataclysmReflect.isCataclysmPresent()) {
            SafeGuard.warnThrottled("mce_b_nocate_" + level.dimension().location(), now,
                    "Bulwark of the Flame: Cataclysm mod not detected, charge cancelled (item should not exist, likely Cataclysm was unloaded)");
            return false;
        }

        // —— 确认第 3 点：冲锋方向 = 当前攻击目标方向；调度器已保证 target 非空，这里再兜底 ——
        if (target == null) {
            SafeGuard.infoThrottled("mce_b_notarget_" + caster.getUUID(), now,
                    "Bulwark of the Flame: no attack target, not charging (prevents blind charging to death)");
            return false;
        }

        // —— 冲量：完全照抄灾变 releaseUsing 的视线公式（先把同伴拧向目标）——
        float yawDegrees = IncineratorSkill.yawToDegrees(caster, target);
        caster.setYRot(yawDegrees);
        caster.setXRot(0.0F);
        caster.setYHeadRot(yawDegrees);

        float f7 = caster.getYRot();
        float f = caster.getXRot();
        float f1 = -Mth.sin(f7 * (float) Math.PI / 180.0F) * Mth.cos(f * (float) Math.PI / 180.0F);
        float f2 = -Mth.sin(f * (float) Math.PI / 180.0F);
        float f3 = Mth.cos(f7 * (float) Math.PI / 180.0F) * Mth.cos(f * (float) Math.PI / 180.0F);
        float f4 = Mth.sqrt(f1 * f1 + f2 * f2 + f3 * f3);
        float f5 = 3.0F * (CHARGE_T / 6.0F);
        f1 *= f5 / f4;
        f3 *= f5 / f4;
        caster.push((double) f1, 0.0D, (double) f3);
        if (caster.onGround()) {
            caster.move(MoverType.SELF, new Vec3(0.0D, 1.1999999F / 2.0D, 0.0D));
        }

        // —— 施法表现：挥一下持盾的那只手 + 状态日志（原版壁垒无蓄力、无音效，命中时灾变自带音效）——
        caster.swing(heldSlot(caster) == net.minecraft.world.entity.EquipmentSlot.OFFHAND
                ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND, true);

        boolean modeA = CataclysmReflect.isChargeAttachmentReady()
                && CataclysmReflect.startCharge(caster, WINDOW_TICKS, DAMAGE_PER_EFFECTIVE_CHARGE,
                        (float) WINDOW_TICKS, f1 * 0.1F, f3 * 0.1F, now);

        ChargeStateStore.beginActive(caster, id(), now, now + WINDOW_TICKS, f1, f3);
        ChargeStateStore.setBulwarkMode(caster, modeA ? ChargeStateStore.MODE_A : ChargeStateStore.MODE_B);
        SkillCooldownStore.markCast(caster, id(), now);
        ChargeStateStore.markSilence(caster, now + SkillPriorityConfig.silenceTicks());

        SafeGuard.info("Bulwark of the Flame: " + IncineratorSkill.describe(caster) + " toward target " + IncineratorSkill.describe(target)
                + " charging (full charge t=" + CHARGE_T + ", window " + WINDOW_TICKS + " ticks, using "
                + (modeA ? "Plan A: Cataclysm charge attachment" : "Plan B: custom hit detection (attachment reflection layer unavailable)")
                + "), cooldown " + cooldownTicks() + " tick"
                + (ignoreCooldown ? " (command forced)" : ""));
        return true;
    }

    @Override
    public boolean tickActive(ServerLevel level, Mob caster, long now) {
        double dx = ChargeStateStore.activeDx(caster);
        double dz = ChargeStateStore.activeDz(caster);

        if (MceConfig.bulwarkHoldVelocity()) {
            // 可选：窗口内钉住速度，避免原版摩擦把冲量吃掉（默认关，保持原版滑行手感）
            caster.setDeltaMovement(dx, caster.getDeltaMovement().y(), dz);
            caster.hurtMarked = true;
        }

        byte mode = ChargeStateStore.getBulwarkMode(caster);
        long until = ChargeStateStore.activeUntil(caster);
        boolean stillCharging = mode == ChargeStateStore.MODE_A
                ? CataclysmReflect.tickCharge(caster)
                : tickModeB(level, caster, dx, dz, now);

        // r9 友军保护（方案 A）：灾变 ChargeAttachment 命中当 tick 就会 new 出 Wall_Watcher 并 watch()
        // 非原版友军目标（玩家/别的同伴在原版 isAlliedTo 眼里往往不算友军），从下一 tick 起
        // 每 tick 把这些目标的水平速度钉死 = 击飞。这里在同一调用栈里把友军从名单摘掉：
        // Wall_Watcher 首 tick 尚未执行，友军一帧都不会被移动；敌方目标不受任何影响。
        // stripAlliesFromWatchers 内部已全程包 SafeGuard，异常返回 -1 不会打断窗口。
        if (mode == ChargeStateStore.MODE_A && !MceConfig.bulwarkFriendlyKnockback()) {
            CataclysmReflect.stripAlliesFromWatchers(level, caster, now);
        }

        if (!stillCharging && mode == ChargeStateStore.MODE_A && now < until
                && !CataclysmReflect.attachmentPresent(caster)) {
            // 冲锋附件不序列化，恰好在窗口内存档重载会把实例丢掉：
            // 用存下来的方向按剩余 tick 重新注入一次，让窗口走完；重注入也失败则降级方案 B。
            int remaining = (int) Math.max(1, until - now);
            SafeGuard.info("Bulwark of the Flame: charge attachment lost inside window (save reload), re-injected with " + remaining + " ticks remaining");
            boolean rearmed = CataclysmReflect.startCharge(caster, remaining, DAMAGE_PER_EFFECTIVE_CHARGE,
                    WINDOW_TICKS, (float) (dx * 0.1D), (float) (dz * 0.1D), now);
            if (rearmed) {
                return true;
            }
            ChargeStateStore.setBulwarkMode(caster, ChargeStateStore.MODE_B);
            return tickModeB(level, caster, dx, dz, now);
        }
        return stillCharging && now < until;
    }

    // ════════════════════════ 方案 B：自建同款判定（附件层挂了才走到） ════════════════════════

    /** 复刻 ChargeAttachment.tick 的服务端分支：膨胀 0.75 的 AABB 探敌人 → 命中结算 3.5 AoE。 */
    private boolean tickModeB(ServerLevel level, Mob caster, double dx, double dz, long now) {
        net.minecraft.world.phys.AABB collideBox = caster.getBoundingBox().inflate(0.75F, 0.75F, 0.75F);
        List<LivingEntity> checks = level.getEntitiesOfClass(LivingEntity.class, collideBox,
                e -> e != caster && e.isAlive());
        if (checks.isEmpty()) {
            // 原版同款结束条件：撞墙或窗口耗尽
            return !caster.horizontalCollision;
        }

        int remaining = (int) Math.max(1, ChargeStateStore.activeUntil(caster) - now);
        List<LivingEntity> impact = level.getEntitiesOfClass(LivingEntity.class,
                caster.getBoundingBox().inflate(3.5F, 0.75F, 3.5F),
                e -> e != caster && e.isAlive());
        List<LivingEntity> victims = new ArrayList<>();
        float totalDamage = DAMAGE_PER_EFFECTIVE_CHARGE * WINDOW_TICKS;
        boolean protectAllies = !MceConfig.bulwarkFriendlyKnockback();
        for (LivingEntity victim : impact) {
            if (victim.isAlliedTo(caster)) {
                continue; // 原版友军判定，始终生效（与灾变一致）
            }
            if (protectAllies && AllyCheck.isAlly(caster, victim)) {
                continue; // r9 扩展友军：玩家/其他同伴/同主人宠物 —— 不结算伤害也不击退（方案 C 击退同样跳过）
            }
            boolean flag = victim.hurt(level.damageSources().mobProjectile(caster, caster), totalDamage);
            if (flag) {
                victims.add(victim);
                victim.playSound(net.minecraft.sounds.SoundEvents.ANVIL_LAND, 1.5F, 0.8F);
            }
        }

        if (!victims.isEmpty()) {
            // 撞墙补伤害 + 眩晕的表现尽量白嫖灾变的 Wall_Watcher；它也不可用就退化为直接击退（方案 C）
            CataclysmReflect.spawnWallWatcher(level, caster.blockPosition(), remaining, WINDOW_TICKS,
                    (double) WINDOW_TICKS, DAMAGE_PER_EFFECTIVE_CHARGE, dx, dz, caster, victims);
            if (!CataclysmReflect.isWallWatcherResolved()) {
                for (LivingEntity victim : victims) {
                    victim.push(dx * WINDOW_TICKS, 0.12D, dz * WINDOW_TICKS); // 方案 C：原版击退，无眩晕
                }
            }
        }
        caster.setDeltaMovement(0.0D, 0.0D, 0.0D);
        caster.hurtMarked = true;
        return false; // 命中一次即收窗（与原版一致）
    }

    /** 给命令/状态自检用：当前壁垒会走哪个方案（含 r9 友军保护状态） */
    public static String modeReport() {
        String ally = MceConfig.bulwarkFriendlyKnockback()
                ? "; ally protection=off (bulwark_friendly_knockback=true, Cataclysm vanilla behavior, allies get knocked back too)"
                : "; ally protection=on (Plan A strips the watcher list"
                        + (CataclysmReflect.isWatcherStripReady() ? "(reflection layer ready)" : "(reflection layer missing, knockback not blocked, damage still blocked at event layer)")
                        + "+ Plan B/C source filtering)";
        if (!CataclysmReflect.isCataclysmPresent()) {
            return "Cataclysm not installed: Bulwark of the Flame skill skipped entirely";
        }
        if (CataclysmReflect.isChargeAttachmentReady()) {
            return "Plan A available (reuses Cataclysm ChargeAttachment, full vanilla hit/knockback/wall stun)" + ally;
        }
        if (CataclysmReflect.isWallWatcherResolved()) {
            return "Plan B (attachment layer mismatch: custom AABB detection + Cataclysm Wall_Watcher)" + ally;
        }
        return "Plan C (both attachment layer and Wall_Watcher mismatch: direct damage + vanilla knockback, no wall stun)" + ally;
    }
}
