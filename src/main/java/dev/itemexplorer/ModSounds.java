package dev.itemexplorer;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** Server-confirmed, one-shot feedback; never driven by rendering or background snapshots. */
public final class ModSounds {
    private static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, ItemExplorer.MOD_ID);
    public static final RegistryObject<SoundEvent> DISK_INSERT = sound("disk_insert");
    public static final RegistryObject<SoundEvent> DISK_EJECT = sound("disk_eject");
    public static final RegistryObject<SoundEvent> TRANSFER_SUCCESS = sound("transfer_success");
    public static final RegistryObject<SoundEvent> ACTION_DENIED = sound("action_denied");
    public static final RegistryObject<SoundEvent> STATION_STARTUP = sound("station_startup");
    public static final RegistryObject<SoundEvent> PROGRAM_COMPLETE = sound("program_complete");
    private static final Set<String> QUIET_RESULTS = Set.of("", "created", "renamed", "deleted", "moved",
            "deposited", "withdrawn", "no_change", "transfer_configured", "port_saved", "port_disconnected",
            "production_saved", "production_started", "production_cancelled");
    // Server-thread only. Weak keys avoid retaining players after disconnecting.
    private static final Map<ServerPlayer, Map<SoundEvent, Integer>> LAST_FEEDBACK = new WeakHashMap<>();
    private static final int FEEDBACK_INTERVAL = 6;

    private ModSounds() {}
    private static RegistryObject<SoundEvent> sound(String name) {
        return SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(new ResourceLocation(ItemExplorer.MOD_ID, name)));
    }
    public static void register(IEventBus bus) { SOUNDS.register(bus); }

    /** Call only for an explicit menu response, using its transient result, not a persistent status. */
    public static void menuResult(Player player, String result) {
        if ("transfer_sent".equals(result)) feedback(player, TRANSFER_SUCCESS.get());
        else if (result != null && !QUIET_RESULTS.contains(result)) feedback(player, ACTION_DENIED.get());
    }

    public static void feedback(Player player, SoundEvent sound) {
        if (!(player instanceof ServerPlayer serverPlayer)) return;
        int now = serverPlayer.server.getTickCount();
        Map<SoundEvent, Integer> times = LAST_FEEDBACK.computeIfAbsent(serverPlayer, ignored -> new HashMap<>());
        Integer previous = times.get(sound);
        if (previous != null && now >= previous && now - previous < FEEDBACK_INTERVAL) return;
        times.put(sound, now);
        serverPlayer.playNotifySound(sound, SoundSource.PLAYERS, 0.8F, 1.0F);
    }

    /** Device effects have distance attenuation and follow the Blocks volume slider. */
    public static void device(Level level, BlockPos pos, SoundEvent sound) {
        if (level != null && !level.isClientSide) level.playSound(null, pos, sound, SoundSource.BLOCKS, 0.8F, 1.0F);
    }
}
