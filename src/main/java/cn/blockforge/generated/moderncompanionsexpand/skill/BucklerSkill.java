package cn.blockforge.generated.moderncompanionsexpand.skill;

import cn.blockforge.generated.moderncompanionsexpand.config.MceConfig;
import cn.blockforge.generated.moderncompanionsexpand.config.SkillPriorityConfig;
import cn.blockforge.generated.moderncompanionsexpand.util.AllyCheck;
import cn.blockforge.generated.moderncompanionsexpand.util.SafeGuard;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * 湮灭圆盾（legendary_monsters:buckler_of_annihilation，传奇怪物）同伴技能：
 * 贴脸撞击（10 点 + 击飞 + 着火）+ 3 枚小型湮灭爆弹（8 点/枚）。
 *
 * 路线（r32 拍板，源码分析见 docs/ANALYSIS_ROUND31_BUCKLER.md、实现细节见 docs/IMPL_ROUND32_BUCKLER.md）：
 * - 瞬发：原版门槛“按住右键 ≥ 11 tick + 潜行 + 松手”全部绑 Player（releaseUsing 第一行
 *   instanceof Player），同伴版不走原版入口、不做蓄力站定（拍板：10 tick 门槛无意义）；
 * - 撞击：自研复刻，判定几何照抄原版 AABB——
 *   getBoundingBox().inflate(0.05, 1.5, 0.05).expandTowards(cos×range, 0, sin×range)
 *   （一块贴脸矩形板，原版就没有角度扇形，拍板不加扇形），buckler_slash_range=1.75 可调；
 *   伤害口径 mobAttack(caster)（与原版 playerAttack 同类，不穿甲）× buckler_slash_damage=10.0；
 * - 击飞（拍板）：保留原版乘法 ×1.25 + 加保底位移——乘完水平速度低于
 *   buckler_knockback_min_horizontal（0.6）就按“背向同伴”方向补到该值，垂直低于
 *   buckler_knockback_min_vertical（0.3）就抬到该值（原版纯乘法对静止目标无效，加保底有存在感）；
 *   命中同时照抄原版 setRemainingFireTicks(60)（着火 3 秒）；
 * - 爆弹：反射复用 SmallAnnihilationBombEntity，构造器 (EntityType, Level, LivingEntity, float)，
 *   damage 传 buckler_bomb_damage=8.0，inaccuracy 0/15/30 照抄（三发同 tick、随机散布），
 *   出膛点 (x, y+1, z)、弹速 1、伤害类型 legendary_monsters:void 全在实体内部，零自研伤害代码；
 * - 耐久放弃（同伴没有耐久概念，原版 hurtAndBreak 不复刻）；
 * - 冷却拆分（拍板）：命中 ≥ 1 个敌人 → buckler_cooldown_ticks=200；一个都没碰到 →
 *   buckler_miss_cooldown_ticks=60。两条冷却各自存 persistentData（键 mce_cd_buckler /
 *   mce_cd_buckler_miss），就绪判定 = 两条都就绪（见 release 内注释的时序推演）；
 * - 自动触发距离：目标 > buckler_trigger_distance（4.0）不自动放（贴脸反击向技能，
 *   远处白扔爆弹浪费冷却）；命令 force 不受此限；
 * - 友军保护：AllyCheck 预过滤（撞击逐个过滤后才 hurt；友军不挨撞不被击飞）+
 *   AllyDamageGuard buckler 窗口（固定 100 tick，拍板）兜底爆弹飞行期——
 *   爆弹伤害口径是 legendary_monsters:void + causing entity = 同伴，与超立方体的
 *   “湮灭接管闸门”同口径，所以闸门在圆盾窗口激活时必须让路（窗口内 void 伤害是爆弹
 *   的正常伤害，只做 AllyCheck 过滤不做无条件取消），见 AllyDamageGuard 判定顺序；
 * - 优先级：bulwark → buckler → tesseract → ...（拍板），圆盾副手、超立方体主手可同时装备，
 *   冷却各自存 persistentData，互不抢；
 * - 命令：/mce buckler status|equip|clear|reset|force（force 走通用路径，跳过冷却与触发距离）。
 */
public final class BucklerSkill implements CompanionSkill {

    public static final BucklerSkill INSTANCE = new BucklerSkill();

    /** 打空冷却的独立 persistentData 键（SkillCooldownStore.keyFor("buckler_miss")） */
    public static final String MISS_COOLDOWN_ID = "buckler_miss";

    /** 友军拦截窗口：撞击瞬发 + 爆弹飞行一两秒，固定 100 tick（拍板） */
    public static final long GUARD_BASE_TICKS = 100L;

    /** 爆弹发射参数（照抄原版 shootAnnihilationBomb：弹速 1、三发 inaccuracy 0/15/30） */
    public static final float BOMB_VELOCITY = 1.0F;
    private static final int[] BOMB_INACCURACIES = {0, 15, 30};
    /** 出膛高度：原版 shootAnnihilationBomb 传 player.getY() + 1（胸口） */
    private static final double BOMB_SPAWN_Y_OFFSET = 1.0D;
    /** 撞击命中后的着火 tick（原版 setRemainingFireTicks(60) 照抄） */
    private static final int HIT_FIRE_TICKS = 60;
    /** 原版击飞乘法因子（照抄 1.25f） */
    private static final double KNOCKBACK_MULTIPLIER = 1.25D;
    /** 撞击判定盒的水平外扩（原版 boxWidth = 0.05f 照抄） */
    private static final double BOX_INFLATE_HORIZONTAL = 0.05D;
    /** 撞击判定盒的上下外扩（原版 1.5f 照抄） */
    private static final double BOX_INFLATE_VERTICAL = 1.5D;

    /** persistentData 键：友军拦截窗口截止 tick / 最近一次结果（status 展示用） */
    private static final String KEY_GUARD_UNTIL = "mce_bk_guard_until";
    private static final String KEY_LAST_RESULT = "mce_bk_last";
    /** 最近一次结果：1=命中（进长冷却）2=打空（进短冷却） */
    public static final byte RESULT_HIT = 1;
    public static final byte RESULT_MISS = 2;

    private BucklerSkill() {}

    // ════════════════════════ CompanionSkill ════════════════════════

    @Override
    public String id() {
        return SkillPriorityConfig.SKILL_BUCKLER;
    }

    @Override
    public net.minecraft.resources.ResourceLocation requiredItem() {
        return LegendaryMonstersReflect.BUCKLER_ITEM;
    }

    /** 湮灭圆盾本质是盾牌（extends ShieldItem），按原版习惯副手持握 */
    @Override
    public EquipmentSlot preferredSlot() {
        return EquipmentSlot.OFFHAND;
    }

    @Override
    public boolean enabled() {
        return MceConfig.bucklerEnabled();
    }

    /**
     * 调度器预检口径：命中/打空两条冷却里较小的一个（真正的分结果冷却在 release() 里查，
     * 预检只做“大概率没戏就别进 release”的性能阀值，与霆浪双冷却同款）。
     */
    @Override
    public int cooldownTicks() {
        return Math.min(MceConfig.bucklerCooldownTicks(), MceConfig.bucklerMissCooldownTicks());
    }

    /** 瞬发：原版 10 tick 蓄力门槛对自动战斗无意义（拍板不做蓄力站定） */
    @Override
    public int chargeTicks() {
        return 0;
    }

    @Override
    public boolean release(ServerLevel level, Mob caster, LivingEntity target, long now, boolean ignoreCooldown) {
        // —— 冷却预检：两条冷却都就绪才放（时序推演见类注释；force 直接跳过）——
        boolean hitReady = ignoreCooldown
                || SkillCooldownStore.isReady(caster, id(), now, MceConfig.bucklerCooldownTicks());
        boolean missReady = ignoreCooldown
                || SkillCooldownStore.isReady(caster, MISS_COOLDOWN_ID, now, MceConfig.bucklerMissCooldownTicks());
        if (!hitReady || !missReady) {
            SafeGuard.infoThrottled("mce_bk_cooling_" + caster.getUUID(), now,
                    "Buckler of Annihilation: " + IncineratorSkill.describe(caster) + " cooldown not ready (hit cooldown left "
                            + SkillCooldownStore.remainingTicks(caster, id(), now, MceConfig.bucklerCooldownTicks())
                            + ", miss cooldown left "
                            + SkillCooldownStore.remainingTicks(caster, MISS_COOLDOWN_ID, now, MceConfig.bucklerMissCooldownTicks())
                            + " ticks), skipped this time");
            return false;
        }
        if (!LegendaryMonstersReflect.isLegendaryMonstersPresent()) {
            SafeGuard.warnThrottled("mce_bk_nolm_" + level.dimension().location(), now,
                    "Buckler of Annihilation: Legendary Monsters mod not detected (bomb entity/sound depend on it), cast cancelled");
            return false;
        }
        EquipmentSlot slot = heldSlot(caster) == EquipmentSlot.OFFHAND
                ? EquipmentSlot.OFFHAND : EquipmentSlot.MAINHAND;
        if (caster.getItemBySlot(slot).isEmpty()) {
            SafeGuard.warn("Buckler of Annihilation: " + IncineratorSkill.describe(caster) + " no longer holds Buckler of Annihilation, cast cancelled");
            return false;
        }
        if (target == null) {
            SafeGuard.infoThrottled("mce_bk_notarget_" + caster.getUUID(), now,
                    "Buckler of Annihilation: needs an attack target (ram direction + bomb aim source), cancelled this time");
            return false;
        }
        // —— 自动触发距离门（拍板补充的防浪费节流；force 不受限）——
        if (!ignoreCooldown && caster.distanceTo(target) > MceConfig.bucklerTriggerDistance()) {
            SafeGuard.infoThrottled("mce_bk_far_" + caster.getUUID(), now,
                    "Buckler of Annihilation: target distance " + String.format("%.1f", caster.distanceTo(target))
                            + " blocks > trigger distance " + MceConfig.bucklerTriggerDistance()
                            + " blocks (buckler_trigger_distance), no auto cast this time (ram only reaches point-blank)");
            return false;
        }

        // —— 朝向：面向目标 + 爆弹瞄准目标胸口（原版用玩家当时的视角，同伴版给个像样的瞄准）——
        float yawDegrees = IncineratorSkill.yawToDegrees(caster, target);
        double dx = target.getX() - caster.getX();
        double dz = target.getZ() - caster.getZ();
        double distH = Math.max(Math.sqrt(dx * dx + dz * dz), 0.1D);
        double aimY = target.getY() + target.getBbHeight() * 0.6D;
        float pitchDegrees = (float) -Math.toDegrees(
                Math.atan2(aimY - (caster.getY() + BOMB_SPAWN_Y_OFFSET), distH));
        caster.setYRot(yawDegrees);
        caster.setXRot(pitchDegrees);
        caster.setYHeadRot(yawDegrees);

        // —— 释放音效 + 挥臂音效（照抄原版 releaseUsing 开头两连音）——
        playSound(level, caster, LegendaryMonstersReflect.getBucklerReleaseSound(), 1.0F, "release");
        playSound(level, caster, LegendaryMonstersReflect.getBucklerSwingSound(), 1.0F, "swing arm");

        // —— 挥击观感：原版是客户端自定义粒子（ANNIHILATION_EXPLOSION + 挥扫粒子），
        //      服务端 sendParticles 复刻同点位（正前方 1 格、胸口高度），查不到就退原版 SWEEP_ATTACK ——
        playSweepVisual(level, caster);

        // —— 三枚湮灭爆弹（照抄：同 tick、同起点 (x, y+1, z)、inaccuracy 0/15/30、弹速 1）——
        int bombs = 0;
        for (int inaccuracy : BOMB_INACCURACIES) {
            try {
                if (LegendaryMonstersReflect.spawnSmallAnnihilationBomb(level, caster,
                        caster.getX(), caster.getY() + BOMB_SPAWN_Y_OFFSET, caster.getZ(),
                        pitchDegrees, yawDegrees, BOMB_VELOCITY, inaccuracy,
                        (float) MceConfig.bucklerBombDamage(), now)) {
                    bombs++;
                }
            } catch (Throwable t) {
                SafeGuard.warnThrottled("mce_bk_bomb_" + inaccuracy, now,
                        "Buckler of Annihilation: inaccuracy=" + inaccuracy + " bomb launch error (caught, that bomb skipped): "
                                + t.getClass().getSimpleName() + " / " + t.getMessage());
            }
        }

        // —— 撞击：判定几何照抄原版 AABB，逐个 AllyCheck 过滤后结算 + 击飞 + 着火 ——
        int hits = slash(level, caster, now);

        // —— 冷却拆分（拍板）：命中 → 200；打空 → 60。两条各自存 persistentData ——
        byte result = hits > 0 ? RESULT_HIT : RESULT_MISS;
        SkillCooldownStore.markCast(caster, result == RESULT_HIT ? id() : MISS_COOLDOWN_ID, now);
        setLastResult(caster, result);

        // 友军拦截窗口照开（打空也开：爆弹还在飞，命中友军要靠窗口兜底取消）
        markGuardWindow(caster, now + GUARD_BASE_TICKS);
        caster.swing(slot == EquipmentSlot.OFFHAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND, true);
        ChargeStateStore.markSilence(caster, now + SkillPriorityConfig.silenceTicks());

        boolean ok = hits > 0 || bombs > 0;
        SafeGuard.info("Buckler of Annihilation: " + IncineratorSkill.describe(caster) + " ram hit " + hits
                + " enemies (damage " + String.format("%.1f", MceConfig.bucklerSlashDamage())
                + " + knockback x" + KNOCKBACK_MULTIPLIER + " min h=" + MceConfig.bucklerKnockbackMinHorizontal()
                + "/v=" + MceConfig.bucklerKnockbackMinVertical() + " + burning " + HIT_FIRE_TICKS / 20 + " seconds), "
                + "launched Annihilation bombs " + bombs + "/3 (each " + String.format("%.1f", MceConfig.bucklerBombDamage())
                + " dmg, inaccuracy 0/15/30 copied from source), cooldown "
                + (result == RESULT_HIT
                        ? MceConfig.bucklerCooldownTicks() + " ticks (on hit)"
                        : MceConfig.bucklerMissCooldownTicks() + " ticks (on miss)")
                + (ignoreCooldown ? " (command forced)" : "") + ", ally interception window " + GUARD_BASE_TICKS + " tick");
        return ok;
    }

    // ════════════════════════ 撞击（自研复刻，几何照抄原版 AABB） ════════════════════════

    /** @return 撞击命中的敌人数（友军不算；AllyCheck 过滤后才 hurt） */
    private static int slash(ServerLevel level, Mob caster, long now) {
        int hits = 0;
        try {
            float range = (float) MceConfig.bucklerSlashRange();
            double rad = Math.toRadians(caster.getYRot() + 90.0D); // 原版 rad = yRot + 90 照抄
            double cos = range * Math.cos(rad);
            double sin = range * Math.sin(rad);
            AABB attackRange = caster.getBoundingBox()
                    .inflate(BOX_INFLATE_HORIZONTAL, BOX_INFLATE_VERTICAL, BOX_INFLATE_HORIZONTAL)
                    .expandTowards(cos, 0, sin);
            // 伤害口径 mobAttack（原版 playerAttack 的同伴等价，不穿甲，护甲/附魔正常减免）
            DamageSource source = caster.damageSources().mobAttack(caster);
            List<LivingEntity> victims = level.getEntitiesOfClass(LivingEntity.class, attackRange);
            for (LivingEntity victim : victims) {
                if (victim == caster || !victim.isAlive() || victim.isRemoved() || victim.isInvulnerable()) {
                    continue;
                }
                if (AllyCheck.isAlly(caster, victim)) {
                    continue; // 友军保护：不挨撞、不被击飞、不被点燃
                }
                if (victim.hurt(source, (float) MceConfig.bucklerSlashDamage())) {
                    hits++;
                    applyHitEffects(caster, victim);
                }
            }
        } catch (Throwable t) {
            SafeGuard.warnThrottled("mce_bk_slash_err", now,
                    "Buckler of Annihilation: ram resolution error (caught, this ram skipped, bombs unaffected): "
                            + t.getClass().getSimpleName() + " / " + t.getMessage());
        }
        return hits;
    }

    /** 命中三连（照抄原版 hurt 成功分支）：着火 60 tick + 击飞（乘法 ×1.25 + 保底位移）+ 命中音效 */
    private static void applyHitEffects(Mob caster, LivingEntity victim) {
        try {
            victim.setRemainingFireTicks(HIT_FIRE_TICKS);
        } catch (Throwable t) {
            SafeGuard.warnThrottled("mce_bk_fire_err", gameTime(victim),
                    "Buckler of Annihilation: failed to ignite target (caught, knockback unaffected): " + t.getClass().getSimpleName());
        }
        try {
            double nx = victim.getDeltaMovement().x * KNOCKBACK_MULTIPLIER;
            double ny = victim.getDeltaMovement().y * KNOCKBACK_MULTIPLIER;
            double nz = victim.getDeltaMovement().z * KNOCKBACK_MULTIPLIER;
            // —— 保底位移（拍板）：原版纯乘法对静止目标无效 ——
            double minH = MceConfig.bucklerKnockbackMinHorizontal();
            double minV = MceConfig.bucklerKnockbackMinVertical();
            double horizontal = Math.sqrt(nx * nx + nz * nz);
            if (horizontal < minH) {
                double awayX = victim.getX() - caster.getX();
                double awayZ = victim.getZ() - caster.getZ();
                double awayLen = Math.sqrt(awayX * awayX + awayZ * awayZ);
                if (awayLen < 1.0E-3D) {
                    // 重叠时退回同伴朝向（-sin(yaw), cos(yaw)）当击飞方向
                    double yawRad = Math.toRadians(caster.getYRot());
                    awayX = -Math.sin(yawRad);
                    awayZ = Math.cos(yawRad);
                    awayLen = 1.0D;
                }
                nx = awayX / awayLen * minH;
                nz = awayZ / awayLen * minH;
            }
            if (ny < minV) {
                ny = minV;
            }
            victim.setDeltaMovement(nx, ny, nz);
        } catch (Throwable t) {
            SafeGuard.warnThrottled("mce_bk_kb_err", gameTime(victim),
                    "Buckler of Annihilation: knockback failed (caught, damage unaffected): " + t.getClass().getSimpleName());
        }
        playSound((ServerLevel) caster.level(), caster,
                LegendaryMonstersReflect.getBucklerHitSound(), 1.0F, "hit");
    }

    // ════════════════════════ 表现 / 工具 ════════════════════════

    /**
     * 挥击观感：原版在客户端 addParticle 两个自定义粒子（ANNIHILATION_EXPLOSION 爆点 +
     * AnnihilationSweepParticle 挥扫弧），同伴版用服务端 sendParticles 复刻——
     * 传奇怪物的 annihilation_explosion 粒子查得到就发它（观感一致），
     * 再补一个原版 SWEEP_ATTACK 当挥扫弧；都查不到/异常只少粒子，不影响技能。
     */
    private static void playSweepVisual(ServerLevel level, Mob caster) {
        try {
            double rad = Math.toRadians(caster.getYRot());
            double frontX = caster.getX() - Math.sin(rad);
            double frontZ = caster.getZ() + Math.cos(rad);
            double y = caster.getY() + 1.0D;
            net.minecraft.core.particles.ParticleOptions custom =
                    LegendaryMonstersReflect.annihilationExplosionParticle();
            if (custom != null) {
                level.sendParticles(custom, frontX, y, frontZ, 1, 0.0D, 0.0D, 0.0D, 0.0D);
            }
            level.sendParticles(ParticleTypes.SWEEP_ATTACK, frontX, y, frontZ, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        } catch (Throwable t) {
            SafeGuard.warnThrottled("mce_bk_vis", gameTime(caster),
                    "Buckler of Annihilation: swing particles failed (caught, no skill impact): " + t.getClass().getSimpleName());
        }
    }

    private static void playSound(ServerLevel level, Mob caster, SoundEvent sound,
                                  float volume, String label) {
        try {
            if (sound == null) {
                SafeGuard.infoThrottled("mce_bk_nosound_" + label, gameTime(caster),
                        "Buckler of Annihilation: Legendary Monsters sound not found (" + label + "), this cast is silent (no skill impact)");
                return;
            }
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                    sound, SoundSource.PLAYERS, volume, 1.0F);
        } catch (Throwable t) {
            SafeGuard.warnThrottled("mce_bk_snd_" + label, gameTime(caster),
                    "Buckler of Annihilation: playing " + label + " sound failed (caught, skill unaffected): "
                            + t.getClass().getSimpleName());
        }
    }

    // ════════════════════════ 状态键 ════════════════════════

    /** 友军拦截窗口（AllyDamageGuard 读；同时是“湮灭接管闸门”给爆弹让路的开关） */
    public static void markGuardWindow(Entity owner, long untilTick) {
        try {
            owner.getPersistentData().putLong(KEY_GUARD_UNTIL, untilTick);
        } catch (Throwable ignored) {
        }
    }

    public static boolean guardWindowActive(Entity owner, long now) {
        try {
            return owner.getPersistentData().getLong(KEY_GUARD_UNTIL) > now;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 最近一次结果（status 展示用）：1=命中 2=打空 */
    public static void setLastResult(Entity owner, byte result) {
        try {
            owner.getPersistentData().putByte(KEY_LAST_RESULT, result);
        } catch (Throwable ignored) {
        }
    }

    public static byte getLastResult(Entity owner) {
        try {
            return owner.getPersistentData().getByte(KEY_LAST_RESULT);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** /mce buckler reset 时一并清掉本技能的易变状态（打空冷却键 + 拦截窗口 + 结果标记） */
    public static void clearVolatileState(Entity owner) {
        try {
            owner.getPersistentData().remove(KEY_GUARD_UNTIL);
            owner.getPersistentData().remove(KEY_LAST_RESULT);
            owner.getPersistentData().remove(SkillCooldownStore.keyFor(MISS_COOLDOWN_ID));
        } catch (Throwable ignored) {
        }
    }

    private static long gameTime(Entity entity) {
        try {
            return entity.level().getGameTime();
        } catch (Throwable t) {
            return -1L;
        }
    }
}