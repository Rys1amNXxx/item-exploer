package dev.itemexplorer.production;

import dev.itemexplorer.storage.StorageInventory;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.ItemHandlerHelper;

import java.util.ArrayList;
import java.util.List;

/** A player-authored batch. Faces belong to the machine, not to the interface. */
public record ProcessingPattern(Direction inputFace, Direction outputFace,
                                List<Material> inputs, List<Material> outputs) {
    public static final int MAX_MATERIALS = 9;

    public record Material(int folder, ItemStack stack, Direction face) {
        public Material {
            if (folder < 0 || stack == null || stack.isEmpty() || stack.getCount() < 1
                    || stack.getCount() > stack.getMaxStackSize()
                    || stack.save(new CompoundTag()).sizeInBytes() > StorageInventory.MAX_ITEM_BYTES
                    || !StorageInventory.acceptsItem(stack)) throw new IllegalArgumentException("production_invalid_config");
            stack = stack.copy();
        }
        public Material(int folder, ItemStack stack) { this(folder, stack, null); }
        @Override public ItemStack stack() { return stack.copy(); }
    }

    public ProcessingPattern {
        if (inputFace == null || outputFace == null || inputs == null || outputs == null
                || inputs.isEmpty() || inputs.size() > MAX_MATERIALS || outputs.isEmpty() || outputs.size() > MAX_MATERIALS)
            throw new IllegalArgumentException("production_invalid_config");
        inputs = List.copyOf(inputs); outputs = List.copyOf(outputs);
        // The generic interface cannot distinguish a returned catalyst from an unprocessed input.
        for (Material input : inputs) for (Material output : outputs)
            if (matches(input.stack(), output.stack())) throw new IllegalArgumentException("production_invalid_config");
    }

    public static boolean matches(ItemStack first, ItemStack second) {
        return !first.isEmpty() && !second.isEmpty() && ItemHandlerHelper.canItemStacksStack(first, second);
    }

    public void validateFolders(StorageInventory inventory) {
        for (Material material : inputs) if (!inventory.hasFolder(material.folder())) throw new IllegalArgumentException("production_invalid_folder");
        for (Material material : outputs) if (!inventory.hasFolder(material.folder())) throw new IllegalArgumentException("production_invalid_folder");
    }

    public Direction inputFace(Material material) { return material.face() == null ? inputFace : material.face(); }
    public Direction outputFace(Material material) { return material.face() == null ? outputFace : material.face(); }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("InputFace", inputFace.get3DDataValue()); tag.putInt("OutputFace", outputFace.get3DDataValue());
        tag.put("Inputs", saveMaterials(inputs)); tag.put("Outputs", saveMaterials(outputs));
        return tag;
    }

    private static ListTag saveMaterials(List<Material> materials) {
        ListTag list = new ListTag();
        for (Material material : materials) {
            CompoundTag row = new CompoundTag(); row.putInt("Folder", material.folder());
            row.put("Stack", material.stack().save(new CompoundTag())); list.add(row);
            if (material.face() != null) row.putInt("Face", material.face().get3DDataValue());
        }
        return list;
    }

    public static ProcessingPattern load(CompoundTag tag) {
        FurnaceProgram.require(tag, "InputFace", Tag.TAG_INT); FurnaceProgram.require(tag, "OutputFace", Tag.TAG_INT);
        int input = tag.getInt("InputFace"), output = tag.getInt("OutputFace");
        if (input < 0 || input >= 6 || output < 0 || output >= 6) throw new IllegalArgumentException("production_invalid_data");
        ProcessingPattern result = new ProcessingPattern(Direction.from3DDataValue(input), Direction.from3DDataValue(output),
                loadMaterials(tag, "Inputs"), loadMaterials(tag, "Outputs"));
        if (!tag.equals(result.save())) throw new IllegalArgumentException("production_invalid_data");
        return result;
    }

    private static List<Material> loadMaterials(CompoundTag tag, String key) {
        FurnaceProgram.require(tag, key, Tag.TAG_LIST);
        ListTag list = (ListTag) tag.get(key);
        if (list.isEmpty() || list.size() > MAX_MATERIALS || list.getElementType() != Tag.TAG_COMPOUND)
            throw new IllegalArgumentException("production_invalid_data");
        List<Material> result = new ArrayList<>();
        for (Tag value : list) {
            CompoundTag row = (CompoundTag) value;
            FurnaceProgram.require(row, "Folder", Tag.TAG_INT);
            Direction face = null;
            if (row.contains("Face")) {
                FurnaceProgram.require(row, "Face", Tag.TAG_INT); int index = row.getInt("Face");
                if (index < 0 || index >= 6) throw new IllegalArgumentException("production_invalid_data");
                face = Direction.from3DDataValue(index);
            }
            result.add(new Material(row.getInt("Folder"), FurnaceProgram.stack(row, "Stack", false), face));
        }
        return result;
    }
}
