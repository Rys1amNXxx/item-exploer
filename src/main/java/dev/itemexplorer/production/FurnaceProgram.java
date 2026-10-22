package dev.itemexplorer.production;

import dev.itemexplorer.storage.StorageInventory;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/** A saved recipe, never an inventory. Runtime counters live separately on the interface. */
public record FurnaceProgram(String name, int inputFolder, int fuelFolder, int outputFolder,
                             int inputEntry, int fuelEntry, ItemStack input, ItemStack fuel,
                             ItemStack result, ResourceLocation recipe, int count,
                             UUID terminal, UUID furnace, String kind, ProcessingPattern pattern) {
    public static final int MAX_COUNT = 4096;

    public FurnaceProgram {
        name = StorageInventory.validName(name);
        if (count < 1 || count > MAX_COUNT || terminal == null || furnace == null || !MachineAdapters.validKind(kind)
                || ("item_handler".equals(kind) != (pattern != null))) throw new IllegalArgumentException("production_invalid_config");
        if (pattern == null && (inputFolder < 0 || fuelFolder < 0 || outputFolder < 0 || inputEntry <= 0 || fuelEntry <= 0
                || count < 1 || count > MAX_COUNT || input.isEmpty() || input.getCount() != 1
                || fuel.isEmpty() || fuel.getCount() != 1 || result.isEmpty()
                || result.getCount() > result.getMaxStackSize() || recipe == null || terminal == null || furnace == null
                || !MachineAdapters.validKind(kind)))
            throw new IllegalArgumentException("production_invalid_config");
        if (pattern != null) {
            inputFolder = pattern.inputs().get(0).folder(); fuelFolder = inputFolder;
            outputFolder = pattern.outputs().get(0).folder(); inputEntry = fuelEntry = 0;
            input = pattern.inputs().get(0).stack(); fuel = ItemStack.EMPTY; result = pattern.outputs().get(0).stack(); recipe = null;
        }
        input = input.copy(); fuel = fuel.copy(); result = result.copy();
    }
    @Override public ItemStack input() { return input.copy(); }
    @Override public ItemStack fuel() { return fuel.copy(); }
    @Override public ItemStack result() { return result.copy(); }

    public boolean generic() { return pattern != null; }

    public FurnaceProgram(String name, ProcessingPattern pattern, int count, UUID terminal, UUID machine) {
        this(name, 0, 0, 0, 0, 0, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, null,
                count, terminal, machine, "item_handler", pattern);
    }

    public FurnaceProgram(String name, int inputFolder, int fuelFolder, int outputFolder,
                          int inputEntry, int fuelEntry, ItemStack input, ItemStack fuel,
                          ItemStack result, ResourceLocation recipe, int count, UUID terminal, UUID furnace, String kind) {
        this(name, inputFolder, fuelFolder, outputFolder, inputEntry, fuelEntry, input, fuel, result, recipe, count, terminal, furnace, kind, null);
    }

    public FurnaceProgram(String name, int inputFolder, int fuelFolder, int outputFolder,
                          int inputEntry, int fuelEntry, ItemStack input, ItemStack fuel,
                          ItemStack result, ResourceLocation recipe, int count, UUID terminal, UUID furnace) {
        this(name, inputFolder, fuelFolder, outputFolder, inputEntry, fuelEntry, input, fuel, result, recipe, count, terminal, furnace, "furnace");
    }

    public FurnaceProgram withName(String nextName) {
        return new FurnaceProgram(nextName, inputFolder, fuelFolder, outputFolder, inputEntry, fuelEntry,
                input, fuel, result, recipe, count, terminal, furnace, kind, pattern);
    }

    /** Each launch gets its own quantity; editing a file never changes an in-flight job. */
    public FurnaceProgram withCount(int nextCount) {
        return new FurnaceProgram(name, inputFolder, fuelFolder, outputFolder, inputEntry, fuelEntry,
                input, fuel, result, recipe, nextCount, terminal, furnace, kind, pattern);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        if (generic()) {
            tag.putString("Name", name); tag.putString("Kind", kind); tag.putInt("Count", count);
            tag.putUUID("Terminal", terminal); tag.putUUID("Furnace", furnace); tag.put("Pattern", pattern.save());
            return tag;
        }
        tag.putString("Name", name); tag.putInt("InputFolder", inputFolder); tag.putInt("FuelFolder", fuelFolder);
        tag.putInt("OutputFolder", outputFolder); tag.putInt("InputEntry", inputEntry); tag.putInt("FuelEntry", fuelEntry);
        tag.put("Input", input.save(new CompoundTag())); tag.put("Fuel", fuel.save(new CompoundTag()));
        tag.put("Result", result.save(new CompoundTag())); tag.putString("Recipe", recipe.toString());
        tag.putInt("Count", count); tag.putUUID("Terminal", terminal); tag.putUUID("Furnace", furnace);
        tag.putString("Kind", kind);
        return tag;
    }

    public static FurnaceProgram load(CompoundTag tag) {
        if (tag.contains("Kind")) require(tag, "Kind", Tag.TAG_STRING);
        if ("item_handler".equals(tag.getString("Kind"))) {
            require(tag, "Name", Tag.TAG_STRING); require(tag, "Count", Tag.TAG_INT); require(tag, "Pattern", Tag.TAG_COMPOUND);
            if (!tag.hasUUID("Terminal") || !tag.hasUUID("Furnace")) throw new IllegalArgumentException();
            FurnaceProgram result = new FurnaceProgram(tag.getString("Name"), ProcessingPattern.load(tag.getCompound("Pattern")),
                    tag.getInt("Count"), tag.getUUID("Terminal"), tag.getUUID("Furnace"));
            if (!result.save().equals(tag)) throw new IllegalArgumentException("production_invalid_data");
            return result;
        }
        if (tag.contains("Pattern")) throw new IllegalArgumentException("production_invalid_data");
        for (String field : new String[]{"InputFolder", "FuelFolder", "OutputFolder", "InputEntry", "FuelEntry", "Count"})
            require(tag, field, Tag.TAG_INT);
        require(tag, "Name", Tag.TAG_STRING); require(tag, "Recipe", Tag.TAG_STRING);
        if (!tag.hasUUID("Terminal") || !tag.hasUUID("Furnace")) throw new IllegalArgumentException();
        return new FurnaceProgram(tag.getString("Name"), tag.getInt("InputFolder"), tag.getInt("FuelFolder"),
                tag.getInt("OutputFolder"), tag.getInt("InputEntry"), tag.getInt("FuelEntry"),
                stack(tag, "Input", false), stack(tag, "Fuel", false), stack(tag, "Result", false),
                ResourceLocation.tryParse(tag.getString("Recipe")), tag.getInt("Count"), tag.getUUID("Terminal"), tag.getUUID("Furnace"),
                tag.contains("Kind") ? tag.getString("Kind") : "furnace");
    }

    public static void require(CompoundTag tag, String key, int type) {
        if (!tag.contains(key, type)) throw new IllegalArgumentException("production_invalid_data");
    }
    public static ItemStack stack(CompoundTag tag, String key, boolean allowEmpty) {
        require(tag, key, Tag.TAG_COMPOUND);
        CompoundTag raw = tag.getCompound(key);
        if (raw.sizeInBytes() > StorageInventory.MAX_ITEM_BYTES) throw new IllegalArgumentException();
        ItemStack stack = ItemStack.of(raw);
        if ((!allowEmpty && stack.isEmpty()) || stack.getCount() > stack.getMaxStackSize()
                || !raw.equals(stack.save(new CompoundTag()))) throw new IllegalArgumentException();
        return stack;
    }
}
