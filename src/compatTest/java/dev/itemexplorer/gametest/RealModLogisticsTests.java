package dev.itemexplorer.gametest;

import appeng.api.config.Actionable;
import appeng.api.util.AEColor;
import appeng.api.networking.security.IActionSource;
import appeng.api.parts.PartHelper;
import appeng.api.stacks.AEItemKey;
import appeng.core.definitions.AEParts;
import appeng.parts.automation.ForgeExternalStorageStrategy;
import dev.itemexplorer.ItemExplorer;
import dev.itemexplorer.ModContent;
import dev.itemexplorer.block.LogisticsPortBlock;
import dev.itemexplorer.block.LogisticsPortBlockEntity;
import dev.itemexplorer.block.NasBlockEntity;
import mekanism.common.lib.transmitter.ConnectionType;
import mekanism.common.tile.transmitter.TileEntityLogisticalTransporter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

@GameTestHolder(ItemExplorer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RealModLogisticsTests {
    private record Setup(NasBlockEntity nas, LogisticsPortBlockEntity port, int folder) {}
    private static Block block(String id) { return ForgeRegistries.BLOCKS.getValue(ResourceLocation.parse(id)); }
    private static Setup setup(GameTestHelper h, int x, int z) {
        BlockPos pos = h.absolutePos(new BlockPos(x,3,z));
        h.getLevel().setBlockAndUpdate(pos, ModContent.NAS_BLOCK.get().defaultBlockState());
        var nas = (NasBlockEntity)h.getLevel().getBlockEntity(pos); nas.install(0,new ItemStack(ModContent.DISK_64K.get()));
        int folder = nas.volume(0).inventory().createFolder(0,"Public");
        h.getLevel().setBlockAndUpdate(pos.north(),ModContent.LOGISTICS_BLOCK.get().defaultBlockState().setValue(LogisticsPortBlock.FACING,Direction.NORTH));
        var port=(LogisticsPortBlockEntity)h.getLevel().getBlockEntity(pos.north());port.configure(nas.volume(0).id().toString(),folder,true,true,false);
        return new Setup(nas,port,folder);
    }
    @GameTest(template="empty")
    public static void ae2RealStorageAdapterListsCountsAndHonorsSimulation(GameTestHelper h) {
        var s=setup(h,2,3); var storage=s.nas.volume(0).inventory();
        storage.insert(new ItemStack(Items.GOLD_INGOT,7),7,0);
        var wrapper=ForgeExternalStorageStrategy.createItem(h.getLevel(),s.port.getBlockPos(),Direction.NORTH).createWrapper(false,()->{});
        var iron=AEItemKey.of(Items.IRON_INGOT);var gold=AEItemKey.of(Items.GOLD_INGOT);var source=IActionSource.empty();
        h.assertTrue(wrapper != null,"AE2 failed to discover interface");
        long revision=storage.revision();
        h.assertTrue(wrapper.insert(iron,1024,Actionable.SIMULATE,source)==1024 && storage.revision()==revision,"AE2 insertion simulation mutated storage");
        h.assertTrue(wrapper.insert(iron,1024,Actionable.MODULATE,source)==1024 && storage.total()==1031,"AE2 failed large insertion");
        h.assertTrue(wrapper.getAvailableStacks().get(iron)==1024 && wrapper.getAvailableStacks().get(gold)==0,"AE2 count wrong or hidden folder leaked");
        h.assertTrue(wrapper.extract(iron,128,Actionable.SIMULATE,source)==128 && storage.total()==1031,"AE2 simulated extraction changed count");
        h.assertTrue(wrapper.extract(iron,128,Actionable.MODULATE,source)==128 && storage.total()==903,"AE2 extraction lost items");
        s.nas.eject(0);h.assertTrue(wrapper.extract(iron,128,Actionable.MODULATE,source)==0,"AE2 retained access after disk ejection");h.succeed();
    }
    private static void mekanism(GameTestHelper h, boolean extract) {
        var s=setup(h,2,3);BlockPos pipePos=s.port.getBlockPos().north(), chestPos=pipePos.north();
        h.getLevel().setBlockAndUpdate(chestPos,Blocks.CHEST.defaultBlockState());var chest=(ChestBlockEntity)h.getLevel().getBlockEntity(chestPos);
        if(extract)s.nas.volume(0).inventory().insert(new ItemStack(Items.IRON_INGOT,16),16,s.folder);else chest.setItem(0,new ItemStack(Items.IRON_INGOT,16));
        h.getLevel().setBlockAndUpdate(pipePos,block("mekanism:basic_logistical_transporter").defaultBlockState());
        var pipe=(TileEntityLogisticalTransporter)h.getLevel().getBlockEntity(pipePos);
        pipe.getTransmitter().setConnectionTypeRaw(extract?Direction.SOUTH:Direction.NORTH,ConnectionType.PULL);pipe.getTransmitter().refreshConnections();
        h.succeedWhen(()->{
            long total=s.nas.volume(0).inventory().total();
            h.assertTrue(extract?chest.getItem(0).getCount()==16&&total==0:chest.isEmpty()&&total==16,"MEK pipe has not completed conserving transfer");
            h.assertTrue(s.port.getBlockState().getValue(LogisticsPortBlock.CONNECTED),"Pipe did not extend connector");
        });
    }
    @GameTest(template="empty",timeoutTicks=500) public static void mekanismPipeInsertsIntoSelectedFolder(GameTestHelper h){mekanism(h,false);}
    @GameTest(template="empty",timeoutTicks=500) public static void mekanismPipeExtractsSelectedFolder(GameTestHelper h){mekanism(h,true);}

    /** Real powered AE2 subnet: import -> storage bus, or storage bus -> export. */
    private static void ae2Buses(GameTestHelper h, boolean intoNas, boolean directBus) {
        var s=setup(h,1,3);var level=h.getLevel();
        BlockPos left=s.port.getBlockPos().north(), right=left.east(2), chestPos=right.south();
        level.setBlockAndUpdate(chestPos,Blocks.CHEST.defaultBlockState());var chest=(ChestBlockEntity)level.getBlockEntity(chestPos);
        for(int i=0;i<3;i++)PartHelper.setPart(level,left.east(i),null,null,AEParts.GLASS_CABLE.item(AEColor.TRANSPARENT));
        level.setBlockAndUpdate(left.east().below(),block("ae2:creative_energy_cell").defaultBlockState());
        if(intoNas) chest.setItem(0,new ItemStack(Items.IRON_INGOT,8));
        else s.nas.volume(0).inventory().insert(new ItemStack(Items.IRON_INGOT,8),8,s.folder);
        PartHelper.setPart(level,directBus?right:left,Direction.SOUTH,null,AEParts.STORAGE_BUS.asItem());
        BlockPos transferPos=directBus?left:right;
        if(intoNas==directBus) {
            var export=PartHelper.setPart(level,transferPos,Direction.SOUTH,null,AEParts.EXPORT_BUS.asItem());
            export.getConfig().addFilter(Items.IRON_INGOT);
        } else PartHelper.setPart(level,transferPos,Direction.SOUTH,null,AEParts.IMPORT_BUS.asItem());
        h.succeedWhen(()->h.assertTrue(intoNas?chest.isEmpty()&&s.nas.volume(0).inventory().total()==8
                :chest.getItem(0).getCount()==8&&s.nas.volume(0).inventory().total()==0,"AE2 powered buses have not completed transfer"));
    }
    @GameTest(template="empty",timeoutTicks=500) public static void ae2ImportToStorageBusInsertsIntoNas(GameTestHelper h){ae2Buses(h,true,false);}
    @GameTest(template="empty",timeoutTicks=500) public static void ae2StorageToExportBusExtractsFromNas(GameTestHelper h){ae2Buses(h,false,false);}
    @GameTest(template="empty",timeoutTicks=500) public static void ae2ExportBusDirectlyInsertsIntoPort(GameTestHelper h){ae2Buses(h,true,true);}
    @GameTest(template="empty",timeoutTicks=500) public static void ae2ImportBusDirectlyExtractsFromPort(GameTestHelper h){ae2Buses(h,false,true);}
}
