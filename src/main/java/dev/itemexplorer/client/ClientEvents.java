package dev.itemexplorer.client;

import dev.itemexplorer.ItemExplorer;
import dev.itemexplorer.ModContent;
import dev.itemexplorer.menu.StorageMenu;
import dev.itemexplorer.menu.NasMenu;
import dev.itemexplorer.menu.LogisticsPortMenu;
import dev.itemexplorer.menu.ProductionPortMenu;
import dev.itemexplorer.network.StorageNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.ModelEvent;

@Mod.EventBusSubscriber(modid = ItemExplorer.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientEvents {
    private ClientEvents() {}

    @SubscribeEvent public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModContent.NAS_ENTITY.get(), NasRenderer::new);
    }
    @SubscribeEvent public static void registerModels(ModelEvent.RegisterAdditional event) {
        event.register(NasRenderer.TRAY); event.register(NasRenderer.BADGE);
        event.register(NasRenderer.FRONT_LED); event.register(NasRenderer.TOP_LED);
    }

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            MenuScreens.register(ModContent.STORAGE_MENU.get(), StorageScreen::new);
            MenuScreens.register(ModContent.NAS_MENU.get(), NasScreen::new);
            MenuScreens.register(ModContent.LOGISTICS_MENU.get(), LogisticsPortScreen::new);
            MenuScreens.register(ModContent.PRODUCTION_MENU.get(), ProductionPortScreen::new);
            MenuScreens.register(ModContent.BASE_STATION_MENU.get(), BaseStationScreen::new);
        });
    }

    public static void receive(StorageNetwork.Snapshot snapshot) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null && minecraft.player.containerMenu instanceof StorageMenu menu
                && menu.containerId == snapshot.menuId()) menu.acceptView(snapshot.view());
        if (minecraft.player != null && minecraft.player.containerMenu instanceof NasMenu menu
                && menu.containerId == snapshot.menuId()) menu.acceptView(snapshot.view());
        if (minecraft.player != null && minecraft.player.containerMenu instanceof LogisticsPortMenu menu
                && menu.containerId == snapshot.menuId()) menu.acceptView(snapshot.view());
        if (minecraft.player != null && minecraft.player.containerMenu instanceof ProductionPortMenu menu
                && menu.containerId == snapshot.menuId()) menu.acceptView(snapshot.view());
    }

    public static void receive(StorageNetwork.SearchCatalog catalog) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null && minecraft.player.containerMenu instanceof StorageMenu menu
                && menu.containerId == catalog.menuId() && minecraft.screen instanceof StorageScreen screen)
            screen.acceptSearchCatalog(catalog);
    }
}
