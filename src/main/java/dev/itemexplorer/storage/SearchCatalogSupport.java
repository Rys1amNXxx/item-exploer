package dev.itemexplorer.storage;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Count-free catalog transport, with room left for the enclosing packet fields. */
public final class SearchCatalogSupport {
    public static final int MAX_DATA_BYTES = 47 * 1024;

    private SearchCatalogSupport() {}

    public static List<CompoundTag> batches(List<CompoundTag> entries, int[] removed) {
        List<CompoundTag> result = new ArrayList<>();
        ListTag pending = new ListTag();
        int bytes = 64;
        for (CompoundTag entry : entries) {
            int entryBytes = encodedBytes(entry);
            if (entryBytes + 64 > MAX_DATA_BYTES) throw new IllegalArgumentException("item_too_large");
            if (!pending.isEmpty() && bytes + entryBytes > MAX_DATA_BYTES) {
                result.add(data(pending, new int[0])); pending = new ListTag(); bytes = 64;
            }
            pending.add(entry.copy()); bytes += entryBytes;
        }
        // At most 4096 removals fit in one packet; keep them separate from large name batches.
        if (!pending.isEmpty()) result.add(data(pending, new int[0]));
        if (removed.length > 0) result.add(data(new ListTag(), removed));
        if (result.isEmpty()) result.add(data(new ListTag(), new int[0]));
        return result;
    }

    private static CompoundTag data(ListTag entries, int[] removed) {
        CompoundTag data = new CompoundTag();
        data.put("Entries", entries); data.putIntArray("Removed", removed);
        return data;
    }

    public static int encodedBytes(CompoundTag data) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            NbtIo.write(data, new DataOutputStream(output));
            return output.size();
        } catch (IOException failure) {
            throw new IllegalArgumentException("item_too_large", failure);
        }
    }
}
