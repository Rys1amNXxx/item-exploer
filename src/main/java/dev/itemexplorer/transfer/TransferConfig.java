package dev.itemexplorer.transfer;

import dev.itemexplorer.storage.StorageInventory;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.UUID;

/** Receiver policy belongs to a terminal, never to whichever folder a viewer opens. */
public final class TransferConfig {
    private String name = "", volume = "";
    private int folder;
    private boolean enabled;
    private long revision;

    public String name() { return name; }
    public String volume() { return volume; }
    public int folder() { return folder; }
    public boolean enabled() { return enabled; }
    public long revision() { return revision; }

    public static String checkedVolume(String value) {
        if (value.isEmpty()) return "";
        try {
            String canonical = UUID.fromString(value).toString();
            if (!canonical.equals(value)) throw new IllegalArgumentException("disk_offline");
            return canonical;
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("disk_offline");
        }
    }

    public void configure(String name, String volume, int folder, boolean enabled, long expectedRevision) {
        if (revision != expectedRevision || revision == Long.MAX_VALUE) throw new IllegalArgumentException("remote_target_changed");
        String checkedName = name.isBlank() ? "" : StorageInventory.validName(name);
        String checkedVolume = checkedVolume(volume);
        if (folder < 0) throw new IllegalArgumentException("invalid_folder");
        this.name = checkedName;
        this.volume = checkedVolume;
        this.folder = folder;
        this.enabled = enabled;
        revision++;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Version", 1);
        tag.putString("Name", name);
        tag.putString("Volume", volume);
        tag.putInt("Folder", folder);
        tag.putBoolean("Enabled", enabled);
        tag.putLong("ConfigRevision", revision);
        return tag;
    }

    /** Invalid/missing policy fails closed; inventory data is managed separately. */
    public void load(Tag value) {
        name = volume = ""; folder = 0; enabled = false; revision = 0;
        if (value == null) return;
        try {
            if (!(value instanceof CompoundTag tag) || !tag.contains("Version", Tag.TAG_INT) || tag.getInt("Version") != 1
                    || !tag.contains("Name", Tag.TAG_STRING) || !tag.contains("Volume", Tag.TAG_STRING)
                    || !tag.contains("Folder", Tag.TAG_INT) || !tag.contains("Enabled", Tag.TAG_BYTE)
                    || !tag.contains("ConfigRevision", Tag.TAG_LONG)) return;
            String loadedName = tag.getString("Name");
            if (!loadedName.isEmpty()) loadedName = StorageInventory.validName(loadedName);
            String loadedVolume = checkedVolume(tag.getString("Volume"));
            int loadedFolder = tag.getInt("Folder");
            long loadedRevision = tag.getLong("ConfigRevision");
            if (loadedFolder < 0 || loadedRevision < 0 || loadedRevision == Long.MAX_VALUE
                    || tag.getByte("Enabled") < 0 || tag.getByte("Enabled") > 1) return;
            name = loadedName; volume = loadedVolume; folder = loadedFolder;
            revision = loadedRevision; enabled = tag.getBoolean("Enabled");
        } catch (IllegalArgumentException ignored) { /* Never enable an invalid receiver. */ }
    }
}
