package cn.blockforge.generated.moderncompanionsexpand.mixin;

import cn.blockforge.generated.moderncompanionsexpand.util.ShieldPassiveSuppression;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 需求②注入点 1/3：霜冻之盾格挡被动（传奇怪物 FrostbittenShieldHurtEvent）。
 *
 * 目标类用字符串点名（本工程编译期零依赖传奇怪物）。"传奇怪物没装 / 日后改名改签名 →
 * 静默跳过"由三件套兜底：本配置文件 modern_companions_expand.mixins.json 的
 * "required": false（目标类缺失时 Mixin 只记 WARN 日志跳过，不崩）、
 * @Inject 的 require = 0（目标方法缺失同理）。注意：NeoForge 21.1 带的
 * sponge-mixin 0.15.x fork 的 @Mixin 注解【没有】require 成员，不要往那上面加。
 * 最坏结果只是"同伴格挡恢复触发被动"（原版行为），绝不崩溃。
 *
 * 注入私有 execute(Event, Entity, Entity)：这是两条公开入口
 * （onEntityAttacked 总线处理器、execute(Entity, Entity)）的必经收口点，
 * 一处掐死全覆盖。取消的是【处理器方法】本身，不是伤害事件——
 * 普通格挡减伤、挡箭、掉耐久、blocking 外观都是原版逻辑，一概不动。
 */
@Mixin(targets = "net.miauczel.legendary_monsters.item.custom.FrostbittenShieldHurtEvent")
public class LMFrostbittenShieldPassiveMixin {

    @Inject(method = "execute(Lnet/neoforged/bus/api/Event;Lnet/minecraft/world/entity/Entity;"
            + "Lnet/minecraft/world/entity/Entity;)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0)
    private static void mce$suppressForCompanion(
            net.neoforged.bus.api.Event event, Entity blocker, Entity attacker, CallbackInfo ci) {
        if (ShieldPassiveSuppression.shouldSuppress("Frostbitten Shield",
                "100% chance on block to freeze the attacker for 3 seconds (plus 5% chance per tick of 2 frostbite points)", blocker)) {
            ci.cancel();
        }
    }
}
