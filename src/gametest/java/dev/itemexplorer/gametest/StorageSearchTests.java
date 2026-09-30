package dev.itemexplorer.gametest;

import com.mojang.authlib.GameProfile;
import dev.itemexplorer.ItemExplorer;
import dev.itemexplorer.ModContent;
import dev.itemexplorer.block.NasBlockEntity;
import dev.itemexplorer.block.StorageBlockEntity;
import dev.itemexplorer.disk.DiskItem;
import dev.itemexplorer.disk.DiskTier;
import dev.itemexplorer.menu.StorageMenu;
import dev.itemexplorer.menu.StorageLayout;
import dev.itemexplorer.network.StorageNetwork;
import dev.itemexplorer.network.StorageNetwork.Action;
import dev.itemexplorer.network.StorageNetwork.SearchAction;
import dev.itemexplorer.storage.SearchCatalogSupport;
import dev.itemexplorer.storage.StorageInventory;
import dev.itemexplorer.storage.StorageSearch;
import io.netty.buffer.Unpooled;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@GameTestHolder(ItemExplorer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class StorageSearchTests {
    private record Opened(StorageBlockEntity entity, ServerPlayer player, StorageMenu menu) {}

    private static Opened terminal(GameTestHelper helper) {
        BlockPos local = new BlockPos(2, 2, 2);
        helper.setBlock(local, ModContent.STORAGE_BLOCK.get());
        var entity = (StorageBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(local));
        ServerPlayer player = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "search-test")) {
            @Override public boolean hasDisconnected() { return false; }
        };
        BlockPos pos = entity.getBlockPos();
        player.setPos(pos.getX() + .5, pos.getY() + 1, pos.getZ() + .5);
        var menu = new StorageMenu(1, player.getInventory(), pos, entity);
        player.containerMenu = menu;
        return new Opened(entity, player, menu);
    }

    private static StorageNetwork.Request normal(StorageMenu menu, Action action, int id, int target, long amount, String name) {
        CompoundTag view = menu.snapshot();
        return new StorageNetwork.Request(menu.containerId, view.getLong("Revision"), action, id, target, amount, name, view.getLong("Session"));
    }

    private static StorageNetwork.SearchRequest search(StorageMenu menu, SearchAction action, int page, int size, int id, long amount, int... matches) {
        CompoundTag view = menu.snapshot();
        return new StorageNetwork.SearchRequest(menu.containerId, view.getLong("Session"), view.getLong("QuerySeq"),
                view.getLong("SearchRevision"), view.getLong("ResultViewSeq"), action,
                view.getBoolean("SearchRecursive"), page, size, id, amount, view.getLong("Revision"), matches);
    }

    private static void start(StorageMenu menu, boolean recursive, int size, int... matches) {
        CompoundTag view = menu.snapshot();
        menu.handleSearch(new StorageNetwork.SearchRequest(menu.containerId, view.getLong("Session"), view.getLong("QuerySeq") + 1,
                view.getLong("SearchRevision"), view.getLong("ResultViewSeq"), SearchAction.START, recursive, 0, size, 0, 0,
                view.getLong("Revision"), new int[0]));
        menu.handleSearch(search(menu, SearchAction.APPLY, 0, size, 0, 0, matches));
    }

    private static Set<Integer> ids(CompoundTag view) {
        Set<Integer> result = new HashSet<>();
        for (Tag value : view.getList("Entries", Tag.TAG_COMPOUND)) result.add(((CompoundTag) value).getInt("Id"));
        return result;
    }

    @GameTest(template = "empty")
    public static void recursiveSearchIncludesDescendantsAndVolumeIncludesOtherFolders(GameTestHelper helper) {
        var storage = new StorageInventory(() -> {});
        int ores = storage.createFolder(0, "矿物"), deep = storage.createFolder(ores, "深层"), other = storage.createFolder(0, "其他");
        storage.insert(new ItemStack(Items.IRON_INGOT, 10), 10, 0);
        storage.insert(new ItemStack(Items.IRON_INGOT, 20), 20, ores);
        storage.insert(new ItemStack(Items.IRON_INGOT, 30), 30, deep);
        storage.insert(new ItemStack(Items.IRON_INGOT, 40), 40, other);
        int[] entries = storage.entries().stream().mapToInt(StorageInventory.Entry::id).toArray();
        Set<Integer> matches = Set.of(entries[0], entries[1], entries[2], entries[3], 99999);
        helper.assertTrue(ids(storage.searchView(ores, true, matches, 0, 6, "")).equals(Set.of(entries[1], entries[2])), "Directory scope included a sibling/root or missed a descendant");
        CompoundTag whole = storage.searchView(ores, false, matches, 0, 6, "");
        helper.assertTrue(ids(whole).size() == 4 && whole.getInt("SearchMatches") == 4 && whole.getIntArray("PageFolders").length == 0, "Volume search did not include every valid entry");
        helper.assertTrue(!storage.inSearchScope(99999, ores, false) && !storage.inSearchScope(entries[0], ores, true), "Invalid or out-of-scope entry accepted");
        helper.assertTrue(whole.getList("Entries", Tag.TAG_COMPOUND).getCompound(2).getInt("Folder") == deep, "Search result lost its source folder");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void catalogKeepsDirectoryAndPotionVariantsWithoutSendingItemPayloads(GameTestHelper helper) {
        var storage = new StorageInventory(() -> {});
        int folder = storage.createFolder(0, "试剂");
        storage.insert(new ItemStack(Items.IRON_INGOT, 2), 2, 0);
        storage.insert(new ItemStack(Items.IRON_INGOT, 3), 3, folder);
        storage.insert(PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.HEALING), 1, folder);
        storage.insert(PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.SWIFTNESS), 1, folder);
        ItemStack custom = new ItemStack(Items.DIAMOND);
        custom.setHoverName(Component.literal("精炼 宝石").withStyle(ChatFormatting.GOLD));
        custom.getOrCreateTag().putString("UnrelatedPayload", "x".repeat(6000));
        storage.insert(custom, 1, folder);
        var catalog = storage.searchCatalog();
        helper.assertTrue(catalog.size() == 5 && catalog.stream().map(row -> row.getInt("Id")).distinct().count() == 5, "Catalog merged separate directories or NBT variants");
        helper.assertTrue(catalog.get(2).getString("Name").contains("translate") && !catalog.get(2).getString("Name").equals(catalog.get(3).getString("Name")), "Potion translations collapsed before client language resolution");
        helper.assertTrue(catalog.get(4).getString("Name").equals(Component.Serializer.toJson(custom.getHoverName()))
                && catalog.get(4).getString("Item").equals("minecraft:diamond"), "Custom name component or registry ID changed");
        for (CompoundTag metadata : catalog) {
            helper.assertTrue(metadata.getAllKeys().equals(Set.of("Id", "Folder", "Item", "Name"))
                    && SearchCatalogSupport.encodedBytes(metadata) < 1000, "Search metadata sent a full sample/count payload");
        }
        catalog.get(0).putString("Name", "modified");
        helper.assertTrue(!storage.searchCatalog().get(0).getString("Name").equals("modified"), "Caller mutated the cached catalog");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void expandedNameFallbackPreservesValidItemDataAndOrdinaryLongNames(GameTestHelper helper) {
        var storage = new StorageInventory(() -> {});
        ItemStack expanded = new ItemStack(Items.IRON_INGOT);
        expanded.getOrCreateTagElement("display").putString("Name", "[" + "0,".repeat(3899) + "0]");
        helper.assertTrue(SearchCatalogSupport.encodedBytes(expanded.save(new CompoundTag())) < StorageInventory.MAX_ITEM_BYTES,
                "Expanded-name regression sample must remain a legal stored item");
        String expandedJson = Component.Serializer.toJson(expanded.copy().getHoverName());
        helper.assertTrue(expandedJson.length() > SearchCatalogSupport.MAX_DATA_BYTES,
                "Regression sample did not expand beyond the catalog transport budget");
        ItemStack ordinary = new ItemStack(Items.IRON_INGOT);
        ordinary.setHoverName(Component.literal("长名称 " + "a".repeat(7000)));
        helper.assertTrue(SearchCatalogSupport.encodedBytes(ordinary.save(new CompoundTag())) < StorageInventory.MAX_ITEM_BYTES,
                "Ordinary long-name sample must remain a legal stored item");
        storage.insert(expanded, 1, 0); storage.insert(ordinary, 1, 0);
        Tag authoritative = storage.save();
        var catalog = storage.searchCatalog();
        helper.assertTrue(catalog.size() == 2 && catalog.get(0).getInt("Id") == storage.entries().get(0).id()
                && catalog.get(0).getInt("Folder") == 0 && catalog.get(0).getString("Item").equals("minecraft:iron_ingot")
                && catalog.get(0).getString("Name").equals(Component.Serializer.toJson(Component.literal("minecraft:iron_ingot"))),
                "Oversized expanded component did not fall back to its registry ID with identity/source intact");
        helper.assertTrue(catalog.get(1).getString("Name").equals(Component.Serializer.toJson(ordinary.getHoverName())),
                "Legal ordinary long name was truncated or unnecessarily replaced");
        helper.assertTrue(authoritative.equals(storage.save()) && storage.total() == 2,
                "Search name fallback changed the authoritative item NBT or quantity");
        var batches = SearchCatalogSupport.batches(catalog, new int[0]);
        helper.assertTrue(batches.stream().allMatch(batch -> SearchCatalogSupport.encodedBytes(batch) <= SearchCatalogSupport.MAX_DATA_BYTES)
                && batches.stream().mapToInt(batch -> batch.getList("Entries", Tag.TAG_COMPOUND).size()).sum() == 2,
                "Expanded-name fallback still prevented a complete bounded catalog");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void searchLayoutKeepsToolbarAndResultsInsideSmallAndLargeWindows(GameTestHelper helper) {
        int[][] viewports = {{320, 234}, {320, 240}, {360, 240}, {480, 270}, {720, 415}, {1920, 1080}};
        var storage = new StorageInventory(() -> {});
        Set<Integer> matches = new HashSet<>();
        for (int i = 0; i < 12; i++) {
            ItemStack stack = new ItemStack(Items.IRON_INGOT);
            stack.getOrCreateTag().putInt("Variant", i);
            storage.insert(stack, 1, 0);
            matches.add(storage.entries().get(i).id());
        }
        for (int[] viewport : viewports) {
            StorageLayout layout = StorageLayout.fit(viewport[0], viewport[1]);
            helper.assertTrue(layout.width() <= viewport[0] && layout.height() <= viewport[1], "Search window extends beyond the viewport");
            helper.assertTrue(layout.searchWidth() > 0 && 8 + layout.searchWidth() + 4 <= layout.searchScopeX()
                    && layout.searchScopeX() + 46 + 4 <= layout.searchExitX()
                    && layout.searchExitX() + 18 + 4 <= layout.width() - 80
                    && layout.width() - 26 + 18 <= layout.width(), "Search field/scope/exit/paging toolbar controls overlap or leave the window");
            helper.assertTrue(23 + 18 < layout.browserY() && layout.searchRows() > 0
                    && layout.searchRows() <= StorageInventory.MAX_PAGE_SIZE
                    && layout.browserY() + layout.searchRows() * 30 < layout.controlsY()
                    && layout.controlsY() + 18 < layout.inventoryY(), "Search rows overlap toolbar, transfer controls or player inventory");
            helper.assertTrue(layout.browserX() + layout.browserWidth() <= layout.width() - 8
                    && layout.browserWidth() - 2 - 30 - 64 > 0, "Search result row lacks bounded space for icon, name and maximum count label");
            CompoundTag result = storage.searchView(0, false, matches, 0, layout.searchRows(), "");
            helper.assertTrue(result.getInt("PageSize") == layout.searchRows()
                    && result.getList("Entries", Tag.TAG_COMPOUND).size() == layout.searchRows()
                    && result.getIntArray("PageFolders").length == 0, "Result pagination does not match the visible search row budget");
        }
        helper.assertTrue(StorageLayout.fit(320, 234).searchRows() == 2
                && StorageLayout.fit(1920, 1080).searchRows() == 5, "Small/large search windows did not expose their expected row counts");
        helper.assertTrue(storage.total() == 12, "Layout/page inspection changed stored inventory");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void countOnlyTransfersKeepCatalogRevisionWhileMembershipAndPathsInvalidateIt(GameTestHelper helper) {
        var storage = new StorageInventory(() -> {});
        int folder = storage.createFolder(0, "目标");
        storage.insert(new ItemStack(Items.IRON_INGOT, 20), 20, 0);
        storage.insert(new ItemStack(Items.IRON_INGOT, 20), 20, folder);
        int source = storage.entries().get(0).id(), destination = storage.entries().get(1).id();
        long revision = storage.searchRevision(), general = storage.revision();
        var catalog = storage.searchCatalog();
        storage.insert(new ItemStack(Items.IRON_INGOT, 5), 5, 0, true);
        storage.take(source, 1, true);
        helper.assertTrue(storage.revision() == general && storage.searchRevision() == revision, "Simulation invalidated the catalog");
        storage.insert(new ItemStack(Items.IRON_INGOT, 5), 5, 0);
        storage.take(source, 1);
        storage.move(source, folder, 3);
        helper.assertTrue(storage.searchRevision() == revision && storage.revision() > general && storage.searchCatalog().equals(catalog), "Count-only transfers rebuilt the catalog");
        storage.move(source, folder, Long.MAX_VALUE);
        helper.assertTrue(storage.searchRevision() > revision && storage.searchCatalog().size() == 1 && storage.entry(destination).count() == 44, "Merged-away source did not invalidate membership");
        revision = storage.searchRevision();
        storage.renameFolder(folder, "改名");
        helper.assertTrue(storage.searchRevision() > revision, "Folder rename did not invalidate displayed paths");
        revision = storage.searchRevision();
        storage.move(destination, 0, Long.MAX_VALUE);
        helper.assertTrue(storage.searchRevision() > revision && storage.searchCatalog().get(0).getInt("Folder") == 0, "Entry move kept a stale source folder");
        revision = storage.searchRevision();
        storage.deleteFolder(folder);
        helper.assertTrue(storage.searchRevision() > revision, "Folder deletion did not invalidate paths");
        revision = storage.searchRevision();
        storage.take(destination, Long.MAX_VALUE);
        helper.assertTrue(storage.searchRevision() > revision && storage.searchCatalog().isEmpty(), "Taking the final stack did not remove the catalog row");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void matcherSupportsChineseNamesIdsNamespacesAndAllKeywords(GameTestHelper helper) {
        helper.assertTrue(StorageSearch.matches("  IRON  INGOT ", "铁锭", "minecraft:iron_ingot"), "Registry ID matching was not case-insensitive/all-keyword");
        helper.assertTrue(StorageSearch.matches("@MINECRAFT 铁", "精炼铁锭", "minecraft:iron_ingot"), "Namespace/name conjunction failed");
        helper.assertTrue(StorageSearch.matches("精炼 铁", "精炼铁锭", "minecraft:iron_ingot"), "Chinese name keywords failed");
        helper.assertTrue(StorageSearch.matches("", "铁锭", "minecraft:iron_ingot"), "Empty query should match all items");
        helper.assertTrue(StorageSearch.matches("@mine", "铁锭", "minecraft:iron_ingot")
                && StorageSearch.matches("铁\u3000锭", "铁锭", "minecraft:iron_ingot"), "Namespace substring or Unicode whitespace failed");
        helper.assertTrue(!StorageSearch.matches("铁 金", "铁锭", "minecraft:iron_ingot")
                && !StorageSearch.matches("@mekanism 铁", "铁锭", "minecraft:iron_ingot")
                && !StorageSearch.matches("@", "铁锭", "minecraft:iron_ingot")
                && !StorageSearch.matches("x".repeat(StorageSearch.MAX_QUERY + 1), "x".repeat(100), "minecraft:iron_ingot"), "Missing keyword, empty namespace or oversized query matched");
        helper.assertTrue(StorageSearch.name(Component.Serializer.toJson(Component.literal("自定义 铁"))).equals("自定义 铁")
                && StorageSearch.name("{broken-json").isEmpty(), "Name component parsing lost a valid name or accepted malformed JSON");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void locatePageIncludesChildFoldersBeforeItems(GameTestHelper helper) {
        var storage = new StorageInventory(() -> {});
        for (int i = 0; i < 7; i++) storage.createFolder(0, "目录" + i);
        for (int i = 0; i < 8; i++) {
            ItemStack item = new ItemStack(Items.IRON_INGOT);
            item.setHoverName(Component.literal("铁锭 " + i));
            storage.insert(item, 1, 0);
        }
        int target = storage.entries().get(4).id();
        int page = storage.locatePage(target, 6);
        helper.assertTrue(page == 1 && ids(storage.view(0, page, 6, "")).contains(target), "Locate ignored the folder/item shared page budget");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void full4096EntryCatalogIsSplitIntoBoundedCompleteBatches(GameTestHelper helper) {
        var storage = new StorageInventory(() -> {}, DiskTier.M16.limits());
        for (int i = 0; i < StorageSearch.MAX_MATCHES; i++) {
            ItemStack stack = new ItemStack(Items.IRON_INGOT);
            stack.getOrCreateTag().putInt("Variant", i);
            storage.insert(stack, 1, 0);
        }
        var batches = SearchCatalogSupport.batches(storage.searchCatalog(), new int[0]);
        Set<Integer> found = new HashSet<>();
        helper.assertTrue(batches.size() > 1, "Full catalog was not split");
        for (CompoundTag batch : batches) {
            helper.assertTrue(SearchCatalogSupport.encodedBytes(batch) <= SearchCatalogSupport.MAX_DATA_BYTES, "Catalog batch exceeded the bounded transport budget");
            for (Tag row : batch.getList("Entries", Tag.TAG_COMPOUND)) helper.assertTrue(found.add(((CompoundTag) row).getInt("Id")), "Catalog batch repeated an entry");
        }
        helper.assertTrue(found.size() == 4096 && storage.total() == 4096, "Batched catalog lost entries or mutated quantities");
        int[] removed = found.stream().mapToInt(Integer::intValue).toArray();
        var removal = SearchCatalogSupport.batches(java.util.List.of(), removed);
        helper.assertTrue(removal.size() == 1 && removal.get(0).getIntArray("Removed").length == 4096
                && SearchCatalogSupport.encodedBytes(removal.get(0)) <= SearchCatalogSupport.MAX_DATA_BYTES, "Maximum removal diff exceeded the packet budget");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void searchRequestsAndCatalogBatchesSurviveWireEncoding(GameTestHelper helper) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            int[] identities = new int[4096];
            for (int i = 0; i < identities.length; i++) identities[i] = i + 1;
            for (SearchAction action : SearchAction.values()) {
                var request = new StorageNetwork.SearchRequest(1, 9876, 35, 18, 42, action, true, 400, 30, 4096, Long.MAX_VALUE, 7890, identities);
                request.encode(buffer);
                var decoded = StorageNetwork.SearchRequest.decode(buffer);
                helper.assertTrue(request.equals(decoded) && request.hashCode() == decoded.hashCode() && !buffer.isReadable(), "Search request codec changed " + action);
                buffer.clear();
            }
            var storage = new StorageInventory(() -> {});
            ItemStack custom = new ItemStack(Items.IRON_INGOT);
            custom.setHoverName(Component.literal("中文搜索"));
            storage.insert(custom, 1, 0);
            CompoundTag data = SearchCatalogSupport.batches(storage.searchCatalog(), new int[]{17, 18}).get(0);
            var catalog = new StorageNetwork.SearchCatalog(3, 45, 67, 1, true, false, data);
            catalog.encode(buffer);
            helper.assertTrue(catalog.equals(StorageNetwork.SearchCatalog.decode(buffer)) && !buffer.isReadable(), "Catalog codec changed Unicode names or batch metadata");
            buffer.clear();
            new StorageNetwork.SearchRequest(1, 2, 3, 4, 5, SearchAction.APPLY, false, 0, 6, 0, 0, 6, new int[0]).encode(buffer);
            // Replace the final length varint, without writing/allocating an oversized identity payload.
            buffer.writerIndex(buffer.writerIndex() - 1);
            buffer.writeVarInt(StorageSearch.MAX_MATCHES + 1);
            boolean rejected = false;
            try { StorageNetwork.SearchRequest.decode(buffer); }
            catch (IllegalArgumentException expected) { rejected = true; }
            helper.assertTrue(rejected, "Decoder accepted an oversized match list");
            int[] mutable = {1, 2};
            var isolated = new StorageNetwork.SearchRequest(1, 2, 3, 4, 5, SearchAction.APPLY, false, 0, 6, 0, 0, 6, mutable);
            mutable[0] = 99; isolated.matches()[1] = 99;
            helper.assertTrue(Arrays.equals(isolated.matches(), new int[]{1, 2}), "Search request retained a mutable identity array");
        } finally { buffer.release(); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void searchOnlyAllowsWithdrawalFromThePublishedResultPage(GameTestHelper helper) {
        Opened opened = terminal(helper);
        var storage = opened.entity().inventory();
        for (int i = 0; i < 3; i++) {
            ItemStack stack = new ItemStack(Items.IRON_INGOT, 8);
            stack.setHoverName(Component.literal("批次" + i));
            storage.insert(stack, 8, 0);
        }
        int first = storage.entries().get(0).id(), second = storage.entries().get(1).id(), excluded = storage.entries().get(2).id();
        StorageMenu menu = opened.menu();
        start(menu, false, 1, first, second);
        helper.assertTrue(menu.snapshot().getBoolean("Searching") && menu.snapshot().getBoolean("SearchReady")
                && menu.snapshot().getInt("Pages") == 2 && ids(menu.snapshot()).equals(Set.of(first)), "First search page was not published");
        long published = menu.snapshot().getLong("ResultViewSeq");
        menu.snapshot(); menu.snapshot();
        helper.assertTrue(menu.snapshot().getLong("ResultViewSeq") == published, "Read-only snapshots changed the published page identity");
        menu.handleSearch(search(menu, SearchAction.TAKE, 0, 1, excluded, 2));
        menu.handleSearch(search(menu, SearchAction.TAKE, 0, 1, second, 2));
        helper.assertTrue(storage.total() == 24 && opened.player().getInventory().isEmpty(), "Hidden/unmatched result could be withdrawn");
        menu.handleSearch(search(menu, SearchAction.TAKE, 0, 1, first, 2));
        helper.assertTrue(storage.entry(first).count() == 6 && opened.player().getInventory().countItem(Items.IRON_INGOT) == 2, "Published result could not be withdrawn");
        var delayed = search(menu, SearchAction.TAKE, 0, 1, first, 2);
        menu.handleSearch(search(menu, SearchAction.PAGE, 1, 1, 0, 0));
        menu.handleSearch(delayed);
        helper.assertTrue(storage.entry(first).count() == 6 && ids(menu.snapshot()).equals(Set.of(second)), "Old page request consumed inventory");
        menu.handleSearch(search(menu, SearchAction.TAKE, 1, 1, second, 3));
        helper.assertTrue(storage.total() == 19 && opened.player().getInventory().countItem(Items.IRON_INGOT) == 5, "Paged withdrawals did not conserve total quantity");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void forgedContextFieldsAndPreviousMenuSessionCannotWithdraw(GameTestHelper helper) {
        Opened opened = terminal(helper);
        var storage = opened.entity().inventory();
        storage.insert(new ItemStack(Items.IRON_INGOT, 16), 16, 0);
        int id = storage.entries().get(0).id();
        StorageMenu menu = opened.menu();
        start(menu, false, 6, id);
        for (int field = 0; field < 6; field++) {
            var valid = search(menu, SearchAction.TAKE, 0, 6, id, 4);
            var forged = new StorageNetwork.SearchRequest(valid.menuId() + (field == 0 ? 1 : 0),
                    valid.session() + (field == 1 ? 1 : 0), valid.querySeq() + (field == 2 ? 1 : 0),
                    valid.catalogRevision() + (field == 3 ? 1 : 0), valid.viewSeq() + (field == 4 ? 1 : 0),
                    valid.action(), valid.recursive(), valid.page(), valid.pageSize(), id, valid.amount(),
                    valid.revision() + (field == 5 ? 1 : 0), valid.matches());
            menu.handleSearch(forged);
        }
        helper.assertTrue(storage.total() == 16 && opened.player().getInventory().isEmpty(), "Forged menu/session/query/catalog/view/inventory context extracted items");
        menu.handleSearch(search(menu, SearchAction.TAKE, 0, 6, id, 4));
        helper.assertTrue(storage.total() == 12 && opened.player().getInventory().countItem(Items.IRON_INGOT) == 4, "Fresh context failed after rejected requests");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void newerQueryRejectsDelayedApplyAndTake(GameTestHelper helper) {
        Opened opened = terminal(helper);
        var storage = opened.entity().inventory();
        storage.insert(new ItemStack(Items.IRON_INGOT, 16), 16, 0);
        storage.insert(new ItemStack(Items.DIAMOND, 16), 16, 0);
        int iron = storage.entries().get(0).id(), diamond = storage.entries().get(1).id();
        StorageMenu menu = opened.menu();
        start(menu, false, 6, iron);
        var oldTake = search(menu, SearchAction.TAKE, 0, 6, iron, 4);
        var oldApply = search(menu, SearchAction.APPLY, 0, 6, 0, 0, iron);
        var newApply = new StorageNetwork.SearchRequest(oldApply.menuId(), oldApply.session(), oldApply.querySeq() + 1,
                oldApply.catalogRevision(), oldApply.viewSeq(), SearchAction.APPLY, false, 0, 6, 0, 0, oldApply.revision(), new int[]{diamond});
        menu.handleSearch(newApply);
        menu.handleSearch(oldApply);
        menu.handleSearch(oldTake);
        helper.assertTrue(ids(menu.snapshot()).equals(Set.of(diamond)) && storage.total() == 32 && opened.player().getInventory().isEmpty(), "Old query replaced new results or extracted an old result");
        menu.handleSearch(search(menu, SearchAction.TAKE, 0, 6, diamond, 4));
        helper.assertTrue(storage.total() == 28 && opened.player().getInventory().countItem(Items.DIAMOND) == 4, "New query withdrawal did not conserve inventory");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void recursiveMenuScopeRejectsSiblingIdsAndCanSwitchToVolume(GameTestHelper helper) {
        Opened opened = terminal(helper);
        var storage = opened.entity().inventory();
        int root = storage.createFolder(0, "范围"), child = storage.createFolder(root, "子目录"), sibling = storage.createFolder(0, "其他");
        storage.insert(new ItemStack(Items.IRON_INGOT, 8), 8, root);
        storage.insert(new ItemStack(Items.DIAMOND, 8), 8, child);
        storage.insert(new ItemStack(Items.GOLD_INGOT, 8), 8, sibling);
        int iron = storage.entries().get(0).id(), diamond = storage.entries().get(1).id(), gold = storage.entries().get(2).id();
        StorageMenu menu = opened.menu();
        menu.handle(normal(menu, Action.OPEN, root, 0, 0, ""));
        start(menu, true, 6, iron, diamond);
        helper.assertTrue(ids(menu.snapshot()).equals(Set.of(iron, diamond)) && menu.snapshot().getBoolean("SearchRecursive"), "Recursive menu results did not include descendants");
        menu.handleSearch(search(menu, SearchAction.APPLY, 0, 6, 0, 0, iron, diamond, gold));
        menu.handleSearch(search(menu, SearchAction.TAKE, 0, 6, gold, 3));
        helper.assertTrue(ids(menu.snapshot()).equals(Set.of(iron, diamond)) && storage.total() == 24
                && opened.player().getInventory().isEmpty(), "Client match list escaped the recursive directory scope");
        var apply = search(menu, SearchAction.APPLY, 0, 6, 0, 0, iron, diamond, gold);
        menu.handleSearch(new StorageNetwork.SearchRequest(apply.menuId(), apply.session(), apply.querySeq() + 1,
                apply.catalogRevision(), apply.viewSeq(), SearchAction.APPLY, false, 0, 6, 0, 0, apply.revision(), apply.matches()));
        helper.assertTrue(ids(menu.snapshot()).equals(Set.of(iron, diamond, gold)) && !menu.snapshot().getBoolean("SearchRecursive"), "Scope switch did not reset and expand results to the selected volume");
        menu.handleSearch(search(menu, SearchAction.TAKE, 0, 6, gold, 3));
        helper.assertTrue(storage.total() == 21 && opened.player().getInventory().countItem(Items.GOLD_INGOT) == 3, "Expanded scope withdrawal did not conserve items");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void simultaneousSearchMenusRejectSharedStaleTakeAndConserveItems(GameTestHelper helper) {
        Opened first = terminal(helper);
        var storage = first.entity().inventory();
        storage.insert(new ItemStack(Items.IRON_INGOT, 64), 64, 0);
        int id = storage.entries().get(0).id();
        ServerPlayer secondPlayer = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "search-other")) {
            @Override public boolean hasDisconnected() { return false; }
        };
        secondPlayer.setPos(first.player().getX(), first.player().getY(), first.player().getZ());
        StorageMenu second = new StorageMenu(1, secondPlayer.getInventory(), first.entity().getBlockPos(), first.entity());
        secondPlayer.containerMenu = second;
        start(first.menu(), false, 6, id); start(second, false, 6, id);
        var delayed = search(second, SearchAction.TAKE, 0, 6, id, 48);
        first.menu().handleSearch(search(first.menu(), SearchAction.TAKE, 0, 6, id, 48));
        second.handleSearch(delayed);
        helper.assertTrue(storage.total() == 16 && first.player().getInventory().countItem(Items.IRON_INGOT) == 48
                && secondPlayer.getInventory().isEmpty(), "Simultaneous search consumers accepted stale shared inventory");
        second.handleSearch(search(second, SearchAction.TAKE, 0, 6, id, 48));
        second.handleSearch(delayed);
        helper.assertTrue(storage.total() == 0 && first.player().getInventory().countItem(Items.IRON_INGOT)
                + secondPlayer.getInventory().countItem(Items.IRON_INGOT) == 64, "Repeated/over-sized search take lost or duplicated shared items");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void countChangeRejectsDelayedTakeWithoutInvalidatingSearchNames(GameTestHelper helper) {
        Opened opened = terminal(helper);
        var storage = opened.entity().inventory();
        storage.insert(new ItemStack(Items.IRON_INGOT, 16), 16, 0);
        int id = storage.entries().get(0).id();
        StorageMenu menu = opened.menu();
        start(menu, false, 6, id);
        long catalogRevision = storage.searchRevision();
        var old = search(menu, SearchAction.TAKE, 0, 6, id, 4);
        storage.insert(new ItemStack(Items.IRON_INGOT, 8), 8, 0);
        menu.handleSearch(old);
        helper.assertTrue(storage.total() == 24 && opened.player().getInventory().isEmpty()
                && storage.searchRevision() == catalogRevision && menu.snapshot().getBoolean("SearchReady"), "Count change accepted stale take or invalidated the name catalog");
        menu.handleSearch(search(menu, SearchAction.TAKE, 0, 6, id, 4));
        helper.assertTrue(storage.total() == 20 && opened.player().getInventory().countItem(Items.IRON_INGOT) == 4, "Fresh count update could not be withdrawn");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void structuralChangeRejectsOldCatalogAndAcceptsRecomputedMatches(GameTestHelper helper) {
        Opened opened = terminal(helper);
        var storage = opened.entity().inventory();
        storage.insert(new ItemStack(Items.IRON_INGOT, 16), 16, 0);
        int id = storage.entries().get(0).id();
        StorageMenu menu = opened.menu();
        start(menu, false, 6, id);
        var old = search(menu, SearchAction.TAKE, 0, 6, id, 4);
        var oldApply = search(menu, SearchAction.APPLY, 0, 6, 0, 0, id);
        storage.insert(new ItemStack(Items.DIAMOND, 8), 8, 0);
        menu.handleSearch(old); menu.handleSearch(oldApply);
        helper.assertTrue(storage.total() == 24 && opened.player().getInventory().isEmpty() && !menu.snapshot().getBoolean("SearchReady"), "Structural change kept stale search results actionable");
        helper.runAtTickTime(6, () -> {
            menu.broadcastChanges();
            menu.handleSearch(search(menu, SearchAction.APPLY, 0, 6, 0, 0, id));
            menu.handleSearch(search(menu, SearchAction.TAKE, 0, 6, id, 4));
            helper.assertTrue(menu.snapshot().getBoolean("SearchReady") && storage.total() == 20
                    && opened.player().getInventory().countItem(Items.IRON_INGOT) == 4, "Recomputed catalog did not restore safe withdrawal");
            helper.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void searchRejectsImplicitDepositsMovesAndNormalWithdrawal(GameTestHelper helper) {
        Opened opened = terminal(helper);
        var storage = opened.entity().inventory();
        int folder = storage.createFolder(0, "目标");
        storage.insert(new ItemStack(Items.IRON_INGOT, 16), 16, 0);
        int id = storage.entries().get(0).id();
        StorageMenu menu = opened.menu();
        start(menu, false, 6, id);
        menu.setCarried(new ItemStack(Items.DIAMOND, 5));
        opened.player().getInventory().setItem(0, new ItemStack(Items.GOLD_INGOT, 7));
        menu.handle(normal(menu, Action.DEPOSIT_CURSOR, 0, 0, 0, ""));
        menu.handle(normal(menu, Action.DEPOSIT_SLOT, 27, 0, 0, ""));
        menu.handle(normal(menu, Action.MOVE, id, folder, 8, ""));
        menu.handle(normal(menu, Action.WITHDRAW, id, 0, 8, ""));
        helper.assertTrue(storage.total() == 16 && storage.entry(id).folder() == 0 && menu.getCarried().getCount() == 5
                && opened.player().getInventory().countItem(Items.GOLD_INGOT) == 7
                && opened.player().getInventory().countItem(Items.IRON_INGOT) == 0, "Searching implicitly deposited, moved or bypassed result withdrawal");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void exitingSearchRestoresFolderPageAndNormalPageSize(GameTestHelper helper) {
        Opened opened = terminal(helper);
        var storage = opened.entity().inventory();
        int folder = storage.createFolder(0, "原目录");
        for (int i = 0; i < 7; i++) storage.createFolder(folder, "子目录" + i);
        storage.insert(new ItemStack(Items.IRON_INGOT, 16), 16, folder);
        storage.insert(new ItemStack(Items.DIAMOND, 16), 16, 0);
        int iron = storage.entries().get(0).id(), diamond = storage.entries().get(1).id();
        StorageMenu menu = opened.menu();
        menu.handle(normal(menu, Action.OPEN, folder, 0, 0, ""));
        menu.handle(normal(menu, Action.PAGE, 0, 0, 1, ""));
        start(menu, false, 1, iron, diamond);
        helper.assertTrue(menu.snapshot().getInt("SearchRoot") == folder && menu.snapshot().getInt("SearchMatches") == 2
                && !menu.snapshot().getBoolean("SearchRecursive"), "Default search did not cover the entire selected volume");
        menu.handleSearch(search(menu, SearchAction.RESIZE, 0, 2, 0, 0));
        var exit = search(menu, SearchAction.EXIT, 0, 2, 0, 0);
        // Text editing advances the client sequence before its debounced APPLY has reached the server.
        menu.handleSearch(new StorageNetwork.SearchRequest(exit.menuId(), exit.session(), exit.querySeq() + 1,
                exit.catalogRevision(), exit.viewSeq(), SearchAction.EXIT, exit.recursive(), 0, 2, 0, 0, exit.revision(), exit.matches()));
        CompoundTag restored = menu.snapshot();
        helper.assertTrue(!restored.getBoolean("Searching") && restored.getInt("Current") == folder && restored.getInt("Page") == 1
                && restored.getInt("PageSize") == 6 && ids(restored).contains(iron), "Exit lost the saved directory/page/page size");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void locatingSearchResultOpensSourcePageAndHighlightsEntry(GameTestHelper helper) {
        Opened opened = terminal(helper);
        var storage = opened.entity().inventory();
        int folder = storage.createFolder(0, "来源目录");
        for (int i = 0; i < 7; i++) storage.createFolder(folder, "子目录" + i);
        storage.insert(new ItemStack(Items.IRON_INGOT, 16), 16, folder);
        int id = storage.entries().get(0).id();
        StorageMenu menu = opened.menu();
        start(menu, false, 1, id);
        menu.handleSearch(search(menu, SearchAction.LOCATE, 0, 1, id, 0));
        CompoundTag located = menu.snapshot();
        helper.assertTrue(!located.getBoolean("Searching") && located.getInt("Current") == folder
                && located.getInt("Page") == 1 && located.getInt("PageSize") == 6
                && located.getInt("Located") == id && ids(located).contains(id), "Locate did not expose/highlight the result after folder-first pagination");
        helper.assertTrue(storage.total() == 16 && opened.player().getInventory().isEmpty(), "Locate moved or withdrew items");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void closedMenuAndReusedContainerIdRejectPreviousSearchRequests(GameTestHelper helper) {
        Opened opened = terminal(helper);
        var storage = opened.entity().inventory();
        storage.insert(new ItemStack(Items.IRON_INGOT, 16), 16, 0);
        int id = storage.entries().get(0).id();
        StorageMenu old = opened.menu();
        start(old, false, 6, id);
        var delayed = search(old, SearchAction.TAKE, 0, 6, id, 8);
        old.removed(opened.player());
        var reopened = new StorageMenu(1, opened.player().getInventory(), opened.entity().getBlockPos(), opened.entity());
        opened.player().containerMenu = reopened;
        start(reopened, false, 6, id);
        old.handleSearch(delayed); reopened.handleSearch(delayed);
        helper.assertTrue(storage.total() == 16 && opened.player().getInventory().isEmpty() && !old.stillValid(opened.player()), "Closed/reopened menu revived a previous search request");
        reopened.handleSearch(search(reopened, SearchAction.TAKE, 0, 6, id, 8));
        helper.assertTrue(storage.total() == 8 && opened.player().getInventory().countItem(Items.IRON_INGOT) == 8, "Reopened search could not make a fresh transfer");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void nasEjectReinsertAndDiskSwitchInvalidateSearchMountSession(GameTestHelper helper) {
        BlockPos local = new BlockPos(2, 2, 3);
        helper.setBlock(local, ModContent.NAS_BLOCK.get());
        var nas = (NasBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(local));
        nas.install(0, new ItemStack(ModContent.DISK_64K.get()));
        nas.install(1, new ItemStack(ModContent.DISK_64K.get()));
        for (int bay = 0; bay < 2; bay++) nas.volume(bay).inventory().insert(new ItemStack(Items.IRON_INGOT, 16), 16, 0);
        Opened opened = terminal(helper);
        StorageMenu menu = opened.menu();
        menu.handle(normal(menu, Action.SELECT_VOLUME, 0, 0, 0, DiskItem.id(nas.disk(0)).toString()));
        start(menu, false, 6, 1);
        var delayed = search(menu, SearchAction.TAKE, 0, 6, 1, 8);
        ItemStack disk = nas.eject(0);
        menu.handleSearch(delayed);
        helper.assertTrue(!menu.snapshot().getBoolean("Available") && !menu.snapshot().getBoolean("Searching")
                && opened.player().getInventory().isEmpty(), "Offline selected disk extracted items or retained actionable search");
        nas.install(0, disk);
        menu.handleSearch(delayed);
        helper.assertTrue(nas.volume(0).inventory().total() == 16, "Reinsert revived old search mount context");
        start(menu, false, 6, 1);
        var beforeSwitch = search(menu, SearchAction.TAKE, 0, 6, 1, 8);
        menu.handle(normal(menu, Action.SELECT_VOLUME, 0, 0, 0, DiskItem.id(nas.disk(1)).toString()));
        menu.handleSearch(beforeSwitch);
        helper.assertTrue(nas.volume(0).inventory().total() == 16 && nas.volume(1).inventory().total() == 16
                && opened.player().getInventory().isEmpty(), "Same-ID/same-revision disk switch accepted previous result context");
        helper.succeed();
    }
}
