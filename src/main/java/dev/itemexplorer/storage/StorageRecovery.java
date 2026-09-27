package dev.itemexplorer.storage;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Independent, content-addressed archives. Never interpreted as live inventory. */
public final class StorageRecovery {
    private StorageRecovery() {}

    public static Path archive(Path directory, Tag original, String dimension, int x, int y, int z, String problem) throws IOException {
        CompoundTag recovery = new CompoundTag();
        recovery.put("Storage", original.copy());
        recovery.putString("Dimension", dimension);
        recovery.putInt("X", x); recovery.putInt("Y", y); recovery.putInt("Z", z);
        recovery.putString("Problem", problem);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        NbtIo.write(recovery, new DataOutputStream(bytes));
        String hash;
        try {
            hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        Files.createDirectories(directory);
        Path destination = directory.resolve(hash + ".nbt");
        if (Files.exists(destination)) {
            // A truncated/externally changed archive must not be mistaken for a completed backup.
            try (var input = Files.newInputStream(destination)) {
                if (recovery.equals(NbtIo.readCompressed(input))) return destination;
            }
            throw new IOException("Existing recovery archive differs: " + destination);
        }
        Path temporary = Files.createTempFile(directory, "recovery-", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) {
                NbtIo.writeCompressed(recovery, output);
            }
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, destination);
            }
            return destination;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
