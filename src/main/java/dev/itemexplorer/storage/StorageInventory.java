package dev.itemexplorer.storage;

import dev.itemexplorer.block.StorageBlock;
import dev.itemexplorer.disk.DiskItem;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Server-owned inventory. Counts are independent of vanilla stack limits. */
public final class StorageInventory implements StorageAccess {
    public static final int DATA_VERSION = 1;
    public static final int CAPACITY = 4096;
    public static final int MAX_ENTRIES = 128;
    public static final int MAX_FOLDERS = 64;
    public static final int MAX_NAME = 24;
    public static final int MAX_DEPTH = 8;
    public static final int MAX_ITEM_BYTES = 8192;
    public static final int PAGE_SIZE = 6;
    public static final int MAX_PAGE_SIZE = 30;

    public record Folder(int id, int parent, String name) {}
    public record Entry(int id, int folder, ItemStack stack, long count) {}

    private final Map<Integer, Folder> folders = new LinkedHashMap<>();
    private final Map<Integer, Entry> entries = new LinkedHashMap<>();
    private final Runnable changed;
    private final StorageLimits limits;
    private long totalCount;
    private int nextFolder = 1;
    private int nextEntry = 1;
    private long revision;
    private Tag protectedData;
    private String loadProblem = "";

    public StorageInventory(Runnable changed) {
        this(changed, StorageLimits.LOCAL);
    }

    public StorageInventory(Runnable changed, StorageLimits limits) {
        this.changed = changed;
        this.limits = limits;
        folders.put(0, new Folder(0, -1, ""));
    }

    public long revision() { return revision; }
    public boolean isLocked() { return protectedData != null; }
    public String loadProblem() { return loadProblem; }
    public long total() { return totalCount; }
    public long capacity() { return limits.capacity(); }
    public boolean hasFolder(int id) { return folders.containsKey(id); }
    public Folder folder(int id) { return folders.get(id); }
    public List<Folder> folders() { return List.copyOf(folders.values()); }
    public List<Entry> entries() { return entries.values().stream().map(StorageInventory::copy).toList(); }
    public Entry entry(int id) { return entries.containsKey(id) ? copy(entries.get(id)) : null; }

    private static Entry copy(Entry entry) {
        return new Entry(entry.id, entry.folder, entry.stack.copy(), entry.count);
    }

    private void touch() {
        revision++;
        changed.run();
    }

    private void requireFolder(int id) {
        if (!hasFolder(id)) throw new IllegalArgumentException("invalid_folder");
    }

    private void requireWritable() {
        if (isLocked()) throw new IllegalArgumentException("storage_locked");
    }

    private String checkedName(int parent, int except, String input) {
        String name = validName(input);
        if (folders.values().stream().anyMatch(f -> f.id != except && f.parent == parent && f.name.equalsIgnoreCase(name))) {
            throw new IllegalArgumentException("duplicate_name");
        }
        return name;
    }

    public static String validName(String input) {
        String name = input.strip();
        if (name.isEmpty() || name.length() > MAX_NAME || name.equals(".") || name.equals("..")
                || name.codePoints().anyMatch(c -> Character.isISOControl(c) || Character.getType(c) == Character.FORMAT
                || c == '/' || c == '\\' || c == '\u00a7')) {
            throw new IllegalArgumentException("invalid_name");
        }
        return name;
    }

    public int createFolder(int parent, String name) {
        requireWritable();
        requireFolder(parent);
        if (folders.size() >= limits.folders() || nextFolder == Integer.MAX_VALUE) throw new IllegalArgumentException("folder_limit");
        int depth = 0;
        for (Folder f = folders.get(parent); f.parent != -1; f = folders.get(f.parent)) depth++;
        if (depth >= MAX_DEPTH) throw new IllegalArgumentException("folder_limit");
        String checked = checkedName(parent, -1, name);
        int id = nextFolder++;
        folders.put(id, new Folder(id, parent, checked));
        touch();
        return id;
    }

    public void renameFolder(int id, String name) {
        requireWritable();
        requireFolder(id);
        if (id == 0) throw new IllegalArgumentException("root_folder");
        Folder folder = folders.get(id);
        folders.put(id, new Folder(id, folder.parent, checkedName(folder.parent, id, name)));
        touch();
    }

    public int deleteFolder(int id) {
        requireWritable();
        requireFolder(id);
        if (id == 0) throw new IllegalArgumentException("root_folder");
        if (folders.values().stream().anyMatch(f -> f.parent == id)
                || entries.values().stream().anyMatch(e -> e.folder == id)) throw new IllegalArgumentException("not_empty");
        int parent = folders.remove(id).parent;
        touch();
        return parent;
    }

    private Entry matching(int folder, ItemStack stack) {
        return entries.values().stream().filter(e -> e.folder == folder && ItemStack.isSameItemSameTags(e.stack, stack))
                .findFirst().orElse(null);
    }

    public int insert(ItemStack stack, int requested, int folder, boolean simulate) {
        requireWritable();
        requireFolder(folder);
        if (stack.isEmpty() || requested <= 0) return 0;
        if (stack.getItem() instanceof DiskItem || stack.getItem() instanceof BlockItem item && item.getBlock() instanceof StorageBlock) {
            throw new IllegalArgumentException("nested_device");
        }
        int amount = (int) Math.min(Math.min(requested, stack.getCount()), capacity() - total());
        if (amount <= 0) return 0;
        Entry match = matching(folder, stack);
        if (match == null) {
            if (entries.size() >= limits.entries() || nextEntry == Integer.MAX_VALUE) throw new IllegalArgumentException("entry_limit");
            ItemStack sample = stack.copyWithCount(1);
            checkItemSize(sample.save(new CompoundTag()));
            if (simulate) return amount;
            int id = nextEntry++;
            entries.put(id, new Entry(id, folder, sample, amount));
        } else {
            if (simulate) return amount;
            entries.put(match.id, new Entry(match.id, folder, match.stack, match.count + amount));
        }
        totalCount += amount;
        touch();
        return amount;
    }

    /** Returns a normal, legal-sized stack; the caller must handle its destination. */
    public ItemStack take(int id, long requested, boolean simulate) {
        requireWritable();
        Entry entry = entries.get(id);
        if (entry == null || requested <= 0) return ItemStack.EMPTY;
        int amount = (int) Math.min(Math.min(entry.count, requested), entry.stack.getMaxStackSize());
        if (simulate) return entry.stack.copyWithCount(amount);
        if (amount == entry.count) entries.remove(id);
        else entries.put(id, new Entry(id, entry.folder, entry.stack, entry.count - amount));
        totalCount -= amount;
        touch();
        return entry.stack.copyWithCount(amount);
    }

    public long move(int id, int target, long requested) {
        requireWritable();
        requireFolder(target);
        Entry source = entries.get(id);
        if (source == null) throw new IllegalArgumentException("missing_item");
        if (source.folder == target || requested <= 0) return 0;
        long amount = Math.min(source.count, requested);
        Entry match = matching(target, source.stack);
        if (match == null && amount == source.count) {
            entries.put(id, new Entry(id, target, source.stack, amount));
        } else {
            if (match == null && (entries.size() >= limits.entries() || nextEntry == Integer.MAX_VALUE)) {
                throw new IllegalArgumentException("entry_limit");
            }
            if (amount == source.count) entries.remove(id);
            else entries.put(id, new Entry(id, source.folder, source.stack, source.count - amount));
            if (match == null) {
                int newId = nextEntry++;
                entries.put(newId, new Entry(newId, target, source.stack.copy(), amount));
            } else {
                entries.put(match.id, new Entry(match.id, target, match.stack, match.count + amount));
            }
        }
        touch();
        return amount;
    }

    /** Protected data is written back verbatim, including an invalid Storage tag type. */
    public Tag save() {
        if (isLocked()) return protectedData.copy();
        CompoundTag tag = new CompoundTag();
        tag.putInt("Version", limits.format());
        tag.putInt("NextFolder", nextFolder);
        tag.putInt("NextEntry", nextEntry);
        ListTag folderTags = new ListTag();
        for (Folder folder : folders.values()) {
            if (folder.id == 0) continue;
            CompoundTag f = new CompoundTag();
            f.putInt("Id", folder.id);
            f.putInt("Parent", folder.parent);
            f.putString("Name", folder.name);
            folderTags.add(f);
        }
        tag.put("Folders", folderTags);
        ListTag itemTags = new ListTag();
        for (Entry entry : entries.values()) {
            CompoundTag e = new CompoundTag();
            e.putInt("Id", entry.id);
            e.putInt("Folder", entry.folder);
            if (limits.format() == 1) e.putInt("Count", (int) entry.count);
            else e.putLong("Count", entry.count);
            e.put("Stack", entry.stack.save(new CompoundTag()));
            itemTags.add(e);
        }
        tag.put("Entries", itemTags);
        return tag;
    }

    /** Validate a temporary inventory before publishing any of its contents. */
    public void load(Tag data) {
        folders.clear();
        entries.clear();
        folders.put(0, new Folder(0, -1, ""));
        nextFolder = nextEntry = 1;
        totalCount = 0;
        protectedData = null;
        loadProblem = "";
        try {
            // A newly placed block may have no Storage tag; an unversioned existing tag is not a new block.
            if (data != null) {
                if (!(data instanceof CompoundTag tag)) throw new IllegalArgumentException("invalid_storage_type");
                StorageInventory parsed = new StorageInventory(() -> {}, limits);
                parsed.readVersioned(tag);
                folders.putAll(parsed.folders);
                entries.putAll(parsed.entries);
                nextFolder = parsed.nextFolder;
                nextEntry = parsed.nextEntry;
                totalCount = parsed.totalCount;
            }
        } catch (RuntimeException failure) {
            protectedData = data.copy();
            loadProblem = failure instanceof IllegalArgumentException && failure.getMessage() != null
                    ? failure.getMessage() : "item_decode_failed";
        }
        revision++;
    }

    private void readVersioned(CompoundTag tag) {
        requireType(tag, "Version", Tag.TAG_INT);
        if (tag.getInt("Version") != limits.format()) throw new IllegalArgumentException("unsupported_version");
        requireType(tag, "NextFolder", Tag.TAG_INT);
        requireType(tag, "NextEntry", Tag.TAG_INT);
        if (tag.getInt("NextFolder") < 1 || tag.getInt("NextEntry") < 1) throw new IllegalArgumentException("invalid_next_id");
        nextFolder = tag.getInt("NextFolder");
        nextEntry = tag.getInt("NextEntry");
        ListTag folderTags = checkedList(tag, "Folders", limits.folders() - 1);
        Map<Integer, Folder> pending = new LinkedHashMap<>();
        for (Tag value : folderTags) {
            CompoundTag f = (CompoundTag) value;
            requireType(f, "Id", Tag.TAG_INT);
            requireType(f, "Parent", Tag.TAG_INT);
            requireType(f, "Name", Tag.TAG_STRING);
            int id = f.getInt("Id");
            if (id <= 0 || id == Integer.MAX_VALUE || pending.containsKey(id)) throw new IllegalArgumentException("invalid_folder_id");
            pending.put(id, new Folder(id, f.getInt("Parent"), f.getString("Name")));
            nextFolder = Math.max(nextFolder, id + 1);
        }
        // Resolve parents independently of list order; no progress means an orphan or a cycle.
        while (!pending.isEmpty()) {
            int before = pending.size();
            var iterator = pending.values().iterator();
            while (iterator.hasNext()) {
                Folder f = iterator.next();
                if (!hasFolder(f.parent)) continue;
                int depth = 1;
                for (Folder parent = folders.get(f.parent); parent.parent != -1; parent = folders.get(parent.parent)) depth++;
                if (depth > MAX_DEPTH) throw new IllegalArgumentException("folder_limit");
                String name = checkedName(f.parent, -1, f.name);
                if (!name.equals(f.name)) throw new IllegalArgumentException("invalid_name");
                folders.put(f.id, f);
                iterator.remove();
            }
            if (pending.size() == before) throw new IllegalArgumentException("invalid_folder_tree");
        }
        long total = 0;
        for (Tag value : checkedList(tag, "Entries", limits.entries())) {
            CompoundTag e = (CompoundTag) value;
            requireType(e, "Id", Tag.TAG_INT);
            requireType(e, "Folder", Tag.TAG_INT);
            requireType(e, "Count", limits.format() == 1 ? Tag.TAG_INT : Tag.TAG_LONG);
            requireType(e, "Stack", Tag.TAG_COMPOUND);
            int id = e.getInt("Id");
            int folder = e.getInt("Folder");
            long count = e.getLong("Count");
            if (id <= 0 || id == Integer.MAX_VALUE || entries.containsKey(id)) throw new IllegalArgumentException("invalid_entry_id");
            requireFolder(folder);
            if (count <= 0 || count > capacity() - total) throw new IllegalArgumentException("invalid_count");
            CompoundTag sample = e.getCompound("Stack");
            checkItemSize(sample);
            requireType(sample, "id", Tag.TAG_STRING);
            requireType(sample, "Count", Tag.TAG_BYTE);
            if (sample.contains("tag")) requireType(sample, "tag", Tag.TAG_COMPOUND);
            if (sample.contains("ForgeCaps")) requireType(sample, "ForgeCaps", Tag.TAG_COMPOUND);
            if (sample.getByte("Count") != 1) throw new IllegalArgumentException("invalid_sample_count");
            ItemStack stack = ItemStack.of(sample);
            if (stack.isEmpty()) throw new IllegalArgumentException("unknown_item");
            if (!sample.equals(stack.save(new CompoundTag()))) throw new IllegalArgumentException("item_data_changed");
            if (stack.getItem() instanceof DiskItem || stack.getItem() instanceof BlockItem item && item.getBlock() instanceof StorageBlock) {
                throw new IllegalArgumentException("nested_device");
            }
            if (matching(folder, stack) != null) throw new IllegalArgumentException("duplicate_entry");
            entries.put(id, new Entry(id, folder, stack, count));
            nextEntry = Math.max(nextEntry, id + 1);
            total += count;
        }
        totalCount = total;
    }

    private static void requireType(CompoundTag tag, String key, int type) {
        if (!tag.contains(key, type)) throw new IllegalArgumentException("invalid_field_" + key);
    }

    private static ListTag checkedList(CompoundTag tag, String key, int limit) {
        requireType(tag, key, Tag.TAG_LIST);
        ListTag list = (ListTag) tag.get(key);
        if (list.size() > limit || (!list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND)) {
            throw new IllegalArgumentException("invalid_list_" + key);
        }
        return list;
    }

    private static void checkItemSize(CompoundTag sample) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            NbtIo.write(sample, new DataOutputStream(bytes));
            if (bytes.size() > MAX_ITEM_BYTES) throw new IllegalArgumentException("item_too_large");
        } catch (IOException e) {
            throw new IllegalArgumentException("item_too_large", e);
        }
    }

    /** Bounded view: the directory tree plus one page, never the entire inventory. */
    public CompoundTag view(int current, int requestedPage, String message) {
        return view(current, requestedPage, PAGE_SIZE, message);
    }

    /** Folders come first; folders and items share the same page budget. */
    public CompoundTag view(int current, int requestedPage, int requestedSize, String message) {
        int pageSize = Math.max(PAGE_SIZE, Math.min(MAX_PAGE_SIZE, requestedSize));
        List<Folder> children = folders.values().stream().filter(f -> f.parent == current).toList();
        List<Entry> visible = entries.values().stream().filter(e -> e.folder == current).toList();
        int totalVisible = children.size() + visible.size();
        int pages = Math.max(1, (totalVisible + pageSize - 1) / pageSize);
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        int start = page * pageSize;
        int end = Math.min(totalVisible, start + pageSize);
        CompoundTag tag = new CompoundTag();
        tag.putLong("Revision", revision);
        tag.putInt("Current", current);
        tag.putInt("Page", page);
        tag.putInt("Pages", pages);
        tag.putInt("PageSize", pageSize);
        tag.putLong("Total", total());
        tag.putLong("Capacity", capacity());
        tag.putBoolean("Locked", isLocked());
        tag.putString("Message", isLocked() ? "storage_locked" : message);
        ListTag fs = new ListTag();
        for (Folder f : folders.values()) {
            CompoundTag t = new CompoundTag();
            t.putInt("Id", f.id); t.putInt("Parent", f.parent); t.putString("Name", f.name);
            fs.add(t);
        }
        tag.put("Folders", fs);
        tag.putIntArray("PageFolders", children.subList(Math.min(start, children.size()), Math.min(end, children.size()))
                .stream().mapToInt(Folder::id).toArray());
        ListTag es = new ListTag();
        for (Entry e : visible.subList(Math.max(0, start - children.size()), Math.max(0, end - children.size()))) {
            CompoundTag t = new CompoundTag();
            t.putInt("Id", e.id); t.putLong("Count", e.count);
            t.put("Stack", e.stack.save(new CompoundTag()));
            es.add(t);
        }
        tag.put("Entries", es);
        return tag;
    }
}
