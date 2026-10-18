package dev.itemexplorer.portable;

import dev.itemexplorer.block.StorageBlockEntity;
import dev.itemexplorer.block.BaseStationBlockEntity;
import dev.itemexplorer.station.StationConnection;
import dev.itemexplorer.storage.StorageInventory;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;
import java.util.UUID;

/** Resolves a handheld's grant against loaded server state without requesting any chunk loads. */
public final class PortableAccess {
    private static final String TAG = "PortableTerminal";
    public static final int OFFHAND_SLOT = 40;

    private PortableAccess() {}

    public record Binding(String dimension, BlockPos pos, UUID terminalId, UUID device, UUID grant) {}
    public record Location(String volume, int folder, int page, int pageSize) {}
    public record OpenResult(String status, StorageBlockEntity terminal, Session session) {}

    public static Binding binding(ItemStack stack) {
        if (!(stack.getItem() instanceof PortableTerminalItem) || !stack.hasTag()
                || !stack.getTag().contains(TAG, Tag.TAG_COMPOUND)) return null;
        CompoundTag tag = stack.getTag().getCompound(TAG);
        if (!tag.contains("Version", Tag.TAG_INT) || tag.getInt("Version") != 1
                || !tag.contains("Dimension", Tag.TAG_STRING) || !tag.contains("Position", Tag.TAG_LONG)
                || !tag.hasUUID("Terminal") || !tag.hasUUID("Device") || !tag.hasUUID("Grant")) return null;
        String dimension = tag.getString("Dimension");
        ResourceLocation key = ResourceLocation.tryParse(dimension);
        if (key == null || !key.toString().equals(dimension)) return null;
        return new Binding(dimension, BlockPos.of(tag.getLong("Position")), tag.getUUID("Terminal"),
                tag.getUUID("Device"), tag.getUUID("Grant"));
    }

    public static String boundName(ItemStack stack) {
        if (!stack.hasTag()) return "";
        String name = stack.getTag().getCompound(TAG).getString("TargetName");
        return name.length() <= 128 ? name : name.substring(0, 128);
    }

    /** Physical pairing grants access even if the base station has not been built yet. */
    public static String pair(ServerPlayer player, InteractionHand hand, StorageBlockEntity terminal) {
        if (player.isSpectator()) return "portable_spectator";
        ItemStack stack = player.getItemInHand(hand);
        if (!(stack.getItem() instanceof PortableTerminalItem) || stack.getCount() != 1) return "portable_device_missing";
        if (terminal == null || terminal.isRemoved() || terminal.getLevel() != player.level()
                || !player.level().hasChunkAt(terminal.getBlockPos())
                || player.level().getBlockEntity(terminal.getBlockPos()) != terminal
                || player.distanceToSqr(terminal.getBlockPos().getX() + .5, terminal.getBlockPos().getY() + .5,
                        terminal.getBlockPos().getZ() + .5) > 64) return "portable_missing_terminal";
        if (!terminal.portableEnabled()) return "portable_disabled";
        Binding previous = binding(stack);
        UUID device = previous == null ? UUID.randomUUID() : previous.device();
        UUID grant;
        try {
            grant = terminal.grantPortableDevice(device, previous == null ? null : previous.grant());
        } catch (IllegalArgumentException failure) {
            return failure.getMessage();
        }
        Binding paired = new Binding(player.level().dimension().location().toString(), terminal.getBlockPos(),
                terminal.productionIdentity(), device, grant);
        // Re-pairing to the same grant preserves this device's navigation preferences.
        CompoundTag tag = paired.equals(previous) ? stack.getTag().getCompound(TAG) : new CompoundTag();
        tag.putInt("Version", 1);
        tag.putString("Dimension", paired.dimension());
        tag.putLong("Position", paired.pos().asLong());
        tag.putUUID("Terminal", paired.terminalId());
        tag.putUUID("Device", paired.device());
        tag.putUUID("Grant", paired.grant());
        String name = terminal.transferConfig().name();
        tag.putString("TargetName", name.isEmpty() ? terminal.getDisplayName().getString() : name);
        stack.getOrCreateTag().put(TAG, tag);
        player.getInventory().setChanged();
        return "";
    }

    public static OpenResult openSession(ServerPlayer player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!(stack.getItem() instanceof PortableTerminalItem) || stack.getCount() != 1)
            return new OpenResult("portable_device_missing", null, null);
        Binding binding = binding(stack);
        if (binding == null) return new OpenResult(stack.hasTag() && stack.getTag().contains(TAG)
                ? "portable_invalid_binding" : "portable_unbound", null, null);
        int slot = hand == InteractionHand.OFF_HAND ? OFFHAND_SLOT : player.getInventory().selected;
        Session session = new Session(player, stack, slot, binding);
        String status = session.status(player);
        return new OpenResult(status, status.isEmpty() ? session.terminal() : null, session);
    }

    public static final class Session {
        private final ServerPlayer owner;
        private final ItemStack stack;
        private final int slot;
        private final Binding binding;

        private Session(ServerPlayer owner, ItemStack stack, int slot, Binding binding) {
            this.owner = owner;
            this.stack = stack;
            this.slot = slot;
            this.binding = binding;
        }

        public int slot() { return slot; }
        public ItemStack stack() { return stack; }
        public BlockPos pos() { return binding.pos(); }

        /** Stack identity prevents replacing the active handheld with a copied or newly moved item. */
        public boolean heldBy(Player player) {
            return player == owner && !stack.isEmpty() && stack.getCount() == 1
                    && player.getInventory().getItem(slot) == stack
                    && Objects.equals(binding, PortableAccess.binding(stack))
                    && player.level().dimension().location().toString().equals(binding.dimension());
        }

        /** Fresh resolution is intentional: a reloaded terminal may be a new block entity instance. */
        public StorageBlockEntity terminal() {
            if (!heldBy(owner) || !owner.level().hasChunkAt(binding.pos())) return null;
            return owner.level().getBlockEntity(binding.pos()) instanceof StorageBlockEntity terminal
                    && !terminal.isRemoved() && terminal.productionIdentity().equals(binding.terminalId()) ? terminal : null;
        }

        /** Empty means connected; all errors are suffixes of gui.itemexplorer.* translation keys. */
        public String status(Player player) {
            if (player != owner) return "portable_device_missing";
            if (player.isSpectator()) return "portable_spectator";
            if (!player.level().dimension().location().toString().equals(binding.dimension())) return "portable_wrong_dimension";
            if (!heldBy(player)) return "portable_device_missing";
            if (!player.level().hasChunkAt(binding.pos())) return "portable_unloaded";
            if (!(player.level().getBlockEntity(binding.pos()) instanceof StorageBlockEntity terminal) || terminal.isRemoved())
                return "portable_missing_terminal";
            if (!terminal.productionIdentity().equals(binding.terminalId())) return "portable_invalid_binding";
            if (!terminal.portableEnabled()) return "portable_disabled";
            if (!terminal.acceptsPortableDevice(binding.device(), binding.grant())) return "portable_revoked";
            StationConnection.Result connection = StationConnection.inspect(player.level(), binding.pos(), false);
            if (connection.status() == StationConnection.Status.UNLOADED) return "portable_station_unloaded";
            if (connection.status() != StationConnection.Status.CONNECTED || connection.controller() == null)
                return "portable_station_unavailable";
            if (!player.level().hasChunkAt(connection.controller())) return "portable_station_unloaded";
            if (!(player.level().getBlockEntity(connection.controller()) instanceof BaseStationBlockEntity station)
                    || station.isRemoved()) return "portable_station_unavailable";
            return station.networkOnline() ? "" : "portable_station_offline";
        }

        public String defaultVolume() {
            return checkedVolume(preferences().getString("DefaultVolume"));
        }

        public void setDefaultVolume(String volume) {
            if (!heldBy(owner)) throw new IllegalArgumentException("portable_device_missing");
            String checked = checkedVolume(volume);
            if (!checked.equals(volume)) throw new IllegalArgumentException("disk_offline");
            preferences().putString("DefaultVolume", checked);
            owner.getInventory().setChanged();
        }

        public Location location() {
            CompoundTag tag = preferences();
            return new Location(checkedVolume(tag.getString("Volume")), Math.max(0, tag.getInt("Folder")),
                    Math.max(0, tag.getInt("Page")), Math.max(StorageInventory.PAGE_SIZE,
                    Math.min(StorageInventory.MAX_PAGE_SIZE, tag.getInt("PageSize"))));
        }

        public void saveLocation(String volume, int folder, int page, int pageSize) {
            if (!heldBy(owner)) return;
            CompoundTag tag = preferences();
            tag.putString("Volume", checkedVolume(volume));
            tag.putInt("Folder", Math.max(0, folder));
            tag.putInt("Page", Math.max(0, page));
            tag.putInt("PageSize", Math.max(StorageInventory.PAGE_SIZE, Math.min(StorageInventory.MAX_PAGE_SIZE, pageSize)));
            owner.getInventory().setChanged();
        }

        private CompoundTag preferences() { return stack.getOrCreateTag().getCompound(TAG); }
    }

    private static String checkedVolume(String volume) {
        if (volume.isEmpty()) return "";
        try {
            String canonical = UUID.fromString(volume).toString();
            return canonical.equals(volume) ? canonical : "";
        } catch (IllegalArgumentException ignored) { return ""; }
    }
}
