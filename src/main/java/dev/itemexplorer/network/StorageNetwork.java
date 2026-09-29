package dev.itemexplorer.network;

import dev.itemexplorer.ItemExplorer;
import dev.itemexplorer.client.ClientEvents;
import dev.itemexplorer.menu.StorageMenu;
import dev.itemexplorer.menu.NasMenu;
import dev.itemexplorer.menu.LogisticsPortMenu;
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
import java.util.function.Supplier;

public final class StorageNetwork {
    private static final String VERSION = "5";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(ItemExplorer.MOD_ID, "storage"), () -> VERSION, VERSION::equals, VERSION::equals);

    public enum Action { OPEN, PAGE, CREATE, RENAME, DELETE, MOVE, WITHDRAW, DEPOSIT_CURSOR, RESIZE, SELECT_VOLUME, RENAME_DISK, DEPOSIT_SLOT }
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
    }

    public static void request(Request request) { CHANNEL.sendToServer(request); }
    public static void request(NasRequest request) { CHANNEL.sendToServer(request); }
    public static void request(PortRequest request) { CHANNEL.sendToServer(request); }
    public static void snapshot(ServerPlayer player, int id, CompoundTag view) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new Snapshot(id, view));
    }
}
