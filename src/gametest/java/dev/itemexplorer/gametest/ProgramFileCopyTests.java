package dev.itemexplorer.gametest;

import com.mojang.authlib.GameProfile;
import dev.itemexplorer.ItemExplorer;
import dev.itemexplorer.ModContent;
import dev.itemexplorer.block.StorageBlockEntity;
import dev.itemexplorer.menu.StorageMenu;
import dev.itemexplorer.network.StorageNetwork;
import dev.itemexplorer.storage.StorageInventory;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

@GameTestHolder(ItemExplorer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ProgramFileCopyTests {
    private record Fixture(StorageBlockEntity terminal, StorageMenu menu, int source) {}
    private static Fixture fixture(GameTestHelper h, String name) {
        BlockPos position = h.absolutePos(new BlockPos(2, 2, 2));
        h.getLevel().setBlockAndUpdate(position, ModContent.STORAGE_BLOCK.get().defaultBlockState());
        var terminal = (StorageBlockEntity) h.getLevel().getBlockEntity(position);
        terminal.inventory().insert(new ItemStack(Items.STONE, 64), 64, 0);
        int source = terminal.programs().create(0, name);
        var player = new FakePlayer(h.getLevel(), new GameProfile(UUID.randomUUID(), "program-copy")) {
            @Override public boolean hasDisconnected() { return false; }
        };
        player.setPos(position.getX() + .5, position.getY() + .5, position.getZ() + .5);
        var menu = new StorageMenu(1, player.getInventory(), position, terminal); player.containerMenu = menu;
        return new Fixture(terminal, menu, source);
    }
    private static StorageNetwork.FileRequest copyRequest(Fixture f, int id) {
        var view = f.menu.snapshot();
        var request = new StorageNetwork.FileRequest(f.menu.containerId, view.getLong("Session"), view.getLong("Revision"),
                view.getLong("ProgramRevision"), StorageNetwork.FileAction.COPY, id, 0, "");
        FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
        try { request.encode(packet); return StorageNetwork.FileRequest.decode(packet); }
        finally { packet.release(); }
    }
    @GameTest(template = "empty")
    public static void emptyNameCopyPacketsCreateDistinctInactiveFilesAndRejectReplays(GameTestHelper h) {
        Fixture f = fixture(h, "自动玻璃.exe"); var before = f.terminal.inventory().save();
        var first = copyRequest(f, f.source); f.menu.handleFile(first); f.menu.handleFile(first);
        h.assertTrue(f.terminal.programs().files().size() == 2, "Empty-name copy failed or stale packet created a second copy");
        f.menu.handleFile(copyRequest(f, f.source));
        var files = f.terminal.programs().files();
        h.assertTrue(files.size() == 3 && files.stream().mapToInt(file -> file.id()).distinct().count() == 3
                && files.stream().noneMatch(file -> file.active()), "Copies reused an identity or inherited an active run");
        h.assertTrue(files.stream().anyMatch(file -> file.name().equals("自动玻璃 (2).exe"))
                && files.stream().anyMatch(file -> file.name().equals("自动玻璃 (3).exe")), "Default copy names were not unique");
        h.assertTrue(before.equals(f.terminal.inventory().save()), "Copying documents duplicated or consumed material items");
        h.succeed();
    }
    @GameTest(template = "empty")
    public static void defaultCopyNamesPreserveExecutableExtensionAndWholeUnicodeCharacters(GameTestHelper h) {
        Fixture f = fixture(h, "ABCDEFGHIJKLMNO🚀🚀X.EXE");
        // This collision is intentionally a different extension case from the source.
        f.terminal.programs().create(0, "ABCDEFGHIJKLMNO (2).exe");
        f.menu.handleFile(copyRequest(f, f.source));
        var copy = f.terminal.programs().files().get(2);
        h.assertTrue(copy.name().equals("ABCDEFGHIJKLMNO (3).EXE") && copy.name().length() <= StorageInventory.MAX_NAME,
                "Truncation split a surrogate, lost the extension, or ignored a case-insensitive collision");
        h.assertTrue(StorageInventory.validName(copy.name()).equals(copy.name())
                && copy.name().codePoints().noneMatch(cp -> cp >= 0xD800 && cp <= 0xDFFF), "Copy filename contains an unpaired surrogate");
        h.succeed();
    }
    @GameTest(template = "empty")
    public static void fileRequestsCannotCopyAcrossTheOpenDirectoryOrDeleteContainingFolders(GameTestHelper h) {
        Fixture f = fixture(h, "根目录程序.exe");
        int folder = f.terminal.inventory().createFolder(0, "程序文件夹");
        int hidden = f.terminal.programs().create(folder, "目录内程序.exe");
        var before = f.terminal.programs().save();
        f.menu.handleFile(copyRequest(f, hidden));
        h.assertTrue(before.equals(f.terminal.programs().save()), "File ID bypassed current directory authorization");
        f.menu.restoreLocation(folder, 0);
        var view = f.menu.snapshot();
        f.menu.handle(new StorageNetwork.Request(f.menu.containerId, view.getLong("Revision"), StorageNetwork.Action.DELETE,
                0, 0, 0, "", view.getLong("Session")));
        h.assertTrue(f.terminal.inventory().hasFolder(folder) && f.terminal.programs().file(hidden) != null,
                "Deleting a directory discarded its program document");
        h.succeed();
    }
}
