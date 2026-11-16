package cn.blockforge.generated.moderncompanionsexpand.skill;

import cn.blockforge.generated.moderncompanionsexpand.util.AllyCheck;
import cn.blockforge.generated.moderncompanionsexpand.util.SafeGuard;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 灾变（Cataclysm）反射代理层——全模组唯一允许出现灾变类名字符串的地方。
 *
 * 编译期零依赖：本工程不链接灾变的任何类，灾变没装也只是“技能放不出来 + 一条日志”，
 * 游戏与其余功能完全不受影响。
 *
 * 复用目标（来自用户上传的灾变源码，1.21.1 版）：
 * - 火柱实体 com.github.L_Ender.cataclysm.entity.effect.Flame_Strike_Entity
 *   公开构造器 (Level, double x, double y, double z, float 朝向弧度, int 持续,
 *   int wait, int delay, float 半径, float 伤害, float 按最大生命%, boolean 灵魂火,
 *   LivingEntity 施法者)——施法者参数是 LivingEntity，同伴实体可以直接当主人传入；
 * - 音效 cataclysm:sword_stomp（炎葬命中/施放音）、cataclysm:flame_burst（蓄力完成音），
 *   都走音效注册表查，不反射类；
 * - 冲锋附件：AttachmentType 直接按注册名 cataclysm:charge_attachment 从 NeoForge
 *   附件注册表取（注册表名在灾变源码 ModDataAttachments 里核对过），
 *   附件对象上的小写驼峰 setter（setcharge/setdamagePerEffectiveCharge/setdZ 这种）用反射；
 * - 撞墙特效实体 Wall_Watcher_Entity（壁垒方案 B 兜底时反射构造）；
 * - 震屏 com.github.L_Ender.cataclysm.entity.effect.ScreenShake_Entity.ScreenShake 静态方法。
 */
public final class CataclysmReflect {

    /** 灾变 modid 与技能物品 ID（物品只做字符串比对，零依赖） */
    public static final String CATACLYSM_MOD_ID = "cataclysm";
    public static final ResourceLocation INCINERATOR_ITEM =
            ResourceLocation.parse(CATACLYSM_MOD_ID + ":the_incinerator");
    public static final ResourceLocation BULWARK_ITEM =
            ResourceLocation.parse(CATACLYSM_MOD_ID + ":bulwark_of_the_flame");
    public static final ResourceLocation INFERNAL_FORGE_ITEM =
            ResourceLocation.parse(CATACLYSM_MOD_ID + ":infernal_forge");
    public static final ResourceLocation SOUL_RENDER_ITEM =
            ResourceLocation.parse(CATACLYSM_MOD_ID + ":soul_render");
    public static final ResourceLocation SWORD_STOMP_SOUND =
            ResourceLocation.parse(CATACLYSM_MOD_ID + ":sword_stomp");
    /** 炼狱锻锤冲击波音效（灾变源码 ModSounds：SOUNDS.register("explosion", …)） */
    public static final ResourceLocation EXPLOSION_SOUND =
            ResourceLocation.parse(CATACLYSM_MOD_ID + ":explosion");
    public static final ResourceLocation FLAME_BURST_SOUND =
            ResourceLocation.parse(CATACLYSM_MOD_ID + ":flame_burst");
    /** 神怒长槊物品（cataclysm:astrape） */
    public static final ResourceLocation ASTRAPE_ITEM =
            ResourceLocation.parse(CATACLYSM_MOD_ID + ":astrape");
    /** 神怒长槊发射音效（Astrape.releaseUsing L53 播的就是 ModSounds.EMP_ACTIVATED） */
    public static final ResourceLocation EMP_ACTIVATED_SOUND =
            ResourceLocation.parse(CATACLYSM_MOD_ID + ":emp_activated");
    /** 霆浪锚戟物品（cataclysm:ceraunus） */
    public static final ResourceLocation CERAUNUS_ITEM =
            ResourceLocation.parse(CATACLYSM_MOD_ID + ":ceraunus");
    /** 霆浪锚戟波浪施放音效（Ceraunus.releaseUsing 潜行分支播的就是 ModSounds.HEAVY_SMASH） */
    public static final ResourceLocation HEAVY_SMASH_SOUND =
            ResourceLocation.parse(CATACLYSM_MOD_ID + ":heavy_smash");
    /** 虚空锻锤物品（cataclysm:void_forge） */
    public static final ResourceLocation VOID_FORGE_ITEM =
            ResourceLocation.parse(CATACLYSM_MOD_ID + ":void_forge");
    /** 掣雷巨锤物品（cataclysm:brontes） */
    public static final ResourceLocation BRONTES_ITEM =
            ResourceLocation.parse(CATACLYSM_MOD_ID + ":brontes");
    /** 绞肉锯开场音效（灾变 Meat_Shredder.use 播的就是 ModSounds.SHREDDER_START；原版同伴入口不走 use()，由我们补播） */
    public static final ResourceLocation SHREDDER_START_SOUND =
            ResourceLocation.parse(CATACLYSM_MOD_ID + ":shredder_start");
    /** 绞肉锯物品（cataclysm:meat_shredder） */
    public static final ResourceLocation MEAT_SHREDDER_ITEM =
            ResourceLocation.parse(CATACLYSM_MOD_ID + ":meat_shredder");
    /**
     * 灾变绞肉伤害类型 key（灾变源码 CMDamageTypes.SHREDDER：cataclysm:shredder）。
     * 该伤害类型挂在原版 bypasses_cooldown 标签里 → hurt() 跳过无敌帧检查，
     * 这正是“每 0.05 秒一次全额伤害”的机制来源；只用于伤害类型比对，零依赖灾变。
     */
    public static final ResourceKey<DamageType> SHREDDER_DAMAGE_TYPE =
            ResourceKey.create(Registries.DAMAGE_TYPE,
                    ResourceLocation.parse(CATACLYSM_MOD_ID + ":shredder"));

    /** 这条伤害源是不是绞肉伤害（cataclysm:shredder）；异常一律按“不是”处理 */
    public static boolean isShredderDamage(DamageSource source) {
        try {
            return source != null && source.is(SHREDDER_DAMAGE_TYPE);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 灾变“锤子飞出去了”物品数据组件的注册名（灾变源码 ModDataComponents：
     * COMPONENTS.register("thrown_hammer", …)，类型 DataComponentType&lt;UUID&gt;）。
     * 同伴版投掷成功后镜像写入、强制恢复时镜像清除（best-effort，找不到注册名就跳过）。
     */
    public static final ResourceLocation THROWN_HAMMER_COMPONENT =
            ResourceLocation.parse(CATACLYSM_MOD_ID + ":thrown_hammer");

    private static final String STRIKE_CLASS_NAME =
            "com.github.L_Ender.cataclysm.entity.effect.Flame_Strike_Entity";
    private static final String WALL_WATCHER_CLASS_NAME =
            "com.github.L_Ender.cataclysm.entity.effect.Wall_Watcher_Entity";
    private static final String SCREEN_SHAKE_CLASS_NAME =
            "com.github.L_Ender.cataclysm.entity.effect.ScreenShake_Entity";
    /** 灾变源码：ATTACHMENT_TYPES.register("charge_attachment", …) */
    public static final ResourceLocation CHARGE_ATTACHMENT_NAME =
            ResourceLocation.parse(CATACLYSM_MOD_ID + ":charge_attachment");

    /** 期望的构造器参数类型（先精确匹配，再宽松匹配） */
    private static final Class<?>[] EXPECTED_PARAMS = {
            Level.class,
            double.class, double.class, double.class,
            float.class,
            int.class, int.class, int.class,
            float.class, float.class, float.class,
            boolean.class,
            LivingEntity.class,
    };

    private static volatile Constructor<?> strikeCtor = null;
    private static volatile boolean ctorProbed = false;

    private CataclysmReflect() {}

    /** 灾变的火柱实体类是否存在（= 灾变是否装了） */
    public static boolean isCataclysmPresent() {
        return strikeClass() != null;
    }

    /** 火柱构造器是否已解析成功 */
    public static boolean isConstructorResolved() {
        return strikeCtor != null;
    }

    /** 物品是否注册（供命令与状态展示用，通用） */
    public static boolean isItemRegistered(ResourceLocation item) {
        try {
            return BuiltInRegistries.ITEM.getOptional(item).isPresent();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 灾变原版施放音效（可能为 null = 没装灾变，null 时跳过音效不报错） */
    public static SoundEvent getSwordStompSound() {
        return lookupSound(SWORD_STOMP_SOUND);
    }

    /** 灾变原版蓄力完成音效（炎葬 onUseTick 第 60 tick 播的就是它） */
    public static SoundEvent getFlameBurstSound() {
        return lookupSound(FLAME_BURST_SOUND);
    }

    /** 神怒长槊发射音效（Astrape.releaseUsing；查不到返回 null = 没装灾变，跳过音效不报错） */
    public static SoundEvent getEmpActivatedSound() {
        return lookupSound(EMP_ACTIVATED_SOUND);
    }

    /** 绞肉锯开场音效（查不到返回 null = 没装灾变，跳过音效不报错） */
    public static SoundEvent getShredderStartSound() {
        return lookupSound(SHREDDER_START_SOUND);
    }

    private static SoundEvent lookupSound(ResourceLocation id) {
        try {
            Optional<SoundEvent> found = BuiltInRegistries.SOUND_EVENT.getOptional(id);
            return found.orElse(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 灾变冲击波音效（炼狱锻锤 useOn 里播的就是它；查不到返回 null = 没装灾变，跳过音效不报错） */
    public static SoundEvent getExplosionSound() {
        return lookupSound(EXPLOSION_SOUND);
    }

    // ════════════════════════ 炼狱锻锤：OriginDamage 反射 ════════════════════════
    //
    // 灾变源码 EarthQuake L96：float basedmg = AttributeUtils.OriginDamage(player, stack);
    // 该方法是 public static (LivingEntity, ItemStack) -> float：
    //   主手持物品时直接返回 living.getAttributeValue(Attributes.ATTACK_DAMAGE)
    //   （物品 +13 攻击加成叠在属性里），所以同伴传自己 + 主手堆也能拿到同口径数值。

    private static final String ATTRIBUTE_UTILS_CLASS_NAME =
            "com.github.L_Ender.cataclysm.util.AttributeUtils";
    private static volatile Method originDamageMethod = null;
    private static volatile boolean originDamageProbed = false;

    private static synchronized Method resolveOriginDamage() {
        if (originDamageProbed) {
            return originDamageMethod;
        }
        originDamageProbed = true;
        try {
            Class<?> cls = Class.forName(ATTRIBUTE_UTILS_CLASS_NAME);
            originDamageMethod = cls.getMethod("OriginDamage", LivingEntity.class, ItemStack.class);
            SafeGuard.info("Infernal Forge reflection layer: AttributeUtils.OriginDamage resolved (companion shockwave damage reads the attack damage attribute, same basis as vanilla)");
            return originDamageMethod;
        } catch (Throwable t) {
            originDamageMethod = null;
            SafeGuard.warn("Infernal Forge reflection layer: AttributeUtils.OriginDamage not found (Cataclysm not installed/class name or signature changed), "
                    + "shockwave damage falls back to reading the companion attack damage attribute directly: "
                    + t.getClass().getSimpleName() + " / " + t.getMessage());
            return null;
        }
    }

    /** OriginDamage 反射层是否可用（/mce infernal_forge status 自检用） */
    public static boolean isOriginDamageResolved() {
        return resolveOriginDamage() != null;
    }

    /**
     * 按灾变同口径取“持锤者的攻击力”（含物品属性加成）。
     *
     * @param fallback 反射层不可用时的退化值（调用方一般传 caster.getAttributeValue(ATTACK_DAMAGE)）
     * @return 伤害数值；任何异常都返回 fallback，绝不抛出
     */
    public static float originAttackDamage(LivingEntity holder, ItemStack stack, float fallback) {
        try {
            Method m = resolveOriginDamage();
            if (m == null) {
                return fallback;
            }
            Object result = m.invoke(null, holder, stack);
            if (result instanceof Float f && f > 0.0F) {
                return f;
            }
            return fallback;
        } catch (InvocationTargetException ite) {
            Throwable cause = ite.getCause() != null ? ite.getCause() : ite;
            SafeGuard.warnThrottled("mce_f_origin", gameTime(holder),
                    "Infernal Forge: OriginDamage reflection internal error (caught, falls back to reading attack damage attribute directly): "
                            + cause.getClass().getSimpleName() + " / " + cause.getMessage());
            return fallback;
        } catch (Throwable t) {
            SafeGuard.warnThrottled("mce_f_origin", gameTime(holder),
                    "Infernal Forge: OriginDamage reflection call failed (caught, falls back to reading attack damage attribute directly): "
                            + t.getClass().getSimpleName() + " / " + t.getMessage());
            return fallback;
        }
    }

    // ════════════════════════ 霆浪锚戟：投掷实体 + 波浪实体 + 配置数值（r19） ════════════════════════
    //
    // r19 排查：force throw 失败根因 = 反射按 float.class 找 setBaseDamage，
    // 而原版 AbstractArrow.setBaseDamage 参数是 double（灾变源码传 float 是自动加宽）→
    // NoSuchMethodException 被笼统 catch 吞掉。修复：伤害/弹道直接调用原版方法
    // （本工程编译依赖原版，无需反射），并给投掷分支按①②③④步拆独立日志。
    //
    // 灾变源码核对（Ceraunus.releaseUsing / Player_Ceraunus_Entity / Wave_Entity）：
    // - 投掷实体 com.github.L_Ender.cataclysm.entity.projectile.Player_Ceraunus_Entity
    //   （继承 AbstractArrow），公开构造器 (Level worldIn, LivingEntity shooter)——
    //   末参 LivingEntity，同伴直接当 owner；构造器内部自己 setOwner + 取 shooter.getEyeY()-0.1
    //   当生成点 + pickup=DISALLOWED。原版发射流程：
    //     setBaseDamage(发射者 ATTACK_DAMAGE 属性值)
    //     → shootFromRotation(shooter, xRot, yRot, 0, 2.5F, 1.0F) → addFreshEntity。
    //   有重力（0.08/tick），抛物线弹道，同伴瞄准要给仰角补偿（CeraunusSkill 里解低抛物线方程）。
    // - 波浪实体 com.github.L_Ender.cataclysm.entity.effect.Wave_Entity（继承 Entity），
    //   公开构造器 (Level level, LivingEntity shooter, int life, float damage)——
    //   非玩家 owner 已被灾变自己验证（Scylla boss 就这么用）；原版施放流程：
    //     播 HEAVY_SMASH → 施法者前方 2 格取统一生成点 → 4 道波浪、间隔 25°、总扇形 75°，
    //     每道 setPos(spawn) / setState(1) / setYRot(-(atan2(dx,dz)×180/π)) → addFreshEntity。
    //   伤害（默认 6）+ 潮湿效果叠加（200 tick，clamp 0~4）都内置在 Wave_Entity.attackEntities，
    //   同伴版只管把实体按原版样子立起来，不需要复刻那 5 行。
    // - 数值来自 CMCommonConfig.Ceraunus 静态字段（attackDamage=16.0、waveDamage=6.0、
    //   cooldown=150），可能被灾变 TOML 覆盖 → 反射直读，读不到退化为源码默认值。

    private static final String THROWN_CERAUNUS_CLASS_NAME =
            "com.github.L_Ender.cataclysm.entity.projectile.Player_Ceraunus_Entity";
    private static final Class<?>[] THROWN_CERAUNUS_PARAMS = {
            Level.class, LivingEntity.class,
    };

    private static volatile Constructor<?> thrownCeraunusCtor = null;
    private static volatile boolean thrownCeraunusProbed = false;

    private static synchronized Constructor<?> resolveThrownCeraunusCtor() {
        if (thrownCeraunusProbed) {
            return thrownCeraunusCtor;
        }
        thrownCeraunusProbed = true;
        Class<?> cls;
        try {
            cls = Class.forName(THROWN_CERAUNUS_CLASS_NAME);
        } catch (Throwable t) {
            thrownCeraunusCtor = null;
            SafeGuard.warn("Ceraunus reflection layer: Player_Ceraunus_Entity not found (Cataclysm not installed/class name changed), throw cancelled: "
                    + t.getClass().getSimpleName() + " / " + t.getMessage());
            return null;
        }
        try {
            thrownCeraunusCtor = cls.getConstructor(THROWN_CERAUNUS_PARAMS);
            SafeGuard.info("Ceraunus reflection layer: Player_Ceraunus_Entity constructor resolved exactly (2 args, matches Cataclysm source)");
            return thrownCeraunusCtor;
        } catch (Throwable exactFail) {
            try {
                for (Constructor<?> c : cls.getConstructors()) {
                    Class<?>[] p = c.getParameterTypes();
                    if (p.length == 2 && p[0] == Level.class && p[1] == LivingEntity.class) {
                        thrownCeraunusCtor = c;
                        SafeGuard.warn("Ceraunus reflection layer: exact signature mismatch, loosely matched a constructor: " + c);
                        return thrownCeraunusCtor;
                    }
                }
            } catch (Throwable scanFail) {
                exactFail = scanFail;
            }
            StringBuilder sb = new StringBuilder();
            try {
                for (Constructor<?> c : cls.getConstructors()) {
                    sb.append("\n  - ").append(c);
                }
            } catch (Throwable ignored) {
            }
            thrownCeraunusCtor = null;
            SafeGuard.warn("Ceraunus reflection layer: Player_Ceraunus_Entity constructor mismatch (Cataclysm version may have changed). Existing public constructors:" + sb,
                    exactFail);
            return null;
        }
    }

    /** 原版 Projectile.shootFromRotation 是 public（已用 1.21.1 官方映射核对），伤害/弹道直接调用，不再反射 */

    /** 投掷实体构造器是否已解析（/mce ceraunus status 自检用） */
    public static boolean isThrownCeraunusResolved() {
        return resolveThrownCeraunusCtor() != null;
    }

    /**
     * 反射生成一个灾变投掷锚戟并放进世界（流程照抄 Ceraunus.releaseUsing 非潜行分支）。
     * 生成点/owner/pickup 都由实体构造器内部按原版处理，这里只补伤害与弹道。
     *
     * 分四步独立 try-catch + 独立失败日志（r19 排查修复）：
     *   ① 构造实体（唯一需要反射的环节，灾变自己的类）
     *   ② 设置伤害——原版 AbstractArrow.setBaseDamage 的参数是 double，灾变源码传 float
     *      是 Java 自动加宽；r18 反射按 float.class 找方法导致 NoSuchMethodException，
     *      这就是 force throw 失败的根因。本工程编译依赖原版，直接转型调用 + 反射兜底。
     *   ③ 设置弹道——原版 Projectile.shootFromRotation 是 public，直接调用。
     *   ④ 加入世界——addFreshEntity 返回 false 也单独报。
     *
     * @param xRot       俯仰角（MC 口径，负=向上），已带抛物线仰角补偿
     * @param yRot       偏航角（MC 口径）
     * @param baseDamage 发射者 ATTACK_DAMAGE 属性值（与原版 setBaseDamage 同口径）
     * @param velocity   弹速（原版 2.5F）
     * @return 成功加入世界返回 true；任何一步失败返回 false，绝不抛出
     */
    public static boolean spawnThrownCeraunus(ServerLevel level, LivingEntity owner,
                                              float xRot, float yRot,
                                              float baseDamage, float velocity, long nowTick) {
        // —— 第 ① 步：反射构造投掷实体（灾变自己的类，只能反射）——
        Constructor<?> ctor = resolveThrownCeraunusCtor();
        if (ctor == null) {
            SafeGuard.warn("Ceraunus: throw branch failed at step 1 'construct entity' -- throw entity class not found or constructor signature mismatch"
                    + "(see reflection-layer logs above for constructor resolution details and the full list of public constructors of this class)");
            return false;
        }
        Object anchor;
        try {
            anchor = ctor.newInstance(level, owner);
        } catch (InvocationTargetException ite) {
            Throwable cause = ite.getCause() != null ? ite.getCause() : ite;
            SafeGuard.warn("Ceraunus: throw branch failed at step 1 'construct entity' -- constructor threw internally (caught): "
                    + cause.getClass().getName() + " / " + cause.getMessage(), cause);
            return false;
        } catch (Throwable t) {
            SafeGuard.warn("Ceraunus: throw branch failed at step 1 'construct entity' -- reflection call error (caught): "
                    + t.getClass().getName() + " / " + t.getMessage(), t);
            return false;
        }
        if (!(anchor instanceof Entity entity)) {
            SafeGuard.warn("Ceraunus: throw branch failed at step 1 'construct entity' -- reflected object is not an Entity, throw abandoned: "
                    + anchor.getClass().getName());
            return false;
        }

        // —— 第 ② 步：设置伤害（原版 AbstractArrow.setBaseDamage(double)，直接调用 + 反射兜底）——
        if (!applyBaseDamage(entity, baseDamage, nowTick)) {
            return false;
        }

        // —— 第 ③ 步：设置弹道（原版 Projectile.shootFromRotation 是 public，直接调用）——
        try {
            if (entity instanceof net.minecraft.world.entity.projectile.Projectile projectile) {
                projectile.shootFromRotation(owner, xRot, yRot, 0.0F, velocity, 1.0F);
            } else {
                // 兜底：实体不是 Projectile（理论上不可能，灾变实体继承 AbstractArrow）
                java.lang.reflect.Method shoot = entity.getClass().getMethod("shootFromRotation",
                        Entity.class, float.class, float.class, float.class, float.class, float.class);
                shoot.invoke(entity, owner, xRot, yRot, 0.0F, velocity, 1.0F);
                SafeGuard.info("Ceraunus: step 3 trajectory setup used reflection fallback (entity is not a Projectile)");
            }
        } catch (Throwable t) {
            SafeGuard.warn("Ceraunus: throw branch failed at step 3 'set trajectory' -- shootFromRotation call error (caught): "
                    + t.getClass().getName() + " / " + t.getMessage(), t);
            return false;
        }

        // —— 第 ④ 步：加入世界 ——
        boolean added = false;
        try {
            added = level.addFreshEntity(entity);
        } catch (Throwable t) {
            SafeGuard.warn("Ceraunus: throw branch failed at step 4 'add to world' -- addFreshEntity threw (caught): "
                    + t.getClass().getName() + " / " + t.getMessage(), t);
            return false;
        }
        if (!added) {
            SafeGuard.warn("Ceraunus: throw branch failed at step 4 'add to world' -- addFreshEntity returned false"
                    + "(entity rejected by the world, position " + String.format("%.1f, %.1f, %.1f",
                            entity.getX(), entity.getY(), entity.getZ()) + ")");
            return false;
        }
        return true;
    }

    /**
     * 给投掷锚戟设置伤害：优先按原版 AbstractArrow 直接调用（setBaseDamage 参数是 double，
     * float 自动加宽）；实体不是 AbstractArrow 或调用异常时，反射找名字 setBaseDamage、
     * 单个 float/double 参数的方法兜底。失败打独立日志并返回 false。
     */
    private static boolean applyBaseDamage(Entity entity, float baseDamage, long nowTick) {
        try {
            if (entity instanceof net.minecraft.world.entity.projectile.AbstractArrow arrow) {
                arrow.setBaseDamage(baseDamage); // float 自动加宽为 double，与灾变源码同口径
                return true;
            }
        } catch (Throwable directFail) {
            SafeGuard.warnThrottled("mce_cn_dmg_direct_" + entity.getId(), nowTick,
                    "Ceraunus: step 2 direct call to AbstractArrow.setBaseDamage failed, switching to reflection fallback (caught): "
                            + directFail.getClass().getName() + " / " + directFail.getMessage());
        }
        // 反射兜底：按方法名找单参 float/double 版本（两种都试，参数再按取值精度回填）
        try {
            Method found = null;
            for (Method m : entity.getClass().getMethods()) {
                if (!"setBaseDamage".equals(m.getName())) {
                    continue;
                }
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 1 && (p[0] == float.class || p[0] == double.class)) {
                    found = m;
                    if (p[0] == float.class) {
                        break; // 有 float 版优先 float（不会精度回填），否则保留 double 版
                    }
                }
            }
            if (found != null) {
                Class<?> param = found.getParameterTypes()[0];
                if (param == float.class) {
                    found.invoke(entity, baseDamage);
                } else {
                    found.invoke(entity, (double) baseDamage);
                }
                SafeGuard.info("Ceraunus: step 2 damage setup used reflection fallback (setBaseDamage(" + param.getSimpleName() + "))");
                return true;
            }
            StringBuilder sb = new StringBuilder();
            for (Method m : entity.getClass().getMethods()) {
                if ("setBaseDamage".equals(m.getName())) {
                    sb.append("\n  - ").append(m);
                }
            }
            SafeGuard.warn("Ceraunus: throw branch failed at step 2 'set damage' -- no setBaseDamage method found on the entity"
                    + "(existing same-name methods:" + (sb.length() == 0 ? " none" : sb) + ")");
            return false;
        } catch (Throwable t) {
            SafeGuard.warn("Ceraunus: throw branch failed at step 2 'set damage' -- reflection fallback also failed (caught): "
                    + t.getClass().getName() + " / " + t.getMessage(), t);
            return false;
        }
    }

    private static final String WAVE_CLASS_NAME =
            "com.github.L_Ender.cataclysm.entity.effect.Wave_Entity";
    private static final Class<?>[] WAVE_PARAMS = {
            Level.class, LivingEntity.class, int.class, float.class,
    };

    private static volatile Constructor<?> waveCtor = null;
    private static volatile boolean waveProbed = false;

    private static synchronized Constructor<?> resolveWaveCtor() {
        if (waveProbed) {
            return waveCtor;
        }
        waveProbed = true;
        Class<?> cls;
        try {
            cls = Class.forName(WAVE_CLASS_NAME);
        } catch (Throwable t) {
            waveCtor = null;
            SafeGuard.warn("Ceraunus reflection layer: Wave_Entity not found (Cataclysm not installed/class name changed), wave cancelled: "
                    + t.getClass().getSimpleName() + " / " + t.getMessage());
            return null;
        }
        try {
            waveCtor = cls.getConstructor(WAVE_PARAMS);
            SafeGuard.info("Ceraunus reflection layer: Wave_Entity constructor resolved exactly (4 args, matches Cataclysm source)");
            return waveCtor;
        } catch (Throwable exactFail) {
            try {
                for (Constructor<?> c : cls.getConstructors()) {
                    Class<?>[] p = c.getParameterTypes();
                    if (p.length == 4 && p[0] == Level.class && p[1] == LivingEntity.class) {
                        waveCtor = c;
                        SafeGuard.warn("Ceraunus reflection layer: exact signature mismatch, loosely matched a constructor: " + c);
                        return waveCtor;
                    }
                }
            } catch (Throwable scanFail) {
                exactFail = scanFail;
            }
            StringBuilder sb = new StringBuilder();
            try {
                for (Constructor<?> c : cls.getConstructors()) {
                    sb.append("\n  - ").append(c);
                }
            } catch (Throwable ignored) {
            }
            waveCtor = null;
            SafeGuard.warn("Ceraunus reflection layer: Wave_Entity constructor mismatch (Cataclysm version may have changed). Existing public constructors:" + sb,
                    exactFail);
            return null;
        }
    }

    /** 波浪构造器是否已解析（/mce ceraunus status 自检用） */
    public static boolean isWaveResolved() {
        return resolveWaveCtor() != null;
    }

    /**
     * 反射生成一道灾变扇形波浪并放进世界（流程照抄 Ceraunus.releaseUsing 潜行分支的单道循环体）。
     * 伤害与潮湿叠加由实体自身每 tick 结算（damageSources().mobProjectile(this, owner)）。
     *
     * @param yRotDegrees 波浪朝向（MC 口径；原版公式 -(atan2(dx,dz)×180/π) 已由调用方算好）
     * @param life        生命周期 tick（原版 60）
     * @return 成功加入世界返回 true；灾变没装/构造器不对/加入失败返回 false，绝不抛出
     */
    public static boolean spawnWave(ServerLevel level, LivingEntity shooter,
                                    double x, double y, double z,
                                    float yRotDegrees, int life, float damage, long nowTick) {
        Constructor<?> ctor = resolveWaveCtor();
        if (ctor == null) {
            return false;
        }
        try {
            Object wave = ctor.newInstance(level, shooter, life, damage);
            if (!(wave instanceof Entity entity)) {
                SafeGuard.warn("Ceraunus: reflected wave is not an Entity, placement abandoned: " + wave.getClass().getName());
                return false;
            }
            entity.setPos(x, y, z);
            entity.getClass().getMethod("setState", int.class).invoke(entity, 1);
            entity.setYRot(yRotDegrees);
            return level.addFreshEntity(entity);
        } catch (InvocationTargetException ite) {
            Throwable cause = ite.getCause() != null ? ite.getCause() : ite;
            SafeGuard.warn("Ceraunus: wave constructor threw internally (caught, no gameplay impact): "
                    + cause.getClass().getName() + " / " + cause.getMessage(), cause);
            return false;
        } catch (Throwable t) {
            SafeGuard.warn("Ceraunus: wave reflection call failed (caught, no gameplay impact): "
                    + t.getClass().getName() + " / " + t.getMessage(), t);
            return false;
        }
    }

    private static volatile boolean ceraunusConfigProbed = false;
    private static volatile double ceraunusWaveDamage = 6.0D;

    /** 反射直读 CMCommonConfig.Ceraunus 的静态字段（可能被灾变 TOML 覆盖），读不到用源码默认值 */
    private static synchronized void probeCeraunusConfig() {
        if (ceraunusConfigProbed) {
            return;
        }
        ceraunusConfigProbed = true;
        try {
            Class<?> inner = Class.forName("com.github.L_Ender.cataclysm.config.CMCommonConfig$Ceraunus");
            ceraunusWaveDamage = inner.getField("waveDamage").getDouble(null);
            SafeGuard.info("Ceraunus reflection layer: CMCommonConfig.Ceraunus read (waveDamage=" + ceraunusWaveDamage + ")");
        } catch (Throwable t) {
            ceraunusWaveDamage = 6.0D;
            SafeGuard.warn("Ceraunus reflection layer: cannot read CMCommonConfig.Ceraunus (Cataclysm not installed/version changed), "
                    + "using source default waveDamage=6.0: "
                    + t.getClass().getSimpleName() + " / " + t.getMessage());
        }
    }

    /** 波浪每次伤害（灾变 CMCommonConfig.Ceraunus.waveDamage，默认 6.0） */
    public static float ceraunusWaveDamage() {
        probeCeraunusConfig();
        return (float) ceraunusWaveDamage;
    }

    /** 霆浪锚戟波浪施放音效（查不到返回 null = 没装灾变，跳过音效不报错） */
    public static SoundEvent getHeavySmashSound() {
        return lookupSound(HEAVY_SMASH_SOUND);
    }

    private static long gameTime(Entity entity) {
        try {
            return entity.level().getGameTime();
        } catch (Throwable t) {
            return -1L;
        }
    }

    private static Class<?> strikeClass() {
        try {
            return Class.forName(STRIKE_CLASS_NAME);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 懒解析构造器：先按灾变源码签名精确找，找不到再宽松匹配（首参 Level、末参 LivingEntity 的 13 参公开构造器） */
    private static synchronized Constructor<?> resolveCtor() {
        if (ctorProbed) {
            return strikeCtor;
        }
        ctorProbed = true;
        Class<?> cls = strikeClass();
        if (cls == null) {
            SafeGuard.warn("Cataclysm reflection layer: Cataclysm pillar of fire entity class not found " + STRIKE_CLASS_NAME + "(Cataclysm not installed, or the class name changed in this version)");
            return null;
        }
        try {
            strikeCtor = cls.getConstructor(EXPECTED_PARAMS);
            SafeGuard.info("Cataclysm reflection layer: Cataclysm pillar of fire constructor matched exactly (13 args, signature matches source)");
            return strikeCtor;
        } catch (Throwable exactFail) {
            // 宽松匹配
            try {
                for (Constructor<?> c : cls.getConstructors()) {
                    Class<?>[] p = c.getParameterTypes();
                    if (p.length == 13 && p[0] == Level.class && p[12] == LivingEntity.class) {
                        strikeCtor = c;
                        SafeGuard.warn("Cataclysm reflection layer: exact signature mismatch, using the loosely matched constructor: " + c);
                        return strikeCtor;
                    }
                }
            } catch (Throwable scanFail) {
                exactFail = scanFail;
            }
            StringBuilder sb = new StringBuilder();
            try {
                for (Constructor<?> c : cls.getConstructors()) {
                    sb.append("\n  - ").append(c);
                }
            } catch (Throwable ignored) {
            }
            SafeGuard.warn("Cataclysm reflection layer: Cataclysm is installed but the pillar of fire constructor mismatches (Cataclysm may have changed the signature). Existing public constructors:" + sb,
                    exactFail);
            return null;
        }
    }

    /**
     * 反射生成一个灾变火柱实体并放进世界。
     * 地面扫描逻辑复刻灾变源码：从 maxY 往下找第一格“顶面坚固”的方块，
     * 找到就把它站的高度作为火柱落点；找不到（悬空/深渊）返回 false。
     *
     * @return 成功生成返回 true；任何失败（灾变没装/构造器不对/参数异常）返回 false 并写日志，绝不抛出
     */
    public static boolean spawnFlameStrike(ServerLevel level,
                                           double x, double z,
                                           int minY, double maxY,
                                           float rotationRadians,
                                           int duration, int wait, int delay,
                                           float radius, float damage, float hpPercent,
                                           LivingEntity caster, long nowTick) {
        Constructor<?> ctor = resolveCtor();
        if (ctor == null) {
            return false;
        }
        // —— 地面扫描（与灾变 The_Incinerator.spawnFlameStrike 同逻辑，用的是原版 API）——
        BlockPos blockpos = BlockPos.containing(x, maxY, z);
        boolean foundGround = false;
        double shapeTop = 0.0D;
        int guard = 0;
        do {
            BlockPos below = blockpos.below();
            BlockState state = level.getBlockState(below);
            if (state.isFaceSturdy(level, below, Direction.UP)) {
                if (!level.isEmptyBlock(blockpos)) {
                    VoxelShape shape = level.getBlockState(blockpos).getCollisionShape(level, blockpos);
                    if (!shape.isEmpty()) {
                        shapeTop = shape.max(Direction.Axis.Y);
                    }
                }
                foundGround = true;
                break;
            }
            blockpos = blockpos.below();
            guard++;
        } while (blockpos.getY() >= minY && guard < 96);

        if (!foundGround) {
            SafeGuard.warnThrottled("mce_ground" + level.dimension().location(), nowTick,
                    "Incinerator: no solid ground found within 96 blocks underfoot, this pillar of fire landing point skipped (x=" + x + ", z=" + z + ")");
            return false;
        }

        try {
            Object flame = ctor.newInstance(level, x, blockpos.getY() + shapeTop, z,
                    rotationRadians, duration, wait, delay,
                    radius, damage, hpPercent, false, caster);
            if (flame instanceof Entity entity) {
                return level.addFreshEntity(entity);
            }
            SafeGuard.warn("Incinerator: reflected object is not an Entity, placement abandoned: " + flame.getClass().getName());
            return false;
        } catch (InvocationTargetException ite) {
            Throwable cause = ite.getCause() != null ? ite.getCause() : ite;
            SafeGuard.warn("Incinerator: pillar of fire constructor threw internally (caught, no gameplay impact): "
                    + cause.getClass().getName() + " / " + cause.getMessage(), cause);
            return false;
        } catch (Throwable t) {
            SafeGuard.warn("Incinerator: reflection call failed (caught, no gameplay impact): "
                    + t.getClass().getName() + " / " + t.getMessage(), t);
            return false;
        }
    }

    // ════════════════════════ 震屏（炎葬释放表现） ════════════════════════

    private static volatile Method screenShakeMethod = null;
    private static volatile boolean screenShakeProbed = false;

    /** 调灾变 ScreenShake_Entity.ScreenShake(Level, Vec3, float radius, float magnitude, int duration, int fadeDuration)。失败只记日志 */
    public static void triggerScreenShake(Level level, net.minecraft.world.phys.Vec3 pos,
                                          float radius, float magnitude, int duration, int fadeDuration) {
        try {
            Method m = resolveScreenShake();
            if (m == null) {
                return;
            }
            m.invoke(null, level, pos, radius, magnitude, duration, fadeDuration);
        } catch (InvocationTargetException ite) {
            Throwable cause = ite.getCause() != null ? ite.getCause() : ite;
            SafeGuard.warnThrottled("mce_shake", level.getGameTime(),
                    "screen shake reflection call internal error (caught): " + cause.getClass().getSimpleName() + " / " + cause.getMessage());
        } catch (Throwable t) {
            SafeGuard.warnThrottled("mce_shake", level.getGameTime(),
                    "screen shake reflection call failed (caught, no gameplay impact): " + t.getClass().getSimpleName() + " / " + t.getMessage());
        }
    }

    private static synchronized Method resolveScreenShake() {
        if (screenShakeProbed) {
            return screenShakeMethod;
        }
        screenShakeProbed = true;
        try {
            Class<?> cls = Class.forName(SCREEN_SHAKE_CLASS_NAME);
            screenShakeMethod = cls.getMethod("ScreenShake", Level.class,
                    net.minecraft.world.phys.Vec3.class,
                    float.class, float.class, int.class, int.class);
            SafeGuard.info("Cataclysm reflection layer: screen shake method resolved (" + SCREEN_SHAKE_CLASS_NAME + ".ScreenShake)");
        } catch (Throwable t) {
            screenShakeMethod = null;
            SafeGuard.warn("Cataclysm reflection layer: screen shake method not found (Cataclysm not installed or signature changed), no screen shake on cast: "
                    + t.getClass().getSimpleName() + " / " + t.getMessage());
        }
        return screenShakeMethod;
    }

    /** 震屏反射层是否可用（/mce 状态自检用） */
    public static boolean isScreenShakeResolved() {
        resolveScreenShake();
        return screenShakeMethod != null;
    }

    // ════════════════════════ 幻影战戟实体（断魂战戟） ════════════════════════
    //
    // 灾变源码 Phantom_Halberd_Entity：公开构造器
    //   (Level, double x, double y, double z, float rotation弧度, int warmupDelayTicks,
    //    LivingEntity caster, float damage)
    // 构造器内部自己 setYRot(rotation * 180/PI)、setCaster(caster)，
    // caster 参数是 LivingEntity → 同伴可以直接当主人传入；
    // 伤害由实体自己在 warmup 结束后按 damage 结算（maledictio_magicae 伤害源，
    // causing entity = caster），所以友军保护在事件层 AllyDamageGuard 用
    // SoulRenderSkill 的召唤窗口拦，反射层只管“把战戟按灾变原版样子立起来”。

    private static final String PHANTOM_HALBERD_CLASS_NAME =
            "com.github.L_Ender.cataclysm.entity.projectile.Phantom_Halberd_Entity";
    private static final Class<?>[] PHANTOM_HALBERD_PARAMS = {
            Level.class,
            double.class, double.class, double.class,
            float.class,
            int.class,
            LivingEntity.class,
            float.class,
    };

    private static volatile Constructor<?> phantomHalberdCtor = null;
    private static volatile boolean phantomHalberdProbed = false;

    private static synchronized Constructor<?> resolvePhantomHalberdCtor() {
        if (phantomHalberdProbed) {
            return phantomHalberdCtor;
        }
        phantomHalberdProbed = true;
        Class<?> cls;
        try {
            cls = Class.forName(PHANTOM_HALBERD_CLASS_NAME);
        } catch (Throwable t) {
            phantomHalberdCtor = null;
            SafeGuard.warn("Soul Render reflection layer: Phantom_Halberd_Entity not found (Cataclysm not installed/class name changed), spiral summon cancelled: "
                    + t.getClass().getSimpleName() + " / " + t.getMessage());
            return null;
        }
        try {
            phantomHalberdCtor = cls.getConstructor(PHANTOM_HALBERD_PARAMS);
            SafeGuard.info("Soul Render reflection layer: Phantom_Halberd_Entity constructor resolved exactly (8 args, matches Cataclysm source)");
            return phantomHalberdCtor;
        } catch (Throwable exactFail) {
            try {
                for (Constructor<?> c : cls.getConstructors()) {
                    Class<?>[] p = c.getParameterTypes();
                    if (p.length == 8 && p[0] == Level.class && p[6] == LivingEntity.class) {
                        phantomHalberdCtor = c;
                        SafeGuard.warn("Soul Render reflection layer: exact signature mismatch, constructor loosely matched: " + c);
                        return phantomHalberdCtor;
                    }
                }
            } catch (Throwable scanFail) {
                exactFail = scanFail;
            }
            StringBuilder sb = new StringBuilder();
            try {
                for (Constructor<?> c : cls.getConstructors()) {
                    sb.append("\n  - ").append(c);
                }
            } catch (Throwable ignored) {
            }
            phantomHalberdCtor = null;
            SafeGuard.warn("Soul Render reflection layer: Phantom_Halberd_Entity constructor mismatch (Cataclysm version may have changed). Existing public constructors:" + sb,
                    exactFail);
            return null;
        }
    }

    /** 幻影战戟构造器是否已解析（/mce soul_render status 自检用） */
    public static boolean isPhantomHalberdResolved() {
        return resolvePhantomHalberdCtor() != null;
    }

    /**
     * 反射生成一个灾变幻影战戟并放进世界（地面扫描逻辑复刻灾变 spawnHalberd：
     * 从 maxY 往下找第一格“顶面坚固”的方块，落点 = 方块顶面 + 碰撞箱高度）。
     *
     * @param rotationRadians 朝向弧度（构造器内部 ×180/π 转回角度）
     * @param warmupDelayTicks 起手延迟 tick（原版螺旋是 1*(i+1)）
     * @return 成功返回 true；灾变没装/构造器不对/落点悬空返回 false，绝不抛出
     */
    public static boolean spawnPhantomHalberd(ServerLevel level,
                                              double x, double z,
                                              int minY, double maxY,
                                              float rotationRadians,
                                              int warmupDelayTicks,
                                              LivingEntity caster,
                                              float damage,
                                              long nowTick) {
        Constructor<?> ctor = resolvePhantomHalberdCtor();
        if (ctor == null) {
            return false;
        }
        BlockPos blockpos = BlockPos.containing(x, maxY, z);
        boolean foundGround = false;
        double shapeTop = 0.0D;
        int guard = 0;
        do {
            BlockPos below = blockpos.below();
            BlockState state = level.getBlockState(below);
            if (state.isFaceSturdy(level, below, Direction.UP)) {
                if (!level.isEmptyBlock(blockpos)) {
                    VoxelShape shape = level.getBlockState(blockpos).getCollisionShape(level, blockpos);
                    if (!shape.isEmpty()) {
                        shapeTop = shape.max(Direction.Axis.Y);
                    }
                }
                foundGround = true;
                break;
            }
            blockpos = blockpos.below();
            guard++;
        } while (blockpos.getY() >= Mth.floor(minY) - 1 && guard < 96);

        if (!foundGround) {
            SafeGuard.warnThrottled("mce_sr_ground" + level.dimension().location(), nowTick,
                    "Soul Render: landing point (" + Mth.floor(x) + ", " + Mth.floor(z) + ") no solid ground found nearby, this phantom halberd skipped");
            return false;
        }

        try {
            Object halberd = ctor.newInstance(level, x, blockpos.getY() + shapeTop, z,
                    rotationRadians, warmupDelayTicks, caster, damage);
            if (halberd instanceof Entity entity) {
                return level.addFreshEntity(entity);
            }
            SafeGuard.warn("Soul Render: reflected object is not an Entity, placement abandoned: " + halberd.getClass().getName());
            return false;
        } catch (InvocationTargetException ite) {
            Throwable cause = ite.getCause() != null ? ite.getCause() : ite;
            SafeGuard.warn("Soul Render: phantom halberd constructor threw internally (caught, no gameplay impact): "
                    + cause.getClass().getName() + " / " + cause.getMessage(), cause);
            return false;
        } catch (Throwable t) {
            SafeGuard.warn("Soul Render: reflection call failed (caught, no gameplay impact): "
                    + t.getClass().getName() + " / " + t.getMessage(), t);
            return false;
        }
    }

    // ════════════════════════ 壁垒冲锋附件（方案 A） ════════════════════════

    private static volatile AttachmentType<?> chargeAttachmentType = null;
    private static volatile boolean attachmentProbed = false;
    private static volatile Method attTick = null;
    private static volatile Method attIsCharge = null;
    private static volatile Method attSetCharge = null;
    private static volatile Method attSetTimer = null;
    private static volatile Method attSetEffective = null;
    private static volatile Method attSetKnockback = null;
    private static volatile Method attSetDamage = null;
    private static volatile Method attSetDx = null;
    private static volatile Method attSetDz = null;
    private static volatile boolean attMethodsProbed = false;

    /**
     * 按注册名从 NeoForge 附件注册表取灾变的 charge_attachment。
     * 这一步不需要反射灾变的类，只需要灾变已注册该附件。取不到返回 null（= 壁垒降级）。
     */
    public static AttachmentType<?> getChargeAttachmentType() {
        if (attachmentProbed) {
            return chargeAttachmentType;
        }
        try {
            chargeAttachmentType = NeoForgeRegistries.ATTACHMENT_TYPES
                    .getOptional(CHARGE_ATTACHMENT_NAME).orElse(null);
            if (chargeAttachmentType == null) {
                SafeGuard.warn("Bulwark reflection layer: attachment registry has no " + CHARGE_ATTACHMENT_NAME
                        + "(Cataclysm not installed or the registered name changed), Bulwark of the Flame will use the fallback");
            } else {
                SafeGuard.info("Bulwark reflection layer: got the Cataclysm charge attachment type " + CHARGE_ATTACHMENT_NAME);
            }
        } catch (Throwable t) {
            chargeAttachmentType = null;
            SafeGuard.warn("Bulwark reflection layer: attachment registry query error (caught): " + t.getClass().getSimpleName()
                    + " / " + t.getMessage());
        }
        attachmentProbed = true;
        return chargeAttachmentType;
    }

    /** 壁垒反射层是否可用（附件类型 + 全部 setter/tick 方法都解析成功） */
    public static boolean isChargeAttachmentReady() {
        return getChargeAttachmentType() != null && resolveAttachmentMethods();
    }

    /**
     * 解析 ChargeAttachment 类上的方法（灾变源码里这些 setter 就叫
     * setdamagePerEffectiveCharge / setknockbackSpeedIndex / setdZ 这种小写驼峰，按名解析）。
     * 任一失败时把该类全部公开方法名写进日志，方便对着新版本改。
     */
    private static synchronized boolean resolveAttachmentMethods() {
        if (attMethodsProbed) {
            return attTick != null && attSetCharge != null && attSetTimer != null
                    && attSetEffective != null && attSetKnockback != null
                    && attSetDamage != null && attSetDx != null && attSetDz != null && attIsCharge != null;
        }
        attMethodsProbed = true;
        AttachmentType<?> type = getChargeAttachmentType();
        if (type == null) {
            return false;
        }
        try {
            // 用附件默认值的类型来定位类：对任意实体 getData 一次即可拿到类
            Class<?> cls = Class.forName("com.github.L_Ender.cataclysm.Attachment.ChargeAttachment");
            attTick = cls.getMethod("tick", LivingEntity.class);
            attIsCharge = cls.getMethod("isCharge");
            attSetCharge = cls.getMethod("setCharge", boolean.class);
            attSetTimer = cls.getMethod("setTimer", int.class);
            attSetEffective = cls.getMethod("seteffectiveChargeTime", int.class);
            attSetKnockback = cls.getMethod("setknockbackSpeedIndex", float.class);
            attSetDamage = cls.getMethod("setdamagePerEffectiveCharge", float.class);
            attSetDx = cls.getMethod("setdx", float.class);
            attSetDz = cls.getMethod("setdZ", float.class);
            SafeGuard.info("Bulwark reflection layer: all 9 ChargeAttachment methods resolved (matches Cataclysm source)");
            return true;
        } catch (Throwable t) {
            StringBuilder sb = new StringBuilder();
            try {
                Class<?> cls = Class.forName("com.github.L_Ender.cataclysm.Attachment.ChargeAttachment");
                for (Method m : cls.getMethods()) {
                    sb.append("\n  - ").append(m.getName());
                }
            } catch (Throwable ignored) {
            }
            attTick = null;
            SafeGuard.warn("Bulwark reflection layer: ChargeAttachment method signature mismatch (Cataclysm version may have changed), the Bulwark will use the fallback. "
                    + "Exception: " + t.getClass().getSimpleName() + " / " + t.getMessage()
                    + ". Existing public methods of this class:" + sb, t);
            return false;
        }
    }

    /** 方案 A：给实体挂上冲锋状态。内部先读现有附件对象（getData 会自动建默认实例），再逐个反射 setter。
     *  @return 成功写入返回 true；反射层不可用或异常返回 false（调用方降级） */
    public static boolean startCharge(Entity entity, int timer, float damage, float knockback,
                                      float dx, float dz, long nowTick) {
        AttachmentType<?> type = getChargeAttachmentType();
        if (type == null || !resolveAttachmentMethods()) {
            return false;
        }
        try {
            Object charge = entity.getData(type);
            if (charge == null) {
                return false;
            }
            attSetCharge.invoke(charge, true);
            attSetTimer.invoke(charge, timer);
            attSetEffective.invoke(charge, timer);
            attSetKnockback.invoke(charge, knockback);
            attSetDamage.invoke(charge, damage);
            attSetDx.invoke(charge, dx);
            attSetDz.invoke(charge, dz);
            return true;
        } catch (InvocationTargetException ite) {
            Throwable cause = ite.getCause() != null ? ite.getCause() : ite;
            SafeGuard.warnThrottled("mce_b_att" + entity.getId(), nowTick,
                    "Bulwark: attachment setter internal error (caught): " + cause.getClass().getName()
                            + " / " + cause.getMessage());
            return false;
        } catch (Throwable t) {
            SafeGuard.warnThrottled("mce_b_att" + entity.getId(), nowTick,
                    "Bulwark: attachment write failed (caught): " + t.getClass().getName() + " / " + t.getMessage());
            return false;
        }
    }

    /** 方案 A 驱动：每 tick 调一次 attachment.tick(entity)（补灾变只给 PlayerTickEvent 驱动的那一环）。
     *  @return 附件当前是否仍在冲锋（isCharge），异常返回 false */
    public static boolean tickCharge(Entity entity) {
        AttachmentType<?> type = getChargeAttachmentType();
        if (type == null || !resolveAttachmentMethods()) {
            return false;
        }
        try {
            Object charge = entity.getExistingDataOrNull(type);
            if (charge == null) {
                return false;
            }
            attTick.invoke(charge, entity);
            Object flag = attIsCharge.invoke(charge);
            return flag instanceof Boolean b && b;
        } catch (InvocationTargetException ite) {
            Throwable cause = ite.getCause() != null ? ite.getCause() : ite;
            SafeGuard.warnThrottled("mce_b_tick" + entity.getId(), entity.level().getGameTime(),
                    "Bulwark: attachment tick internal error (caught, window ended): " + cause.getClass().getName()
                            + " / " + cause.getMessage());
            return false;
        } catch (Throwable t) {
            SafeGuard.warnThrottled("mce_b_tick" + entity.getId(), entity.level().getGameTime(),
                    "Bulwark: attachment tick call failed (caught): " + t.getClass().getName() + " / " + t.getMessage());
            return false;
        }
    }

    /** 附件是否已被灾变自身判定结束（撞墙/超时/命中都算）——只读，不推进 */
    public static boolean isAttachmentCharging(Entity entity) {
        AttachmentType<?> type = getChargeAttachmentType();
        if (type == null || !resolveAttachmentMethods()) {
            return false;
        }
        try {
            Object charge = entity.getExistingDataOrNull(type);
            if (charge == null) {
                return false;
            }
            Object flag = attIsCharge.invoke(charge);
            return flag instanceof Boolean b && b;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 实体身上是否还挂着冲锋附件实例（附件不序列化，世界重载后实例会丢）——
     *  用来区分“附件丢失”和“命中/撞墙正常收窗” */
    public static boolean attachmentPresent(Entity entity) {
        AttachmentType<?> type = getChargeAttachmentType();
        if (type == null || !resolveAttachmentMethods()) {
            return false;
        }
        try {
            return entity.getExistingDataOrNull(type) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    // ════════════════════════ Wall_Watcher（壁垒方案 B 兜底） ════════════════════════

    private static volatile Constructor<?> wallWatcherCtor = null;
    private static volatile Method wallWatcherWatch = null;
    private static volatile boolean wallWatcherProbed = false;

    private static synchronized boolean resolveWallWatcher() {
        if (wallWatcherProbed) {
            return wallWatcherCtor != null && wallWatcherWatch != null;
        }
        wallWatcherProbed = true;
        try {
            Class<?> cls = Class.forName(WALL_WATCHER_CLASS_NAME);
            wallWatcherCtor = cls.getConstructor(Level.class, BlockPos.class,
                    int.class, int.class, double.class, float.class,
                    double.class, double.class, LivingEntity.class);
            wallWatcherWatch = cls.getMethod("watch", LivingEntity.class);
            SafeGuard.info("Bulwark reflection layer: Wall_Watcher_Entity constructor and watch method resolved");
            return true;
        } catch (Throwable t) {
            wallWatcherCtor = null;
            SafeGuard.warn("Bulwark reflection layer: Wall_Watcher_Entity not found (Cataclysm not installed/class renamed), fallback damage will have no wall-hit stun: "
                    + t.getClass().getSimpleName() + " / " + t.getMessage());
            return false;
        }
    }

    /** 方案 B：自己造一个 Wall_Watcher 观察实体盯住被击退目标（撞墙补伤害+眩晕的表现白嫖灾变）。
     *  观察实体本身不造成伤害，命中伤害由调用方自己结算。 */
    public static void spawnWallWatcher(Level level, BlockPos pos, int timer, int effectiveChargeTime,
                                        double knockback, float damage, double dx, double dz,
                                        LivingEntity source, List<LivingEntity> watchTargets) {
        try {
            if (!resolveWallWatcher()) {
                return;
            }
            Object watcher = wallWatcherCtor.newInstance(level, pos, timer, effectiveChargeTime,
                    knockback, damage, dx, dz, source);
            if (watcher instanceof Entity entity) {
                for (LivingEntity victim : watchTargets) {
                    wallWatcherWatch.invoke(entity, victim);
                }
                level.addFreshEntity(entity);
            }
        } catch (Throwable t) {
            SafeGuard.warnThrottled("mce_b_watcher", level.getGameTime(),
                    "Bulwark fallback: Wall_Watcher reflection creation failed (caught, damage still settles as usual, just no wall-hit stun): "
                            + t.getClass().getSimpleName() + " / " + t.getMessage());
        }
    }

    /** 方案 B 是否带撞墙特效（状态自检用） */
    public static boolean isWallWatcherResolved() {
        resolveWallWatcher();
        return wallWatcherCtor != null;
    }

    // ════════════════════════ Wall_Watcher 友军拦截（r9：冲锋不击飞友军） ════════════════════════
    //
    // 灾变源码核对：冲锋“击飞”不是 hurt()，而是 Wall_Watcher_Entity.tick 每 tick 对被观察者
    // setMotion(dx*击退指数, 0, dz*击退指数) + hurtMarked，钉着水平速度直到撞墙；
    // 观察者列表在命中那一 tick 于 ChargeAttachment.tick 里通过 watch(target) 定死。
    // 因此拦截方案：命中发生的同一个调用栈里（我们的 tickActive 驱动附件 tick 之后），
    // 反射把 source=施法者 的观察者实体名单里的友军摘掉 → 友军从此不会被钉速度。

    private static volatile Class<?> watcherClassForStrip = null;
    private static volatile Field watcherSourceField = null;
    private static volatile Field watcherWatchedField = null;
    private static volatile Field entryLivingField = null;
    private static volatile boolean watcherFieldsProbed = false;

    private static synchronized void probeWatcherFields() {
        if (watcherFieldsProbed) {
            return;
        }
        watcherFieldsProbed = true;
        try {
            Class<?> cls = Class.forName(WALL_WATCHER_CLASS_NAME);
            watcherSourceField = cls.getDeclaredField("source");
            watcherWatchedField = cls.getDeclaredField("watchedEntities");
            Class<?> entryClass = null;
            for (Class<?> inner : cls.getDeclaredClasses()) {
                if ("YUnchangedLivingEntity".equals(inner.getSimpleName())) {
                    entryClass = inner;
                    break;
                }
            }
            if (entryClass == null) {
                throw new ClassNotFoundException("Wall_Watcher_Entity$YUnchangedLivingEntity");
            }
            entryLivingField = entryClass.getDeclaredField("livingEntity");
            watcherSourceField.setAccessible(true);
            watcherWatchedField.setAccessible(true);
            entryLivingField.setAccessible(true);
            watcherClassForStrip = cls;
            SafeGuard.info("Bulwark reflection layer: Wall_Watcher ally interception fields resolved (source / watchedEntities / YUnchangedLivingEntity.livingEntity)");
        } catch (Throwable t) {
            watcherClassForStrip = null;
            watcherSourceField = null;
            watcherWatchedField = null;
            entryLivingField = null;
            SafeGuard.warn("Bulwark reflection layer: failed to resolve Wall_Watcher fields (Cataclysm not installed/inner class or field renamed), "
                    + "knockback cannot be stripped when the charge hits an ally (damage is still caught by the event layer): "
                    + t.getClass().getSimpleName() + " / " + t.getMessage());
        }
    }

    /** 友军摘除反射层是否可用（/mce bulwark status 自检用） */
    public static boolean isWatcherStripReady() {
        probeWatcherFields();
        return watcherClassForStrip != null;
    }

    /**
     * 壁垒友军保护：扫描本维度里由该施法者冲锋生成的 Wall_Watcher，
     * 把观察名单里的友军（AllyCheck.isAlly 成立）整条摘掉，并清掉可能已经钉上的水平速度。
     *
     * <p>只影响“位移”这一环：敌方目标不受影响，冲锋速度/收窗/撞墙眩晕全按灾变原版走。
     * 由 BulwarkSkill.tickActive 在驱动附件 tick 的同一调用栈里调用，命中当 tick 即可完成摘除，
     * 观察者实体的第一次 tick 还没发生，友军一帧都不会被移动。</p>
     *
     * @return 实际摘除条数；-1 = 反射层不可用（调用方按原版行为继续，不崩）
     */
    public static int stripAlliesFromWatchers(ServerLevel level, LivingEntity caster, long now) {
        probeWatcherFields();
        if (watcherClassForStrip == null) {
            return -1;
        }
        try {
            AABB area = caster.getBoundingBox().inflate(24.0D);
            List<Entity> watchers = level.getEntities(caster, area, watcherClassForStrip::isInstance);
            int removed = 0;
            for (Entity watcher : watchers) {
                try {
                    Object source = watcherSourceField.get(watcher);
                    if (source != caster) {
                        continue; // 只管本次冲锋自己生成的观察者
                    }
                    Object listObj = watcherWatchedField.get(watcher);
                    if (!(listObj instanceof List)) {
                        continue;
                    }
                    @SuppressWarnings("unchecked")
                    List<Object> entries = (List<Object>) listObj;
                    for (Object entry : new ArrayList<>(entries)) {
                        try {
                            Object held = entryLivingField.get(entry);
                            if (held instanceof LivingEntity victim && AllyCheck.isAlly(caster, victim)) {
                                entries.remove(entry); // 条目无 equals 重写 = 按引用移除
                                victim.setDeltaMovement(0.0D, victim.getDeltaMovement().y(), 0.0D);
                                victim.hurtMarked = true;
                                removed++;
                            }
                        } catch (Throwable perEntry) {
                            SafeGuard.warnThrottled("mce_b_strip_entry", now,
                                    "Bulwark ally interception: failed to process watch list entry (this entry skipped): "
                                            + perEntry.getClass().getSimpleName() + " / " + perEntry.getMessage());
                        }
                    }
                } catch (Throwable perWatcher) {
                    SafeGuard.warnThrottled("mce_b_strip_watcher", now,
                            "Bulwark ally interception: failed to process watcher entity (skipped): "
                                    + perWatcher.getClass().getSimpleName() + " / " + perWatcher.getMessage());
                }
            }
            if (removed > 0) {
                SafeGuard.info("Bulwark ally protection: removed " + removed
                        + " allies (no longer velocity-pinned and flung, wall-hit bonus damage/stun will not hit them either)");
            }
            return removed;
        } catch (Throwable t) {
            SafeGuard.warnThrottled("mce_b_strip", now,
                    "Bulwark ally interception: Wall_Watcher scan error (caught, continuing with vanilla behavior this time): "
                            + t.getClass().getSimpleName() + " / " + t.getMessage());
            return -1;
        }
    }

    // ════════════════════════ 神怒长槊：鸣雷槊实体 + 配置数值（r16） ════════════════════════
    //
    // 灾变源码核对（Astrape.releaseUsing L48-79）：
    // - 鸣雷槊实体 com.github.L_Ender.cataclysm.entity.projectile.Lightning_Spear_Entity，
    //   公开构造器 (LivingEntity owner, Vec3 direction, Level level, float damage, double accel)
    //   —— 首参就是 LivingEntity，同伴可直接当 owner；伤害结算内置在实体 onHitEntity，
    //   撞方块后自己生成静电场（Lightning_Area_Effect_Entity）+ 落雷爆发（Lightning_Storm_Entity）。
    // - 释放流程（照抄）：setYRot/setXRot → setPos(getX(), owner.getY(0.75), getZ())
    //   → setAreaDamage(2.0F) → setAreaRadius(1.0F) → addFreshEntity。
    // - 数值来自 CMCommonConfig.Astrape 静态字段（damage=11.0、areaDamage=2.0、cooldown=60），
    //   可能被灾变自己的 TOML 覆盖 → 反射直读，读不到退化为源码默认值。

    private static final String LIGHTNING_SPEAR_CLASS_NAME =
            "com.github.L_Ender.cataclysm.entity.projectile.Lightning_Spear_Entity";
    private static final Class<?>[] LIGHTNING_SPEAR_PARAMS = {
            LivingEntity.class, net.minecraft.world.phys.Vec3.class,
            Level.class, float.class, double.class,
    };

    private static volatile Constructor<?> lightningSpearCtor = null;
    private static volatile boolean lightningSpearProbed = false;

    private static synchronized Constructor<?> resolveLightningSpearCtor() {
        if (lightningSpearProbed) {
            return lightningSpearCtor;
        }
        lightningSpearProbed = true;
        Class<?> cls;
        try {
            cls = Class.forName(LIGHTNING_SPEAR_CLASS_NAME);
        } catch (Throwable t) {
            lightningSpearCtor = null;
            SafeGuard.warn("Astrape reflection layer: Lightning_Spear_Entity not found (Cataclysm not installed/class name changed), launch cancelled: "
                    + t.getClass().getSimpleName() + " / " + t.getMessage());
            return null;
        }
        try {
            lightningSpearCtor = cls.getConstructor(LIGHTNING_SPEAR_PARAMS);
            SafeGuard.info("Astrape reflection layer: Lightning_Spear_Entity constructor resolved exactly (5 args, matches Cataclysm source)");
            return lightningSpearCtor;
        } catch (Throwable exactFail) {
            try {
                for (Constructor<?> c : cls.getConstructors()) {
                    Class<?>[] p = c.getParameterTypes();
                    if (p.length == 5 && p[0] == LivingEntity.class
                            && p[1] == net.minecraft.world.phys.Vec3.class && p[2] == Level.class) {
                        lightningSpearCtor = c;
                        SafeGuard.warn("Astrape reflection layer: exact signature mismatch, constructor loosely matched: " + c);
                        return lightningSpearCtor;
                    }
                }
            } catch (Throwable scanFail) {
                exactFail = scanFail;
            }
            StringBuilder sb = new StringBuilder();
            try {
                for (Constructor<?> c : cls.getConstructors()) {
                    sb.append("\n  - ").append(c);
                }
            } catch (Throwable ignored) {
            }
            lightningSpearCtor = null;
            SafeGuard.warn("Astrape reflection layer: Lightning_Spear_Entity constructor mismatch (Cataclysm version may have changed). Existing public constructors:" + sb,
                    exactFail);
            return null;
        }
    }

    /** 鸣雷槊构造器是否已解析（/mce astrape status 自检用） */
    public static boolean isLightningSpearResolved() {
        return resolveLightningSpearCtor() != null;
    }

    private static volatile boolean astrapeConfigProbed = false;
    private static volatile double astrapeDamage = 11.0D;
    private static volatile double astrapeAreaDamage = 2.0D;

    /** 反射直读 CMCommonConfig.Astrape 的静态字段（可能被灾变 TOML 覆盖），读不到用源码默认值 */
    private static synchronized void probeAstrapeConfig() {
        if (astrapeConfigProbed) {
            return;
        }
        astrapeConfigProbed = true;
        try {
            Class<?> inner = Class.forName("com.github.L_Ender.cataclysm.config.CMCommonConfig$Astrape");
            astrapeDamage = inner.getField("damage").getDouble(null);
            astrapeAreaDamage = inner.getField("areaDamage").getDouble(null);
            SafeGuard.info("Astrape reflection layer: CMCommonConfig.Astrape read (damage=" + astrapeDamage
                    + ", areaDamage=" + astrapeAreaDamage + ")");
        } catch (Throwable t) {
            astrapeDamage = 11.0D;
            astrapeAreaDamage = 2.0D;
            SafeGuard.warn("Astrape reflection layer: cannot read CMCommonConfig.Astrape (Cataclysm not installed/version changed), "
                    + "using source defaults damage=11.0 / areaDamage=2.0: "
                    + t.getClass().getSimpleName() + " / " + t.getMessage());
        }
    }

    /** 鸣雷槊直接伤害（灾变 CMCommonConfig.Astrape.damage，默认 11.0） */
    public static float astrapeDirectDamage() {
        probeAstrapeConfig();
        return (float) astrapeDamage;
    }

    /** 静电场每次伤害（灾变 CMCommonConfig.Astrape.areaDamage，默认 2.0） */
    public static float astrapeAreaDamage() {
        probeAstrapeConfig();
        return (float) astrapeAreaDamage;
    }

    /**
     * 反射生成一个灾变鸣雷槊并放进世界（流程照抄 Astrape.releaseUsing 第 5~8 步）。
     *
     * @param direction 已归一化的飞行方向（同伴瞄准向量）
     * @param spawnY    出膛高度（原版 = owner.getY(0.75)）
     * @return 成功加入世界返回 true；灾变没装/构造器不对/加入失败返回 false，绝不抛出
     */
    public static boolean spawnLightningSpear(ServerLevel level, LivingEntity owner,
                                              net.minecraft.world.phys.Vec3 direction,
                                              float damage, double accel,
                                              float areaDamage, float areaRadius,
                                              float yRotDegrees, float xRotDegrees,
                                              double spawnY, long nowTick) {
        Constructor<?> ctor = resolveLightningSpearCtor();
        if (ctor == null) {
            return false;
        }
        try {
            Object spear = ctor.newInstance(owner, direction.normalize(), level, damage, accel);
            if (!(spear instanceof Entity entity)) {
                SafeGuard.warn("Astrape: reflected object is not an Entity, launch abandoned: " + spear.getClass().getName());
                return false;
            }
            entity.setYRot(yRotDegrees);
            entity.setXRot(xRotDegrees);
            entity.setPos(entity.getX(), spawnY, entity.getZ());
            invokeFloatSetter(entity, "setAreaDamage", areaDamage, "lightning spear");
            invokeFloatSetter(entity, "setAreaRadius", areaRadius, "lightning spear");
            return level.addFreshEntity(entity);
        } catch (InvocationTargetException ite) {
            Throwable cause = ite.getCause() != null ? ite.getCause() : ite;
            SafeGuard.warn("Astrape: lightning spear constructor threw internally (caught, no gameplay impact): "
                    + cause.getClass().getName() + " / " + cause.getMessage(), cause);
            return false;
        } catch (Throwable t) {
            SafeGuard.warn("Astrape: reflection call failed (caught, no gameplay impact): "
                    + t.getClass().getName() + " / " + t.getMessage(), t);
            return false;
        }
    }

    /** 按名调用 float 单参 setter（setAreaDamage/setAreaRadius 这类灾变自有方法）；失败只记日志不抛出 */
    private static void invokeFloatSetter(Entity entity, String name, float value, String label) {
        try {
            Method setter = entity.getClass().getMethod(name, float.class);
            setter.invoke(entity, value);
        } catch (Throwable t) {
            SafeGuard.warnThrottled("mce_asp_setter_" + name, gameTime(entity),
                    label + ": " + name + " reflection call failed (caught, this value falls back to the entity default): "
                            + t.getClass().getSimpleName() + " / " + t.getMessage());
        }
    }

    // —— 神怒长槊友军拦截的“动态窗口”判定：伤害源的直接实体是不是该同伴的灾变雷电实体 ——
    //
    // 静电场（Lightning_Area_Effect_Entity）伤害源 = causeLightningDamage(静电场, caster)，
    // 事件层 getDirectEntity() 就是静电场本身 → 直接按类名 + owner 比对识别，无需扫描全维度；
    // 静电场还在场（每 5 tick 结算一次）时不断续窗，直到实体自灭，友军全程免疫。

    /** 灾变“雷电链”实体类名（鸣雷槊 / 静电场 / 落雷爆发 / 掣雷锤本体 / 虚空符文）——按具体类全名精确比对 */
    private static final Set<String> LIGHTNING_ENTITY_CLASS_NAMES = Set.of(
            "com.github.L_Ender.cataclysm.entity.projectile.Lightning_Spear_Entity",
            "com.github.L_Ender.cataclysm.entity.effect.Lightning_Area_Effect_Entity",
            "com.github.L_Ender.cataclysm.entity.effect.Lightning_Storm_Entity",
            // r21 掣雷巨锤：直接命中的伤害源 directEntity 是锤子实体本身（owner 走 AbstractArrow.getOwner）
            "com.github.L_Ender.cataclysm.entity.projectile.Brontes_Entity",
            // r21 虚空锻锤：符文伤害源 indirectMagic(rune, caster) 的 directEntity 是符文实体本身
            "com.github.L_Ender.cataclysm.entity.projectile.Void_Rune_Entity");

    private static final Map<String, Method> LIGHTNING_OWNER_METHODS = new ConcurrentHashMap<>();

    /**
     * 该伤害源的直接实体是否为“该同伴施放的灾变雷电实体”（鸣雷槊/静电场/落雷爆发/
     * 掣雷巨锤本体/虚空符文任一）。
     * 用于神怒长槊/掣雷巨锤/虚空锻锤友军拦截窗口的动态顺延：事件驱动，零扫描成本，
     * 实体消失后自动失效。
     */
    public static boolean isCataclysmLightningOf(Entity directEntity, LivingEntity caster) {
        if (directEntity == null || caster == null) {
            return false;
        }
        try {
            if (!LIGHTNING_ENTITY_CLASS_NAMES.contains(directEntity.getClass().getName())) {
                return false;
            }
            LivingEntity owner = lightningEntityOwner(directEntity);
            return owner != null && owner == caster;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 反射取雷电实体的施法者：鸣雷槊/静电场叫 getOwner()，落雷爆发叫 getCaster() */
    private static LivingEntity lightningEntityOwner(Entity entity) {
        for (String methodName : new String[]{"getOwner", "getCaster"}) {
            try {
                Method m = LIGHTNING_OWNER_METHODS.computeIfAbsent(
                        entity.getClass().getName() + "#" + methodName,
                        k -> {
                            try {
                                return entity.getClass().getMethod(methodName);
                            } catch (Throwable inner) {
                                return null;
                            }
                        });
                if (m == null) {
                    continue;
                }
                Object result = m.invoke(entity);
                if (result instanceof LivingEntity living) {
                    return living;
                }
            } catch (Throwable ignored) {
                // 换下一个候选方法名
            }
        }
        return null;
    }

    // ════════════════════════ 虚空锻锤：符文实体 + 配置数值（r21） ════════════════════════
    //
    // 灾变源码核对（void_forge.useOn / spawnFangs / Void_Rune_Entity）：
    // - 布阵在物品 useOn：7 条射线（look 及 yRot ±0.3/±0.6/±0.9 弧度，共 7 列）
    //   × 每条 5 个符文（距离 1.75×(i+1)，warmup = i），中心 = 施法者脚下、headY = getY()+1，
    //   standingOnY = floor(getY()) - 3，f = Mth.atan2(vec.z, vec.x)（弧度）。
    // - 符文实体 com.github.L_Ender.cataclysm.entity.projectile.Void_Rune_Entity（继承 Entity），
    //   公开构造器 (Level, double x, double y, double z, float yRot弧度, int warmup,
    //   float damage, LivingEntity caster)——末参 LivingEntity，同伴直接当施法者；
    //   构造器内部自己 setCaster/setDamage/setYRot(×180/π)/setPos。
    // - 伤害类型 damageSources().indirectMagic(rune, caster)，directEntity = 符文本体，
    //   caster 可经 getCaster()/getOwner() 反射取到 → 已加入拦截集合。
    // - 地面扫描与火柱同一套（从 headY 往下找“顶面坚固”，悬空不出符文），spawnVoidRune 里复刻。

    private static final String VOID_RUNE_CLASS_NAME =
            "com.github.L_Ender.cataclysm.entity.projectile.Void_Rune_Entity";
    private static final Class<?>[] VOID_RUNE_PARAMS = {
            Level.class,
            double.class, double.class, double.class,
            float.class,
            int.class,
            float.class,
            LivingEntity.class,
    };

    private static volatile Constructor<?> voidRuneCtor = null;
    private static volatile boolean voidRuneProbed = false;

    private static synchronized Constructor<?> resolveVoidRuneCtor() {
        if (voidRuneProbed) {
            return voidRuneCtor;
        }
        voidRuneProbed = true;
        Class<?> cls;
        try {
            cls = Class.forName(VOID_RUNE_CLASS_NAME);
        } catch (Throwable t) {
            voidRuneCtor = null;
            SafeGuard.warn("Void Forge reflection layer: Void_Rune_Entity not found (Cataclysm not installed/class name changed), rune placement cancelled: "
                    + t.getClass().getSimpleName() + " / " + t.getMessage());
            return null;
        }
        try {
            voidRuneCtor = cls.getConstructor(VOID_RUNE_PARAMS);
            SafeGuard.info("Void Forge reflection layer: Void_Rune_Entity constructor resolved exactly (8 args, matches Cataclysm source)");
            return voidRuneCtor;
        } catch (Throwable exactFail) {
            try {
                for (Constructor<?> c : cls.getConstructors()) {
                    Class<?>[] p = c.getParameterTypes();
                    if (p.length == 8 && p[0] == Level.class && p[7] == LivingEntity.class) {
                        voidRuneCtor = c;
                        SafeGuard.warn("Void Forge reflection layer: exact signature mismatch, constructor loosely matched: " + c);
                        return voidRuneCtor;
                    }
                }
            } catch (Throwable scanFail) {
                exactFail = scanFail;
            }
            StringBuilder sb = new StringBuilder();
            try {
                for (Constructor<?> c : cls.getConstructors()) {
                    sb.append("\n  - ").append(c);
                }
            } catch (Throwable ignored) {
            }
            voidRuneCtor = null;
            SafeGuard.warn("Void Forge reflection layer: Void_Rune_Entity constructor mismatch (Cataclysm version may have changed). Existing public constructors:" + sb,
                    exactFail);
            return null;
        }
    }

    /** 符文构造器是否已解析（/mce void_forge status 自检用） */
    public static boolean isVoidRuneResolved() {
        return resolveVoidRuneCtor() != null;
    }

    private static volatile boolean voidForgeConfigProbed = false;
    private static volatile double voidForgeRuneDamage = 7.0D;

    /** 反射直读 CMCommonConfig.VoidForge 的静态字段（可能被灾变 TOML 覆盖），读不到用源码默认值 */
    private static synchronized void probeVoidForgeConfig() {
        if (voidForgeConfigProbed) {
            return;
        }
        voidForgeConfigProbed = true;
        try {
            Class<?> inner = Class.forName("com.github.L_Ender.cataclysm.config.CMCommonConfig$VoidForge");
            voidForgeRuneDamage = inner.getField("runeDamage").getDouble(null);
            SafeGuard.info("Void Forge reflection layer: CMCommonConfig.VoidForge read (runeDamage=" + voidForgeRuneDamage + ")");
        } catch (Throwable t) {
            voidForgeRuneDamage = 7.0D;
            SafeGuard.warn("Void Forge reflection layer: cannot read CMCommonConfig.VoidForge (Cataclysm not installed/version changed), "
                    + "using source default runeDamage=7.0: "
                    + t.getClass().getSimpleName() + " / " + t.getMessage());
        }
    }

    /** 符文每次伤害（灾变 CMCommonConfig.VoidForge.runeDamage，默认 7.0） */
    public static float voidForgeRuneDamage() {
        probeVoidForgeConfig();
        return (float) voidForgeRuneDamage;
    }

    /**
     * 反射生成一个虚空符文并放进世界（复刻 void_forge.spawnFangs：先做同款地面扫描——
     * 从 headY 往下找第一格“顶面坚固”的方块，找到才落符文；悬空处跳过）。
     *
     * @param yRotRadians 朝向弧度（原版 f = atan2(vec.z, vec.x)；构造器内部 ×180/π 转回角度）
     * @param warmup       起手延迟 tick（原版第 i 排传 i）
     * @return 成功返回 true；灾变没装/构造器不对/落点悬空返回 false，绝不抛出
     */
    public static boolean spawnVoidRune(ServerLevel level, LivingEntity caster,
                                        double x, double headY, double z,
                                        int lowestYCheck, float yRotRadians,
                                        int warmup, float damage, long nowTick) {
        Constructor<?> ctor = resolveVoidRuneCtor();
        if (ctor == null) {
            return false;
        }
        // —— 地面扫描（与灾变 void_forge.spawnFangs 同逻辑，原版 API）——
        BlockPos blockpos = BlockPos.containing(x, headY, z);
        boolean foundGround = false;
        double shapeTop = 0.0D;
        int guard = 0;
        do {
            BlockPos below = blockpos.below();
            BlockState state = level.getBlockState(below);
            if (state.isFaceSturdy(level, below, Direction.UP)) {
                if (!level.isEmptyBlock(blockpos)) {
                    VoxelShape shape = level.getBlockState(blockpos).getCollisionShape(level, blockpos);
                    if (!shape.isEmpty()) {
                        shapeTop = shape.max(Direction.Axis.Y);
                    }
                }
                foundGround = true;
                break;
            }
            blockpos = blockpos.below();
            guard++;
        } while (blockpos.getY() >= lowestYCheck && guard < 96);

        if (!foundGround) {
            SafeGuard.warnThrottled("mce_vf_ground" + level.dimension().location(), nowTick,
                    "Void Forge: landing point (" + Mth.floor(x) + ", " + Mth.floor(z) + ") no solid ground found nearby, this void rune skipped");
            return false;
        }

        try {
            Object rune = ctor.newInstance(level, x, blockpos.getY() + shapeTop, z,
                    yRotRadians, warmup, damage, caster);
            if (rune instanceof Entity entity) {
                return level.addFreshEntity(entity);
            }
            SafeGuard.warn("Void Forge: reflected object is not an Entity, placement abandoned: " + rune.getClass().getName());
            return false;
        } catch (InvocationTargetException ite) {
            Throwable cause = ite.getCause() != null ? ite.getCause() : ite;
            SafeGuard.warn("Void Forge: void rune constructor threw internally (caught, no gameplay impact): "
                    + cause.getClass().getName() + " / " + cause.getMessage(), cause);
            return false;
        } catch (Throwable t) {
            SafeGuard.warn("Void Forge: reflection call failed (caught, no gameplay impact): "
                    + t.getClass().getName() + " / " + t.getMessage(), t);
            return false;
        }
    }

    // ════════════════════════ 掣雷巨锤：投掷实体 + 风暴/火花实体 + 配置数值（r21） ════════════════════════
    //
    // 灾变源码核对（Brontes.releaseUsing / Brontes_Entity / Spark_Entity / Lightning_Storm_Entity）：
    // - 投掷实体 com.github.L_Ender.cataclysm.entity.projectile.Brontes_Entity（继承 AbstractArrow），
    //   公开构造器 (Level worldIn, LivingEntity shooter)——生成点 = shooter.getEyeY()-0.1、
    //   pickup=DISALLOWED，本体物品始终留在手上。原版发射流程：
    //     setBaseDamage(OriginDamage) → setAreaDamage(stormareadamage) → setStormDamage(stormdamage)
    //     → shootFromRotation(player, xRot, yRot, 0, 2.5F, 1.0F) → addFreshEntity
    //     → stack.set(THROWN_HAMMER, brontes.getUUID())（“锤没回来不能再扔”的原版锁）。
    // - 命中（onHit）：震屏 ScreenShake(25, 0.03, 0, 20) + 8 个放射 Spark_Entity（速度 1.0、
    //   上抛 0.125、散布 5.0）+ 1 个 Lightning_Storm_Entity（size 3.0、delay -9、伤害 stormdamage）
    //   → setReturn(true) 飞回 owner，距 owner <3 格消失。
    // - Spark_Entity 公开构造器 (Level, LivingEntity thrower)；落地各自生成静电场 + 小风暴（实体内部）。
    // - 数值来自 CMCommonConfig.Brontes（stormdamage=10.0、stormareadamage=1.0），
    //   可能被灾变 TOML 覆盖 → 反射直读，读不到退化为源码默认值。

    private static final String THROWN_BRONTES_CLASS_NAME =
            "com.github.L_Ender.cataclysm.entity.projectile.Brontes_Entity";
    private static final Class<?>[] THROWN_BRONTES_PARAMS = {
            Level.class, LivingEntity.class,
    };

    private static volatile Constructor<?> thrownBrontesCtor = null;
    private static volatile boolean thrownBrontesProbed = false;

    private static synchronized Constructor<?> resolveThrownBrontesCtor() {
        if (thrownBrontesProbed) {
            return thrownBrontesCtor;
        }
        thrownBrontesProbed = true;
        Class<?> cls;
        try {
            cls = Class.forName(THROWN_BRONTES_CLASS_NAME);
        } catch (Throwable t) {
            thrownBrontesCtor = null;
            SafeGuard.warn("Brontes reflection layer: Brontes_Entity not found (Cataclysm not installed/class name changed), throw cancelled: "
                    + t.getClass().getSimpleName() + " / " + t.getMessage());
            return null;
        }
        try {
            thrownBrontesCtor = cls.getConstructor(THROWN_BRONTES_PARAMS);
            SafeGuard.info("Brontes reflection layer: Brontes_Entity constructor resolved exactly (2 args, matches Cataclysm source)");
            return thrownBrontesCtor;
        } catch (Throwable exactFail) {
            try {
                for (Constructor<?> c : cls.getConstructors()) {
                    Class<?>[] p = c.getParameterTypes();
                    if (p.length == 2 && p[0] == Level.class && p[1] == LivingEntity.class) {
                        thrownBrontesCtor = c;
                        SafeGuard.warn("Brontes reflection layer: exact signature mismatch, loosely matched a constructor: " + c);
                        return thrownBrontesCtor;
                    }
                }
            } catch (Throwable scanFail) {
                exactFail = scanFail;
            }
            StringBuilder sb = new StringBuilder();
            try {
                for (Constructor<?> c : cls.getConstructors()) {
                    sb.append("\n  - ").append(c);
                }
            } catch (Throwable ignored) {
            }
            thrownBrontesCtor = null;
            SafeGuard.warn("Brontes reflection layer: Brontes_Entity constructor mismatch (Cataclysm version may have changed). Existing public constructors:" + sb,
                    exactFail);
            return null;
        }
    }

    /** 掣雷投掷实体构造器是否已解析（/mce brontes status 自检用） */
    public static boolean isThrownBrontesResolved() {
        return resolveThrownBrontesCtor() != null;
    }

    private static volatile boolean brontesConfigProbed = false;
    private static volatile double brontesStormDamage = 10.0D;
    private static volatile double brontesStormAreaDamage = 1.0D;

    /** 反射直读 CMCommonConfig.Brontes 的静态字段（可能被灾变 TOML 覆盖），读不到用源码默认值 */
    private static synchronized void probeBrontesConfig() {
        if (brontesConfigProbed) {
            return;
        }
        brontesConfigProbed = true;
        try {
            Class<?> inner = Class.forName("com.github.L_Ender.cataclysm.config.CMCommonConfig$Brontes");
            brontesStormDamage = inner.getField("stormdamage").getDouble(null);
            brontesStormAreaDamage = inner.getField("stormareadamage").getDouble(null);
            SafeGuard.info("Brontes reflection layer: CMCommonConfig.Brontes read (stormdamage=" + brontesStormDamage
                    + ", stormareadamage=" + brontesStormAreaDamage + ")");
        } catch (Throwable t) {
            brontesStormDamage = 10.0D;
            brontesStormAreaDamage = 1.0D;
            SafeGuard.warn("Brontes reflection layer: cannot read CMCommonConfig.Brontes (Cataclysm not installed/version changed), "
                    + "using source defaults stormdamage=10.0 / stormareadamage=1.0: "
                    + t.getClass().getSimpleName() + " / " + t.getMessage());
        }
    }

    /** 闪电风暴每次伤害（灾变 CMCommonConfig.Brontes.stormdamage，默认 10.0） */
    public static float brontesStormDamage() {
        probeBrontesConfig();
        return (float) brontesStormDamage;
    }

    /** 静电场每次伤害（灾变 CMCommonConfig.Brontes.stormareadamage，默认 1.0） */
    public static float brontesStormAreaDamage() {
        probeBrontesConfig();
        return (float) brontesStormAreaDamage;
    }

    /**
     * 反射生成一把掣雷投掷锤并放进世界（流程照抄 Brontes.releaseUsing 第 4~9 步）。
     *
     * 分步独立 try-catch + 独立失败日志（同霆浪 r19 拆法）：
     *   ① 构造实体（唯一需要反射的环节）；
     *   ② 设置伤害（AbstractArrow.setBaseDamage(double)，直接调用 + 反射兜底，复用霆浪的 applyBaseDamage）；
     *   ③ 设置范围伤害数值（setAreaDamage/setStormDamage，灾变自有 float setter，按名反射）；
     *   ④ 设置弹道（Projectile.shootFromRotation 是 public，直接调用）；
     *   ⑤ 加入世界。
     *
     * @param xRot       俯仰角（MC 口径，负=向上），已带抛物线仰角补偿
     * @param yRot       偏航角（MC 口径）
     * @param baseDamage 发射者 ATTACK_DAMAGE 属性值（与原版 OriginDamage 同口径）
     * @param velocity   弹速（原版 2.5F）
     * @return 成功返回锤子实体 UUID（同伴版投掷锁与友军拦截都靠它）；失败返回 null，绝不抛出
     */
    public static java.util.UUID spawnThrownBrontes(ServerLevel level, LivingEntity owner,
                                                    float xRot, float yRot,
                                                    float baseDamage, float velocity,
                                                    float areaDamage, float stormDamage,
                                                    long nowTick) {
        // —— 第 ① 步：反射构造投掷实体 ——
        Constructor<?> ctor = resolveThrownBrontesCtor();
        if (ctor == null) {
            SafeGuard.warn("Brontes: throw branch failed at step 1 'construct entity' -- Brontes_Entity class not found or constructor signature mismatch"
                    + "(see reflection-layer logs above for constructor resolution details and the full list of public constructors of this class)");
            return null;
        }
        Object hammerObj;
        try {
            hammerObj = ctor.newInstance(level, owner);
        } catch (InvocationTargetException ite) {
            Throwable cause = ite.getCause() != null ? ite.getCause() : ite;
            SafeGuard.warn("Brontes: throw branch failed at step 1 'construct entity' -- constructor threw internally (caught): "
                    + cause.getClass().getName() + " / " + cause.getMessage(), cause);
            return null;
        } catch (Throwable t) {
            SafeGuard.warn("Brontes: throw branch failed at step 1 'construct entity' -- reflection call failed (caught): "
                    + t.getClass().getName() + " / " + t.getMessage(), t);
            return null;
        }
        if (!(hammerObj instanceof Entity entity)) {
            SafeGuard.warn("Brontes: throw branch failed at step 1 'construct entity' -- reflected object is not an Entity, throw aborted: "
                    + hammerObj.getClass().getName());
            return null;
        }

        // —— 第 ② 步：设置伤害（复用霆浪的 applyBaseDamage，double/float 都能兜底）——
        if (!applyBaseDamage(entity, baseDamage, nowTick)) {
            return null;
        }

        // —— 第 ③ 步：范围伤害数值（setAreaDamage/setStormDamage，灾变自有 float setter）——
        invokeFloatSetter(entity, "setAreaDamage", areaDamage, "Brontes");
        invokeFloatSetter(entity, "setStormDamage", stormDamage, "Brontes");

        // —— 第 ④ 步：设置弹道（Projectile.shootFromRotation 是 public，直接调用）——
        try {
            if (entity instanceof net.minecraft.world.entity.projectile.Projectile projectile) {
                projectile.shootFromRotation(owner, xRot, yRot, 0.0F, velocity, 1.0F);
            } else {
                java.lang.reflect.Method shoot = entity.getClass().getMethod("shootFromRotation",
                        Entity.class, float.class, float.class, float.class, float.class, float.class);
                shoot.invoke(entity, owner, xRot, yRot, 0.0F, velocity, 1.0F);
                SafeGuard.info("Brontes: step 4 trajectory setup used the reflection fallback (entity is not a Projectile)");
            }
        } catch (Throwable t) {
            SafeGuard.warn("Brontes: throw branch failed at step 4 'set trajectory' -- shootFromRotation threw (caught): "
                    + t.getClass().getName() + " / " + t.getMessage(), t);
            return null;
        }

        // —— 第 ⑤ 步：加入世界 ——
        boolean added;
        try {
            added = level.addFreshEntity(entity);
        } catch (Throwable t) {
            SafeGuard.warn("Brontes: throw branch failed at step 5 'add to world' -- addFreshEntity threw (caught): "
                    + t.getClass().getName() + " / " + t.getMessage(), t);
            return null;
        }
        if (!added) {
            SafeGuard.warn("Brontes: throw branch failed at step 5 'add to world' -- addFreshEntity returned false"
                    + "(entity rejected by the world, position " + String.format("%.1f, %.1f, %.1f",
                            entity.getX(), entity.getY(), entity.getZ()) + ")");
            return null;
        }
        try {
            return entity.getUUID();
        } catch (Throwable t) {
            SafeGuard.warn("Brontes: throw succeeded but reading the UUID failed (caught, throw lock degrades to plain cooldown throttling): "
                    + t.getClass().getSimpleName());
            return null;
        }
    }

    private static final String SPARK_CLASS_NAME =
            "com.github.L_Ender.cataclysm.entity.projectile.Spark_Entity";
    /** 原版火花上抛分量：peq.shoot(xDir, 0.125, zDir, speed, 5.0F) */
    private static final double SPARK_Y = 0.125D;
    private static final Class<?>[] SPARK_PARAMS = {
            Level.class, LivingEntity.class,
    };

    private static volatile Constructor<?> sparkCtor = null;
    private static volatile boolean sparkProbed = false;

    private static synchronized Constructor<?> resolveSparkCtor() {
        if (sparkProbed) {
            return sparkCtor;
        }
        sparkProbed = true;
        Class<?> cls;
        try {
            cls = Class.forName(SPARK_CLASS_NAME);
        } catch (Throwable t) {
            sparkCtor = null;
            SafeGuard.warn("Brontes reflection layer: Spark_Entity not found (Cataclysm missing / class renamed), spark cancelled: "
                    + t.getClass().getSimpleName() + " / " + t.getMessage());
            return null;
        }
        try {
            sparkCtor = cls.getConstructor(SPARK_PARAMS);
            SafeGuard.info("Brontes reflection layer: Spark_Entity constructor resolved exactly (2 args, matches Cataclysm source)");
            return sparkCtor;
        } catch (Throwable exactFail) {
            try {
                for (Constructor<?> c : cls.getConstructors()) {
                    Class<?>[] p = c.getParameterTypes();
                    if (p.length == 2 && p[0] == Level.class && p[1] == LivingEntity.class) {
                        sparkCtor = c;
                        SafeGuard.warn("Brontes reflection layer: exact signature mismatch, loosely matched a constructor: " + c);
                        return sparkCtor;
                    }
                }
            } catch (Throwable scanFail) {
                exactFail = scanFail;
            }
            sparkCtor = null;
            SafeGuard.warn("Brontes reflection layer: Spark_Entity constructor mismatch (Cataclysm version may have changed)", exactFail);
            return null;
        }
    }

    /** 火花构造器是否已解析（/mce brontes status 自检用） */
    public static boolean isSparkResolved() {
        return resolveSparkCtor() != null;
    }

    /**
     * 反射生成一枚放射火花并放进世界（流程照抄 Brontes_Entity.onHit 的火花段）。
     * 火花本身不造成伤害，落地后由灾变自己生成静电场 + 小风暴（owner = caster）。
     *
     * @param dirX/dirZ 水平方向（原版 = 8 方向的 cos/sin）
     * @param speed     出膛速度（原版 1.0F）
     * @return 成功返回 true；灾变没装/构造器不对/加入失败返回 false，绝不抛出
     */
    public static boolean spawnSpark(ServerLevel level, LivingEntity caster,
                                     double x, double y, double z,
                                     double dirX, double dirZ,
                                     float speed, float inaccuracy,
                                     float areaDamage, float stormDamage, float areaRadius,
                                     long nowTick) {
        Constructor<?> ctor = resolveSparkCtor();
        if (ctor == null) {
            return false;
        }
        try {
            Object spark = ctor.newInstance(level, caster);
            if (!(spark instanceof Entity entity)) {
                SafeGuard.warn("Brontes: reflected spark is not an Entity, aborted: " + spark.getClass().getName());
                return false;
            }
            entity.setPos(x, y, z);
            invokeFloatSetter(entity, "setAreaDamage", areaDamage, "Brontes spark");
            invokeFloatSetter(entity, "setDamage", stormDamage, "Brontes spark");
            invokeFloatSetter(entity, "setAreaRadius", areaRadius, "Brontes spark");
            // Projectile.shoot(double,double,double,float,float) 是 public（1.21.1 官方映射）
            if (entity instanceof net.minecraft.world.entity.projectile.Projectile projectile) {
                projectile.shoot(dirX, SPARK_Y, dirZ, speed, inaccuracy);
            } else {
                // 兜底：实体不是 Projectile（理论上不可能，Spark_Entity 继承 ThrowableProjectile）
                java.lang.reflect.Method shoot = entity.getClass().getMethod("shoot",
                        double.class, double.class, double.class, float.class, float.class);
                shoot.invoke(entity, dirX, SPARK_Y, dirZ, speed, inaccuracy);
                SafeGuard.info("Brontes: spark trajectory setup used the reflection fallback (entity is not a Projectile)");
            }
            return level.addFreshEntity(entity);
        } catch (InvocationTargetException ite) {
            Throwable cause = ite.getCause() != null ? ite.getCause() : ite;
            SafeGuard.warn("Brontes: spark constructor threw internally (caught, no game impact): "
                    + cause.getClass().getName() + " / " + cause.getMessage(), cause);
            return false;
        } catch (Throwable t) {
            SafeGuard.warn("Brontes: spark reflection call failed (caught, no game impact): "
                    + t.getClass().getName() + " / " + t.getMessage(), t);
            return false;
        }
    }

    private static final String LIGHTNING_STORM_CLASS_NAME =
            "com.github.L_Ender.cataclysm.entity.effect.Lightning_Storm_Entity";
    private static final Class<?>[] LIGHTNING_STORM_PARAMS = {
            Level.class,
            double.class, double.class, double.class,
            float.class,
            int.class,
            float.class, float.class,
            LivingEntity.class,
            float.class,
    };

    private static volatile Constructor<?> lightningStormCtor = null;
    private static volatile boolean lightningStormProbed = false;

    private static synchronized Constructor<?> resolveLightningStormCtor() {
        if (lightningStormProbed) {
            return lightningStormCtor;
        }
        lightningStormProbed = true;
        Class<?> cls;
        try {
            cls = Class.forName(LIGHTNING_STORM_CLASS_NAME);
        } catch (Throwable t) {
            lightningStormCtor = null;
            SafeGuard.warn("Brontes reflection layer: Lightning_Storm_Entity not found (Cataclysm missing / class renamed), storm cancelled: "
                    + t.getClass().getSimpleName() + " / " + t.getMessage());
            return null;
        }
        try {
            lightningStormCtor = cls.getConstructor(LIGHTNING_STORM_PARAMS);
            SafeGuard.info("Brontes reflection layer: Lightning_Storm_Entity constructor resolved exactly (10 args, matches Cataclysm source)");
            return lightningStormCtor;
        } catch (Throwable exactFail) {
            try {
                for (Constructor<?> c : cls.getConstructors()) {
                    Class<?>[] p = c.getParameterTypes();
                    if (p.length == 10 && p[0] == Level.class && p[8] == LivingEntity.class) {
                        lightningStormCtor = c;
                        SafeGuard.warn("Brontes reflection layer: exact signature mismatch, loosely matched a constructor: " + c);
                        return lightningStormCtor;
                    }
                }
            } catch (Throwable scanFail) {
                exactFail = scanFail;
            }
            lightningStormCtor = null;
            SafeGuard.warn("Brontes reflection layer: Lightning_Storm_Entity constructor mismatch (Cataclysm version may have changed)", exactFail);
            return null;
        }
    }

    /** 闪电风暴构造器是否已解析（/mce brontes status 自检用） */
    public static boolean isLightningStormResolved() {
        return resolveLightningStormCtor() != null;
    }

    /**
     * 反射生成一个闪电风暴并放进世界（参数与灾变 Brontes_Entity.onHit 完全同口径）。
     *
     * @param yRotDegrees 朝向角度（构造器内部 ×180/π 无所谓，风暴是圆形判定）
     * @param delay       起手延迟（原版 -9，使风暴“立即可用”并延长 9 tick 寿命）
     * @param damage      风暴伤害（原版 stormdamage）
     * @param hpDamage    按最大生命% 的额外伤害（原版传 0）
     * @param size        风暴尺寸（原版 3.0）
     */
    public static boolean spawnLightningStorm(ServerLevel level, LivingEntity caster,
                                              double x, double y, double z,
                                              float yRotDegrees, int delay,
                                              float damage, float hpDamage, float size,
                                              long nowTick) {
        Constructor<?> ctor = resolveLightningStormCtor();
        if (ctor == null) {
            return false;
        }
        try {
            Object storm = ctor.newInstance(level, x, y, z, yRotDegrees, delay,
                    damage, hpDamage, caster, size);
            if (storm instanceof Entity entity) {
                return level.addFreshEntity(entity);
            }
            SafeGuard.warn("Brontes: reflected storm is not an Entity, aborted: " + storm.getClass().getName());
            return false;
        } catch (InvocationTargetException ite) {
            Throwable cause = ite.getCause() != null ? ite.getCause() : ite;
            SafeGuard.warn("Brontes: storm constructor threw internally (caught, no game impact): "
                    + cause.getClass().getName() + " / " + cause.getMessage(), cause);
            return false;
        } catch (Throwable t) {
            SafeGuard.warn("Brontes: storm reflection call failed (caught, no game impact): "
                    + t.getClass().getName() + " / " + t.getMessage(), t);
            return false;
        }
    }

    // ════════════════════════ 掣雷：物品数据组件镜像（THROWN_HAMMER） ════════════════════════
    //
    // 原版投掷成功后 stack.set(ModDataComponents.THROWN_HAMMER, 锤子UUID)，这就是
    // “锤没回来不能再扔”的原版锁。同伴版把锁的权威记录放在同伴的 persistentData 里
    // （读档/复活都稳），同时 best-effort 镜像写/清灾变的数据组件——组件注册名按
    // "cataclysm:thrown_hammer" 从原版数据组件注册表取，灾变没装/版本改了名就跳过，不报错。
    // （灾变自己的 inventoryTick 也会在锤子实体消失时清掉这个组件，两条路最终一致。）

    /** 投掷成功后往掣雷物品里写入“锤子 UUID”（best-effort；组件不存在 = 无操作） */
    public static void writeThrownHammerComponent(ItemStack stack, java.util.UUID hammerUuid) {
        try {
            DataComponentType<?> type = thrownHammerComponentType();
            if (type == null || stack == null || stack.isEmpty()) {
                return;
            }
            setComponentUnchecked(stack, type, hammerUuid);
        } catch (Throwable t) {
            SafeGuard.warnThrottled("mce_br_comp", 0L,
                    "Brontes: writing the THROWN_HAMMER component failed (caught, throw lock still tracked by persistentData): "
                            + t.getClass().getSimpleName() + " / " + t.getMessage());
        }
    }

    /** 强制恢复时清除掣雷物品上的投掷锁组件（best-effort；组件不存在 = 无操作） */
    public static void clearThrownHammerComponent(ItemStack stack) {
        try {
            DataComponentType<?> type = thrownHammerComponentType();
            if (type == null || stack == null || stack.isEmpty()) {
                return;
            }
            if (stack.has(type)) {
                stack.remove(type);
            }
        } catch (Throwable t) {
            SafeGuard.warnThrottled("mce_br_comp", 0L,
                    "Brontes: clearing the THROWN_HAMMER component failed (caught): "
                            + t.getClass().getSimpleName() + " / " + t.getMessage());
        }
    }

    private static volatile DataComponentType<?> thrownHammerType = null;
    private static volatile boolean thrownHammerTypeProbed = false;

    private static DataComponentType<?> thrownHammerComponentType() {
        if (thrownHammerTypeProbed) {
            return thrownHammerType;
        }
        thrownHammerTypeProbed = true;
        try {
            thrownHammerType = BuiltInRegistries.DATA_COMPONENT_TYPE.getOptional(THROWN_HAMMER_COMPONENT)
                    .orElse(null);
            if (thrownHammerType == null) {
                SafeGuard.info("Brontes reflection layer: not present in the data component registry " + THROWN_HAMMER_COMPONENT
                        + " (Cataclysm missing / registry name changed), item component mirror skipped (no functional impact)");
            }
        } catch (Throwable t) {
            thrownHammerType = null;
            SafeGuard.warn("Brontes reflection layer: querying the data component registry threw (caught, mirror skipped): "
                    + t.getClass().getSimpleName() + " / " + t.getMessage());
        }
        return thrownHammerType;
    }

    /** 通配 DataComponentType 的写入桥（类型实际是 DataComponentType<UUID>，值也只有 UUID 一种） */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void setComponentUnchecked(ItemStack stack, DataComponentType type, Object value) {
        try {
            stack.set((DataComponentType) type, value);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

}
