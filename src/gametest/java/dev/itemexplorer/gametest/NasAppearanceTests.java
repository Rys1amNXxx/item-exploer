package dev.itemexplorer.gametest;

import dev.itemexplorer.ItemExplorer;
import dev.itemexplorer.ModContent;
import dev.itemexplorer.block.NasBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

@GameTestHolder(ItemExplorer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class NasAppearanceTests {
    @GameTest(template = "empty")
    public static void recessedCaseKeepsNeighborFacesInEveryOrientation(GameTestHelper h) {
        BlockPos pos = h.absolutePos(new BlockPos(2, 2, 2));
        var level = h.getLevel();
        var stone = Blocks.STONE.defaultBlockState();
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            var nas = ModContent.NAS_BLOCK.get().defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, facing);
            level.setBlockAndUpdate(pos, nas);
            h.assertTrue(!Shapes.joinIsNotEmpty(nas.getCollisionShape(level, pos), Shapes.block(), BooleanOp.NOT_SAME)
                    && !Shapes.joinIsNotEmpty(nas.getShape(level, pos), Shapes.block(), BooleanOp.NOT_SAME),
                    "NAS occlusion fix changed collision or selection bounds");
            for (Direction side : Direction.values()) {
                BlockPos neighbor = pos.relative(side);
                for (var adjacent : new net.minecraft.world.level.block.state.BlockState[]{stone, Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.GLASS.defaultBlockState()}) {
                    level.setBlockAndUpdate(neighbor, adjacent);
                    h.assertTrue(Block.shouldRenderFace(adjacent, level, neighbor, side.getOpposite(), pos),
                            "NAS hides an exposed neighbor face: facing=" + facing + ", side=" + side + ", neighbor=" + adjacent);
                }
                level.setBlockAndUpdate(neighbor, stone);
            }
            h.assertTrue(!nas.getFaceOcclusionShape(level, pos, facing).isEmpty(), "Solid front rim stopped providing any occlusion");
            h.assertTrue(nas.getFaceOcclusionShape(level, pos, facing.getOpposite()).isEmpty(), "Inset back still occludes its neighbor");
        }
        // Full-cube terminals retain normal face culling; this fix is specific to the NAS.
        level.setBlockAndUpdate(pos, ModContent.STORAGE_BLOCK.get().defaultBlockState());
        for (Direction side : Direction.values()) h.assertTrue(!Block.shouldRenderFace(stone, level, pos.relative(side), side.getOpposite(), pos),
                "NAS fix disabled face culling for full-cube terminals");
        h.succeed();
    }

    private static NasBlockEntity nas(GameTestHelper h, int x, int z, Direction facing) {
        BlockPos pos = new BlockPos(x, 2, z);
        h.setBlock(pos, ModContent.NAS_BLOCK.get().defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, facing));
        return (NasBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(pos));
    }

    @GameTest(template = "empty")
    public static void appearanceTracksMountsAndOnlySyncsPublicState(GameTestHelper h) {
        NasBlockEntity empty = nas(h, 1, 1, Direction.NORTH);
        NasBlockEntity sparse = nas(h, 3, 1, Direction.EAST);
        NasBlockEntity full = nas(h, 1, 3, Direction.SOUTH);
        NasBlockEntity fault = nas(h, 3, 3, Direction.WEST);
        sparse.install(0, new ItemStack(ModContent.DISK_64K.get()));
        sparse.install(2, new ItemStack(ModContent.DISK_16M.get()));
        var tiers = new net.minecraft.world.item.Item[]{ModContent.DISK_64K.get(), ModContent.DISK_256K.get(), ModContent.DISK_1M.get(), ModContent.DISK_16M.get()};
        for (int i = 0; i < 4; i++) full.install(i, new ItemStack(tiers[i]));
        fault.install(1, new ItemStack(ModContent.DISK_1M.get()));
        CompoundTag broken = fault.saveWithFullMetadata();
        broken.getCompound("Nas").getList("Bays", Tag.TAG_COMPOUND).getCompound(1)
                .getCompound("Stack").getCompound("tag").putUUID("DiskId", UUID.randomUUID());
        fault.load(broken); fault.onLoad();
        h.assertTrue(!empty.hasVisibleDisk(0) && !empty.isVisibleLocked(), "Empty NAS has phantom disks");
        h.assertTrue(sparse.hasVisibleDisk(0) && !sparse.hasVisibleDisk(1) && sparse.hasVisibleDisk(2)
                && sparse.isVisibleOnline(2) && sparse.visibleTier(2) == 3, "Sparse bays or tier badge are incorrect");
        for (int i = 0; i < 4; i++) h.assertTrue(full.hasVisibleDisk(i) && full.isVisibleOnline(i)
                && full.visibleTier(i) == i && !full.isVisibleActive(i), "Installation falsely showed activity or lost a bay");
        h.assertTrue(fault.hasVisibleDisk(1) && !fault.isVisibleOnline(1), "Missing disk data did not show a fault");
        sparse.volume(0).inventory().insert(new ItemStack(Items.DIAMOND, 64), 64, 0);
        sparse.rename(0, "private disk name");
        CompoundTag publicTag = sparse.getUpdateTag();
        h.assertTrue(publicTag.getAllKeys().equals(java.util.Set.of("Appearance")), "World rendering leaked disk or inventory data");
        CompoundTag before = sparse.saveWithFullMetadata();
        NasBlockEntity replica = new NasBlockEntity(sparse.getBlockPos(), sparse.getBlockState());
        replica.load(before); replica.handleUpdateTag(publicTag);
        h.assertTrue(replica.hasVisibleDisk(2) && replica.visibleTier(2) == 3
                && before.equals(replica.saveWithFullMetadata()), "Chunk visual sync overwrote persistent disk state");
        replica.onDataPacket(null, sparse.getUpdatePacket());
        h.assertTrue(before.equals(replica.saveWithFullMetadata()), "Block update packet was treated as a saved inventory");
        sparse.eject(2);
        h.assertTrue(!sparse.hasVisibleDisk(2) && !sparse.isVisibleOnline(2), "Ejected disk remains visible");
        CompoundTag protectedTag = new CompoundTag(); protectedTag.putInt("Version", 99);
        CompoundTag protectedNas = new CompoundTag(); protectedNas.put("Nas", protectedTag);
        empty.load(protectedNas); empty.onLoad();
        h.assertTrue(empty.isVisibleLocked() && protectedTag.equals(empty.saveWithFullMetadata().get("Nas")), "Fault display changed protected data");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void activityOnlyFollowsRealTransfersAndExpires(GameTestHelper h) {
        NasBlockEntity nas = nas(h, 2, 2, Direction.NORTH);
        nas.install(0, new ItemStack(ModContent.DISK_64K.get()));
        var storage = nas.volume(0).inventory();
        int folder = storage.createFolder(0, "folder");
        storage.renameFolder(folder, "renamed"); nas.rename(0, "renamed disk");
        storage.insert(new ItemStack(Items.IRON_INGOT, 64), 64, 0, true);
        storage.view(0, 0, "");
        h.runAtTickTime(7, () -> {
            h.assertTrue(!nas.isVisibleActive(0), "Browsing, renaming or simulation flashed an activity light");
            storage.insert(new ItemStack(Items.IRON_INGOT, 64), 64, 0);
        });
        h.runAtTickTime(13, () -> {
            h.assertTrue(nas.isVisibleActive(0) && !nas.isVisibleActive(1), "Successful insert did not light only its bay");
            storage.take(1, 1, true); storage.insert(ItemStack.EMPTY, 1, 0);
        });
        h.runAtTickTime(28, () -> {
            h.assertTrue(!nas.isVisibleActive(0) && nas.isVisibleOnline(0), "Activity failed to expire back to online");
            storage.take(1, 1);
        });
        h.runAtTickTime(34, () -> {
            h.assertTrue(nas.isVisibleActive(0) && storage.total() == 63, "Withdrawal activity changed inventory results");
            storage.move(1, folder, 63);
            ItemStack disk = nas.eject(0); nas.install(0, disk);
            h.assertTrue(!nas.isVisibleActive(0), "Reinsert inherited a previous mount's pulse");
        });
        h.runAtTickTime(42, () -> {
            h.assertTrue(!nas.isVisibleActive(0) && storage.total() == 63, "Remount replayed old transfer activity");
            storage.move(1, 0, 63);
        });
        h.runAtTickTime(48, () -> {
            h.assertTrue(nas.isVisibleActive(0), "Real folder movement did not trigger activity"); h.succeed();
        });
    }
}
