package dev.itemexplorer.gametest;

import dev.itemexplorer.ItemExplorer;
import dev.itemexplorer.ModContent;
import dev.itemexplorer.block.*;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Manual review fixture, enabled only in the separate review launch profile. Never shipped. */
@Mod.EventBusSubscriber(modid = ItemExplorer.MOD_ID)
public final class LogisticsReviewScene {
    @SubscribeEvent public static void register(RegisterCommandsEvent event) {
        if (!Boolean.getBoolean("itemexplorer.review")) return;
        event.getDispatcher().register(Commands.literal("itemexplorer-review").requires(s -> s.hasPermission(2)).executes(context -> {
            var player = context.getSource().getPlayerOrException(); var level = player.serverLevel();
            BlockPos base = new BlockPos(0, 82, 0);
            for (int x=-4;x<=5;x++) for (int z=-5;z<=4;z++) {
                level.setBlockAndUpdate(new BlockPos(x,81,z), Blocks.SMOOTH_STONE.defaultBlockState());
                for(int y=82;y<=87;y++) level.setBlockAndUpdate(new BlockPos(x,y,z), Blocks.AIR.defaultBlockState());
            }
            level.setBlockAndUpdate(base, ModContent.NAS_BLOCK.get().defaultBlockState());
            var nas = (NasBlockEntity) level.getBlockEntity(base);
            var disks = java.util.List.of(ModContent.DISK_64K.get(),ModContent.DISK_256K.get(),ModContent.DISK_1M.get(),ModContent.DISK_16M.get());
            for(int i=0;i<4;i++) nas.install(i,new ItemStack(disks.get(i)));
            var storage = nas.volume(0).inventory(); int folder = storage.createFolder(0,"公共材料"), child = storage.createFolder(folder,"金属");
            storage.createFolder(0,"铁路工程"); storage.insert(new ItemStack(Items.IRON_INGOT,64),64,child);
            for(int i=0;i<7;i++) storage.createFolder(folder,"材料 " + (i+1));
            for(Direction side:java.util.List.of(Direction.EAST,Direction.UP)) {
                BlockPos pos=base.relative(side);level.setBlockAndUpdate(pos,ModContent.LOGISTICS_BLOCK.get().defaultBlockState().setValue(LogisticsPortBlock.FACING,side));
                ((LogisticsPortBlockEntity)level.getBlockEntity(pos)).configure(nas.volume(0).id().toString(),folder,true,true,true);
            }
            BlockPos terminal=base.west(2);level.setBlockAndUpdate(terminal,ModContent.STORAGE_BLOCK.get().defaultBlockState().setValue(HorizontalDirectionalBlock.FACING,Direction.NORTH));
            level.setBlockAndUpdate(terminal.above(),ModContent.LOGISTICS_BLOCK.get().defaultBlockState().setValue(LogisticsPortBlock.FACING,Direction.UP));
            player.setGameMode(GameType.CREATIVE);player.teleportTo(level,3.6,82,-3.6,45,15);
            for(int i=0;i<4;i++)player.getInventory().setItem(i,new ItemStack(disks.get(i)));
            player.getInventory().setItem(4,new ItemStack(ModContent.LOGISTICS_ITEM.get()));
            player.getInventory().setItem(5,new ItemStack(ModContent.NAS_ITEM.get()));
            player.getInventory().setItem(6,new ItemStack(ModContent.STORAGE_ITEM.get()));
            player.getInventory().setItem(7,new ItemStack(Items.IRON_INGOT,64));
            player.getInventory().setItem(8,ItemStack.EMPTY);player.getInventory().selected=8;player.getInventory().setChanged();
            level.setDayTime(6000);return 1;
        }));
    }
}
