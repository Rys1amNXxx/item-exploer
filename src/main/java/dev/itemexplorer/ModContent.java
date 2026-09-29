package dev.itemexplorer;

import dev.itemexplorer.block.StorageBlock;
import dev.itemexplorer.block.StorageBlockEntity;
import dev.itemexplorer.menu.StorageMenu;
import dev.itemexplorer.menu.NasMenu;
import dev.itemexplorer.block.NasBlock;
import dev.itemexplorer.block.NasBlockEntity;
import dev.itemexplorer.disk.DiskItem;
import dev.itemexplorer.disk.DiskTier;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
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

    public static final RegistryObject<Block> STORAGE_BLOCK = BLOCKS.register("storage_terminal", StorageBlock::new);
    public static final RegistryObject<Item> STORAGE_ITEM = ITEMS.register("storage_terminal",
            () -> new BlockItem(STORAGE_BLOCK.get(), new Item.Properties()));
    public static final RegistryObject<BlockEntityType<StorageBlockEntity>> STORAGE_ENTITY = ENTITIES.register("storage_terminal",
            () -> BlockEntityType.Builder.of(StorageBlockEntity::new, STORAGE_BLOCK.get()).build(null));
    public static final RegistryObject<MenuType<StorageMenu>> STORAGE_MENU = MENUS.register("storage_terminal",
            () -> IForgeMenuType.create(StorageMenu::new));

    private ModContent() {}

    public static final RegistryObject<Block> NAS_BLOCK = BLOCKS.register("nas", NasBlock::new);
    public static final RegistryObject<Item> NAS_ITEM = ITEMS.register("nas", () -> new BlockItem(NAS_BLOCK.get(), new Item.Properties()));
    public static final RegistryObject<BlockEntityType<NasBlockEntity>> NAS_ENTITY = ENTITIES.register("nas",
            () -> BlockEntityType.Builder.of(NasBlockEntity::new, NAS_BLOCK.get()).build(null));
    public static final RegistryObject<MenuType<NasMenu>> NAS_MENU = MENUS.register("nas", () -> IForgeMenuType.create(NasMenu::new));
    public static final RegistryObject<Item> DISK_64K = disk(DiskTier.K64);
    public static final RegistryObject<Item> DISK_256K = disk(DiskTier.K256);
    public static final RegistryObject<Item> DISK_1M = disk(DiskTier.M1);
    public static final RegistryObject<Item> DISK_16M = disk(DiskTier.M16);
    private static RegistryObject<Item> disk(DiskTier tier) { return ITEMS.register("disk_" + tier.id(), () -> new DiskItem(tier)); }

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        ENTITIES.register(bus);
        MENUS.register(bus);
    }
}
