package dev.itemexplorer;

import com.mojang.logging.LogUtils;
import dev.itemexplorer.network.StorageNetwork;
import dev.itemexplorer.station.StationChunkLoading;
import dev.itemexplorer.station.StationChunkLoadingConfig;
import net.minecraftforge.common.world.ForgeChunkManager;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/** Entry point shared by the client and dedicated server. */
@Mod(ItemExplorer.MOD_ID)
public final class ItemExplorer {
    public static final String MOD_ID = "itemexplorer";
    private static final Logger LOGGER = LogUtils.getLogger();

    public ItemExplorer() {
        // Constructor context injection is only supported in Forge 47.3.10 and later.
        var context = FMLJavaModLoadingContext.get();
        ModContent.register(context.getModEventBus());
        StorageNetwork.register();
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, StationChunkLoadingConfig.SPEC);
        context.getModEventBus().addListener(this::commonSetup);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(() -> ForgeChunkManager.setForcedChunkLoadingCallback(MOD_ID, StationChunkLoading::validateTickets));
        LOGGER.info("Item Explorer initialized");
    }
}
