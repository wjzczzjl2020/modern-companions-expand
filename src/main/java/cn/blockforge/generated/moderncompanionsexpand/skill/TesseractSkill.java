package cn.blockforge.generated.moderncompanionsexpand.skill;

import cn.blockforge.generated.moderncompanionsexpand.config.MceConfig;
import cn.blockforge.generated.moderncompanionsexpand.config.SkillPriorityConfig;
import cn.blockforge.generated.moderncompanionsexpand.util.AllyCheck;
import cn.blockforge.generated.moderncompanionsexpand.util.SafeGuard;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/**
 * 超立方体（legendary_monsters:the_tesseract，传奇怪物）同伴技能：五角星湮灭传送门。
 *
 * 路线（r30 拍板：“外观复用 + 伤害自研”，详见 docs/IMPL_ROUND30_TESSERACT.md）：
 * - 蓄力站定：复用炎葬的蓄力站定框架（调度器 CHARGING 状态 + beginCharge/tickCharge），
 *   门槛照抄原版 maxChargeTick() = 30 tick（1.5 秒），蓄满即发；原版 use() 只是
 *   startUsingItem 无条件，releaseUsing 的门槛 useDuration >= 30 是唯一数值门槛；
 * - 引力放弃：完全去掉引力效果（原版 onUseTick 预览引力 + 传送门 tick 的引力全在
 *   isDangerous() 分支里，同伴版传送门 dangerous=false 后天然不存在，无需另删）；
 * - 外观复用：反射创建 11 个 AnnihilationPortalEntity，全部 dangerous=false（纯外观：
 *   实体自己的周期伤害/引力都锁在 isDangerous() 分支里）——外环 5 个 r=7.5、
 *   内环 5 个 r=4、中心 1 个（scale 4 大门外观），caster 传同伴，落点照抄原版
 *   createAnnihilationPortal 的向下地面扫描（Y+5 → Y-5，找不到实地面就不出该门）；
 * - 伤害自研：11 个点位由本类驱动（SkillTickHandler 每 tick 调 tickSalvo，状态存
 *   persistentData，读档按 gameTime 差续算），每 5 tick 对各点位盒内敌人
 *   AllyCheck 过滤后结算——外/内环 8 点、中心门 12.5 点（照抄原版数值），
 *   第 50 tick 的最后一轮即“到期爆炸”（传送门自己 lifeTicks=50 到期自灭）；
 * - 中心门冲击波：首版省略（拍板）——原版 scale=4 的中心门在 43~52 tick 会通过
 *   flameRadagonShockwave 生成 AnnihilationGroundNukeStrikeEntity（7 点/发 + 3% 最大生命），
 *   这些冲击实体伤害同为 legendary_monsters:void + caster=同伴，会被 AllyDamageGuard
 *   的湮灭接管闸门取消——即“冲击波只可能残留观感（警告粒子/音效），伤害一律不做”；
 * - 五角星排列：固定角度公式逐行照抄原版 doPortalEffect——
 *   angle = k * 2π * size / amountOfPortals + π * size / 10（size=2、amount=5
 *   → 36° 起步每 144° 一个，正是五角星点位），不加朝向偏移（与原版表现一致）；
 * - 冷却 150 tick（7.5 秒，原版 cooldown() = MathUtils.toTicks(7.5f) 照抄），
 *   原版绑 Player.getCooldowns() → 同伴版存 persistentData；
 * - 残留伤害接管：AnnihilationPortalEntity / AnnihilationGroundNukeStrikeEntity 的伤害
 *   口径全部是 legendary_monsters:void + causing entity = caster（已在
 *   LegendaryMonstersReflect.AREA_DAMAGE_CLASS_NOTE 记入未来拦截集合备注），
 *   AllyDamageGuard 按这个口径把同伴名下的原版湮灭伤害整体取消——
 *   同时保证自研伤害绝不走 void 口径（用原版 indirectMagic），不会被自己的闸门误杀。
 */
public final class TesseractSkill implements CompanionSkill {

    public static final TesseractSkill INSTANCE = new TesseractSkill();

    // ══════════════════ 原版数值（照抄，不另立配置） ══════════════════

    /** 五角星外环半径（原版 releaseUsing 第一个 doPortalEffect 的 multiplier） */
    public static final double OUTER_RADIUS = 7.5D;
    /** 五角星内环半径（原版第二个 doPortalEffect 的 multiplier） */
    public static final double INNER_RADIUS = 4.0D;
    /** 外/内环传送门伤害（原版 doPortalEffect 传入 damage=8） */
    public static final float RING_DAMAGE = 8.0F;
    /** 中心门伤害（原版 releaseUsing 直接 new 的 12.5f） */
    public static final float CENTER_DAMAGE = 12.5F;
    /** 传送门 warmup（原版 uniformWarmup = -15：出现即激活） */
    private static final int PORTAL_WARMUP = -15;
    /** 传送门生命周期（原版 lifeTick = 50） */
    private static final int PORTAL_LIFE_TICKS = 50;
    /** 外/内环传送门 scale（原版 2） */
    private static final float RING_SCALE = 2.0F;
    /** 中心门 scale（原版 4，isBig 大门外观） */
    private static final float CENTER_SCALE = 4.0F;
    /** 伤害结算节奏（原版传送门 damage() 的 tickCount % 5 == 0） */
    private static final long ROUND_INTERVAL = 5L;
    /** 首轮（对齐原版 tickCount%5 的首个命中时机） */
    private static final long FIRST_ROUND = 5L;
    /** 最后一轮 = 到期爆炸（与传送门 lifeTicks=50 自灭对齐） */
    private static final long FINAL_ROUND = 50L;
    /** 伤害查询盒：外/内环门户横向半宽（对齐 scale=2 传送门 3 格宽） */
    private static final double RING_HALF_WIDTH = 1.5D;
    /** 伤害查询盒：中心门横向半宽（对齐 scale=4 传送门 5 格宽） */
    private static final double CENTER_HALF_WIDTH = 2.5D;
    /** 伤害查询盒纵向高度（原版 dangerous 传送门碰撞箱高 4 格，伤害盒照此） */
    private static final double BOX_HEIGHT = 4.0D;
    /** 五角星点位角步进：原版 size=2、amount=5 → k * 2π*2/5 + π*2/10 */
    private static final double ANGLE_STEP = Math.PI * 2.0D * 2.0D / 5.0D;
    private static final double ANGLE_OFFSET = Math.PI * 2.0D / 10.0D;

    // ══════════════════ persistentData 键（读档续算，gameTime 为基准） ══════════════════

    private static final String KEY_SALVO_START = "mce_tess_salvo_start";
    private static final String KEY_SALVO_LAST = "mce_tess_salvo_last";
    private static final String KEY_SALVO_POINTS = "mce_tess_salvo_points";

    private TesseractSkill() {}

    // ════════════════════════ CompanionSkill ════════════════════════

    @Override
    public String id() {
        return SkillPriorityConfig.SKILL_TESSERACT;
    }

    @Override
    public net.minecraft.resources.ResourceLocation requiredItem() {
        return LegendaryMonstersReflect.TESSERACT_ITEM;
    }

    /** 超立方体是巨剑类武器，按原版习惯主手持握 */
    @Override
    public EquipmentSlot preferredSlot() {
        return EquipmentSlot.MAINHAND;
    }

    @Override
    public boolean enabled() {
        return MceConfig.tesseractEnabled();
    }

    /** 原版 cooldown() = MathUtils.toTicks(7.5f) = 150 tick，照抄 */
    @Override
    public int cooldownTicks() {
        return MceConfig.tesseractCooldownTicks();
    }

    /** 原版蓄力门槛 maxChargeTick() = 30 tick（1.5 秒），照抄 */
    @Override
    public int chargeTicks() {
        return MceConfig.tesseractChargeTicks();
    }

    @Override
    public void beginCharge(ServerLevel level, Mob caster, LivingEntity target, long now) {
        try {
            // 持握起手：哪只手拿着超立方体就用哪只手做 UseAnim.BOW 姿势（原版 getUseAnimation = BOW）
            caster.startUsingItem(heldSlot(caster) == EquipmentSlot.OFFHAND
                    ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
        } catch (Throwable t) {
            SafeGuard.warn("The Tesseract charge: start pose failed (skill unaffected): " + t.getClass().getSimpleName());
        }
    }

    /**
     * 蓄力期表现：面向目标 + 绿色湮灭粒子环随进度增强（原版 onUseTick 的环/轨道粒子
     * 是纯客户端自定义粒子，反射不划算 → 用原版粒子补同观感）；
     * 引力预览按拍板放弃，不做。
     */
    @Override
    public void tickCharge(ServerLevel level, Mob caster, LivingEntity target, int progress, long now) {
        try {
            if (target != null) {
                float yawDegrees = IncineratorSkill.yawToDegrees(caster, target);
                caster.setYRot(yawDegrees);
                caster.setYHeadRot(yawDegrees);
            }
            int chargeNeed = chargeTicks();
            float ratio = chargeNeed <= 0 ? 1.0F : Math.min(1.0F, progress / (float) chargeNeed);
            double radius = 0.7D + 0.5D * ratio;
            int points = 3 + (int) (5 * ratio);
            double spin = (now % 360) * Math.PI / 180.0D;
            double baseY = caster.getY() + 0.15D;
            for (int i = 0; i < points; i++) {
                double a = spin + (Math.PI * 2 * i) / points;
                level.sendParticles(ParticleTypes.GLOW,
                        caster.getX() + Mth.cos((float) a) * radius,
                        baseY + ((i % 3) * 0.3D),
                        caster.getZ() + Mth.sin((float) a) * radius,
                        1, 0.02D, 0.03D, 0.02D, 0.0D);
            }
            // 外圈薄薄一层 PORTAL 粒子，暗示“传送门正在聚拢”
            double ring = OUTER_RADIUS * ratio;
            for (int i = 0; i < 5; i++) {
                double a = ANGLE_OFFSET + ANGLE_STEP * i;
                level.sendParticles(ParticleTypes.PORTAL,
                        caster.getX() + Math.cos(a) * ring,
                        caster.getY() + 0.5D,
                        caster.getZ() + Math.sin(a) * ring,
                        1, 0.05D, 0.2D, 0.05D, 0.01D);
            }
        } catch (Throwable t) {
            SafeGuard.warnThrottled("mce_tess_charge", level.getGameTime(),
                    "The Tesseract: the charge visual threw (caught, charging unaffected): " + t.getClass().getSimpleName());
        }
    }

    @Override
    public void endCharge(ServerLevel level, Mob caster, long now) {
        try {
            if (caster.isUsingItem()) {
                caster.stopUsingItem();
            }
        } catch (Throwable t) {
            SafeGuard.warn("The Tesseract charge: finish pose failed (logic unaffected): " + t.getClass().getSimpleName());
        }
    }

    // ════════════════════════ 释放（force 命令也走这里；目标可为空） ════════════════════════

    @Override
    public boolean release(ServerLevel level, Mob caster, LivingEntity target, long now, boolean ignoreCooldown) {
        return tryCast(level, caster, target, now, ignoreCooldown) > 0;
    }

    /**
     * 纯释放：算点位 → 落地扫描 → 反射生成 11 门 → 记录自研伤害循环起点。
     *
     * @return 成功生成的传送门数量（0 = 本次没有放出技能）
     */
    public static int tryCast(ServerLevel level, Mob caster, LivingEntity target, long now, boolean ignoreCooldown) {
        int cooldownTicks = MceConfig.tesseractCooldownTicks();
        if (!ignoreCooldown && !SkillCooldownStore.isReady(caster, SkillPriorityConfig.SKILL_TESSERACT, now, cooldownTicks)) {
            SafeGuard.infoThrottled("mce_tess_cooling_" + caster.getUUID(), now,
                    "The Tesseract: " + IncineratorSkill.describe(caster) + " is still on cooldown ("
                            + SkillCooldownStore.remainingTicks(caster, SkillPriorityConfig.SKILL_TESSERACT, now, cooldownTicks)
                            + " ticks), skipped this time");
            return 0;
        }
        if (!LegendaryMonstersReflect.isLegendaryMonstersPresent()) {
            SafeGuard.warnThrottled("mce_tess_nolm_" + level.dimension().location(), now,
                    "The Tesseract: Legendary Monsters not detected (the annihilation portal entity depends on it), cast cancelled");
            return 0;
        }
        EquipmentSlot slot = INSTANCE.heldSlot(caster);
        if (slot == null || caster.getItemBySlot(slot).isEmpty()) {
            SafeGuard.warn("The Tesseract: " + IncineratorSkill.describe(caster) + " no longer holding The Tesseract, cast cancelled");
            return 0;
        }

        // —— r37 触发距离门（拍板：距离内没有敌人不释放；force 不受限）——
        // 传送门以同伴为中心排布（外环 7.5 / 内环 4 / 中心门在脚下），核心伤害区域是
        // 内环 4 格 + 中心门；同伴离敌人很远时曾空放整套传送门纯浪费冷却。
        // 以同伴为中心 inflate(tesseract_trigger_distance) 扫描盒，一个敌对目标都没有就不释放
        // ——markCast 在后面才执行，空放不消耗冷却。
        if (!ignoreCooldown) {
            LivingEntity nearbyEnemy = AllyCheck.findEnemyNear(caster, MceConfig.tesseractTriggerDistance());
            if (nearbyEnemy == null) {
                SafeGuard.infoThrottled("mce_tess_far_" + caster.getUUID(), now,
                        "The Tesseract: " + IncineratorSkill.describe(caster) + " trigger distance "
                                + MceConfig.tesseractTriggerDistance()
                                + " blocks (tesseract_trigger_distance) contains no enemy"
                                + " (the core damage area = inner ring 4 blocks + centre portal), not cast this time and no cooldown");
                return 0;
            }
        }

        // —— 朝向：有目标就面向目标（纯视觉；五角星固定角度公式不随朝向偏移，拍板）——
        if (target != null) {
            float yawDegrees = IncineratorSkill.yawToDegrees(caster, target);
            caster.setYRot(yawDegrees);
            caster.setXRot(0.0F);
            caster.setYHeadRot(yawDegrees);
        }

        // —— 蓄满音（原版 onUseTick 第 30 tick）+ 释放音（原版 releaseUsing）——
        playSound(level, caster, LegendaryMonstersReflect.getTesseractChargeSound(), "ultimate_flame_impact");
        playSound(level, caster, LegendaryMonstersReflect.getTesseractFlameBurstSound(), "flame_burst");

        // —— 11 个点位：外环 5 + 内环 5（同一角度公式，各自半径）+ 中心 1 ——
        List<double[]> points = new ArrayList<>(11);
        List<Float> damages = new ArrayList<>(11);
        List<Float> scales = new ArrayList<>(11);
        for (int k = 0; k < 5; k++) {
            double angle = ANGLE_OFFSET + ANGLE_STEP * k;
            points.add(new double[]{
                    caster.getX() + Math.cos(angle) * OUTER_RADIUS, 0.0D,
                    caster.getZ() + Math.sin(angle) * OUTER_RADIUS});
            damages.add(RING_DAMAGE);
            scales.add(RING_SCALE);
        }
        for (int k = 0; k < 5; k++) {
            double angle = ANGLE_OFFSET + ANGLE_STEP * k;
            points.add(new double[]{
                    caster.getX() + Math.cos(angle) * INNER_RADIUS, 0.0D,
                    caster.getZ() + Math.sin(angle) * INNER_RADIUS});
            damages.add(RING_DAMAGE);
            scales.add(RING_SCALE);
        }
        points.add(new double[]{caster.getX(), 0.0D, caster.getZ()});
        damages.add(CENTER_DAMAGE);
        scales.add(CENTER_SCALE);

        // —— 落点地面扫描（照抄原版 createAnnihilationPortal：从 Y+5 向下找实地面；
        //      原版中心门不扫描、直接在施法者脚下生成，同伴版照抄）——
        List<double[]> placed = new ArrayList<>(11);
        List<Float> placedDamage = new ArrayList<>(11);
        List<Float> placedScale = new ArrayList<>(11);
        for (int i = 0; i < points.size(); i++) {
            double[] p = points.get(i);
            boolean isCenter = i == points.size() - 1;
            double y;
            if (isCenter) {
                y = caster.getY();
            } else {
                y = scanGroundAt(level, p[0], p[2], caster.getY() + 5.0D, caster.getY() - 5.0D);
                if (y == Double.NEGATIVE_INFINITY) {
                    continue; // 该点位悬空，原版同样跳过不出门
                }
            }
            placed.add(new double[]{p[0], y, p[2]});
            placedDamage.add(damages.get(i));
            placedScale.add(scales.get(i));
        }

        // —— 生成传送门（dangerous=false 纯外观；damage 传 0，伤害由自研循环结算）——
        int spawned = 0;
        for (int i = 0; i < placed.size(); i++) {
            double[] p = placed.get(i);
            try {
                if (LegendaryMonstersReflect.spawnAnnihilationPortal(level, caster,
                        p[0], p[1], p[2], PORTAL_WARMUP, PORTAL_LIFE_TICKS, placedScale.get(i), now)) {
                    spawned++;
                }
            } catch (Throwable t) {
                SafeGuard.warn("The Tesseract: portal " + (i + 1) + " threw while spawning (caught, that spot skipped): "
                        + t.getClass().getSimpleName() + " / " + t.getMessage());
            }
        }

        // 冷却照常记录（原版松手即进冷却，与命中无关），防止失败时逐 tick 疯狂重试
        SkillCooldownStore.markCast(caster, SkillPriorityConfig.SKILL_TESSERACT, now);
        if (spawned <= 0) {
            SafeGuard.warn("The Tesseract: " + IncineratorSkill.describe(caster) + " cast failed (not a single portal stood up, "
                    + "most likely all " + (int) OUTER_RADIUS + " blocks around are void or the reflection layer mismatches, see the log above)");
            return 0;
        }

        // —— 记录自研伤害循环：起点 + 点位（含各点伤害/半宽），读档按 gameTime 差续算 ——
        ListTag pointTag = new ListTag();
        for (int i = 0; i < placed.size(); i++) {
            double[] p = placed.get(i);
            CompoundTag pt = new CompoundTag();
            pt.putDouble("x", p[0]);
            pt.putDouble("y", p[1]);
            pt.putDouble("z", p[2]);
            pt.putDouble("d", placedDamage.get(i));
            pt.putDouble("w", placedScale.get(i) >= CENTER_SCALE ? CENTER_HALF_WIDTH : RING_HALF_WIDTH);
            pointTag.add(pt);
        }
        CompoundTag data = caster.getPersistentData();
        data.putLong(KEY_SALVO_START, now);
        data.putLong(KEY_SALVO_LAST, 0L);
        data.put(KEY_SALVO_POINTS, pointTag);

        caster.swing(slot == EquipmentSlot.OFFHAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND, true);
        ChargeStateStore.markSilence(caster, now + SkillPriorityConfig.silenceTicks());
        SafeGuard.info("The Tesseract: cast successful!" + IncineratorSkill.describe(caster) + " raised "
                + spawned + "/" + points.size() + " annihilation portals around (outer ring 5 x r=" + OUTER_RADIUS + " + inner ring 5 x r="
                + INNER_RADIUS + " + 1 centre, all dangerous=false purely cosmetic, the pull effect was dropped by decision), "
                + "custom damage loop: one round every " + ROUND_INTERVAL + " ticks, outer/inner ring points deal "
                + String.format("%.1f", RING_DAMAGE) + " points, centre portal "
                + String.format("%.1f", CENTER_DAMAGE) + " each (vanilla numbers copied, allies filtered by AllyCheck), "
                + "round " + FINAL_ROUND + " tick is the final round and explodes; cooldown " + cooldownTicks + " tick"
                + (ignoreCooldown ? " (command forced)" : "") + " left, global silence "
                + SkillPriorityConfig.silenceTicks() + " tick");
        return spawned;
    }

    // ════════════════════════ 自研伤害循环（SkillTickHandler 每 tick 驱动） ════════════════════════

    /**
     * 每 tick 驱动一次（内部先查 persistentData，无状态零成本直通）：
     * elapsed = now - 起点时间；每 5 tick 一轮（第 5/10/.../50 tick），第 50 tick = 到期爆炸。
     * 状态存 persistentData（KEY_SALVO_*），读档后按 gameTime 差续算，超时自动清理。
     */
    public static void tickSalvo(ServerLevel level, Mob caster, long now) {
        try {
            CompoundTag data = caster.getPersistentData();
            if (!data.contains(KEY_SALVO_START)) {
                return;
            }
            long start = data.getLong(KEY_SALVO_START);
            long elapsed = now - start;
            if (elapsed > FINAL_ROUND + 5L) {
                // 传送门早已自灭，清掉循环状态（防键残留）
                clearSalvo(caster);
                return;
            }
            if (elapsed < FIRST_ROUND || elapsed % ROUND_INTERVAL != 0L) {
                return;
            }
            long last = data.getLong(KEY_SALVO_LAST);
            if (elapsed <= last) {
                return; // 同一轮已放过（读档重放/重复驱动防护）
            }
            Tag raw = data.get(KEY_SALVO_POINTS);
            if (!(raw instanceof ListTag points) || points.isEmpty()) {
                clearSalvo(caster);
                return;
            }
            data.putLong(KEY_SALVO_LAST, elapsed);
            boolean explosion = elapsed >= FINAL_ROUND;
            int hits = 0;
            for (Tag entry : points) {
                if (entry instanceof CompoundTag pt) {
                    hits += damageAroundPoint(level, caster,
                            pt.getDouble("x"), pt.getDouble("y"), pt.getDouble("z"),
                            pt.getDouble("w"), (float) pt.getDouble("d"), explosion);
                }
            }
            SafeGuard.infoThrottled("mce_tess_round_" + caster.getUUID(), now,
                    "The Tesseract damage round: " + IncineratorSkill.describe(caster) + " round "
                            + (elapsed / ROUND_INTERVAL) + "/" + (FINAL_ROUND / ROUND_INTERVAL)
                            + " (tick " + elapsed + " tick)" + (explosion ? " [final explosion round]" : "")
                            + "resolved " + hits + " hits (allies are always let through by AllyCheck)");
            if (explosion) {
                clearSalvo(caster);
            }
        } catch (Throwable t) {
            SafeGuard.warnThrottled("mce_tess_salvo_err", now,
                    "The Tesseract: the damage loop threw (caught, this round skipped): " + t.getClass().getSimpleName()
                            + " / " + t.getMessage());
        }
    }

    /** 单点位结算：盒内 LivingEntity 逐个 AllyCheck 过滤后 hurt（同伴本人永远豁免） */
    private static int damageAroundPoint(ServerLevel level, Mob caster,
                                         double x, double y, double z,
                                         double halfWidth, float damage, boolean explosion) {
        int hits = 0;
        try {
            AABB box = new AABB(x - halfWidth, y, z - halfWidth, x + halfWidth, y + BOX_HEIGHT, z + halfWidth);
            // 伤害口径：indirectMagic(同伴, 同伴)——不能沿用原版 legendary_monsters:void，
            // 那个口径会被湮灭接管闸门取消（闸门只负责拦原版传送门/冲击波的残留伤害）
            DamageSource source = caster.damageSources().indirectMagic(caster, caster);
            for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class, box)) {
                if (victim == caster || !victim.isAlive() || victim.isRemoved() || victim.isInvulnerable()) {
                    continue;
                }
                if (AllyCheck.isAlly(caster, victim)) {
                    continue; // 友军保护（拍板：AllyCheck 过滤后才 hurt）
                }
                if (victim.hurt(source, damage)) {
                    hits++;
                }
            }
        } catch (Throwable t) {
            SafeGuard.warnThrottled("mce_tess_point_err", level.getGameTime(),
                    "The Tesseract: spot (" + Mth.floor(x) + ", " + Mth.floor(y) + ", " + Mth.floor(z)
                            + ") resolution threw (caught, that point skipped): " + t.getClass().getSimpleName());
        }
        return hits;
    }

    /** 清掉自研循环状态（/mce tesseract reset 与到期清理共用） */
    public static void clearSalvo(Entity owner) {
        try {
            CompoundTag data = owner.getPersistentData();
            data.remove(KEY_SALVO_START);
            data.remove(KEY_SALVO_LAST);
            data.remove(KEY_SALVO_POINTS);
        } catch (Throwable ignored) {
        }
    }

    /** /mce tesseract reset 时一并清掉本技能的易变状态 */
    public static void clearVolatileState(Entity owner) {
        clearSalvo(owner);
    }

    // ════════════════════════ 工具 ════════════════════════

    /**
     * 向下地面扫描（逐行照抄原版 createAnnihilationPortal）：
     * 从 maxY 向下找第一个“上表面坚实”的方块，返回该面世界坐标（含碰撞箱顶）；
     * 一直扫到 minY 以下还没找到 → 返回 Double.NEGATIVE_INFINITY（调用方跳过该点位）。
     */
    static double scanGroundAt(ServerLevel level, double x, double z, double maxY, double minY) {
        try {
            BlockPos blockpos = BlockPos.containing(x, maxY, z);
            boolean flag = false;
            double d0 = 0.0D;
            do {
                BlockPos below = blockpos.below();
                BlockState state = level.getBlockState(below);
                if (state.isFaceSturdy(level, below, Direction.UP)) {
                    if (!level.isEmptyBlock(blockpos)) {
                        BlockState state1 = level.getBlockState(blockpos);
                        if (!state1.getCollisionShape(level, blockpos).isEmpty()) {
                            d0 = state1.getCollisionShape(level, blockpos).max(Direction.Axis.Y);
                        }
                    }
                    flag = true;
                    break;
                }
                blockpos = below;
            } while (blockpos.getY() >= Mth.floor(minY) - 1);
            if (!flag) {
                return Double.NEGATIVE_INFINITY;
            }
            return blockpos.getY() + d0;
        } catch (Throwable t) {
            SafeGuard.warnThrottled("mce_tess_scan_err", level.getGameTime(),
                    "The Tesseract: the ground scan threw (caught, that spot skipped): " + t.getClass().getSimpleName());
            return Double.NEGATIVE_INFINITY;
        }
    }

    private static void playSound(ServerLevel level, Mob caster, SoundEvent sound, String label) {
        try {
            if (sound == null) {
                SafeGuard.infoThrottled("mce_tess_nosound_" + label, level.getGameTime(),
                        "The Tesseract: Legendary Monsters sound not found " + label + ", this cast is silent (skill unaffected)");
                return;
            }
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                    sound, SoundSource.PLAYERS, 1.0F, 1.0F);
        } catch (Throwable t) {
            SafeGuard.warnThrottled("mce_tess_snd_" + label, level.getGameTime(),
                    "The Tesseract: playing " + label + " failed (caught, skill unaffected): " + t.getClass().getSimpleName());
        }
    }
}
