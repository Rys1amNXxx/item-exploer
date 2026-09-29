package dev.itemexplorer.gametest;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import dev.itemexplorer.ItemExplorer;
import dev.itemexplorer.ModContent;
import dev.itemexplorer.block.LogisticsPortBlock;
import dev.itemexplorer.block.LogisticsPortBlockEntity;
import dev.itemexplorer.block.NasBlockEntity;
import dev.itemexplorer.block.StorageBlockEntity;
import dev.itemexplorer.storage.StorageInventory;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.NetworkHooks;

import java.util.List;
import java.util.Locale;

/** Disposable manual acceptance fixtures. Lives only in the gametest source set. */
@Mod.EventBusSubscriber(modid = ItemExplorer.MOD_ID)
public final class AcceptanceReviewScene {
    private static final BlockPos NAS = new BlockPos(20, 82, 0);
    private static final BlockPos TERMINAL = NAS.north();
    private static final BlockPos PORT = NAS.east();
    private static final BlockPos PROTECTED = new BlockPos(16, 82, 0);
    private static final BlockPos PORT_DISPLAY = new BlockPos(27, 84, 0);
    private static final SimpleCommandExceptionType TEST_WORLD_ONLY = new SimpleCommandExceptionType(
            Component.literal("Acceptance helpers require a world under work/acceptance-ui/ or -Ditemexplorer.acceptance=true."));
    private static final SimpleCommandExceptionType SETUP_FIRST = new SimpleCommandExceptionType(
            Component.literal("Run /itemexplorer-acceptance setup in this test world first."));

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        if (!Boolean.getBoolean("itemexplorer.review")) return;
        event.getDispatcher().register(Commands.literal("itemexplorer-acceptance").requires(s -> s.hasPermission(2))
                .then(Commands.literal("setup").executes(c -> setup(c.getSource())))
                .then(Commands.literal("nas").executes(c -> open(c.getSource(), NAS)))
                .then(Commands.literal("terminal").executes(c -> open(c.getSource(), TERMINAL)))
                .then(Commands.literal("port").executes(c -> open(c.getSource(), PORT)))
                .then(Commands.literal("protected").executes(c -> open(c.getSource(), PROTECTED)))
                .then(Commands.literal("full-inventory").executes(c -> inventory(c.getSource(), true)))
                .then(Commands.literal("clear-inventory").executes(c -> inventory(c.getSource(), false)))
                .then(Commands.literal("view-nas").executes(c -> view(c.getSource(), false)))
                .then(Commands.literal("view-ports").executes(c -> view(c.getSource(), true))));
    }

    private static ServerPlayer player(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        String path = player.serverLevel().getServer().getWorldPath(LevelResource.ROOT)
                .toAbsolutePath().normalize().toString().replace('\\', '/').toLowerCase(Locale.ROOT) + "/";
        if (!Boolean.getBoolean("itemexplorer.acceptance") && !path.contains("/work/acceptance-ui/"))
            throw TEST_WORLD_ONLY.create();
        return player;
    }

    private static List<Item> disks() {
        return List.of(ModContent.DISK_64K.get(), ModContent.DISK_256K.get(), ModContent.DISK_1M.get(), ModContent.DISK_16M.get());
    }

    private static NasBlockEntity placeNas(ServerLevel level, BlockPos pos) {
        level.setBlockAndUpdate(pos, ModContent.NAS_BLOCK.get().defaultBlockState()
                .setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH));
        return (NasBlockEntity) level.getBlockEntity(pos);
    }

    private static int seed(StorageInventory inventory) {
        int parent = inventory.createFolder(0, "公共材料");
        int child = inventory.createFolder(parent, "铜材子目录");
        inventory.insert(new ItemStack(Items.IRON_INGOT, 64), 64, parent, false);
        inventory.insert(new ItemStack(Items.COPPER_INGOT, 32), 32, child, false);
        inventory.createFolder(0, "铁路工程预留");
        for (int i = 1; i <= 8; i++) inventory.createFolder(parent, "滚动验收目录" + i);
        return parent;
    }

    private static LogisticsPortBlockEntity placePort(ServerLevel level, BlockPos host, Direction side,
                                                      NasBlockEntity nas, int folder) {
        BlockPos pos = host.relative(side);
        level.setBlockAndUpdate(pos, ModContent.LOGISTICS_BLOCK.get().defaultBlockState()
                .setValue(LogisticsPortBlock.FACING, side));
        var port = (LogisticsPortBlockEntity) level.getBlockEntity(pos);
        port.configure(nas.volume(0).id().toString(), folder, true, true, false);
        return port;
    }

    private static int setup(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = player(source);
        ServerLevel level = player.serverLevel();
        player.closeContainer();
        // Explicitly disposable area: x=12..31, z=-6..9, y=81..89.
        for (int x = 12; x <= 31; x++) for (int z = -6; z <= 9; z++) {
            for (int y = 82; y <= 89; y++) level.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(x, 81, z), Blocks.SMOOTH_STONE.defaultBlockState());
        }
        NasBlockEntity nas = placeNas(level, NAS);
        int folder = 0;
        for (int i = 0; i < disks().size(); i++) {
            nas.install(i, new ItemStack(disks().get(i)));
            nas.rename(i, List.of("材料盘64K", "工程盘256K", "设备盘1M", "大容量盘16M").get(i));
            int parent = seed(nas.volume(i).inventory());
            if (i == 0) folder = parent;
        }
        nas.volume(3).inventory().insert(new ItemStack(Items.COBBLESTONE, 16_000_000), 16_000_000, 0, false);
        level.setBlockAndUpdate(TERMINAL, ModContent.STORAGE_BLOCK.get().defaultBlockState()
                .setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH));
        var terminal = (StorageBlockEntity) level.getBlockEntity(TERMINAL);
        seed(terminal.inventory());
        terminal.inventory().insert(new ItemStack(Items.WHEAT, 16), 16, 0, false);
        placePort(level, NAS, Direction.EAST, nas, folder);

        NasBlockEntity display = placeNas(level, PORT_DISPLAY);
        display.install(0, new ItemStack(ModContent.DISK_64K.get()));
        int displayFolder = seed(display.volume(0).inventory());
        for (Direction side : Direction.values()) placePort(level, PORT_DISPLAY, side, display, displayFolder);
        level.setBlockAndUpdate(PORT_DISPLAY.east(2), Blocks.CHEST.defaultBlockState());

        // North-facing NAS examples with stone, grass and glass against their east/back faces.
        var neighbors = List.of(Blocks.STONE, Blocks.GRASS_BLOCK, Blocks.GLASS);
        for (int i = 0; i < neighbors.size(); i++) {
            BlockPos pos = new BlockPos(14 + 5 * i, 82, 5);
            NasBlockEntity example = placeNas(level, pos);
            example.install(i, new ItemStack(disks().get(i)));
            level.setBlockAndUpdate(pos.east(), neighbors.get(i).defaultBlockState());
            level.setBlockAndUpdate(pos.south(), neighbors.get(i).defaultBlockState());
        }

        level.setBlockAndUpdate(PROTECTED, ModContent.STORAGE_BLOCK.get().defaultBlockState());
        var locked = (StorageBlockEntity) level.getBlockEntity(PROTECTED);
        CompoundTag badStorage = (CompoundTag) locked.inventory().save();
        badStorage.putInt("Version", 999);
        CompoundTag badTerminal = new CompoundTag();
        badTerminal.put("Storage", badStorage);
        locked.load(badTerminal);
        locked.setChanged();

        player.setGameMode(GameType.CREATIVE);
        for (int i = 0; i < 36; i++) player.getInventory().setItem(i, ItemStack.EMPTY);
        for (int i = 0; i < disks().size(); i++) player.getInventory().setItem(i, new ItemStack(disks().get(i)));
        player.getInventory().setItem(4, new ItemStack(Items.IRON_INGOT, 64));
        player.getInventory().setItem(5, new ItemStack(Items.COPPER_INGOT, 32));
        player.getInventory().setItem(6, new ItemStack(ModContent.LOGISTICS_ITEM.get()));
        player.getInventory().selected = 8;
        player.getInventory().setChanged();
        player.inventoryMenu.broadcastChanges();
        level.setDayTime(6000);
        source.sendSuccess(() -> Component.literal("验收场景已生成：NAS (20,82,0)，终端在北侧，接口在东侧；六向展示 (27,84,0)；石/草/玻璃对照位于 z=5。"), false);
        return view(source, false);
    }

    private static int open(CommandSourceStack source, BlockPos pos) throws CommandSyntaxException {
        ServerPlayer player = player(source);
        var entity = player.serverLevel().getBlockEntity(pos);
        if (!(entity instanceof MenuProvider provider)) throw SETUP_FIRST.create();
        player.closeContainer();
        player.teleportTo(player.serverLevel(), pos.getX() + 0.5, 82, pos.getZ() - 2.0, 0, 10);
        NetworkHooks.openScreen(player, provider, pos);
        return 1;
    }

    private static int inventory(CommandSourceStack source, boolean full) throws CommandSyntaxException {
        ServerPlayer player = player(source);
        player.closeContainer();
        for (int i = 0; i < 36; i++) player.getInventory().setItem(i, full ? new ItemStack(Items.DIRT, 64) : ItemStack.EMPTY);
        player.getInventory().setChanged();
        player.inventoryMenu.broadcastChanges();
        source.sendSuccess(() -> Component.literal(full ? "测试玩家的36格主背包已填满泥土。" : "测试玩家的36格主背包已清空。"), false);
        return 1;
    }

    private static int view(CommandSourceStack source, boolean ports) throws CommandSyntaxException {
        ServerPlayer player = player(source);
        player.closeContainer();
        if (ports) player.teleportTo(player.serverLevel(), 30.5, 84, -4.5, 38, 15);
        else player.teleportTo(player.serverLevel(), 22.8, 82, -4.2, 35, 14);
        return 1;
    }
}
