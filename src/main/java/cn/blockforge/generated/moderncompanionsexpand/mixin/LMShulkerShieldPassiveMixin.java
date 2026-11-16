package cn.blockforge.generated.moderncompanionsexpand.mixin;

import cn.blockforge.generated.moderncompanionsexpand.util.ShieldPassiveSuppression;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 需求②注入点 2/3：潜影之盾格挡被动（传奇怪物 ShulkerShieldHurtEvent）。
 *
 * 注意：作者源码里 execute 的第一个参数类型是 jdk.jfr.Event（手滑 import，
 * 但字节码描述符就是它），注入描述符必须照抄，否则找不到方法（require=0 兜底不崩）。
 * 总线处理器 onEntityAttacked 走公开的 execute(Entity, Entity)，它内部又委托到这个
 * 私有三参 execute——收口点掐一处，两条路径全覆盖。
 * 容错：config json 的 "required": false + @Inject require = 0（sponge-mixin 0.15.x
 * 的 @Mixin 注解没有 require 成员），传奇怪物没装/改签名 = WARN 跳过，不崩。
 */
@Mixin(targets = "net.miauczel.legendary_monsters.item.custom.ShulkerShieldHurtEvent")
public class LMShulkerShieldPassiveMixin {

    @Inject(method = "execute(Ljdk/jfr/Event;Lnet/minecraft/world/entity/Entity;"
            + "Lnet/minecraft/world/entity/Entity;)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0)
    private static void mce$suppressForCompanion(
            jdk.jfr.Event event, Entity blocker, Entity attacker, CallbackInfo ci) {
        if (ShieldPassiveSuppression.shouldSuppress("Shulker Shield",
                "60% chance on block to give the attacker Levitation II (40 ticks)", blocker)) {
            ci.cancel();
        }
    }
}
