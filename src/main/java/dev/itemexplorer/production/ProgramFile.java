package dev.itemexplorer.production;

import dev.itemexplorer.storage.StorageInventory;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.UUID;

/** A directory document. It has no item count and owns no ingredient inventory. */
public record ProgramFile(int id, int folder, String name, FurnaceProgram definition,
                          BlockPos portPos, UUID portIdentity, boolean active, UUID runIdentity) {
    public ProgramFile {
        name = StorageInventory.validName(name);
        if (id <= 0 || id == Integer.MAX_VALUE || folder < 0
                || (definition == null) != (portPos == null) || (definition == null) != (portIdentity == null)
                || (active && definition == null) || active != (runIdentity != null)) throw new IllegalArgumentException("production_invalid_data");
        if (definition != null) definition = definition.withName(name).withCount(1);
        if (portPos != null) portPos = portPos.immutable();
    }

    public ProgramFile(int id, int folder, String name, FurnaceProgram definition, BlockPos portPos, UUID portIdentity, boolean active) {
        this(id, folder, name, definition, portPos, portIdentity, active, active ? UUID.randomUUID() : null);
    }

    public boolean configured() { return definition != null; }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Id", id); tag.putInt("Folder", folder); tag.putString("Name", name); tag.putBoolean("Active", active);
        if (active) tag.putUUID("RunIdentity", runIdentity);
        if (configured()) {
            tag.put("Definition", definition.save()); tag.putLong("PortPos", portPos.asLong());
            tag.putUUID("PortIdentity", portIdentity);
        }
        return tag;
    }

    public static ProgramFile load(CompoundTag tag) {
        FurnaceProgram.require(tag, "Id", Tag.TAG_INT); FurnaceProgram.require(tag, "Folder", Tag.TAG_INT);
        FurnaceProgram.require(tag, "Name", Tag.TAG_STRING); FurnaceProgram.require(tag, "Active", Tag.TAG_BYTE);
        if (tag.getByte("Active") != 0 && tag.getByte("Active") != 1) throw new IllegalArgumentException();
        String name = tag.getString("Name");
        if (!StorageInventory.validName(name).equals(name)) throw new IllegalArgumentException();
        FurnaceProgram definition = null; BlockPos pos = null; UUID identity = null;
        if (tag.contains("Definition")) {
            FurnaceProgram.require(tag, "Definition", Tag.TAG_COMPOUND); FurnaceProgram.require(tag, "PortPos", Tag.TAG_LONG);
            if (!tag.hasUUID("PortIdentity")) throw new IllegalArgumentException();
            definition = FurnaceProgram.load(tag.getCompound("Definition"));
            if (!definition.name().equals(name) || definition.count() != 1) throw new IllegalArgumentException();
            pos = BlockPos.of(tag.getLong("PortPos")); identity = tag.getUUID("PortIdentity");
        } else if (tag.contains("PortPos") || tag.contains("PortIdentity")) throw new IllegalArgumentException();
        boolean active = tag.getBoolean("Active");
        if (active != tag.hasUUID("RunIdentity") || (!active && tag.contains("RunIdentity"))) throw new IllegalArgumentException();
        return new ProgramFile(tag.getInt("Id"), tag.getInt("Folder"), name, definition, pos, identity, active,
                active ? tag.getUUID("RunIdentity") : null);
    }
}
