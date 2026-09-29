package dev.itemexplorer.client;

import dev.itemexplorer.ItemExplorer;
import dev.itemexplorer.ModContent;
import dev.itemexplorer.menu.StorageMenu;
import dev.itemexplorer.menu.NasMenu;
import dev.itemexplorer.network.StorageNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

@Mod.EventBusSubscriber(modid = ItemExplorer.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientEvents {
    private ClientEvents() {}

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            MenuScreens.register(ModContent.STORAGE_MENU.get(), StorageScreen::new);
            MenuScreens.register(ModContent.NAS_MENU.get(), NasScreen::new);
        });
    }

    public static void receive(StorageNetwork.Snapshot snapshot) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null && minecraft.player.containerMenu instanceof StorageMenu menu
                && menu.containerId == snapshot.menuId()) menu.acceptView(snapshot.view());
        if (minecraft.player != null && minecraft.player.containerMenu instanceof NasMenu menu
                && menu.containerId == snapshot.menuId()) menu.acceptView(snapshot.view());
    }
}
