package dev.itemexplorer.gametest;

import com.mojang.authlib.GameProfile;
import dev.itemexplorer.ItemExplorer;
import dev.itemexplorer.ModContent;
import dev.itemexplorer.ModSounds;
import dev.itemexplorer.block.NasBlockEntity;
import dev.itemexplorer.menu.NasMenu;
import dev.itemexplorer.network.StorageNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.PlayLevelSoundEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

@GameTestHolder(ItemExplorer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SoundFeedbackTests {
    /** Observe actual server sound dispatch without an audio device or test-only production hooks. */
    static final class DeviceCapture implements AutoCloseable {
        int count;
        private final Consumer<PlayLevelSoundEvent.AtPosition> listener;
        DeviceCapture(GameTestHelper h, BlockPos pos, SoundEvent sound) {
            listener = event -> {
                if (event.getLevel() == h.getLevel() && BlockPos.containing(event.getPosition()).equals(pos)
                        && event.getSound().value() == sound) count++;
            };
            MinecraftForge.EVENT_BUS.addListener(listener);
        }
        @Override public void close() { MinecraftForge.EVENT_BUS.unregister(listener); }
    }

    @GameTest(template = "empty")
    public static void nasSoundsFollowCommittedActionsAndCoalesceRapidFailures(GameTestHelper h) {
        BlockPos pos = h.absolutePos(new BlockPos(2, 2, 2));
        h.getLevel().setBlockAndUpdate(pos, ModContent.NAS_BLOCK.get().defaultBlockState());
        NasBlockEntity nas = (NasBlockEntity) h.getLevel().getBlockEntity(pos);
        List<SoundEvent> heard = new ArrayList<>();
        var player = new FakePlayer(h.getLevel(), new GameProfile(UUID.randomUUID(), "soundtest")) {
            @Override public void playNotifySound(SoundEvent sound, SoundSource source, float volume, float pitch) {
                heard.add(sound);
            }
        };
        GameTestSupport.standOn(player, pos);
        NasMenu menu = new NasMenu(1, player.getInventory(), pos, nas); player.containerMenu = menu;
        menu.broadcastChanges();
        h.assertTrue(heard.isEmpty(), "Opening NAS played a sound without an operation");
        menu.setCarried(new ItemStack(ModContent.DISK_64K.get()));
        menu.handle(new StorageNetwork.NasRequest(1, menu.session(), nas.revision(), 0, false));
        menu.handle(new StorageNetwork.NasRequest(1, menu.session(), nas.revision(), 0, true));
        menu.handle(new StorageNetwork.NasRequest(1, menu.session(), nas.revision(), 0, true));
        menu.broadcastChanges();
        h.assertTrue(heard.equals(List.of(ModSounds.DISK_INSERT.get(), ModSounds.DISK_EJECT.get())),
                "Insert/eject feedback was missing, or empty-bay ejection/background sync replayed it");
        menu.setCarried(new ItemStack(Items.STONE));
        for (int i = 0; i < 3; i++)
            menu.handle(new StorageNetwork.NasRequest(1, menu.session(), nas.revision(), 0, false));
        h.assertTrue(heard.size() == 3 && heard.get(2) == ModSounds.ACTION_DENIED.get()
                        && nas.disk(0).isEmpty() && menu.getCarried().is(Items.STONE),
                "Rejected installation played success, spammed errors or changed inventory");
        h.runAfterDelay(7, () -> {
            // Shift-click is a separate vanilla path and must also produce installation feedback.
            player.getInventory().setItem(9, new ItemStack(ModContent.DISK_64K.get()));
            menu.quickMoveStack(player, 0);
            h.assertTrue(heard.size() == 4 && heard.get(3) == ModSounds.DISK_INSERT.get() && !nas.disk(0).isEmpty(),
                    "Shift-click installation was silent, or feedback remained suppressed after its cooldown");
            menu.removed(player); h.succeed();
        });
    }
}
