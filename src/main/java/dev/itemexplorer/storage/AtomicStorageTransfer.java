package dev.itemexplorer.storage;

import java.util.Objects;

/**
 * Exact-quantity transfers between authoritative inventories on the server thread.
 * All validation and item copying completes before either inventory is changed.
 * Owner callbacks must be non-throwing dirty markers and run only after both states commit.
 *
 * <p>This guarantees in-memory conservation; ordinary owner saves persist both sides.
 * It does not make separate chunk and disk files crash-atomic. There is deliberately
 * no snapshot replay journal that could overwrite later inventory changes.</p>
 */
public final class AtomicStorageTransfer {
    private AtomicStorageTransfer() {}

    /**
     * Moves exactly {@code requested} items, or throws without changing either inventory.
     * Same-inventory transfers move directory membership; the same directory returns zero.
     * Counts are independent of the item's vanilla stack limit.
     */
    public static long move(StorageInventory source, int entry, StorageInventory target, int folder, long requested) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        return source.transferTo(entry, target, folder, requested);
    }
}
