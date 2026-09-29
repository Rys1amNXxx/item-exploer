package dev.itemexplorer;

import com.mojang.logging.LogUtils;
import dev.itemexplorer.network.StorageNetwork;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
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
        context.getModEventBus().addListener(this::creativeTab);
    }

    private void creativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(ModContent.STORAGE_ITEM); event.accept(ModContent.NAS_ITEM);
            event.accept(ModContent.LOGISTICS_ITEM);
            event.accept(ModContent.DISK_64K); event.accept(ModContent.DISK_256K);
            event.accept(ModContent.DISK_1M); event.accept(ModContent.DISK_16M);
        }
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("Item Explorer initialized");
    }
}
