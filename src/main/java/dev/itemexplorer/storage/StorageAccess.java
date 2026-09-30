package dev.itemexplorer.storage;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Set;

/** Server-side storage operations shared by menus and future automation adapters.
 * Callers must resolve an online, authorized mount before each operation.
 * Simulation never mutates stacks, inventory revisions, or persistence state.
 */
public interface StorageAccess {
    long capacity();
    long total();
    long revision();
    long searchRevision();
    boolean isLocked();
    boolean hasFolder(int id);
    StorageInventory.Entry entry(int id);
    int createFolder(int parent, String name);
    void renameFolder(int id, String name);
    int deleteFolder(int id);
    long move(int entry, int target, long requested);
    int insert(ItemStack stack, int requested, int folder, boolean simulate);
    ItemStack take(int entry, long requested, boolean simulate);
    CompoundTag view(int folder, int page, int pageSize, String message);
    List<CompoundTag> searchCatalog();
    boolean inSearchScope(int entry, int root, boolean recursive);
    CompoundTag searchView(int root, boolean recursive, Set<Integer> matches, int page, int pageSize, String message);
    int locatePage(int entry, int pageSize);

    default int insert(ItemStack stack, int requested, int folder) { return insert(stack, requested, folder, false); }
    default ItemStack take(int entry, long requested) { return take(entry, requested, false); }
}
