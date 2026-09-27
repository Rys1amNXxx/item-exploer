package dev.itemexplorer;

import dev.itemexplorer.block.StorageBlock;
import dev.itemexplorer.block.StorageBlockEntity;
import dev.itemexplorer.menu.StorageMenu;
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

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        ENTITIES.register(bus);
        MENUS.register(bus);
    }
}
