package dev.itemexplorer.portable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** A terminal's explicit, persistent wireless grants, independent of its receiving policy. */
public final class PortableConfig {
    public static final int MAX_DEVICES = 64;
    private final Map<UUID, UUID> grants = new LinkedHashMap<>();
    private boolean enabled;
    private long revision;

    public boolean enabled() { return enabled; }
    public long revision() { return revision; }
    public int deviceCount() { return grants.size(); }
    public boolean accepts(UUID device, UUID grant) {
        return enabled && device != null && grant != null && grant.equals(grants.get(device));
    }

    public void setEnabled(boolean enabled) {
        if (this.enabled == enabled) return;
        advanceRevision();
        this.enabled = enabled;
    }

    public UUID grant(UUID device, UUID previousGrant) {
        if (!enabled) throw new IllegalArgumentException("portable_disabled");
        if (device == null) throw new IllegalArgumentException("portable_invalid_binding");
        if (accepts(device, previousGrant)) return previousGrant;
        if (!grants.containsKey(device) && grants.size() >= MAX_DEVICES)
            throw new IllegalArgumentException("portable_device_limit");
        advanceRevision();
        UUID grant = UUID.randomUUID();
        grants.put(device, grant);
        return grant;
    }

    public void revokeAll() {
        advanceRevision();
        grants.clear();
    }

    private void advanceRevision() {
        if (revision == Long.MAX_VALUE) throw new IllegalArgumentException("portable_config_changed");
        revision++;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Version", 1);
        tag.putBoolean("Enabled", enabled);
        tag.putLong("Revision", revision);
        ListTag devices = new ListTag();
        grants.forEach((device, grant) -> {
            CompoundTag value = new CompoundTag();
            value.putUUID("Device", device);
            value.putUUID("Grant", grant);
            devices.add(value);
        });
        tag.put("Devices", devices);
        return tag;
    }

    /** Invalid or missing authorization data fails closed without affecting stored items. */
    public void load(Tag value) {
        enabled = false;
        revision = 0;
        grants.clear();
        if (!(value instanceof CompoundTag tag) || !tag.contains("Version", Tag.TAG_INT)
                || tag.getInt("Version") != 1 || !tag.contains("Enabled", Tag.TAG_BYTE)
                || tag.getByte("Enabled") < 0 || tag.getByte("Enabled") > 1
                || !tag.contains("Revision", Tag.TAG_LONG) || tag.getLong("Revision") < 0
                || tag.getLong("Revision") == Long.MAX_VALUE || !tag.contains("Devices", Tag.TAG_LIST)) return;
        Tag rawDevices = tag.get("Devices");
        if (!(rawDevices instanceof ListTag devices) || devices.size() > MAX_DEVICES
                || (!devices.isEmpty() && devices.getElementType() != Tag.TAG_COMPOUND)) return;
        Map<UUID, UUID> loaded = new LinkedHashMap<>();
        for (Tag entry : devices) {
            if (!(entry instanceof CompoundTag device) || !device.hasUUID("Device") || !device.hasUUID("Grant")) return;
            if (loaded.put(device.getUUID("Device"), device.getUUID("Grant")) != null) return;
        }
        grants.putAll(loaded);
        revision = tag.getLong("Revision");
        enabled = tag.getBoolean("Enabled");
    }
}
