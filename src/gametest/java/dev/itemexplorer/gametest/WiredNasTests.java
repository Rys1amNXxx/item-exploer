package dev.itemexplorer.gametest;

import com.mojang.authlib.GameProfile;
import dev.itemexplorer.ItemExplorer;
import dev.itemexplorer.ModContent;
import dev.itemexplorer.block.BaseStationPartBlock;
import dev.itemexplorer.block.NasBlockEntity;
import dev.itemexplorer.block.StorageBlockEntity;
import dev.itemexplorer.cable.CableStorageAccess;
import dev.itemexplorer.disk.DiskItem;
import dev.itemexplorer.menu.StorageMenu;
import dev.itemexplorer.network.StorageNetwork;
import dev.itemexplorer.network.StorageNetwork.Action;
import dev.itemexplorer.station.StationConnection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.HashSet;
import java.util.UUID;

@GameTestHolder(ItemExplorer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class WiredNasTests {
    private record Scene(BlockPos terminal, BlockPos breakPoint, NasBlockEntity a, NasBlockEntity b, StorageMenu menu, ServerPlayer player) {}
    private static NasBlockEntity nas(GameTestHelper h, BlockPos p) {
        h.setBlock(p, ModContent.NAS_BLOCK.get());
        return (NasBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(p));
    }
    private static StorageMenu open(GameTestHelper h, BlockPos pos, ServerPlayer player) {
        player.setPos(pos.getX()+.5, pos.getY()+1, pos.getZ()+.5);
        StorageMenu menu = new StorageMenu(1, player.getInventory(), pos, (StorageBlockEntity) h.getLevel().getBlockEntity(pos));
        player.containerMenu = menu;
        return menu;
    }
    private static ServerPlayer player(GameTestHelper h) {
        return new FakePlayer(h.getLevel(), new GameProfile(UUID.randomUUID(), "wired-nas-test")) {
            @Override public boolean hasDisconnected() { return false; }
        };
    }
    private static Scene scene(GameTestHelper h) {
        BlockPos terminal = new BlockPos(0,1,2);
        h.setBlock(terminal, ModContent.STORAGE_BLOCK.get());
        for (int x = 1; x <= 3; x++) h.setBlock(new BlockPos(x,1,2), ModContent.DATA_CABLE_BLOCK.get());
        h.setBlock(new BlockPos(2,1,3), ModContent.DATA_CABLE_BLOCK.get());
        NasBlockEntity a = nas(h, new BlockPos(4,1,2)), b = nas(h, new BlockPos(2,1,4));
        a.install(0, new ItemStack(ModContent.DISK_64K.get())); b.install(0, new ItemStack(ModContent.DISK_64K.get()));
        ServerPlayer player = player(h); BlockPos pos = h.absolutePos(terminal);
        return new Scene(pos, h.absolutePos(new BlockPos(1,1,2)), a, b, open(h,pos,player), player);
    }
    private static StorageNetwork.Request request(StorageMenu menu, Action action, int id, long amount, String name) {
        CompoundTag view = menu.snapshot();
        return new StorageNetwork.Request(menu.containerId, view.getLong("Revision"), action, id, 0, amount, name, view.getLong("Session"));
    }
    private static void select(StorageMenu menu, NasBlockEntity nas, int bay) {
        menu.handle(request(menu, Action.SELECT_VOLUME, 0, 0, DiskItem.id(nas.disk(bay)).toString()));
    }
    private static long iron(ServerPlayer player) {
        long count = 0;
        for (int i=0; i<36; i++) if (player.getInventory().getItem(i).is(Items.IRON_INGOT)) count += player.getInventory().getItem(i).getCount();
        return count;
    }

    @GameTest(template = "empty")
    public static void twoRemoteNasExposeAllEightDrivesWithoutAnyStation(GameTestHelper h) {
        Scene s = scene(h);
        for (int i=1; i<4; i++) { s.a.install(i,new ItemStack(ModContent.DISK_64K.get())); s.b.install(i,new ItemStack(ModContent.DISK_64K.get())); }
        var view = s.menu.snapshot(); var drives = view.getList("Volumes", Tag.TAG_COMPOUND);
        h.assertTrue(view.getInt("NasCount") == 2 && view.getInt("WiredNasCount") == 2 && drives.size() == 9, "Not all remote drives were listed");
        var ids = new HashSet<String>();
        for (Tag tag : drives) {
            CompoundTag drive = (CompoundTag) tag; h.assertTrue(ids.add(drive.getString("Id")), "Drive identity appeared twice");
            h.assertTrue(drive.getBoolean("Online"), "A reachable drive is offline");
            if (!drive.getString("Id").isEmpty()) h.assertTrue(drive.contains("NasX") && drive.getInt("Bay") >= 1, "Drive lacks cabinet/bay metadata");
        }
        status(h, s, StationConnection.Status.NO_STATION);
        h.succeed();
    }
    private static void status(GameTestHelper h, Scene s, StationConnection.Status expected) {
        h.assertTrue(StationConnection.inspect(h.getLevel(), s.terminal, false).status() == expected, "Unexpected station status");
    }
    @GameTest(template = "empty")
    public static void remoteRenameWithdrawAndDepositStayOnSelectedDisk(GameTestHelper h) {
        Scene s = scene(h);
        s.a.volume(0).inventory().insert(new ItemStack(Items.IRON_INGOT,32), 32, 0);
        s.b.volume(0).inventory().insert(new ItemStack(Items.IRON_INGOT,19), 19, 0);
        select(s.menu,s.b,0);
        s.menu.handle(request(s.menu,Action.RENAME_DISK,0,0,"远端材料盘"));
        int entry = s.b.volume(0).inventory().entries().get(0).id();
        s.menu.handle(request(s.menu,Action.WITHDRAW,entry,7,""));
        s.menu.setCarried(new ItemStack(Items.GOLD_INGOT,5));
        s.menu.handle(request(s.menu,Action.DEPOSIT_CURSOR,0,0,""));
        h.assertTrue(s.a.volume(0).inventory().total()==32 && s.a.volume(0).name().isEmpty(), "Operation touched the other NAS");
        h.assertTrue(s.b.volume(0).inventory().total()==17 && s.b.volume(0).name().equals("远端材料盘") && iron(s.player)==7
                && s.menu.getCarried().isEmpty(), "Remote operation failed or duplicated inventory");
        h.succeed();
    }
    @GameTest(template = "empty")
    public static void disconnectedOldRequestIsRejectedBeforeNextMenuPoll(GameTestHelper h) {
        Scene s = scene(h); var inventory = s.a.volume(0).inventory();
        inventory.insert(new ItemStack(Items.IRON_INGOT,32),32,0); select(s.menu,s.a,0);
        int entry=inventory.entries().get(0).id();
        var old = request(s.menu,Action.WITHDRAW,entry,10,"");
        h.getLevel().setBlockAndUpdate(s.breakPoint,Blocks.AIR.defaultBlockState());
        s.menu.handle(old);
        h.assertTrue(inventory.total()==32 && iron(s.player)==0 && !s.menu.snapshot().getBoolean("Available"), "Disconnected cached drive was still writable");
        h.getLevel().setBlockAndUpdate(s.breakPoint,ModContent.DATA_CABLE_BLOCK.get().defaultBlockState());
        s.menu.handle(old);
        h.assertTrue(inventory.total()==32 && iron(s.player)==0, "Reconnection accepted the previous session");
        s.menu.handle(request(s.menu,Action.WITHDRAW,entry,10,""));
        h.assertTrue(inventory.total()==22 && iron(s.player)==10, "Fresh request after reconnect failed");
        h.succeed();
    }
    @GameTest(template = "empty")
    public static void movingDiskBetweenReachableNasInvalidatesOldMountSession(GameTestHelper h) {
        Scene s = scene(h); var disk=s.a.volume(0); disk.inventory().insert(new ItemStack(Items.IRON_INGOT,16),16,0);
        select(s.menu,s.a,0); int entry=disk.inventory().entries().get(0).id();
        var old=request(s.menu,Action.WITHDRAW,entry,8,"");
        s.b.install(3,s.a.eject(0)); s.menu.handle(old);
        h.assertTrue(disk.inventory().total()==16 && iron(s.player)==0, "Moving disks did not invalidate the mount session");
        s.menu.handle(request(s.menu,Action.WITHDRAW,entry,8,""));
        h.assertTrue(disk.inventory().total()==8 && iron(s.player)==8 && s.menu.snapshot().getString("Volume").equals(disk.id().toString()), "Moved drive did not reconnect by stable ID");
        h.succeed();
    }
    @GameTest(template = "empty")
    public static void replacingRemoteNasAtSamePositionCannotRetargetOldDisk(GameTestHelper h) {
        Scene s=scene(h); var original=s.a.volume(0); original.inventory().insert(new ItemStack(Items.IRON_INGOT,16),16,0);
        select(s.menu,s.a,0); var old=request(s.menu,Action.WITHDRAW,original.inventory().entries().get(0).id(),8,"");
        BlockPos p=s.a.getBlockPos(); h.getLevel().setBlockAndUpdate(p,Blocks.AIR.defaultBlockState());
        h.getLevel().setBlockAndUpdate(p,ModContent.NAS_BLOCK.get().defaultBlockState());
        NasBlockEntity replacement=(NasBlockEntity)h.getLevel().getBlockEntity(p);
        replacement.install(0,new ItemStack(ModContent.DISK_64K.get())); replacement.volume(0).inventory().insert(new ItemStack(Items.IRON_INGOT,99),99,0);
        s.menu.handle(old);
        h.assertTrue(original.inventory().total()==16 && replacement.volume(0).inventory().total()==99 && iron(s.player)==0
                && !s.menu.snapshot().getBoolean("Available"), "Same-position replacement silently retargeted storage");
        h.succeed();
    }
    @GameTest(template = "empty")
    public static void twoTerminalsShareOneAuthoritativeDiskAndRejectStaleWithdrawal(GameTestHelper h) {
        Scene s=scene(h); var inventory=s.a.volume(0).inventory(); inventory.insert(new ItemStack(Items.IRON_INGOT,32),32,0);
        BlockPos second=h.absolutePos(new BlockPos(2,2,2)); h.getLevel().setBlockAndUpdate(second,ModContent.STORAGE_BLOCK.get().defaultBlockState());
        ServerPlayer peer=player(h); StorageMenu other=open(h,second,peer);
        select(s.menu,s.a,0); select(other,s.a,0); int entry=inventory.entries().get(0).id();
        var stale=request(other,Action.WITHDRAW,entry,20,"");
        s.menu.handle(request(s.menu,Action.WITHDRAW,entry,20,"")); other.handle(stale);
        h.assertTrue(inventory.total()==12 && iron(peer)==0, "Second terminal used a stale inventory revision");
        other.handle(request(other,Action.WITHDRAW,entry,20,""));
        h.assertTrue(inventory.total()==0 && iron(peer)+iron(s.player)==32, "Shared NAS duplicated or lost items");
        h.succeed();
    }
    @GameTest(template = "empty")
    public static void adjacentAndWiredRoutesDeduplicateSameNas(GameTestHelper h) {
        Scene s=scene(h); var adjacent=nas(h,new BlockPos(0,1,3)); adjacent.install(0,new ItemStack(ModContent.DISK_64K.get()));
        h.setBlock(new BlockPos(1,1,3),ModContent.DATA_CABLE_BLOCK.get());
        var view=s.menu.snapshot();
        h.assertTrue(view.getInt("NasCount")==3 && view.getList("Volumes",Tag.TAG_COMPOUND).size()==4, "Dual path listed a cabinet/drive twice");
        select(s.menu,adjacent,0);
        h.getLevel().setBlockAndUpdate(s.breakPoint,Blocks.AIR.defaultBlockState());
        h.setBlock(new BlockPos(1,1,3),Blocks.AIR);
        h.assertTrue(s.menu.snapshot().getBoolean("Available") && s.menu.snapshot().getInt("NasCount")==1, "Existing direct rear NAS connection was lost");
        h.succeed();
    }
    @GameTest(template = "empty")
    public static void conflictingStationsDoNotDisableLocalWiredNas(GameTestHelper h) {
        Scene s=scene(h);
        for (int x : new int[]{1,3}) h.setBlock(new BlockPos(x,1,1),ModContent.BASE_STATION_NETWORK_PORT_BLOCK.get().defaultBlockState().setValue(BaseStationPartBlock.FACING,Direction.SOUTH));
        status(h,s,StationConnection.Status.CONFLICT); select(s.menu,s.a,0);
        h.assertTrue(s.menu.snapshot().getBoolean("Available") && CableStorageAccess.discover(h.getLevel(),s.terminal).cabinets().size()==2,
                "Station policy blocked the independent local storage network");
        h.succeed();
    }
    @GameTest(template = "empty")
    public static void disconnectedSearchCannotContinueUsingOldRemoteInventory(GameTestHelper h) {
        Scene s=scene(h); select(s.menu,s.a,0); var view=s.menu.snapshot();
        var start=new StorageNetwork.SearchRequest(s.menu.containerId,view.getLong("Session"),1,view.getLong("SearchRevision"),0,
                StorageNetwork.SearchAction.START,true,0,4,0,0,view.getLong("Revision"),new int[0]);
        s.menu.handleSearch(start); h.assertTrue(s.menu.snapshot().getBoolean("Searching"),"Remote search did not start");
        h.getLevel().setBlockAndUpdate(s.breakPoint,Blocks.AIR.defaultBlockState()); s.menu.handleSearch(start);
        view=s.menu.snapshot();
        h.assertTrue(!view.getBoolean("Searching") && !view.getBoolean("Available"),"Disconnected search kept its old session/catalog");
        h.succeed();
    }
}
