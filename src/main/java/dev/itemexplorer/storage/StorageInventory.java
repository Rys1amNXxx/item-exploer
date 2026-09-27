package dev.itemexplorer.storage;

import dev.itemexplorer.block.StorageBlock;
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
public final class StorageInventory {
    public static final int CAPACITY = 4096;
    public static final int MAX_ENTRIES = 128;
    public static final int MAX_FOLDERS = 64;
    public static final int MAX_NAME = 24;
    public static final int MAX_DEPTH = 8;
    public static final int MAX_ITEM_BYTES = 8192;
    public static final int PAGE_SIZE = 6;

    public record Folder(int id, int parent, String name) {}
    public record Entry(int id, int folder, ItemStack stack, int count) {}

    private final Map<Integer, Folder> folders = new LinkedHashMap<>();
    private final Map<Integer, Entry> entries = new LinkedHashMap<>();
    private final Runnable changed;
    private int nextFolder = 1;
    private int nextEntry = 1;
    private long revision;

    public StorageInventory(Runnable changed) {
        this.changed = changed;
        folders.put(0, new Folder(0, -1, ""));
    }

    public long revision() { return revision; }
    public int total() { return entries.values().stream().mapToInt(Entry::count).sum(); }
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

    private String checkedName(int parent, int except, String input) {
        String name = input.strip();
        if (name.isEmpty() || name.length() > MAX_NAME || name.equals(".") || name.equals("..")
                || name.chars().anyMatch(c -> Character.isISOControl(c) || c == '/' || c == '\\' || c == '\u00a7')) {
            throw new IllegalArgumentException("invalid_name");
        }
        if (folders.values().stream().anyMatch(f -> f.id != except && f.parent == parent && f.name.equalsIgnoreCase(name))) {
            throw new IllegalArgumentException("duplicate_name");
        }
        return name;
    }

    public int createFolder(int parent, String name) {
        requireFolder(parent);
        if (folders.size() >= MAX_FOLDERS || nextFolder == Integer.MAX_VALUE) throw new IllegalArgumentException("folder_limit");
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
        requireFolder(id);
        if (id == 0) throw new IllegalArgumentException("root_folder");
        Folder folder = folders.get(id);
        folders.put(id, new Folder(id, folder.parent, checkedName(folder.parent, id, name)));
        touch();
    }

    public int deleteFolder(int id) {
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

    public int insert(ItemStack stack, int requested, int folder) {
        requireFolder(folder);
        if (stack.isEmpty() || requested <= 0) return 0;
        if (stack.getItem() instanceof BlockItem item && item.getBlock() instanceof StorageBlock) {
            throw new IllegalArgumentException("nested_device");
        }
        int amount = Math.min(Math.min(requested, stack.getCount()), CAPACITY - total());
        if (amount <= 0) return 0;
        Entry match = matching(folder, stack);
        if (match == null) {
            if (entries.size() >= MAX_ENTRIES || nextEntry == Integer.MAX_VALUE) throw new IllegalArgumentException("entry_limit");
            ItemStack sample = stack.copyWithCount(1);
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                NbtIo.write(sample.save(new CompoundTag()), new DataOutputStream(bytes));
                if (bytes.size() > MAX_ITEM_BYTES) throw new IllegalArgumentException("item_too_large");
            } catch (IOException e) {
                throw new IllegalArgumentException("item_too_large", e);
            }
            int id = nextEntry++;
            entries.put(id, new Entry(id, folder, sample, amount));
        } else {
            entries.put(match.id, new Entry(match.id, folder, match.stack, match.count + amount));
        }
        touch();
        return amount;
    }

    /** Returns a normal, legal-sized stack; the caller must handle its destination. */
    public ItemStack take(int id, int requested) {
        Entry entry = entries.get(id);
        if (entry == null || requested <= 0) return ItemStack.EMPTY;
        int amount = Math.min(Math.min(entry.count, requested), entry.stack.getMaxStackSize());
        if (amount == entry.count) entries.remove(id);
        else entries.put(id, new Entry(id, entry.folder, entry.stack, entry.count - amount));
        touch();
        return entry.stack.copyWithCount(amount);
    }

    public int move(int id, int target, int requested) {
        requireFolder(target);
        Entry source = entries.get(id);
        if (source == null) throw new IllegalArgumentException("missing_item");
        if (source.folder == target || requested <= 0) return 0;
        int amount = Math.min(source.count, requested);
        Entry match = matching(target, source.stack);
        if (match == null && amount == source.count) {
            entries.put(id, new Entry(id, target, source.stack, amount));
        } else {
            if (match == null && (entries.size() >= MAX_ENTRIES || nextEntry == Integer.MAX_VALUE)) {
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

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Version", 1);
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
            e.putInt("Count", entry.count);
            e.put("Stack", entry.stack.save(new CompoundTag()));
            itemTags.add(e);
        }
        tag.put("Entries", itemTags);
        return tag;
    }

    public void load(CompoundTag tag) {
        folders.clear();
        entries.clear();
        folders.put(0, new Folder(0, -1, ""));
        nextFolder = Math.max(1, tag.getInt("NextFolder"));
        nextEntry = Math.max(1, tag.getInt("NextEntry"));
        for (Tag value : tag.getList("Folders", Tag.TAG_COMPOUND)) {
            CompoundTag f = (CompoundTag) value;
            int id = f.getInt("Id");
            // Insertion order is parent-before-child, since folders are never reparented.
            if (id > 0 && id < Integer.MAX_VALUE && hasFolder(f.getInt("Parent"))) {
                folders.put(id, new Folder(id, f.getInt("Parent"), f.getString("Name")));
                nextFolder = Math.max(nextFolder, id + 1);
            }
        }
        for (Tag value : tag.getList("Entries", Tag.TAG_COMPOUND)) {
            CompoundTag e = (CompoundTag) value;
            ItemStack stack = ItemStack.of(e.getCompound("Stack"));
            int id = e.getInt("Id");
            int folder = hasFolder(e.getInt("Folder")) ? e.getInt("Folder") : 0;
            int count = e.getInt("Count");
            if (!stack.isEmpty() && id > 0 && id < Integer.MAX_VALUE && count > 0) {
                entries.put(id, new Entry(id, folder, stack.copyWithCount(1), count));
                nextEntry = Math.max(nextEntry, id + 1);
            }
        }
        revision++;
    }

    /** Bounded view: the directory tree plus one page, never the entire inventory. */
    public CompoundTag view(int current, int requestedPage, String message) {
        List<Entry> visible = entries.values().stream().filter(e -> e.folder == current).toList();
        int pages = Math.max(1, (visible.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        CompoundTag tag = new CompoundTag();
        tag.putLong("Revision", revision);
        tag.putInt("Current", current);
        tag.putInt("Page", page);
        tag.putInt("Pages", pages);
        tag.putInt("Total", total());
        tag.putString("Message", message);
        ListTag fs = new ListTag();
        for (Folder f : folders.values()) {
            CompoundTag t = new CompoundTag();
            t.putInt("Id", f.id); t.putInt("Parent", f.parent); t.putString("Name", f.name);
            fs.add(t);
        }
        tag.put("Folders", fs);
        ListTag es = new ListTag();
        for (Entry e : visible.subList(page * PAGE_SIZE, Math.min(visible.size(), (page + 1) * PAGE_SIZE))) {
            CompoundTag t = new CompoundTag();
            t.putInt("Id", e.id); t.putInt("Count", e.count);
            t.put("Stack", e.stack.save(new CompoundTag()));
            es.add(t);
        }
        tag.put("Entries", es);
        return tag;
    }
}
