package dev.itemexplorer;

import com.mojang.logging.LogUtils;
import dev.itemexplorer.network.StorageNetwork;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/** Entry point shared by the client and dedicated server. */
@Mod(ItemExplorer.MOD_ID)
public final class ItemExplorer {
    public static final String MOD_ID = "itemexplorer";
    private static final Logger LOGGER = LogUtils.getLogger();

    public ItemExplorer(FMLJavaModLoadingContext context) {
        ModContent.register(context.getModEventBus());
        StorageNetwork.register();
        context.getModEventBus().addListener(this::commonSetup);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("Item Explorer initialized");
    }
}
