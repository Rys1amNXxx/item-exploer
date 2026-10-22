package dev.itemexplorer.gametest;

import dev.itemexplorer.ItemExplorer;
import dev.itemexplorer.ModContent;
import dev.itemexplorer.block.ProductionPortBlock;
import dev.itemexplorer.block.ProductionPortBlockEntity;
import dev.itemexplorer.block.StorageBlockEntity;
import dev.itemexplorer.menu.ProductionPortMenu;
import dev.itemexplorer.network.StorageNetwork.PatternRow;
import dev.itemexplorer.network.StorageNetwork.ProcessingPatternRequest;
import dev.itemexplorer.network.StorageNetwork.ProductionAction;
import dev.itemexplorer.network.StorageNetwork.ProductionRequest;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.Collections;
import java.util.List;

import static dev.itemexplorer.gametest.GameTestSupport.*;

@GameTestHolder(ItemExplorer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class GenericProductionMenuTests {
    private record Fixture(StorageBlockEntity terminal, ProductionPortBlockEntity port, ProductionPortMenu menu,
                           ServerPlayer player, int file, int input, int output, int sampleFolder, int sand, int glass) {}

    private static Fixture fixture(GameTestHelper h) {
        var level = h.getLevel(); BlockPos machine = h.absolutePos(new BlockPos(2, 2, 2));
        BlockPos portPos = machine.north(), cable = portPos.north(), terminalPos = cable.west();
        level.setBlockAndUpdate(machine, Blocks.CHEST.defaultBlockState());
        level.setBlockAndUpdate(portPos, ModContent.PRODUCTION_BLOCK.get().defaultBlockState().setValue(ProductionPortBlock.FACING, Direction.NORTH));
        level.setBlockAndUpdate(cable, ModContent.DATA_CABLE_BLOCK.get().defaultBlockState());
        level.setBlockAndUpdate(terminalPos, ModContent.STORAGE_BLOCK.get().defaultBlockState());
        var terminal = (StorageBlockEntity) level.getBlockEntity(terminalPos);
        var port = (ProductionPortBlockEntity) level.getBlockEntity(portPos);
        int input = terminal.inventory().createFolder(0, "输入"), output = terminal.inventory().createFolder(0, "输出");
        int sampleFolder = terminal.inventory().createFolder(0, "样本");
        terminal.inventory().insert(new ItemStack(Items.SAND, 8), 8, input);
        ItemStack glass = new ItemStack(Items.GLASS); glass.getOrCreateTag().putString("SampleMarker", "server-owned");
        terminal.inventory().insert(glass, 1, sampleFolder);
        int sandId = entry(terminal.inventory(), input, Items.SAND), glassId = entry(terminal.inventory(), sampleFolder, Items.GLASS);
        int file = terminal.programs().create(0, "加工样板");
        ServerPlayer player = player(h, terminalPos);
        var menu = new ProductionPortMenu(14, player.getInventory(), terminalPos, terminal, file, 0, 0);
        player.containerMenu = menu;
        return new Fixture(terminal, port, menu, player, file, input, output, sampleFolder, sandId, glassId);
    }

    private static ProcessingPatternRequest request(Fixture f, CompoundTag view, String name, List<PatternRow> inputs, List<PatternRow> outputs) {
        return new ProcessingPatternRequest(f.menu.containerId, view.getLong("Session"), view.getLong("Context"), view.getLong("Revision"),
                name, f.port.getBlockPos().asLong(), Direction.UP, Direction.DOWN, inputs, outputs);
    }
    private static ProcessingPatternRequest request(Fixture f) {
        return request(f, f.menu.snapshot(), "加工样板", List.of(new PatternRow(f.input, f.sand, 2, Direction.WEST.ordinal())),
                List.of(new PatternRow(f.output, f.glass, 1)));
    }
    private static void rejects(GameTestHelper h, Runnable operation, String message) {
        boolean rejected = false;
        try { operation.run(); } catch (RuntimeException expected) { rejected = true; }
        h.assertTrue(rejected, message);
    }

    @GameTest(template = "empty")
    public static void processingPacketRoundTripsBoundedReferencesAndSides(GameTestHelper h) {
        var original = new ProcessingPatternRequest(17, Long.MIN_VALUE, Long.MAX_VALUE, 19, "多输入加工", Long.MIN_VALUE,
                Direction.NORTH, Direction.SOUTH,
                List.of(new PatternRow(1, Integer.MAX_VALUE, 64, 5), new PatternRow(2, -9, 1)),
                Collections.nCopies(9, new PatternRow(3, 123, 16, 0)));
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            original.encode(buffer);
            h.assertTrue(original.equals(ProcessingPatternRequest.decode(buffer)) && !buffer.isReadable(),
                    "Pattern packet changed material references, faces, quantities or authorization fields");
        } finally { buffer.release(); }
        rejects(h, () -> new PatternRow(0, 1, 0), "Zero quantity accepted");
        rejects(h, () -> new PatternRow(0, 1, 10000), "Oversized quantity accepted");
        rejects(h, () -> new PatternRow(0, -10, 1), "Out-of-range saved row accepted");
        rejects(h, () -> new PatternRow(0, 1, 1, 6), "Out-of-range material face accepted");
        rejects(h, () -> new PatternRow(0, 1, 1, -2), "Invalid default material face accepted");
        rejects(h, () -> new ProcessingPatternRequest(1, 1, 1, 1, "x", 1, Direction.UP, Direction.DOWN,
                Collections.nCopies(10, new PatternRow(0, 1, 1)), List.of(new PatternRow(0, 2, 1))), "Ten material rows accepted");
        var malformed = new FriendlyByteBuf(Unpooled.buffer());
        try {
            malformed.writeVarInt(1); malformed.writeLong(1); malformed.writeLong(1); malformed.writeLong(1);
            malformed.writeUtf("x"); malformed.writeLong(1); malformed.writeEnum(Direction.UP); malformed.writeEnum(Direction.DOWN);
            malformed.writeVarInt(Integer.MAX_VALUE);
            rejects(h, () -> ProcessingPatternRequest.decode(malformed), "Unbounded packet row count accepted");
        } finally { malformed.release(); }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void menuResolvesExactServerSamplesAndIndependentOutputDestination(GameTestHelper h) {
        Fixture f = fixture(h); Tag before = f.terminal.inventory().save();
        f.menu.handle(request(f));
        var file = f.terminal.programs().file(f.file);
        h.assertTrue(file.configured() && file.definition().generic(), "Current authorized request did not create a generic definition");
        var pattern = file.definition().pattern();
        h.assertTrue(pattern.inputs().get(0).folder() == f.input && pattern.inputs().get(0).stack().is(Items.SAND)
                        && pattern.inputs().get(0).stack().getCount() == 2 && pattern.inputs().get(0).face() == Direction.WEST,
                "Input source, count or per-material side was not retained");
        h.assertTrue(pattern.outputs().get(0).folder() == f.output && pattern.outputs().get(0).stack().is(Items.GLASS)
                        && "server-owned".equals(pattern.outputs().get(0).stack().getOrCreateTag().getString("SampleMarker")),
                "Output sample was not copied from the server or its destination was tied to its source folder");
        h.assertTrue(before.equals(f.terminal.inventory().save()), "Saving a sample consumed inventory");
        h.assertTrue("block.minecraft.chest".equals(f.menu.snapshot().getList("Machines", Tag.TAG_COMPOUND).getCompound(0).getString("Label")),
                "Generic machine label was guessed from adapter kind instead of the actual block");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void depletedSamplesCanBeSavedAgainThroughServerOwnedRowReferences(GameTestHelper h) {
        Fixture f = fixture(h); f.menu.handle(request(f));
        f.terminal.inventory().take(f.sand, 8); f.terminal.inventory().take(f.glass, 1);
        Tag inventory = f.terminal.inventory().save();
        f.menu.handle(request(f, f.menu.snapshot(), "已保存样本", List.of(new PatternRow(f.input, -1, 3)), List.of(new PatternRow(f.output, -1, 1))));
        var file = f.terminal.programs().file(f.file);
        h.assertTrue("已保存样本".equals(file.name()) && file.definition().pattern().inputs().get(0).stack().getCount() == 3
                        && file.definition().pattern().outputs().get(0).stack().is(Items.GLASS)
                        && inventory.equals(f.terminal.inventory().save()),
                "Depleted material references could not be edited safely without inventing items");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void invalidMaterialReferencesAndCountsCannotConfigureAProgram(GameTestHelper h) {
        Fixture f = fixture(h); Tag before = f.terminal.programs().save(), inventory = f.terminal.inventory().save();
        List<PatternRow> output = List.of(new PatternRow(f.output, f.glass, 1));
        List<List<PatternRow>> invalid = List.of(List.of(new PatternRow(f.input, Integer.MAX_VALUE, 1)),
                List.of(new PatternRow(f.output, f.sand, 1)), List.of(new PatternRow(f.input, f.sand, 65)),
                List.of(new PatternRow(f.input, -1, 1)));
        for (List<PatternRow> inputs : invalid) f.menu.handle(request(f, f.menu.snapshot(), "不应保存", inputs, output));
        h.assertTrue(before.equals(f.terminal.programs().save()) && inventory.equals(f.terminal.inventory().save()),
                "Missing entries, wrong source folders, oversized stacks or unsaved row references changed program/inventory");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void staleAuthorizationAndRunningEditsCannotReplaceProcessingPatterns(GameTestHelper h) {
        Fixture f = fixture(h); var valid = request(f); Tag before = f.terminal.programs().save();
        f.menu.handle(new ProcessingPatternRequest(valid.menuId(), valid.session() + 1, valid.context(), valid.revision(), "坏会话",
                valid.machinePos(), valid.inputFace(), valid.outputFace(), valid.inputs(), valid.outputs()));
        f.menu.handle(new ProcessingPatternRequest(valid.menuId(), valid.session(), valid.context() + 1, valid.revision(), "旧连接",
                valid.machinePos(), valid.inputFace(), valid.outputFace(), valid.inputs(), valid.outputs()));
        f.menu.handle(new ProcessingPatternRequest(valid.menuId(), valid.session(), valid.context(), valid.revision() - 1, "旧版本",
                valid.machinePos(), valid.inputFace(), valid.outputFace(), valid.inputs(), valid.outputs()));
        h.assertTrue(before.equals(f.terminal.programs().save()), "A stale session, context or revision configured the file");
        f.menu.handle(valid);
        f.port.startProgram(f.terminal, f.file, f.terminal.programs().file(f.file).definition(), 1);
        Tag running = f.terminal.programs().save();
        f.menu.handle(request(f, f.menu.snapshot(), "运行中改写", valid.inputs(), valid.outputs()));
        h.assertTrue(running.equals(f.terminal.programs().save()) && f.port.isRunning(), "Running pattern accepted an edit");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void saveReceiptSurvivesClearedMessagesAndIdenticalResaves(GameTestHelper h) {
        Fixture f = fixture(h); var request = request(f);
        f.menu.handle(request);
        CompoundTag view = f.menu.snapshot(), receipt = view.getCompound("SaveReceipt").copy();
        h.assertTrue(view.getString("Message").isEmpty() && receipt.getBoolean("Success")
                        && receipt.getLong("Serial") == 1 && receipt.getLong("RequestContext") == request.context()
                        && receipt.getLong("RequestRevision") == request.revision()
                        && receipt.getLong("MachinePos") == request.machinePos()
                        && receipt.getLong("Revision") == view.getLong("Revision"),
                "Saving must retain an attributable confirmation after its transient message is cleared");
        f.menu.broadcastChanges(); f.menu.broadcastChanges();
        h.assertTrue(receipt.equals(f.menu.snapshot().getCompound("SaveReceipt")), "Ordinary broadcasts cleared the save confirmation");
        f.menu.handle(request(f));
        CompoundTag resaved = f.menu.snapshot().getCompound("SaveReceipt");
        h.assertTrue(resaved.getBoolean("Success") && resaved.getLong("Serial") == 2
                        && resaved.getLong("RequestRevision") == receipt.getLong("Revision")
                        && resaved.getLong("Revision") > receipt.getLong("Revision") && !f.port.isRunning(),
                "Identical resaves must have a fresh receipt and must not implicitly start a job");
        f.terminal.programs().rename(f.file, "其他玩家修改");
        h.assertTrue(f.menu.snapshot().getLong("Revision") != resaved.getLong("Revision"),
                "A later edit must be distinguishable from the confirmed definition before auto-start");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void rejectedSavesReplaceSuccessReceiptsButUnauthorizedRequestsDoNot(GameTestHelper h) {
        Fixture f = fixture(h); var first = request(f); f.menu.handle(first);
        f.menu.handle(first); // Old revision: cannot reuse the preceding success to authorize a launch.
        CompoundTag stale = f.menu.snapshot().getCompound("SaveReceipt").copy();
        h.assertTrue(stale.getLong("Serial") == 2 && !stale.getBoolean("Success")
                        && "production_stale".equals(stale.getString("Message"))
                        && stale.getLong("RequestRevision") == first.revision(),
                "Stale save did not replace the previous success with an attributable failure");
        var current = request(f);
        f.menu.handle(new ProcessingPatternRequest(current.menuId(), current.session() + 1, current.context(), current.revision(),
                current.name(), current.machinePos(), current.inputFace(), current.outputFace(), current.inputs(), current.outputs()));
        h.assertTrue(stale.equals(f.menu.snapshot().getCompound("SaveReceipt")), "Unauthorized request changed the open menu's receipt");
        f.menu.handle(request(f, f.menu.snapshot(), "非法样本", List.of(new PatternRow(f.input, Integer.MAX_VALUE, 1)), current.outputs()));
        CompoundTag failed = f.menu.snapshot().getCompound("SaveReceipt");
        h.assertTrue(failed.getLong("Serial") == 3 && !failed.getBoolean("Success")
                        && "production_invalid_sample".equals(failed.getString("Message")) && !f.port.isRunning(),
                "Business validation failure lost its persistent receipt or started the machine");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void vanillaSavesAlsoRetainConfirmationAndStaleFailure(GameTestHelper h) {
        Fixture f = fixture(h);
        h.getLevel().setBlockAndUpdate(f.port.machinePosition(), Blocks.FURNACE.defaultBlockState());
        f.terminal.inventory().insert(new ItemStack(Items.COAL), 1, f.input);
        int coal = entry(f.terminal.inventory(), f.input, Items.COAL);
        CompoundTag view = f.menu.snapshot();
        var request = new ProductionRequest(f.menu.containerId, view.getLong("Session"), view.getLong("Context"),
                view.getLong("Revision"), ProductionAction.SAVE, "熔炉样板", f.input, f.input, f.output, f.sand, coal, 1,
                f.port.getBlockPos().asLong());
        f.menu.handle(request);
        CompoundTag saved = f.menu.snapshot().getCompound("SaveReceipt");
        h.assertTrue(saved.getBoolean("Success") && saved.getLong("Serial") == 1
                        && f.terminal.programs().file(f.file).configured() && !f.terminal.programs().file(f.file).definition().generic(),
                "Native machine saves did not retain a success confirmation");
        f.menu.handle(request);
        CompoundTag rejected = f.menu.snapshot().getCompound("SaveReceipt");
        h.assertTrue(!rejected.getBoolean("Success") && rejected.getLong("Serial") == 2
                        && "production_stale".equals(rejected.getString("Message")) && !f.port.isRunning(),
                "Native stale save retained a misleading success confirmation");
        h.succeed();
    }
}
