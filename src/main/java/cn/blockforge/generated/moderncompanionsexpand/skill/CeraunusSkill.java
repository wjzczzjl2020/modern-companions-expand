package cn.blockforge.generated.moderncompanionsexpand.skill;

import cn.blockforge.generated.moderncompanionsexpand.config.MceConfig;
import cn.blockforge.generated.moderncompanionsexpand.config.SkillPriorityConfig;
import cn.blockforge.generated.moderncompanionsexpand.util.AllyCheck;
import cn.blockforge.generated.moderncompanionsexpand.util.SafeGuard;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * 霆浪锚戟（cataclysm:ceraunus）同伴双技能：抛物线投掷 + 扇形波浪。
 *
 * 对齐灾变源码 Ceraunus（详见 docs/ANALYSIS_ROUND17_CERAUNUS.md）：
 * - 原版两个技能都在 releaseUsing() 里，第一行 instanceof Player——同伴整段进不去；
 *   潜行只是释放那一瞬的分支条件（isShiftKeyDown 在 Entity 上），同伴版分支由我们决定，
 *   不需要模拟潜行、不需要绕盾牌、不需要假 Player，直接反射构造两个实体（构造器末参都是
 *   LivingEntity，同伴直接当 owner，见 CataclysmReflect.spawnThrownCeraunus / spawnWave）。
 * - 投掷（自研瞄准 + 原版实体）：伤害 = 发射者 ATTACK_DAMAGE 属性值（与原版 setBaseDamage
 *   同口径，不是写死的 16）；弹速 2.5、重力 0.08 → 抛物线弹道，同伴按低抛物线方程解仰角
 *   （disc 无解时退化为 30° 仰角）。命中后锚戟飞回 owner 手里——同伴 owner 完全兼容；
 *   击退在 hurt 成功分支内，友军伤害被事件层取消后击退也不会发生，无残留问题。
 * - 波浪（原版实体 + 扇形排布逐行复刻）：HEAVY_SMASH 音效（0.6F/1.0F/PLAYERS）→
 *   施法者前方 2 格统一生成点 → 4 道波浪、间隔 25°、总扇形 75°，每道 setState(1) +
 *   原版 yRot 公式。伤害 6（反射读 CMCommonConfig.Ceraunus.waveDamage）+ 潮湿叠加
 *   （200 tick = 10 秒，按源码，等级 clamp 0~4）都内置在 Wave_Entity.attackEntities，
 *   同伴版不复刻。
 * - 波浪的唯一坑：物理击退在 hurt 事件之外，友军仍会被推 → 按确认默认开启
 *   ceraunus_friendly_wave_block 预扫描：按原版扇形（±37.5°、纵深 5 格）采样，
 *   扇形内有友军一律退回投掷，不冒险。
 * - 冷却：双冷却独立存 persistentData——投掷键 mce_cd_ceraunus（默认 100，原版无冷却
 *   靠单实例组件节流，同伴不走那个组件）；波浪键 mce_cd_ceraunus_wave（默认 150，
 *   对齐源码 CMCommonConfig.Ceraunus.cooldown）。cooldownTicks() 返回两者较小值，
 *   只用于调度器预检，真正的分模式冷却在 release() 里查。
 * - 情境判定（按确认）：目标 ≤ ceraunus_prefer_wave_distance（默认 4.0 格）、或
 *   5 格内敌对生物 ≥ ceraunus_wave_min_enemies（默认 2）→ 波浪；否则投掷；
 *   波浪出局（冷却中/扇形有友军）→ 退回投掷。
 * - 友军保护：伤害源 causing entity 都是同伴本人 → AllyDamageGuard 的霆浪拦截窗口
 *   （释放后 100 tick，覆盖波浪全程 60 tick + 投掷飞行 ~31 tick 有余量）+ AllyCheck 兜底。
 * - force 命令：/mce ceraunus force @e[...] throw|wave，跳过冷却与情境判定。
 */
public final class CeraunusSkill implements CompanionSkill {

    public static final CeraunusSkill INSTANCE = new CeraunusSkill();

    /** force 命令的模式参数 */
    public static final String FORCE_MODE_THROW = "throw";
    public static final String FORCE_MODE_WAVE = "wave";

    /** 窗口模式（persistentData，状态展示用）：1=投掷，2=波浪 */
    public static final byte MODE_THROW = 1;
    public static final byte MODE_WAVE = 2;

    /** 波浪冷却的独立 persistentData 键（SkillCooldownStore.keyFor("ceraunus_wave")） */
    public static final String WAVE_COOLDOWN_ID = "ceraunus_wave";

    private static final String KEY_CN_MODE = "mce_cn_mode";
    private static final String KEY_CN_GUARD_UNTIL = "mce_cn_guard_until";

    /** 原版弹速：shootFromRotation(…, 2.5F, 1.0F) */
    public static final float THROW_VELOCITY = 2.5F;
    /** 实体自带重力：Player_Ceraunus_Entity.getDefaultGravity() = 0.08 */
    private static final double THROW_GRAVITY = 0.08D;
    /** 仰角补偿无解（目标太远/太高）时的兜底仰角 */
    private static final float FALLBACK_PITCH_DEGREES = 30.0F;

    /** 原版扇形：4 道、间隔 25°、中心±37.5°（firstAngleOffset = (4-1)/2×25 = 37.5） */
    private static final int WAVE_COUNT = 4;
    private static final float WAVE_ANGLE_STEP = 25.0F;
    private static final float WAVE_FIRST_OFFSET = (WAVE_COUNT - 1) / 2.0F * WAVE_ANGLE_STEP;
    private static final int WAVE_LIFE = 60;
    /** 原版统一生成点：施法者前方 2 格 */
    private static final double WAVE_SPAWN_AHEAD = 2.0D;
    /** 预扫描纵深：波浪初速 0.1/tick 逐 tick 衰减，实际推进 3~4 格，按 5 格采样留余量 */
    private static final double WAVE_SCAN_DEPTH = 5.0D;
    /** “被包围”判定半径（与断魂一致） */
    private static final double SURROUNDED_RADIUS = 5.0D;

    /** 友军拦截窗口：波浪全程 60 tick、投掷飞行约 31 tick，100 tick 起步覆盖两者有余量 */
    public static final long GUARD_BASE_TICKS = 100L;

    private CeraunusSkill() {}

    // ════════════════════════ CompanionSkill ════════════════════════

    @Override
    public String id() {
        return SkillPriorityConfig.SKILL_CERAUNUS;
    }

    @Override
    public net.minecraft.resources.ResourceLocation requiredItem() {
        return CataclysmReflect.CERAUNUS_ITEM;
    }

    /** 霆浪锚戟是投掷武器，按原版习惯主手持握 */
    @Override
    public EquipmentSlot preferredSlot() {
        return EquipmentSlot.MAINHAND;
    }

    @Override
    public boolean enabled() {
        return MceConfig.ceraunusEnabled();
    }

    /**
     * 调度器预检口径：投掷/波浪两个冷却里较小的一个（真正的分模式冷却在 release() 里查，
     * 波浪出局时还会退回投掷，预检只做“大概率没戏就别进 release”的性能阀值）。
     */
    @Override
    public int cooldownTicks() {
        return Math.min(MceConfig.ceraunusThrowCooldownTicks(), MceConfig.ceraunusWaveCooldownTicks());
    }

    /** 瞬发：原版蓄力门槛只有约 12 tick 且不影响数值（与神怒同款，就绪即发） */
    @Override
    public int chargeTicks() {
        return 0;
    }

    @Override
    public boolean release(ServerLevel level, Mob caster, LivingEntity target, long now, boolean ignoreCooldown) {
        boolean throwReady = ignoreCooldown
                || SkillCooldownStore.isReady(caster, id(), now, MceConfig.ceraunusThrowCooldownTicks());
        boolean waveReady = ignoreCooldown
                || SkillCooldownStore.isReady(caster, WAVE_COOLDOWN_ID, now, MceConfig.ceraunusWaveCooldownTicks());
        if (!throwReady && !waveReady) {
            SafeGuard.infoThrottled("mce_cn_cooling_" + caster.getUUID(), now,
                    "Ceraunus: " + IncineratorSkill.describe(caster) + ", neither cooldown ready (throw left "
                            + SkillCooldownStore.remainingTicks(caster, id(), now, MceConfig.ceraunusThrowCooldownTicks())
                            + ", wave left "
                            + SkillCooldownStore.remainingTicks(caster, WAVE_COOLDOWN_ID, now, MceConfig.ceraunusWaveCooldownTicks())
                            + " ticks), skipped this time");
            return false;
        }
        if (!CataclysmReflect.isCataclysmPresent()) {
            SafeGuard.warnThrottled("mce_cn_nocate_" + level.dimension().location(), now,
                    "Ceraunus: Cataclysm not detected (throw entity / wave entity / sounds / values all depend on Cataclysm), skill cancelled");
            return false;
        }
        EquipmentSlot slot = heldSlot(caster) == EquipmentSlot.OFFHAND
                ? EquipmentSlot.OFFHAND : EquipmentSlot.MAINHAND;
        ItemStack stack = caster.getItemBySlot(slot);
        if (stack.isEmpty()) {
            SafeGuard.warn("Ceraunus: " + IncineratorSkill.describe(caster) + " no longer holding Ceraunus, skill cancelled");
            return false;
        }

        // —— 情境判定 + 分模式冷却 + 友军预扫描逐级回退 ——
        byte mode = decideMode(caster, target);
        String fallbackNote = "";
        if (mode == MODE_WAVE) {
            if (!waveReady) {
                mode = MODE_THROW;
                fallbackNote = " (wave on cooldown, fell back to throw)";
            } else if (MceConfig.ceraunusFriendlyWaveBlock() && sectorHasAlly(level, caster)) {
                mode = MODE_THROW;
                fallbackNote = " (ally detected inside the fan, fell back to throw per ceraunus_friendly_wave_block)";
            }
        }

        boolean ok;
        if (mode == MODE_WAVE) {
            ok = castWave(level, caster, target, slot, now, ignoreCooldown);
            // 与神怒同款防刷屏口径：无论成败都记一次施放时刻，防止失败时逐 tick 疯狂重试
            SkillCooldownStore.markCast(caster, WAVE_COOLDOWN_ID, now);
        } else {
            ok = castThrow(level, caster, target, stack, slot, now, ignoreCooldown);
            SkillCooldownStore.markCast(caster, id(), now);
        }
        setMode(caster, mode);
        if (ok) {
            markGuardWindow(caster, now + GUARD_BASE_TICKS);
            ChargeStateStore.markSilence(caster, now + SkillPriorityConfig.silenceTicks());
        } else if (!fallbackNote.isEmpty()) {
            SafeGuard.info("Ceraunus: wave fell back to throw and still failed, see the failure logs above");
        }
        return ok;
    }

    // ════════════════════════ 投掷（自研瞄准 + 原版实体） ════════════════════════

    private boolean castThrow(ServerLevel level, Mob caster, LivingEntity target,
                              ItemStack stack, EquipmentSlot slot, long now, boolean ignoreCooldown) {
        if (target == null) {
            SafeGuard.infoThrottled("mce_cn_notarget_" + caster.getUUID(), now,
                    "Ceraunus: throw needs an attack target (as the direction source), cancelled this time");
            return false;
        }
        try {
            // —— 瞄准：先拧身面向目标，再解抛物线仰角（弹速 2.5 + 重力 0.08，低弹道解）——
            float yawDegrees = IncineratorSkill.yawToDegrees(caster, target);
            double spawnY = caster.getEyeY() - 0.1D; // 与实体构造器内部生成点同口径
            double aimY = target.getY() + target.getBbHeight() * 0.5D;
            double dx = target.getX() - caster.getX();
            double dz = target.getZ() - caster.getZ();
            double distH = Math.sqrt(dx * dx + dz * dz);
            float pitchUp = ballisticPitch(distH, aimY - spawnY);
            boolean compensated = true;
            if (Float.isNaN(pitchUp) || Float.isInfinite(pitchUp)) {
                // 边界保护：解算出 NaN/∞（目标太近/太远的极端几何）→ 退回直接朝目标中身高发射，不做仰角补偿
                pitchUp = (float) Math.toDegrees(Math.atan2(aimY - spawnY, Math.max(distH, 1.0E-3D)));
                compensated = false;
                SafeGuard.warnThrottled("mce_cn_pitch_" + caster.getUUID(), now,
                        "Ceraunus: elevation solve failed (no valid parabola solution, horizontal distance "
                                + String.format("%.2f", distH) + " blocks, height difference "
                                + String.format("%.2f", aimY - spawnY) + " blocks), "
                                + "falls back to firing straight at the target (no elevation compensation)");
            }
            float xRot = -pitchUp; // MC 俯仰：负=向上
            caster.setYRot(yawDegrees);
            caster.setXRot(xRot);
            caster.setYHeadRot(yawDegrees);

            // 方向向量的 Y 分量（归一化后），排查“矛朝上飞”用：应 ≤ 0 或微小正值
            double dirY = -Math.sin(Math.toRadians(xRot));
            float baseDamage = (float) caster.getAttributeValue(Attributes.ATTACK_DAMAGE);
            boolean spawned = CataclysmReflect.spawnThrownCeraunus(level, caster, xRot, yawDegrees,
                    baseDamage, THROW_VELOCITY, now);
            caster.swing(slot == EquipmentSlot.OFFHAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND, true);
            if (!spawned) {
                SafeGuard.warn("Ceraunus: throw failed (the anchor could not be spawned, see the per-step reasons in the [MCE] logs)."
                        + IncineratorSkill.describe(caster));
                return false;
            }
            SafeGuard.info("Ceraunus: " + IncineratorSkill.describe(caster) + " toward target "
                    + IncineratorSkill.describe(target) + " threw the anchor (damage = attack attribute "
                    + String.format("%.1f", baseDamage) + ", projectile speed " + THROW_VELOCITY + " + gravity 0.08 parabola, "
                    + (compensated ? "elevation compensation " : "elevation compensation degraded (direct aim)") + String.format("%.1f", pitchUp) + "°, "
                    + "direction Y component " + String.format("%+.2f", dirY) + ", horizontal distance "
                    + String.format("%.2f", distH) + " blocks, flies back to hand on hit), "
                    + "cooldown " + MceConfig.ceraunusThrowCooldownTicks() + " tick"
                    + (ignoreCooldown ? " (command forced)" : ""));
            return true;
        } catch (Throwable t) {
            SafeGuard.error("Ceraunus: throw branch threw (caught, no game impact)", t);
            return false;
        }
    }

    /**
     * 抛物线低弹道仰角解：tanθ = (v² − √(v⁴ − g(g·d² + 2·h·v²))) / (g·d)。
     * 空气阻力（每 tick ×0.99）忽略不计，误差在近中距离 < 1 格；disc < 0（目标太远/太高
     * 打不着）时退化为 30° 仰角硬抛。
     */
    private static float ballisticPitch(double distH, double heightDiff) {
        try {
            if (distH < 1.0E-3D) {
                return FALLBACK_PITCH_DEGREES;
            }
            double v = THROW_VELOCITY;
            double v2 = v * v;
            double v4 = v2 * v2;
            double disc = v4 - THROW_GRAVITY * (THROW_GRAVITY * distH * distH + 2.0D * heightDiff * v2);
            if (disc < 0.0D) {
                return FALLBACK_PITCH_DEGREES;
            }
            double tan = (v2 - Math.sqrt(disc)) / (THROW_GRAVITY * distH);
            return (float) Math.toDegrees(Math.atan(tan));
        } catch (Throwable t) {
            return FALLBACK_PITCH_DEGREES;
        }
    }

    // ════════════════════════ 扇形波浪（原版实体，排布逐行复刻） ════════════════════════

    private boolean castWave(ServerLevel level, Mob caster, LivingEntity target,
                             EquipmentSlot slot, long now, boolean ignoreCooldown) {
        // 原版以玩家 getYRot() 为扇形中心：同伴先面向目标（无目标 = 保持当前朝向，force wave 可空手放）
        if (target != null) {
            float yawDegrees = IncineratorSkill.yawToDegrees(caster, target);
            caster.setYRot(yawDegrees);
            caster.setXRot(0.0F);
            caster.setYHeadRot(yawDegrees);
        }
        float yaw = caster.getYRot();

        playHeavySmashSound(level, caster);

        // —— 以下逐行复刻 Ceraunus.releaseUsing 潜行分支 ——
        double yawRadians = Math.toRadians(90.0F + yaw);
        double spawnX = caster.getX() + Math.cos(yawRadians) * WAVE_SPAWN_AHEAD;
        double spawnY = caster.getY();
        double spawnZ = caster.getZ() + Math.sin(yawRadians) * WAVE_SPAWN_AHEAD;
        float waveDamage = CataclysmReflect.ceraunusWaveDamage();
        int spawned = 0;
        for (int k = 0; k < WAVE_COUNT; k++) {
            try {
                double angle = yaw - WAVE_FIRST_OFFSET + (k * WAVE_ANGLE_STEP);
                double rad = Math.toRadians(angle);
                double wdx = -Math.sin(rad);
                double wdz = Math.cos(rad);
                float waveYaw = (float) -(Mth.atan2(wdx, wdz) * (180F / Math.PI));
                if (CataclysmReflect.spawnWave(level, caster, spawnX, spawnY, spawnZ,
                        waveYaw, WAVE_LIFE, waveDamage, now)) {
                    spawned++;
                }
            } catch (Throwable perWave) {
                SafeGuard.warnThrottled("mce_cn_wave_" + k, now,
                        "Ceraunus: wave " + (k + 1) + " failed to spawn (that wave skipped): "
                                + perWave.getClass().getSimpleName() + " / " + perWave.getMessage());
            }
        }
        caster.swing(slot == EquipmentSlot.OFFHAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND, true);

        if (spawned <= 0) {
            SafeGuard.warn("Ceraunus: fan wave failed (not a single wave spawned, most likely the Cataclysm reflection layer mismatches)."
                    + IncineratorSkill.describe(caster));
            return false;
        }
        SafeGuard.info("Ceraunus: " + IncineratorSkill.describe(caster) + " summoned a fan of " + spawned
                + "/" + WAVE_COUNT + " waves (each dealing " + String.format("%.1f", waveDamage)
                + ", waterlogged 200 ticks per Cataclysm source, level clamped 0~4, hit interval capped by vanilla invulnerability ticks at about 10 ticks), "
                + "cooldown " + MceConfig.ceraunusWaveCooldownTicks() + " tick"
                + (ignoreCooldown ? " (command forced)" : ""));
        return true;
    }

    /** 原版施放音效：level.playSound(null, x, y, z, HEAVY_SMASH, PLAYERS, 0.6F, 1.0F) */
    private static void playHeavySmashSound(ServerLevel level, Mob caster) {
        try {
            net.minecraft.sounds.SoundEvent smash = CataclysmReflect.getHeavySmashSound();
            if (smash == null) {
                SafeGuard.infoThrottled("mce_cn_nosound_" + level.dimension().location(),
                        level.getGameTime(), "Ceraunus: Cataclysm sound not found " + CataclysmReflect.HEAVY_SMASH_SOUND
                                + ", this cast is silent (skill unaffected)");
                return;
            }
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                    smash, SoundSource.PLAYERS, 0.6F, 1.0F);
        } catch (Throwable t) {
            SafeGuard.warnThrottled("mce_cn_snd", level.getGameTime(),
                    "Ceraunus: playing the sound failed (caught, skill unaffected): " + t.getClass().getSimpleName());
        }
    }

    // ════════════════════════ 情境判定 / 预扫描 / force ════════════════════════

    /** 目标更近（≤ prefer_wave_distance）或被包围（5 格内敌对 ≥ min_enemies）→ 波浪；否则投掷 */
    private byte decideMode(Mob caster, LivingEntity target) {
        try {
            int minEnemies = MceConfig.ceraunusWaveMinEnemies();
            List<LivingEntity> hostiles = caster.level().getEntitiesOfClass(LivingEntity.class,
                    caster.getBoundingBox().inflate(SURROUNDED_RADIUS),
                    e -> e != caster && e.isAlive() && !AllyCheck.isAlly(caster, e));
            if (hostiles.size() >= minEnemies) {
                return MODE_WAVE;
            }
            if (target != null
                    && caster.distanceTo(target) <= MceConfig.ceraunusPreferWaveDistance()) {
                return MODE_WAVE;
            }
            return MODE_THROW;
        } catch (Throwable t) {
            SafeGuard.warnThrottled("mce_cn_mode", gameTime(caster),
                    "Ceraunus: mode check failed (caught, falls back to throw): "
                            + t.getClass().getSimpleName() + " / " + t.getMessage());
            return MODE_THROW;
        }
    }

    /**
     * 波浪友军预扫描：按原版扇形（中心 = 同伴朝向、±37.5° 共 5 条采样线、纵深 5 格）采样，
     * 任一采样点附近（±0.9 格、高 2.5 格）有友军 → true。纯几何判定，成本可忽略。
     */
    private static boolean sectorHasAlly(ServerLevel level, Mob caster) {
        try {
            float[] offsets = {-WAVE_FIRST_OFFSET, -WAVE_ANGLE_STEP / 2.0F, 0.0F,
                    WAVE_ANGLE_STEP / 2.0F, WAVE_FIRST_OFFSET};
            double[] distances = {1.0D, 2.5D, 4.0D, WAVE_SCAN_DEPTH};
            for (float offset : offsets) {
                double rad = Math.toRadians(caster.getYRot() + offset);
                double dirX = -Math.sin(rad);
                double dirZ = Math.cos(rad);
                for (double dist : distances) {
                    double x = caster.getX() + dirX * dist;
                    double z = caster.getZ() + dirZ * dist;
                    AABB box = new AABB(x - 0.9D, caster.getY() - 0.5D, z - 0.9D,
                            x + 0.9D, caster.getY() + 2.0D, z + 0.9D);
                    for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box)) {
                        if (e != caster && e.isAlive() && AllyCheck.isAlly(caster, e)) {
                            SafeGuard.infoThrottled("mce_cn_scan_" + caster.getUUID(),
                                    level.getGameTime(), "Ceraunus: fan pre-scan found ally "
                                            + IncineratorSkill.describe(e) + ", wave dropped");
                            return true;
                        }
                    }
                }
            }
            return false;
        } catch (Throwable t) {
            // 预扫描本身出错时按“有友军”保守处理：宁可退回投掷，不冒险推友军
            SafeGuard.warnThrottled("mce_cn_scan", gameTime(caster),
                    "Ceraunus: fan pre-scan failed (caught, treated as if an ally is present): "
                            + t.getClass().getSimpleName() + " / " + t.getMessage());
            return true;
        }
    }

    /** force 命令入口：指定模式直接释放（跳过冷却、情境判定与预扫描） */
    public static boolean forceCast(ServerLevel level, Mob caster, LivingEntity target,
                                    String mode, long now) {
        try {
            if (!CataclysmReflect.isCataclysmPresent()) {
                SafeGuard.warn("Ceraunus: Cataclysm not detected, forced cast cancelled");
                return false;
            }
            EquipmentSlot slot = INSTANCE.heldSlot(caster) == EquipmentSlot.OFFHAND
                    ? EquipmentSlot.OFFHAND : EquipmentSlot.MAINHAND;
            ItemStack stack = caster.getItemBySlot(slot);
            if (stack.isEmpty()) {
                SafeGuard.warn("Ceraunus: " + IncineratorSkill.describe(caster) + " not holding Ceraunus, forced cast cancelled");
                return false;
            }
            boolean ok;
            if (FORCE_MODE_THROW.equals(mode)) {
                if (target == null) {
                    SafeGuard.info("Ceraunus: throw mode needs an attack target (as the direction source), forced cast cancelled");
                    return false;
                }
                ok = INSTANCE.castThrow(level, caster, target, stack, slot, now, true);
                if (ok) {
                    SkillCooldownStore.markCast(caster, INSTANCE.id(), now);
                    setMode(caster, MODE_THROW);
                }
            } else if (FORCE_MODE_WAVE.equals(mode)) {
                ok = INSTANCE.castWave(level, caster, target, slot, now, true);
                if (ok) {
                    SkillCooldownStore.markCast(caster, WAVE_COOLDOWN_ID, now);
                    setMode(caster, MODE_WAVE);
                }
            } else {
                SafeGuard.warn("Ceraunus: unknown mode '" + mode + "' (available: throw / wave)");
                return false;
            }
            if (ok) {
                markGuardWindow(caster, now + GUARD_BASE_TICKS);
                ChargeStateStore.markSilence(caster, now + SkillPriorityConfig.silenceTicks());
                SafeGuard.info("Ceraunus: command forced cast '" + mode + "' completed, cooldown recorded as usual");
            }
            return ok;
        } catch (Throwable t) {
            SafeGuard.error("Ceraunus: forced cast threw (caught)", t);
            return false;
        }
    }

    // ════════════════════════ 状态键 ════════════════════════

    /** 当前模式（状态展示用） */
    public static void setMode(Entity owner, byte mode) {
        try {
            owner.getPersistentData().putByte(KEY_CN_MODE, mode);
        } catch (Throwable ignored) {
        }
    }

    public static byte getMode(Entity owner) {
        try {
            return owner.getPersistentData().getByte(KEY_CN_MODE);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 霆浪友军拦截窗口（AllyDamageGuard 读）：窗口内该同伴的锚戟/波浪伤害参与 AllyCheck 判定 */
    public static void markGuardWindow(Entity owner, long untilTick) {
        try {
            owner.getPersistentData().putLong(KEY_CN_GUARD_UNTIL, untilTick);
        } catch (Throwable ignored) {
        }
    }

    public static boolean guardWindowActive(Entity owner, long now) {
        try {
            return owner.getPersistentData().getLong(KEY_CN_GUARD_UNTIL) > now;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void clearGuardWindow(Entity owner) {
        try {
            owner.getPersistentData().remove(KEY_CN_GUARD_UNTIL);
        } catch (Throwable ignored) {
        }
    }

    /** /mce ceraunus reset 时一并清掉本技能的易变状态（含波浪独立冷却键） */
    public static void clearVolatileState(Entity owner) {
        try {
            owner.getPersistentData().remove(KEY_CN_MODE);
            owner.getPersistentData().remove(KEY_CN_GUARD_UNTIL);
            owner.getPersistentData().remove(SkillCooldownStore.keyFor(WAVE_COOLDOWN_ID));
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
