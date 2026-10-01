package dev.itemexplorer.production;

import dev.itemexplorer.storage.StorageInventory;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Separate terminal metadata, sharing stable folder IDs but never the material inventory. */
public final class ProgramLibrary {
    public static final int MAX_FILES = 32;
    private final StorageInventory inventory;
    private final Runnable changed;
    private final Map<Integer, ProgramFile> files = new LinkedHashMap<>();
    private int nextId = 1;
    private long revision;
    private Tag protectedData;

    public ProgramLibrary(StorageInventory inventory, Runnable changed) { this.inventory = inventory; this.changed = changed; }
    public long revision() { return revision; }
    public boolean isLocked() { return protectedData != null; }
    public List<ProgramFile> files() { return List.copyOf(files.values()); }
    public ProgramFile file(int id) { return files.get(id); }
    public boolean isActive(int id) { ProgramFile file = files.get(id); return file != null && file.active(); }
    public boolean hasFilesIn(int folder) { return files.values().stream().anyMatch(file -> file.folder() == folder); }

    private void touch() { revision++; changed.run(); }
    private void writable() {
        if (isLocked() || inventory.isLocked()) throw new IllegalArgumentException("production_storage_locked");
    }
    private void folder(int id) {
        if (!inventory.hasFolder(id)) throw new IllegalArgumentException("production_invalid_folder");
    }
    private ProgramFile require(int id) {
        ProgramFile file = files.get(id);
        if (file == null) throw new IllegalArgumentException("production_missing_program");
        return file;
    }
    private ProgramFile editable(int id) {
        writable(); ProgramFile file = require(id);
        if (file.active()) throw new IllegalArgumentException("production_running");
        return file;
    }
    private String name(int folder, int except, String requested) {
        String name = StorageInventory.validName(requested);
        if (files.values().stream().anyMatch(file -> file.id() != except && file.folder() == folder && file.name().equalsIgnoreCase(name)))
            throw new IllegalArgumentException("duplicate_name");
        return name;
    }
    private void room() {
        if (files.size() >= MAX_FILES || nextId == Integer.MAX_VALUE) throw new IllegalArgumentException("production_program_limit");
    }
    public int create(int folder, String requestedName) {
        writable(); folder(folder); room(); String name = name(folder, -1, requestedName); int id = nextId++;
        files.put(id, new ProgramFile(id, folder, name, null, null, null, false)); touch(); return id;
    }
    public void rename(int id, String requestedName) {
        ProgramFile file = editable(id); String name = name(file.folder(), id, requestedName);
        files.put(id, new ProgramFile(id, file.folder(), name, file.definition(), file.portPos(), file.portIdentity(), false)); touch();
    }
    public void move(int id, int targetFolder) {
        ProgramFile file = editable(id); folder(targetFolder); name(targetFolder, id, file.name());
        files.put(id, new ProgramFile(id, targetFolder, file.name(), file.definition(), file.portPos(), file.portIdentity(), false)); touch();
    }
    public int copy(int id, int targetFolder, String requestedName) {
        writable(); ProgramFile file = require(id); folder(targetFolder); room();
        String name = name(targetFolder, -1, requestedName); int copyId = nextId++;
        files.put(copyId, new ProgramFile(copyId, targetFolder, name, file.definition(), file.portPos(), file.portIdentity(), false)); touch(); return copyId;
    }
    public void delete(int id) { editable(id); files.remove(id); touch(); }
    public void configure(int id, FurnaceProgram definition, BlockPos portPos, UUID portIdentity) {
        ProgramFile file = editable(id);
        if (definition == null || portPos == null || portIdentity == null) throw new IllegalArgumentException("production_invalid_config");
        folder(definition.inputFolder()); folder(definition.fuelFolder()); folder(definition.outputFolder());
        files.put(id, new ProgramFile(id, file.folder(), file.name(), definition, portPos, portIdentity, false)); touch();
    }
    /** Server runtime owns this flag; saved independently so disconnecting a port cannot enable a second launch. */
    public void setActive(int id, boolean active) {
        writable(); ProgramFile file = require(id);
        if (file.active() == active) return;
        files.put(id, new ProgramFile(id, file.folder(), file.name(), file.definition(), file.portPos(), file.portIdentity(), active)); touch();
    }
    public Tag save() {
        if (isLocked()) return protectedData.copy();
        CompoundTag tag = new CompoundTag(); tag.putInt("Version", 1); tag.putInt("NextId", nextId);
        ListTag list = new ListTag(); for (ProgramFile file : files.values()) list.add(file.save()); tag.put("Files", list); return tag;
    }
    public void load(Tag raw) {
        files.clear(); nextId = 1; protectedData = null; revision++;
        if (raw == null) return;
        try {
            if (!(raw instanceof CompoundTag tag) || inventory.isLocked()) throw new IllegalArgumentException();
            FurnaceProgram.require(tag, "Version", Tag.TAG_INT); FurnaceProgram.require(tag, "NextId", Tag.TAG_INT);
            FurnaceProgram.require(tag, "Files", Tag.TAG_LIST);
            if (tag.getInt("Version") != 1 || tag.getInt("NextId") < 1) throw new IllegalArgumentException();
            ListTag list = (ListTag) tag.get("Files");
            if (list.size() > MAX_FILES || (!list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND)) throw new IllegalArgumentException();
            Map<Integer, ProgramFile> parsed = new LinkedHashMap<>(); int next = tag.getInt("NextId");
            for (Tag value : list) {
                ProgramFile file = ProgramFile.load((CompoundTag) value); folder(file.folder());
                if (parsed.containsKey(file.id()) || parsed.values().stream().anyMatch(other -> other.folder() == file.folder()
                        && other.name().equalsIgnoreCase(file.name()))) throw new IllegalArgumentException();
                // Source/output folders can subsequently be removed; that is a repairable broken reference,
                // not corrupt data. The file's own containing folder must always exist.
                parsed.put(file.id(), file); next = Math.max(next, file.id() + 1);
            }
            files.putAll(parsed); nextId = next;
        } catch (RuntimeException invalid) { files.clear(); protectedData = raw.copy(); }
    }
}
