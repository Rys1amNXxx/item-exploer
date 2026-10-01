package dev.itemexplorer.network;

import dev.itemexplorer.ItemExplorer;
import dev.itemexplorer.client.ClientEvents;
import dev.itemexplorer.menu.StorageMenu;
import dev.itemexplorer.menu.NasMenu;
import dev.itemexplorer.menu.LogisticsPortMenu;
import dev.itemexplorer.menu.ProductionPortMenu;
import dev.itemexplorer.storage.StorageSearch;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Supplier;

public final class StorageNetwork {
    private static final String VERSION = "10";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(ItemExplorer.MOD_ID, "storage"), () -> VERSION, VERSION::equals, VERSION::equals);

    public enum Action { OPEN, PAGE, CREATE, RENAME, DELETE, MOVE, WITHDRAW, DEPOSIT_CURSOR, RESIZE, SELECT_VOLUME, RENAME_DISK, DEPOSIT_SLOT }
    public enum SearchAction { START, APPLY, PAGE, RESIZE, EXIT, LOCATE, TAKE }

    /** Search never carries inventory samples from the client, only bounded entry identities. */
    public record SearchRequest(int menuId, long session, long querySeq, long catalogRevision, long viewSeq,
                                SearchAction action, boolean recursive, int page, int pageSize,
                                int entryId, long amount, long revision, int[] matches) {
        public SearchRequest {
            Objects.requireNonNull(action);
            Objects.requireNonNull(matches);
            if (matches.length > StorageSearch.MAX_MATCHES) throw new IllegalArgumentException("Too many search matches");
            matches = matches.clone();
        }
        @Override public int[] matches() { return matches.clone(); }
        public static SearchRequest decode(FriendlyByteBuf buf) {
            int menu = buf.readVarInt(); long session = buf.readLong(), query = buf.readLong();
            long catalog = buf.readLong(), view = buf.readLong();
            SearchAction action = buf.readEnum(SearchAction.class); boolean recursive = buf.readBoolean();
            int page = buf.readVarInt(), size = buf.readVarInt(), entry = buf.readVarInt();
            long amount = buf.readLong(), revision = buf.readLong();
            int length = buf.readVarInt();
            if (length < 0 || length > StorageSearch.MAX_MATCHES) throw new IllegalArgumentException("Too many search matches");
            int[] matches = new int[length];
            for (int i = 0; i < length; i++) matches[i] = buf.readVarInt();
            return new SearchRequest(menu, session, query, catalog, view, action, recursive, page, size, entry, amount, revision, matches);
        }
        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(menuId); buf.writeLong(session); buf.writeLong(querySeq);
            buf.writeLong(catalogRevision); buf.writeLong(viewSeq); buf.writeEnum(action); buf.writeBoolean(recursive);
            buf.writeVarInt(page); buf.writeVarInt(pageSize); buf.writeVarInt(entryId);
            buf.writeLong(amount); buf.writeLong(revision); buf.writeVarInt(matches.length);
            for (int match : matches) buf.writeVarInt(match);
        }
        public void handle(Supplier<NetworkEvent.Context> context) {
            context.get().enqueueWork(() -> {
                ServerPlayer player = context.get().getSender();
                if (player != null && player.containerMenu instanceof StorageMenu menu
                        && menu.containerId == menuId && menu.stillValid(player)) menu.handleSearch(this);
            });
            context.get().setPacketHandled(true);
        }
        @Override public boolean equals(Object other) {
            return other instanceof SearchRequest r && menuId == r.menuId && session == r.session
                    && querySeq == r.querySeq && catalogRevision == r.catalogRevision && viewSeq == r.viewSeq
                    && action == r.action && recursive == r.recursive && page == r.page && pageSize == r.pageSize
                    && entryId == r.entryId && amount == r.amount && revision == r.revision && Arrays.equals(matches, r.matches);
        }
        @Override public int hashCode() {
            return 31 * Objects.hash(menuId, session, querySeq, catalogRevision, viewSeq, action, recursive,
                    page, pageSize, entryId, amount, revision) + Arrays.hashCode(matches);
        }
    }

    /** A chunk of searchable names; quantities and full stacks stay in the paged snapshot. */
    public record SearchCatalog(int menuId, long session, long catalogRevision, int batch,
                                boolean reset, boolean complete, CompoundTag data) {
        public static SearchCatalog decode(FriendlyByteBuf buf) {
            int menu = buf.readVarInt(); long session = buf.readLong(), revision = buf.readLong();
            int batch = buf.readVarInt(); boolean reset = buf.readBoolean(), complete = buf.readBoolean();
            CompoundTag data = buf.readNbt();
            return new SearchCatalog(menu, session, revision, batch, reset, complete, data == null ? new CompoundTag() : data);
        }
        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(menuId); buf.writeLong(session); buf.writeLong(catalogRevision); buf.writeVarInt(batch);
            buf.writeBoolean(reset); buf.writeBoolean(complete); buf.writeNbt(data);
        }
        public void handle(Supplier<NetworkEvent.Context> context) {
            context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> ClientEvents.receive(this)));
            context.get().setPacketHandled(true);
        }
    }
    public record Request(int menuId, long revision, Action action, int id, int target, long amount, String name, long session) {
        public Request(int menuId, long revision, Action action, int id, int target, long amount, String name) {
            this(menuId, revision, action, id, target, amount, name, 0);
        }
        public Request withSession(long session) { return new Request(menuId, revision, action, id, target, amount, name, session); }
        public static Request decode(FriendlyByteBuf buf) {
            return new Request(buf.readVarInt(), buf.readLong(), buf.readEnum(Action.class),
                    buf.readVarInt(), buf.readVarInt(), buf.readLong(), buf.readUtf(64), buf.readLong());
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(menuId); buf.writeLong(revision); buf.writeEnum(action);
            buf.writeVarInt(id); buf.writeVarInt(target); buf.writeLong(amount); buf.writeUtf(name, 64); buf.writeLong(session);
        }

        public void handle(Supplier<NetworkEvent.Context> context) {
            context.get().enqueueWork(() -> {
                ServerPlayer player = context.get().getSender();
                if (player != null && player.containerMenu instanceof StorageMenu menu
                        && menu.containerId == menuId && menu.stillValid(player)) menu.handle(this);
            });
            context.get().setPacketHandled(true);
        }
    }

    public record NasRequest(int menuId, long session, long revision, int bay, boolean eject) {
        public static NasRequest decode(FriendlyByteBuf buf) {
            return new NasRequest(buf.readVarInt(), buf.readLong(), buf.readLong(), buf.readVarInt(), buf.readBoolean());
        }
        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(menuId); buf.writeLong(session); buf.writeLong(revision); buf.writeVarInt(bay); buf.writeBoolean(eject);
        }
        public void handle(Supplier<NetworkEvent.Context> context) {
            context.get().enqueueWork(() -> {
                ServerPlayer player = context.get().getSender();
                if (player != null && player.containerMenu instanceof NasMenu menu) menu.handle(this);
            });
            context.get().setPacketHandled(true);
        }
    }

    public enum PortAction { SELECT, APPLY, DISCONNECT }
    public record PortRequest(int menuId, long session, long context, long revision, PortAction action,
                              String volume, int folder, boolean input, boolean output, boolean recursive) {
        public static PortRequest decode(FriendlyByteBuf buf) {
            return new PortRequest(buf.readVarInt(), buf.readLong(), buf.readLong(), buf.readLong(), buf.readEnum(PortAction.class),
                    buf.readUtf(36), buf.readVarInt(), buf.readBoolean(), buf.readBoolean(), buf.readBoolean());
        }
        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(menuId); buf.writeLong(session); buf.writeLong(context); buf.writeLong(revision); buf.writeEnum(action);
            buf.writeUtf(volume, 36); buf.writeVarInt(folder); buf.writeBoolean(input); buf.writeBoolean(output); buf.writeBoolean(recursive);
        }
        public void handle(Supplier<NetworkEvent.Context> context) {
            context.get().enqueueWork(() -> {
                ServerPlayer player = context.get().getSender();
                if (player != null && player.containerMenu instanceof LogisticsPortMenu menu) menu.handle(this);
            });
            context.get().setPacketHandled(true);
        }
    }

    public enum FileAction { CREATE, OPEN, RENAME, DELETE, MOVE, COPY }
    public record FileRequest(int menuId, long session, long revision, long programRevision,
                              FileAction action, int id, int target, String name) {
        public FileRequest { Objects.requireNonNull(action); Objects.requireNonNull(name); }
        public static FileRequest decode(FriendlyByteBuf buf) {
            return new FileRequest(buf.readVarInt(), buf.readLong(), buf.readLong(), buf.readLong(),
                    buf.readEnum(FileAction.class), buf.readVarInt(), buf.readVarInt(), buf.readUtf(64));
        }
        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(menuId); buf.writeLong(session); buf.writeLong(revision); buf.writeLong(programRevision);
            buf.writeEnum(action); buf.writeVarInt(id); buf.writeVarInt(target); buf.writeUtf(name, 64);
        }
        public void handle(Supplier<NetworkEvent.Context> context) {
            context.get().enqueueWork(() -> {
                ServerPlayer player = context.get().getSender();
                if (player != null && player.containerMenu instanceof StorageMenu menu) menu.handleFile(this);
            });
            context.get().setPacketHandled(true);
        }
    }

    public enum ProductionAction { SAVE, START, CANCEL, SELECT_MACHINE, BACK }
    public record ProductionRequest(int menuId, long session, long context, long revision, ProductionAction action,
                                    String name, int inputFolder, int fuelFolder, int outputFolder,
                                    int inputEntry, int fuelEntry, int count, long machinePos) {
        public ProductionRequest(int menuId, long session, long context, long revision, ProductionAction action,
                                 String name, int inputFolder, int fuelFolder, int outputFolder, int inputEntry, int fuelEntry, int count) {
            this(menuId, session, context, revision, action, name, inputFolder, fuelFolder, outputFolder, inputEntry, fuelEntry, count, 0);
        }
        public ProductionRequest {
            Objects.requireNonNull(action); Objects.requireNonNull(name);
            if (name.length() > 64) throw new IllegalArgumentException("Production name is too long");
        }
        public static ProductionRequest decode(FriendlyByteBuf buf) {
            return new ProductionRequest(buf.readVarInt(), buf.readLong(), buf.readLong(), buf.readLong(),
                    buf.readEnum(ProductionAction.class), buf.readUtf(64), buf.readVarInt(), buf.readVarInt(),
                    buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readLong());
        }
        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(menuId); buf.writeLong(session); buf.writeLong(context); buf.writeLong(revision);
            buf.writeEnum(action); buf.writeUtf(name, 64); buf.writeVarInt(inputFolder); buf.writeVarInt(fuelFolder);
            buf.writeVarInt(outputFolder); buf.writeVarInt(inputEntry); buf.writeVarInt(fuelEntry); buf.writeVarInt(count); buf.writeLong(machinePos);
        }
        public void handle(Supplier<NetworkEvent.Context> context) {
            context.get().enqueueWork(() -> {
                ServerPlayer player = context.get().getSender();
                if (player != null && player.containerMenu instanceof ProductionPortMenu menu
                        && menu.containerId == menuId && menu.stillValid(player)) menu.handle(this);
            });
            context.get().setPacketHandled(true);
        }
    }

    public record Snapshot(int menuId, CompoundTag view) {
        public static Snapshot decode(FriendlyByteBuf buf) {
            int id = buf.readVarInt();
            CompoundTag tag = buf.readNbt();
            return new Snapshot(id, tag == null ? new CompoundTag() : tag);
        }
        public void encode(FriendlyByteBuf buf) { buf.writeVarInt(menuId); buf.writeNbt(view); }
        public void handle(Supplier<NetworkEvent.Context> context) {
            context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> ClientEvents.receive(this)));
            context.get().setPacketHandled(true);
        }
    }

    private StorageNetwork() {}

    public static void register() {
        CHANNEL.registerMessage(0, Request.class, Request::encode, Request::decode, Request::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(1, Snapshot.class, Snapshot::encode, Snapshot::decode, Snapshot::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(2, NasRequest.class, NasRequest::encode, NasRequest::decode, NasRequest::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(3, PortRequest.class, PortRequest::encode, PortRequest::decode, PortRequest::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(4, SearchRequest.class, SearchRequest::encode, SearchRequest::decode, SearchRequest::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(5, SearchCatalog.class, SearchCatalog::encode, SearchCatalog::decode, SearchCatalog::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(6, ProductionRequest.class, ProductionRequest::encode, ProductionRequest::decode, ProductionRequest::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(7, FileRequest.class, FileRequest::encode, FileRequest::decode, FileRequest::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
    }

    public static void request(Request request) { CHANNEL.sendToServer(request); }
    public static void request(NasRequest request) { CHANNEL.sendToServer(request); }
    public static void request(PortRequest request) { CHANNEL.sendToServer(request); }
    public static void request(SearchRequest request) { CHANNEL.sendToServer(request); }
    public static void request(ProductionRequest request) { CHANNEL.sendToServer(request); }
    public static void request(FileRequest request) { CHANNEL.sendToServer(request); }
    public static void searchCatalog(ServerPlayer player, SearchCatalog packet) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }
    public static void snapshot(ServerPlayer player, int id, CompoundTag view) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new Snapshot(id, view));
    }
}
