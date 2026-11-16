package cn.blockforge.generated.moderncompanionsexpand.skill;

import cn.blockforge.generated.moderncompanionsexpand.config.MceConfig;
import cn.blockforge.generated.moderncompanionsexpand.config.SkillPriorityConfig;
import cn.blockforge.generated.moderncompanionsexpand.util.AllyCheck;
import cn.blockforge.generated.moderncompanionsexpand.util.SafeGuard;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * 断魂战戟（cataclysm:soul_render）同伴双技能：冲刺（自研循环）+ 螺旋召唤（反射灾变幻影战戟）。
 *
 * 对齐灾变源码 Soul_Render（详见 docs/ANALYSIS_ROUND12_SOUL_RENDER.md）：
 * - 原版两个技能都在 releaseUsing() 里，第一行就是 instanceof Player——同伴整段进不去；
 *   副手举盾优先在 use() 里（canPerformAction(SHIELD_BLOCK) → fail）。同伴版不走这两个入口，
 *   所以副手有盾牌也照常释放（方案 A：绕过 use()/releaseUsing，直接自己结算）。
 * - 冲刺（用户确认的补救方案）：自研循环复刻 RenderRushAttachment.tick 的服务端分支——
 *   每 tick 取朝向前方 3 格的膨胀 AABB 结算伤害；伤害源 mobAttack(同伴)，
 *   伤害数值读攻击力属性（与原版 setdamage(getAttributeValue(ATTACK_DAMAGE)) 同口径）；
 *   友军（AllyCheck.isAlly）硬过滤：不结算伤害、不进任何位移逻辑——宁可牺牲光环粒子也不牺牲友军保护。
 *   路径上的幻影战戟走灾变原版实体（反射构造，见 CataclysmReflect.spawnPhantomHalberd），
 *   其伤害在事件层 AllyDamageGuard 的“召唤窗口”内按 AllyCheck 拦截。
 *   紫色轨迹粒子用原版 ParticleTypes.PORTAL / SOUL_FIRE_FLAME 圈出来（成本很低，做了）。
 * - 螺旋召唤：反射复刻 StrikeWindmillHalberd(7 分支 × 5 列, 初始半径 1, 步进 1, 曲率 0.2, 延迟 1)
 *   的纯数学排布 + 原版 Phantom_Halberd_Entity 实体（伤害 12，与 CMCommonConfig.SoulRender
 *   .phantomHalberdDamage 默认一致），外观/音效/伤害结算全白嫖灾变。
 * - 冷却：原版两个分支都写 player.getCooldowns().addCooldown(this, 100) → 同伴版共享一个
 *   persistentData 冷却键 mce_cd_soul_render，默认 100 tick（soul_render_cooldown_ticks）。
 * - 情境判定（按确认的第 3 点）：目标 > soul_render_prefer_dash_distance（默认 4.0 格）→ 冲刺；
 *   目标更近、或 5 格内敌对生物 ≥ 2（被包围）→ 螺旋召唤。/mce soul_render force 可强制指定模式。
 */
public final class SoulRenderSkill implements CompanionSkill {

    public static final SoulRenderSkill INSTANCE = new SoulRenderSkill();

    /** force 命令的模式参数 */
    public static final String FORCE_MODE_DASH = "dash";
    public static final String FORCE_MODE_WINDMILL = "windmill";

    /** 窗口模式（persistentData）：1=冲刺，2=螺旋召唤 */
    public static final byte MODE_DASH = 1;
    public static final byte MODE_WINDMILL = 2;

    private static final String KEY_SR_MODE = "mce_sr_mode";
    private static final String KEY_SR_DAMAGE = "mce_sr_damage";
    private static final String KEY_SR_YAW = "mce_sr_yaw";
    private static final String KEY_SR_SPEED = "mce_sr_speed";
    /** 召唤/冲刺的友军伤害拦截窗口截止时刻：幻影战戟 warmup 最长约 35 tick，留 100 tick 余量 */
    private static final String KEY_SR_GUARD_UNTIL = "mce_sr_guard_until";

    /** 灾变源码 CMCommonConfig.SoulRender.phantomHalberdDamage 默认 12.0 */
    private static final float PHANTOM_HALBERD_DAMAGE = 12.0F;
    /** 螺旋召唤：StrikeWindmillHalberd(level, player, 7, 5, 1.0, 1.0, 0.2, 1) */
    private static final int WINDMILL_BRANCHES = 7;
    private static final int WINDMILL_PER_BRANCH = 5;
    private static final double WINDMILL_INITIAL_RADIUS = 1.0D;
    private static final double WINDMILL_RADIUS_STEP = 1.0D;
    private static final double WINDMILL_CURVE_FACTOR = 0.2D;
    private static final int WINDMILL_BASE_DELAY = 1;
    /** “被包围”判定半径：5 格内敌对生物 ≥ 2 也走螺旋召唤 */
    private static final double SURROUNDED_RADIUS = 5.0D;
    private static final int SURROUNDED_COUNT = 2;
    /** 冲刺路径幻影战戟：灾变 attachment tick 每 2 tick 立一根（spawnFangs temp%2==0） */
    private static final int DASH_HALBERD_INTERVAL = 2;

    /** 冲刺窗口内“已结算过冲刺伤害”的目标（每个目标一场冲刺只吃一次，防逐 tick 刀） */
    private static final WeakHashMap<Mob, Set<UUID>> DASH_HIT = new WeakHashMap<>();

    private SoulRenderSkill() {}

    // ════════════════════════ CompanionSkill ════════════════════════

    @Override
    public String id() {
        return SkillPriorityConfig.SKILL_SOUL_RENDER;
    }

    @Override
    public net.minecraft.resources.ResourceLocation requiredItem() {
        return CataclysmReflect.SOUL_RENDER_ITEM;
    }

    /** 断魂战戟是长柄武器，按原版习惯主手持握 */
    @Override
    public EquipmentSlot preferredSlot() {
        return EquipmentSlot.MAINHAND;
    }

    @Override
    public boolean enabled() {
        return MceConfig.soulRenderEnabled();
    }

    @Override
    public int cooldownTicks() {
        return MceConfig.soulRenderCooldownTicks();
    }

    @Override
    public int chargeTicks() {
        return 0; // 瞬发：同伴不会“长按”，调度器就绪即放（冲刺窗口由 ACTIVE 状态机接管）
    }

    @Override
    public int activeTicks() {
        return Math.max(1, MceConfig.soulRenderDashT() / 2);
    }

    @Override
    public boolean release(ServerLevel level, Mob caster, LivingEntity target, long now, boolean ignoreCooldown) {
        if (!ignoreCooldown && !SkillCooldownStore.isReady(caster, id(), now, cooldownTicks())) {
            SafeGuard.infoThrottled("mce_sr_cooling_" + caster.getUUID(), now,
                    "Soul Render: " + IncineratorSkill.describe(caster) + " cooldown remaining "
                            + SkillCooldownStore.remainingTicks(caster, id(), now, cooldownTicks()) + " ticks, skipped this time");
            return false;
        }
        if (!CataclysmReflect.isCataclysmPresent()) {
            SafeGuard.warnThrottled("mce_sr_nocate_" + level.dimension().location(), now,
                    "Soul Render: Cataclysm not detected (the phantom halberd entity and damage resolution depend on it), skill cancelled");
            return false;
        }
        EquipmentSlot slot = heldSlot(caster) == EquipmentSlot.OFFHAND
                ? EquipmentSlot.OFFHAND : EquipmentSlot.MAINHAND;
        ItemStack stack = caster.getItemBySlot(slot);
        if (stack.isEmpty()) {
            SafeGuard.warn("Soul Render: " + IncineratorSkill.describe(caster) + " no longer holding the Soul Render, skill cancelled");
            return false;
        }

        boolean ok = cast(level, caster, target, stack, slot, now, ignoreCooldown);
        // 与炎葬/锻锤同款防刷屏口径：无论成败都记一次施放时刻，防止失败时逐 tick 疯狂重试
        SkillCooldownStore.markCast(caster, id(), now);
        if (ok) {
            ChargeStateStore.markSilence(caster, now + SkillPriorityConfig.silenceTicks());
        }
        return ok;
    }

    /** 自动路径：情境判定选模式后释放（force 路径走 forceCast） */
    private boolean cast(ServerLevel level, Mob caster, LivingEntity target,
                         ItemStack stack, EquipmentSlot slot, long now, boolean ignoreCooldown) {
        byte mode = decideMode(caster, target);
        if (mode == MODE_DASH) {
            return castDash(level, caster, target, stack, slot, now, ignoreCooldown);
        }
        return castWindmill(level, caster, slot, now, ignoreCooldown);
    }

    @Override
    public boolean tickActive(ServerLevel level, Mob caster, long now) {
        if (getMode(caster) != MODE_DASH) {
            return false; // 螺旋召唤没有窗口
        }
        long until = ChargeStateStore.activeUntil(caster);
        double dirX = ChargeStateStore.activeDx(caster);
        double dirZ = ChargeStateStore.activeDz(caster);
        double speed = caster.getPersistentData().getDouble(KEY_SR_SPEED);
        float damage = caster.getPersistentData().getFloat(KEY_SR_DAMAGE);

        // —— 钉速度：同伴的移动 AI 会和冲刺冲量抢方向盘，默认每 tick 把水平速度钉回起手方向 ——
        if (MceConfig.soulRenderHoldVelocity() && speed > 0.0D && (dirX != 0.0D || dirZ != 0.0D)) {
            caster.setDeltaMovement(dirX * speed, caster.getDeltaMovement().y(), dirZ * speed);
            caster.hurtMarked = true;
        }

        tickDashDamage(level, caster, dirX, dirZ, damage, now);
        tickDashHalberds(level, caster, now);
        tickDashParticles(level, caster);

        if (now >= until || caster.isRemoved() || !caster.isAlive()) {
            DASH_HIT.remove(caster);
            return false;
        }
        return true; // 撞到实体不停下（原版语义）：窗口走满为止
    }

    // ════════════════════════ 冲刺（自研循环） ════════════════════════

    private boolean castDash(ServerLevel level, Mob caster, LivingEntity target,
                             ItemStack stack, EquipmentSlot slot, long now, boolean ignoreCooldown) {
        if (target == null) {
            SafeGuard.infoThrottled("mce_sr_notarget_" + caster.getUUID(), now,
                    "Soul Render: the dash needs an attack target (for direction), cancelled this time");
            return false;
        }
        int t = MceConfig.soulRenderDashT();
        int timer = Math.max(1, t / 2);
        float f = 0.1F * t; // 原版冲量系数：0.1 * t

        // —— 方向 = 朝向目标（原版用 getViewVector；同伴先拧身再取同款公式）——
        float yawDegrees = IncineratorSkill.yawToDegrees(caster, target);
        caster.setYRot(yawDegrees);
        caster.setXRot(0.0F);
        caster.setYHeadRot(yawDegrees);
        Vec3 view = caster.getViewVector(1.0F).normalize();

        // —— 冲量：照抄原版 releaseUsing 的冲刺分支（view*(f, f*0.15, f) + 落地上抬 0.2）——
        Vec3 delta = caster.getDeltaMovement();
        caster.setDeltaMovement(
                delta.x + view.x * f,
                delta.y + view.y * f * 0.15D + (caster.onGround() ? 0.2D : 0.0D),
                delta.z + view.z * f);
        caster.hurtMarked = true;

        float damage = CataclysmReflect.originAttackDamage(caster, stack,
                (float) caster.getAttributeValue(Attributes.ATTACK_DAMAGE));

        // —— 状态：窗口/方向/伤害全进 persistentData（读档后窗口由调度器按 until 收口）——
        double dirX = view.x;
        double dirZ = view.z;
        double norm = Math.sqrt(dirX * dirX + dirZ * dirZ);
        if (norm < 1.0E-4D) {
            dirX = 1.0D;
            dirZ = 0.0D;
            norm = 1.0D;
        }
        ChargeStateStore.beginActive(caster, id(), now, now + timer, dirX / norm, dirZ / norm);
        setMode(caster, MODE_DASH);
        CompoundTag tag = caster.getPersistentData();
        tag.putFloat(KEY_SR_DAMAGE, damage);
        tag.putFloat(KEY_SR_YAW, yawDegrees);
        tag.putDouble(KEY_SR_SPEED, f);
        DASH_HIT.put(caster, new HashSet<>());
        markGuardWindow(caster, now + 100L); // 冲刺路径上的幻影战戟也在 ~35 tick 后结算伤害
        caster.swing(slot == EquipmentSlot.OFFHAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND, true);

        SafeGuard.info("Soul Render: " + IncineratorSkill.describe(caster) + " toward target " + IncineratorSkill.describe(target)
                + " dash (equivalent charge t=" + t + ", impulse " + String.format("%.2f", f)
                + ", window " + timer + " ticks, dash damage " + String.format("%.1f", damage)
                + ", one phantom halberd along the path every " + DASH_HALBERD_INTERVAL + " ticks, allies (AllyCheck) hard-filtered), "
                + "cooldown " + cooldownTicks() + " tick"
                + (ignoreCooldown ? " (command forced)" : ""));
        return true;
    }

    /** 复刻 RenderRushAttachment.tick 服务端分支：朝向前方 3 格膨胀 AABB，友军/重复目标跳过 */
    private void tickDashDamage(ServerLevel level, Mob caster, double dirX, double dirZ, float damage, long now) {
        try {
            double yaw = Math.atan2(dirZ, dirX);
            AABB attackRange = caster.getBoundingBox()
                    .expandTowards(3.0D * Math.cos(yaw), 0.0D, 3.0D * Math.sin(yaw));
            Set<UUID> hit = DASH_HIT.get(caster);
            if (hit == null) {
                hit = new HashSet<>();
                DASH_HIT.put(caster, hit);
            }
            for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class, attackRange)) {
                if (victim == caster || !victim.isAlive() || hit.contains(victim.getUUID())) {
                    continue;
                }
                // 友军硬过滤（确认第 1 点）：不结算伤害，也不做任何位移
                if (AllyCheck.isAlly(caster, victim)) {
                    continue;
                }
                try {
                    if (victim.hurt(level.damageSources().mobAttack(caster), damage)) {
                        hit.add(victim.getUUID());
                        SafeGuard.infoThrottled("mce_sr_hit_" + victim.getId(), now,
                                "Soul Render: the dash hit " + IncineratorSkill.describe(victim)
                                        + ", damage " + String.format("%.1f", damage) + " (each target is resolved once per dash)");
                    }
                } catch (Throwable perEntity) {
                    SafeGuard.warnThrottled("mce_sr_hit_err_" + victim.getId(), now,
                            "Soul Render: dash single-target resolution failed (that target skipped): "
                                    + perEntity.getClass().getSimpleName() + " / " + perEntity.getMessage());
                }
            }
        } catch (Throwable t) {
            SafeGuard.warnThrottled("mce_sr_dmg_tick", now,
                    "Soul Render: the dash damage tick threw (caught, the window continues): "
                            + t.getClass().getSimpleName() + " / " + t.getMessage());
        }
    }

    /** 冲刺路径幻影战戟：复刻 attachment 每 2 tick 立一根（旋转 = 90° + 偏航，延迟 1 tick，伤害 12） */
    private void tickDashHalberds(ServerLevel level, Mob caster, long now) {
        try {
            long elapsed = now - ChargeStateStore.chargeStart(caster);
            if (elapsed < 0 || (MceConfig.soulRenderDashT() / 2 - elapsed) % DASH_HALBERD_INTERVAL != 0) {
                return;
            }
            float yawDegrees = caster.getPersistentData().getFloat(KEY_SR_YAW);
            float rotation = (float) Math.toRadians(90.0F + yawDegrees);
            CataclysmReflect.spawnPhantomHalberd(level,
                    caster.getX(), caster.getZ(),
                    Mth.floor(caster.getY()) - 3, caster.getY() + 2.0D,
                    rotation, 1, caster, PHANTOM_HALBERD_DAMAGE, now);
        } catch (Throwable t) {
            SafeGuard.warnThrottled("mce_sr_fang", now,
                    "Soul Render: spawning the path phantom halberd failed (caught, the dash continues): "
                            + t.getClass().getSimpleName() + " / " + t.getMessage());
        }
    }

    /** 紫色冲刺轨迹（确认的补救措施）：PORTAL 圈 + SOUL_FIRE_FLAME 尾迹，纯原版粒子 API */
    private void tickDashParticles(ServerLevel level, Mob caster) {
        try {
            double y = caster.getY() + caster.getBbHeight() * 0.5D;
            for (int i = 0; i < 8; i++) {
                double a = Math.PI * 2 * i / 8.0D;
                level.sendParticles(ParticleTypes.PORTAL,
                        caster.getX() + Math.cos(a) * 0.9D,
                        y,
                        caster.getZ() + Math.sin(a) * 0.9D,
                        1, 0.02D, 0.02D, 0.02D, 0.0D);
            }
            level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME,
                    caster.getX(), caster.getY() + 0.2D, caster.getZ(),
                    2, 0.1D, 0.02D, 0.1D, 0.005D);
        } catch (Throwable t) {
            SafeGuard.warnThrottled("mce_sr_particle", level.getGameTime(),
                    "Soul Render: the dash particles failed (caught, skill unaffected): "
                            + t.getClass().getSimpleName() + " / " + t.getMessage());
        }
    }

    // ════════════════════════ 螺旋召唤（反射灾变幻影战戟） ════════════════════════

    /** 复刻 Soul_Render.StrikeWindmillHalberd：7 分支 × 5 列螺旋，外观/伤害全走灾变原版实体 */
    private boolean castWindmill(ServerLevel level, Mob caster, EquipmentSlot slot,
                                 long now, boolean ignoreCooldown) {
        float angleIncrement = (float) (2.0D * Math.PI / WINDMILL_BRANCHES);
        double baseY = caster.getY();
        int spawned = 0;
        for (int branch = 0; branch < WINDMILL_BRANCHES; branch++) {
            float baseAngle = angleIncrement * branch;
            for (int i = 0; i < WINDMILL_PER_BRANCH; i++) {
                try {
                    double currentRadius = WINDMILL_INITIAL_RADIUS + i * WINDMILL_RADIUS_STEP;
                    float currentAngle = (float) (baseAngle
                            + i * angleIncrement / WINDMILL_INITIAL_RADIUS
                            + i * WINDMILL_CURVE_FACTOR);
                    double x = caster.getX() + currentRadius * Math.cos(currentAngle);
                    double z = caster.getZ() + currentRadius * Math.sin(currentAngle);
                    int delay = WINDMILL_BASE_DELAY * (i + 1);
                    // 原版客户端在这里撒 PHANTOM_WING_FLAME；服务端用 PORTAL 补一点施法反馈
                    level.sendParticles(ParticleTypes.PORTAL,
                            x, baseY + 0.3D, z, 2, 0.0D, 0.05D, 0.0D, 0.01D);
                    if (CataclysmReflect.spawnPhantomHalberd(level, x, z,
                            Mth.floor(baseY) - 5, baseY + 3.0D,
                            currentAngle, delay, caster, PHANTOM_HALBERD_DAMAGE, now)) {
                        spawned++;
                    }
                } catch (Throwable perHalberd) {
                    SafeGuard.warnThrottled("mce_sr_wind_err_" + branch + "_" + i, now,
                            "Soul Render: spawning one spiral halberd failed (that halberd skipped): "
                                    + perHalberd.getClass().getSimpleName() + " / " + perHalberd.getMessage());
                }
            }
        }

        if (spawned <= 0) {
            SafeGuard.warn("Soul Render: the spiral summon failed (not a single phantom halberd stood up, usually the Cataclysm reflection layer mismatches or there is no ground around)."
                    + IncineratorSkill.describe(caster));
            return false;
        }

        markGuardWindow(caster, now + 100L);
        caster.swing(slot == EquipmentSlot.OFFHAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND, true);
        SafeGuard.info("Soul Render: " + IncineratorSkill.describe(caster) + " summoned a spiral of " + spawned
                + "/" + (WINDMILL_BRANCHES * WINDMILL_PER_BRANCH)
                + " phantom halberds (each dealing " + String.format("%.1f", PHANTOM_HALBERD_DAMAGE)
                + ", ally damage is intercepted at the event layer by AllyCheck), cooldown " + cooldownTicks() + " tick"
                + (ignoreCooldown ? " (command forced)" : ""));
        return true;
    }

    // ════════════════════════ 情境判定 / force / 状态键 ════════════════════════

    /** 远距离 → 冲刺；近身或被包围（5 格内敌对 ≥2）→ 螺旋召唤（确认第 3 点） */
    private byte decideMode(Mob caster, LivingEntity target) {
        try {
            List<LivingEntity> hostiles = caster.level().getEntitiesOfClass(LivingEntity.class,
                    caster.getBoundingBox().inflate(SURROUNDED_RADIUS),
                    e -> e != caster && e.isAlive() && !AllyCheck.isAlly(caster, e));
            if (hostiles.size() >= SURROUNDED_COUNT) {
                return MODE_WINDMILL;
            }
            if (target != null && caster.distanceTo(target) > MceConfig.soulRenderPreferDashDistance()) {
                return MODE_DASH;
            }
            return MODE_WINDMILL;
        } catch (Throwable t) {
            SafeGuard.warnThrottled("mce_sr_mode", gameTime(caster),
                    "Soul Render: the mode check threw (caught, falls back to the close-range summon): "
                            + t.getClass().getSimpleName() + " / " + t.getMessage());
            return MODE_WINDMILL;
        }
    }

    /** force 命令入口：指定模式直接释放（跳过冷却与情境判定） */
    public static boolean forceCast(ServerLevel level, Mob caster, LivingEntity target,
                                    String mode, long now) {
        try {
            if (!CataclysmReflect.isCataclysmPresent()) {
                SafeGuard.warn("Soul Render: Cataclysm not detected, forced cast cancelled");
                return false;
            }
            EquipmentSlot slot = INSTANCE.heldSlot(caster) == EquipmentSlot.OFFHAND
                    ? EquipmentSlot.OFFHAND : EquipmentSlot.MAINHAND;
            ItemStack stack = caster.getItemBySlot(slot);
            if (stack.isEmpty()) {
                SafeGuard.warn("Soul Render: " + IncineratorSkill.describe(caster) + " not holding the Soul Render, forced cast cancelled");
                return false;
            }
            boolean ok;
            if (FORCE_MODE_DASH.equals(mode)) {
                if (target == null) {
                    SafeGuard.info("Soul Render: the dash mode needs an attack target, forced cast cancelled");
                    return false;
                }
                ok = INSTANCE.castDash(level, caster, target, stack, slot, now, true);
            } else if (FORCE_MODE_WINDMILL.equals(mode)) {
                ok = INSTANCE.castWindmill(level, caster, slot, now, true);
            } else {
                SafeGuard.warn("Soul Render: unknown mode '" + mode + "' (available: dash / windmill)");
                return false;
            }
            if (ok) {
                SkillCooldownStore.markCast(caster, SkillPriorityConfig.SKILL_SOUL_RENDER, now);
                ChargeStateStore.markSilence(caster, now + SkillPriorityConfig.silenceTicks());
                SafeGuard.info("Soul Render: command forced cast '" + mode + "' completed, cooldown recorded as usual");
            }
            return ok;
        } catch (Throwable t) {
            SafeGuard.error("Soul Render: the forced cast threw (caught)", t);
            return false;
        }
    }

    // —— persistentData 小助手（全部包 try-catch，异常按默认值处理）——

    public static void setMode(Entity owner, byte mode) {
        try {
            owner.getPersistentData().putByte(KEY_SR_MODE, mode);
        } catch (Throwable ignored) {
        }
    }

    public static byte getMode(Entity owner) {
        try {
            return owner.getPersistentData().getByte(KEY_SR_MODE);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 幻影战戟友军拦截窗口（AllyDamageGuard 读）：窗口内该同伴的幻影战戟伤害参与 AllyCheck 判定 */
    public static void markGuardWindow(Entity owner, long untilTick) {
        try {
            owner.getPersistentData().putLong(KEY_SR_GUARD_UNTIL, untilTick);
        } catch (Throwable ignored) {
        }
    }

    public static boolean guardWindowActive(Entity owner, long now) {
        try {
            return owner.getPersistentData().getLong(KEY_SR_GUARD_UNTIL) > now;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void clearGuardWindow(Entity owner) {
        try {
            owner.getPersistentData().remove(KEY_SR_GUARD_UNTIL);
        } catch (Throwable ignored) {
        }
    }

    /** /mce soul_render reset 时一并清掉本技能的易变状态（调度器 clearState 不认识的键） */
    public static void clearVolatileState(Entity owner) {
        try {
            CompoundTag tag = owner.getPersistentData();
            tag.remove(KEY_SR_MODE);
            tag.remove(KEY_SR_DAMAGE);
            tag.remove(KEY_SR_YAW);
            tag.remove(KEY_SR_SPEED);
            tag.remove(KEY_SR_GUARD_UNTIL);
        } catch (Throwable ignored) {
        }
        DASH_HIT.remove(owner);
    }

    private static long gameTime(Entity entity) {
        try {
            return entity.level().getGameTime();
        } catch (Throwable t) {
            return -1L;
        }
    }

    /** 冲刺已结算目标集合（状态展示用） */
    public static Set<UUID> dashHitView(Mob caster) {
        Set<UUID> hit = DASH_HIT.get(caster);
        return hit == null ? Set.of() : hit;
    }
}