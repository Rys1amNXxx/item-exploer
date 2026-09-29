package dev.itemexplorer.gametest;

import com.mojang.logging.LogUtils;
import dev.itemexplorer.ItemExplorer;
import dev.itemexplorer.ModContent;
import dev.itemexplorer.block.LogisticsPortBlock;
import dev.itemexplorer.block.LogisticsPortBlockEntity;
import dev.itemexplorer.block.NasBlockEntity;
import dev.itemexplorer.block.StorageBlockEntity;
import dev.itemexplorer.storage.StorageInventory;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/** Two independent dedicated-server JVMs exercise real world saves, not an in-memory reload. */
@Mod.EventBusSubscriber(modid = ItemExplorer.MOD_ID)
public final class RestartPersistenceProbe {
    private static final Logger LOG = LogUtils.getLogger();
    private static final BlockPos NAS = new BlockPos(8, 82, 8), TERMINAL = new BlockPos(12, 82, 8);
    private static final Direction[] SIDES = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
    private static final Path EXPECTED = Path.of("restart-probe-expected.nbt");
    private static MinecraftServer server;
    private static int ticks;
    private static boolean done;

    @SubscribeEvent public static void started(ServerStartedEvent event) {
        if (System.getProperty("itemexplorer.restartProbe") == null) return;
        server = event.getServer();
        server.overworld().getChunkAt(NAS);
        line("START phase=" + phase() + " pid=" + ProcessHandle.current().pid());
    }

    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event) {
        if (server == null || done || event.phase != TickEvent.Phase.END || ++ticks < 40) return;
        done = true;
        try {
            if (phase().equals("seed") || phase().equals("seed-crash")) {
                seed(server.overworld());
                if (phase().equals("seed-crash")) {
                    server.saveEverything(false, true, true);
                    line("CRASH_READY explicit saveEverything(flush=true) completed; awaiting external termination");
                    Files.writeString(Path.of("restart-probe-crash-ready.pid"), Long.toString(ProcessHandle.current().pid()));
                    return;
                }
                line("SEED_PASS requesting normal server stop; shutdown must persist world and SavedData");
            } else if (phase().equals("verify")) {
                verify(server.overworld());
                line("VERIFY_PASS independent JVM restored every fixture and active logistics capability");
            } else throw new IllegalArgumentException("Unknown probe phase " + phase());
        } catch (Throwable failure) {
            LOG.error("RESTART_PROBE FAIL", failure);
            line("FAIL " + failure);
        }
        server.halt(false);
    }

    private static String phase() { return System.getProperty("itemexplorer.restartProbe"); }
    private static void line(String message) {
        LOG.info("RESTART_PROBE {}", message);
        try { Files.writeString(Path.of("restart-probe-" + phase() + ".txt"), message + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        line("PASS " + message);
    }
    private static void same(Object expected, Object actual, String message) {
        check(expected.equals(actual), message + " [exact NBT equality]");
    }

    private static ItemStack modItem(String id, int count, int fixture) {
        var item = ForgeRegistries.ITEMS.getValue(ResourceLocation.parse(id));
        if (item == null || item == Items.AIR) throw new IllegalStateException("Missing real mod item: " + id);
        ItemStack stack = new ItemStack(item, count);
        stack.setHoverName(Component.literal("Restart " + id));
        CompoundTag custom = new CompoundTag();
        custom.putInt("Fixture", fixture);
        custom.putLong("LargeValue", 9876543210123L);
        custom.putString("Unicode", "重启验证");
        custom.putIntArray("Coordinates", new int[]{-32, 82, 257});
        stack.getOrCreateTag().put("ItemExplorerProbe", custom);
        return stack;
    }

    private static int fill(StorageInventory inventory, int fixture) {
        int parent = inventory.createFolder(0, "材料 " + fixture);
        int child = inventory.createFolder(parent, "附魔与模组");
        int empty = inventory.createFolder(parent, "临时");
        inventory.renameFolder(empty, "保留空目录");
        inventory.insert(new ItemStack(Items.IRON_INGOT, 513 + fixture), 513 + fixture, parent);
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        sword.enchant(Enchantments.SHARPNESS, 5);
        sword.enchant(Enchantments.UNBREAKING, 3);
        sword.setDamageValue(47 + fixture);
        sword.setHoverName(Component.literal("重启验收剑 " + fixture));
        sword.getOrCreateTag().putString("ProbeCustom", "preserve me");
        inventory.insert(sword, 1, child);
        inventory.insert(modItem("ae2:calculation_processor", 19 + fixture, fixture), 19 + fixture, child);
        ItemStack tablet = modItem("mekanism:energy_tablet", Boolean.getBoolean("itemexplorer.restartProbeEnergy") ? 1 : 3, fixture);
        if (Boolean.getBoolean("itemexplorer.restartProbeEnergy")) {
            var energy = tablet.getCapability(ForgeCapabilities.ENERGY).orElseThrow(() -> new AssertionError("MEK energy tablet has no Forge ENERGY capability"));
            // Stay below Mekanism's per-call input limit (2000 FE with default settings).
            int requested = 1000 + fixture * 100;
            int received = energy.receiveEnergy(requested, false);
            check(received == requested && energy.getEnergyStored() == requested, "MEK tablet " + fixture + " charged via native capability energy=" + energy.getEnergyStored());
        }
        inventory.insert(tablet, tablet.getCount(), child);
        inventory.insert(new ItemStack(Items.GOLD_INGOT, 11), 11, 0);
        return parent;
    }

    private static int energy(StorageInventory inventory) {
        ItemStack tablet = inventory.entries().stream().map(StorageInventory.Entry::stack)
                .filter(stack -> ResourceLocation.parse("mekanism:energy_tablet").equals(ForgeRegistries.ITEMS.getKey(stack.getItem())))
                .findFirst().orElseThrow(() -> new AssertionError("Missing MEK energy tablet"));
        return tablet.getCapability(ForgeCapabilities.ENERGY)
                .orElseThrow(() -> new AssertionError("Stored MEK tablet lost its Forge ENERGY capability")).getEnergyStored();
    }

    private static void seed(ServerLevel level) throws Exception {
        check(!Files.exists(EXPECTED), "Fresh probe world; no previous expected snapshot");
        for (int x = 6; x <= 14; x++) for (int z = 6; z <= 10; z++) {
            level.setBlockAndUpdate(new BlockPos(x, 81, z), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(x, 82, z), Blocks.AIR.defaultBlockState());
        }
        level.setBlockAndUpdate(NAS, ModContent.NAS_BLOCK.get().defaultBlockState());
        level.setBlockAndUpdate(TERMINAL, ModContent.STORAGE_BLOCK.get().defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH));
        var nas = (NasBlockEntity) level.getBlockEntity(NAS);
        var terminal = (StorageBlockEntity) level.getBlockEntity(TERMINAL);
        fill(terminal.inventory(), 0);
        var disks = List.of(ModContent.DISK_64K.get(), ModContent.DISK_256K.get(), ModContent.DISK_1M.get(), ModContent.DISK_16M.get());
        CompoundTag expected = new CompoundTag();
        expected.putLong("SourcePid", ProcessHandle.current().pid());
        expected.put("Terminal", terminal.inventory().save());
        expected.putString("TerminalSession", terminal.accessSession());
        if (Boolean.getBoolean("itemexplorer.restartProbeEnergy")) {
            check(energy(terminal.inventory()) == 1000, "Terminal insertion retained native MEK energy");
            expected.putInt("TerminalEnergy", energy(terminal.inventory()));
        }
        for (int slot = 0; slot < 4; slot++) {
            nas.install(slot, new ItemStack(disks.get(slot)));
            nas.rename(slot, "验收盘 " + (slot + 1));
            var volume = nas.volume(slot);
            int folder = fill(volume.inventory(), slot + 1);
            expected.put("Disk" + slot, volume.save(new CompoundTag()));
            expected.putString("MountSession" + slot, nas.stamp(slot));
            if (Boolean.getBoolean("itemexplorer.restartProbeEnergy")) {
                check(energy(volume.inventory()) == 1100 + slot * 100, "Disk " + slot + " insertion retained native MEK energy");
                expected.putInt("DiskEnergy" + slot, energy(volume.inventory()));
            }
            BlockPos portPos = NAS.relative(SIDES[slot]);
            level.setBlockAndUpdate(portPos, ModContent.LOGISTICS_BLOCK.get().defaultBlockState().setValue(LogisticsPortBlock.FACING, SIDES[slot]));
            var port = (LogisticsPortBlockEntity) level.getBlockEntity(portPos);
            port.configure(volume.id().toString(), folder, slot < 3, slot != 1, slot % 2 == 0);
            expected.put("Port" + slot, port.saveWithFullMetadata().getCompound("Port"));
            check("online".equals(port.status()), "Seed disk " + slot + " mounted and port online UUID=" + volume.id());
        }
        expected.put("Nas", nas.saveWithFullMetadata().getCompound("Nas"));
        try (var out = Files.newOutputStream(EXPECTED)) { NbtIo.writeCompressed(expected, out); }
        long terminalTotal = Boolean.getBoolean("itemexplorer.restartProbeEnergy") ? 545 : 547;
        check(terminal.inventory().total() == terminalTotal, "Seed terminal item total=" + terminalTotal);
        line("FIXTURE terminal plus 4 disk tiers; 20 nested/custom item entries and 5 outside-folder entries; actual AE2 and Mekanism items loaded");
    }

    private static void verify(ServerLevel level) throws Exception {
        CompoundTag expected;
        try (var in = Files.newInputStream(EXPECTED)) { expected = NbtIo.readCompressed(in); }
        check(expected.getLong("SourcePid") != ProcessHandle.current().pid(), "Fresh process sourcePid=" + expected.getLong("SourcePid") + " verifierPid=" + ProcessHandle.current().pid());
        check(level.getBlockEntity(TERMINAL) instanceof StorageBlockEntity, "Terminal block persisted in region file");
        check(level.getBlockEntity(NAS) instanceof NasBlockEntity, "NAS block persisted in region file");
        var terminal = (StorageBlockEntity) level.getBlockEntity(TERMINAL);
        var nas = (NasBlockEntity) level.getBlockEntity(NAS);
        check(!terminal.inventory().isLocked() && !nas.isLocked(), "Terminal and NAS are writable after restart");
        same(expected.get("Terminal"), terminal.inventory().save(), "Terminal folders, counts, enchantments, damage, custom NBT, AE2 and Mekanism item payloads preserved");
        if (expected.contains("TerminalEnergy")) check(energy(terminal.inventory()) == expected.getInt("TerminalEnergy"), "Terminal MEK ENERGY capability restored with energy=" + energy(terminal.inventory()));
        check(!expected.getString("TerminalSession").equals(terminal.accessSession()), "Terminal access session regenerated");
        same(expected.get("Nas"), nas.saveWithFullMetadata().get("Nas"), "NAS identity, four bay stacks and disk UUIDs preserved");
        for (int slot = 0; slot < 4; slot++) {
            var volume = nas.volume(slot);
            check(volume != null && !volume.isLocked(), "Disk " + slot + " re-mounted without owner conflict");
            same(expected.get("Disk" + slot), volume.save(new CompoundTag()), "Disk " + slot + " name, tier, UUID, owner, folders and full item payloads preserved");
            if (expected.contains("DiskEnergy" + slot)) check(energy(volume.inventory()) == expected.getInt("DiskEnergy" + slot), "Disk " + slot + " MEK ENERGY capability restored with energy=" + energy(volume.inventory()));
            check(!expected.getString("MountSession" + slot).equals(nas.stamp(slot)), "Disk " + slot + " mount access session regenerated");
            var port = (LogisticsPortBlockEntity) level.getBlockEntity(NAS.relative(SIDES[slot]));
            check(port != null && !port.isLocked() && "online".equals(port.status()), "Logistics port " + slot + " online after restart");
            same(expected.get("Port" + slot), port.saveWithFullMetadata().get("Port"), "Port " + slot + " volume, folder, input, output and recursive settings preserved");
            var handler = port.getCapability(ForgeCapabilities.ITEM_HANDLER, SIDES[slot]).orElseThrow(() -> new AssertionError("Missing item capability"));
            long before = volume.inventory().total();
            var iron = new ItemStack(Items.IRON_INGOT, 7);
            var remainder = handler.insertItem(0, iron, true);
            check(remainder.isEmpty() == port.allowInput() && volume.inventory().total() == before, "Port " + slot + " restored input policy; simulation does not mutate");
            int visible = 0;
            for (int itemSlot = 1; itemSlot < handler.getSlots(); itemSlot++) if (!handler.getStackInSlot(itemSlot).isEmpty()) visible++;
            check(visible == (port.recursive() ? 4 : 1), "Port " + slot + " restored recursive scope visibleEntries=" + visible);
            var extracted = handler.extractItem(1, 3, true);
            check(extracted.isEmpty() != port.allowOutput() && volume.inventory().total() == before, "Port " + slot + " restored output policy; simulation does not mutate");
            if (slot == 0) {
                var taken = handler.extractItem(1, 3, false);
                check(taken.getCount() == 3 && volume.inventory().total() == before - 3, "Post-restart real extraction removes exactly 3");
                check(handler.insertItem(0, taken, false).isEmpty() && volume.inventory().total() == before, "Post-restart real insertion restores exactly 3");
                same(expected.getCompound("Disk0").get("Inventory"), volume.inventory().save(), "Post-restart transfer conserves full disk contents");
            }
        }
    }
}
