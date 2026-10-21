package dev.itemexplorer.client;

import com.mojang.blaze3d.audio.OggAudioStream;
import dev.itemexplorer.ItemExplorer;
import dev.itemexplorer.ModSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.sound.PlaySoundEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Verify shipped audio with Minecraft's decoder and observe real server-to-client playback. */
@Mod.EventBusSubscriber(modid = ItemExplorer.MOD_ID, value = Dist.CLIENT)
public final class SoundClientProbe {
    private static final Map<ResourceLocation, Integer> PLAYED = new HashMap<>();

    @SubscribeEvent public static void sound(PlaySoundEvent event) {
        if (System.getProperty("itemexplorer.remoteClientProbe") == null || event.getSound() == null) return;
        ResourceLocation id = event.getSound().getLocation();
        if (id.getNamespace().equals(ItemExplorer.MOD_ID)) PLAYED.merge(id, 1, Integer::sum);
    }

    static void verify(Minecraft mc) throws Exception {
        for (var registration : List.of(ModSounds.DISK_INSERT, ModSounds.DISK_EJECT, ModSounds.TRANSFER_SUCCESS,
                ModSounds.ACTION_DENIED, ModSounds.STATION_STARTUP, ModSounds.PROGRAM_COMPLETE)) {
            ResourceLocation id = registration.getId();
            var event = mc.getSoundManager().getSoundEvent(id);
            check(event != null && event.getWeight() > 0 && event.getSubtitle() != null, "Missing sound or subtitle: " + id);
            var resource = mc.getResourceManager().getResource(new ResourceLocation(id.getNamespace(), "sounds/" + id.getPath() + ".ogg")).orElseThrow();
            try (var stream = new OggAudioStream(resource.open())) {
                check(stream.getFormat().getChannels() == 1 && stream.getFormat().getSampleRate() == 48000,
                        "Unexpected audio format: " + id);
                check(stream.readAll().remaining() > 0, "Minecraft decoded empty audio: " + id);
            }
        }
        check(PLAYED.getOrDefault(ModSounds.TRANSFER_SUCCESS.getId(), 0) == 1,
                "Expected exactly one successful-transfer sound, including after synthetic snapshot replays: " + PLAYED);
        check(PLAYED.getOrDefault(ModSounds.ACTION_DENIED.getId(), 0) >= 1,
                "Rejected real transfer produced no failure sound: " + PLAYED);
    }

    private static void check(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }
}
