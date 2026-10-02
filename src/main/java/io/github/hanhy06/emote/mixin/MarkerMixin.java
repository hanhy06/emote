package io.github.hanhy06.emote.mixin;

import io.github.hanhy06.emote.playback.MarkerEmoteAccess;
import io.github.hanhy06.emote.playback.MarkerEmoteSettings;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Marker.class)
abstract class MarkerMixin implements MarkerEmoteAccess {
    @Unique private MarkerEmoteSettings emote$settings = MarkerEmoteSettings.EMPTY;

    @Override public MarkerEmoteSettings emote$getSettings() { return this.emote$settings; }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void emote$readSettings(ValueInput input, CallbackInfo callback) {
        this.emote$settings = MarkerEmoteSettings.read(input);
    }

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void emote$writeSettings(ValueOutput output, CallbackInfo callback) {
        this.emote$settings.write(output);
    }
}
