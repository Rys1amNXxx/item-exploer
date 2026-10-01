package dev.itemexplorer;

import dev.itemexplorer.block.StorageBlock;
import dev.itemexplorer.block.DataCableBlock;
import dev.itemexplorer.block.StorageBlockEntity;
import dev.itemexplorer.menu.StorageMenu;
import dev.itemexplorer.menu.NasMenu;
import dev.itemexplorer.block.NasBlock;
import dev.itemexplorer.block.NasBlockEntity;
import dev.itemexplorer.disk.DiskItem;
import dev.itemexplorer.disk.DiskTier;
import dev.itemexplorer.block.LogisticsPortBlock;
import dev.itemexplorer.block.LogisticsPortBlockEntity;
import dev.itemexplorer.menu.LogisticsPortMenu;
import dev.itemexplorer.block.ProductionPortBlock;
import dev.itemexplorer.block.ProductionPortBlockEntity;
import dev.itemexplorer.menu.ProductionPortMenu;
import dev.itemexplorer.block.BaseStationControllerBlock;
import dev.itemexplorer.block.BaseStationBlockEntity;
import dev.itemexplorer.block.BaseStationPartBlock;
import dev.itemexplorer.block.BaseStationMastBlock;
import dev.itemexplorer.menu.BaseStationMenu;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModContent {
    private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, ItemExplorer.MOD_ID);
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, ItemExplorer.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> ENTITIES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, ItemExplorer.MOD_ID);
    private static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(ForgeRegistries.MENU_TYPES, ItemExplorer.MOD_ID);
    private static final DeferredRegister<CreativeModeTab> CREATIVE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, ItemExplorer.MOD_ID);

    public static final RegistryObject<Block> STORAGE_BLOCK = BLOCKS.register("storage_terminal", StorageBlock::new);
    public static final RegistryObject<Item> STORAGE_ITEM = ITEMS.register("storage_terminal",
            () -> new BlockItem(STORAGE_BLOCK.get(), new Item.Properties()));
    public static final RegistryObject<BlockEntityType<StorageBlockEntity>> STORAGE_ENTITY = ENTITIES.register("storage_terminal",
            () -> BlockEntityType.Builder.of(StorageBlockEntity::new, STORAGE_BLOCK.get()).build(null));
    public static final RegistryObject<MenuType<StorageMenu>> STORAGE_MENU = MENUS.register("storage_terminal",
            () -> IForgeMenuType.create(StorageMenu::new));

    private ModContent() {}

    public static final RegistryObject<Block> DATA_CABLE_BLOCK = BLOCKS.register("data_cable", DataCableBlock::new);
    public static final RegistryObject<Item> DATA_CABLE_ITEM = blockItem("data_cable", DATA_CABLE_BLOCK);

    public static final RegistryObject<Block> LOGISTICS_BLOCK = BLOCKS.register("logistics_port", LogisticsPortBlock::new);
    public static final RegistryObject<Item> LOGISTICS_ITEM = ITEMS.register("logistics_port", () -> new BlockItem(LOGISTICS_BLOCK.get(), new Item.Properties()));
    public static final RegistryObject<BlockEntityType<LogisticsPortBlockEntity>> LOGISTICS_ENTITY = ENTITIES.register("logistics_port",
            () -> BlockEntityType.Builder.of(LogisticsPortBlockEntity::new, LOGISTICS_BLOCK.get()).build(null));
    public static final RegistryObject<MenuType<LogisticsPortMenu>> LOGISTICS_MENU = MENUS.register("logistics_port", () -> IForgeMenuType.create(LogisticsPortMenu::new));

    public static final RegistryObject<Block> PRODUCTION_BLOCK = BLOCKS.register("production_port", ProductionPortBlock::new);
    public static final RegistryObject<Item> PRODUCTION_ITEM = blockItem("production_port", PRODUCTION_BLOCK);
    public static final RegistryObject<BlockEntityType<ProductionPortBlockEntity>> PRODUCTION_ENTITY = ENTITIES.register("production_port",
            () -> BlockEntityType.Builder.of(ProductionPortBlockEntity::new, PRODUCTION_BLOCK.get()).build(null));
    public static final RegistryObject<MenuType<ProductionPortMenu>> PRODUCTION_MENU = MENUS.register("production_port",
            () -> IForgeMenuType.create(ProductionPortMenu::new));

    public static final RegistryObject<Block> NAS_BLOCK = BLOCKS.register("nas", NasBlock::new);
    public static final RegistryObject<Item> NAS_ITEM = ITEMS.register("nas", () -> new BlockItem(NAS_BLOCK.get(), new Item.Properties()));
    public static final RegistryObject<BlockEntityType<NasBlockEntity>> NAS_ENTITY = ENTITIES.register("nas",
            () -> BlockEntityType.Builder.of(NasBlockEntity::new, NAS_BLOCK.get()).build(null));
    public static final RegistryObject<MenuType<NasMenu>> NAS_MENU = MENUS.register("nas", () -> IForgeMenuType.create(NasMenu::new));

    public static final RegistryObject<Block> BASE_STATION_CASING_BLOCK = BLOCKS.register("base_station_casing",
            () -> new BaseStationPartBlock(BaseStationPartBlock.Part.CASING));
    public static final RegistryObject<Block> BASE_STATION_CONTROLLER_BLOCK = BLOCKS.register("base_station_controller", BaseStationControllerBlock::new);
    public static final RegistryObject<Block> BASE_STATION_NETWORK_PORT_BLOCK = BLOCKS.register("base_station_network_port",
            () -> new BaseStationPartBlock(BaseStationPartBlock.Part.NETWORK_PORT));
    public static final RegistryObject<Block> BASE_STATION_MODULE_BLOCK = BLOCKS.register("base_station_module",
            () -> new BaseStationPartBlock(BaseStationPartBlock.Part.MODULE));
    public static final RegistryObject<Block> BASE_STATION_MAST_BLOCK = BLOCKS.register("base_station_mast", BaseStationMastBlock::new);
    public static final RegistryObject<Block> BASE_STATION_ANTENNA_BLOCK = BLOCKS.register("base_station_antenna",
            () -> new BaseStationPartBlock(BaseStationPartBlock.Part.ANTENNA));
    public static final RegistryObject<Block> BASE_STATION_CAP_BLOCK = BLOCKS.register("base_station_cap",
            () -> new BaseStationPartBlock(BaseStationPartBlock.Part.CAP));
    public static final RegistryObject<Item> BASE_STATION_CASING_ITEM = blockItem("base_station_casing", BASE_STATION_CASING_BLOCK);
    public static final RegistryObject<Item> BASE_STATION_CONTROLLER_ITEM = blockItem("base_station_controller", BASE_STATION_CONTROLLER_BLOCK);
    public static final RegistryObject<Item> BASE_STATION_NETWORK_PORT_ITEM = blockItem("base_station_network_port", BASE_STATION_NETWORK_PORT_BLOCK);
    public static final RegistryObject<Item> BASE_STATION_MODULE_ITEM = blockItem("base_station_module", BASE_STATION_MODULE_BLOCK);
    public static final RegistryObject<Item> BASE_STATION_MAST_ITEM = blockItem("base_station_mast", BASE_STATION_MAST_BLOCK);
    public static final RegistryObject<Item> BASE_STATION_ANTENNA_ITEM = blockItem("base_station_antenna", BASE_STATION_ANTENNA_BLOCK);
    public static final RegistryObject<Item> BASE_STATION_CAP_ITEM = blockItem("base_station_cap", BASE_STATION_CAP_BLOCK);
    public static final RegistryObject<BlockEntityType<BaseStationBlockEntity>> BASE_STATION_ENTITY = ENTITIES.register("base_station_controller",
            () -> BlockEntityType.Builder.of(BaseStationBlockEntity::new, BASE_STATION_CONTROLLER_BLOCK.get()).build(null));
    public static final RegistryObject<MenuType<BaseStationMenu>> BASE_STATION_MENU = MENUS.register("base_station_controller",
            () -> IForgeMenuType.create(BaseStationMenu::new));
    private static RegistryObject<Item> blockItem(String name, RegistryObject<Block> block) {
        return ITEMS.register(name, () -> new BlockItem(block.get(), new Item.Properties()));
    }
    public static final RegistryObject<Item> DISK_64K = disk(DiskTier.K64);
    public static final RegistryObject<Item> DISK_256K = disk(DiskTier.K256);
    public static final RegistryObject<Item> DISK_1M = disk(DiskTier.M1);
    public static final RegistryObject<Item> DISK_16M = disk(DiskTier.M16);
    private static RegistryObject<Item> disk(DiskTier tier) { return ITEMS.register("disk_" + tier.id(), () -> new DiskItem(tier)); }

    public static final RegistryObject<CreativeModeTab> ITEM_EXPLORER_TAB = CREATIVE_TABS.register("item_explorer",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.itemexplorer"))
                    .icon(() -> STORAGE_ITEM.get().getDefaultInstance())
                    .displayItems((parameters, output) -> {
                        output.accept(STORAGE_ITEM.get());
                        output.accept(NAS_ITEM.get());
                        output.accept(LOGISTICS_ITEM.get());
                        output.accept(PRODUCTION_ITEM.get());
                        output.accept(DATA_CABLE_ITEM.get());
                        output.accept(DISK_64K.get());
                        output.accept(DISK_256K.get());
                        output.accept(DISK_1M.get());
                        output.accept(DISK_16M.get());
                        output.accept(BASE_STATION_CONTROLLER_ITEM.get());
                        output.accept(BASE_STATION_CASING_ITEM.get());
                        output.accept(BASE_STATION_NETWORK_PORT_ITEM.get());
                        output.accept(BASE_STATION_MODULE_ITEM.get());
                        output.accept(BASE_STATION_MAST_ITEM.get());
                        output.accept(BASE_STATION_ANTENNA_ITEM.get());
                        output.accept(BASE_STATION_CAP_ITEM.get());
                    })
                    .build());

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        ENTITIES.register(bus);
        MENUS.register(bus);
        CREATIVE_TABS.register(bus);
    }
}
