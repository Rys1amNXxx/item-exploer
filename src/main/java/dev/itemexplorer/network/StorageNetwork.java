package dev.itemexplorer.network;

import dev.itemexplorer.ItemExplorer;
import dev.itemexplorer.client.ClientEvents;
import dev.itemexplorer.menu.StorageMenu;
import dev.itemexplorer.storage.StorageInventory;
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
    private static final String VERSION = "2";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(ItemExplorer.MOD_ID, "storage"), () -> VERSION, VERSION::equals, VERSION::equals);

    public enum Action { OPEN, PAGE, CREATE, RENAME, DELETE, MOVE, WITHDRAW, DEPOSIT_CURSOR, RESIZE }
    public record Request(int menuId, long revision, Action action, int id, int target, int amount, String name) {
        public static Request decode(FriendlyByteBuf buf) {
            return new Request(buf.readVarInt(), buf.readLong(), buf.readEnum(Action.class),
                    buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readUtf(StorageInventory.MAX_NAME));
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(menuId); buf.writeLong(revision); buf.writeEnum(action);
            buf.writeVarInt(id); buf.writeVarInt(target); buf.writeVarInt(amount); buf.writeUtf(name, StorageInventory.MAX_NAME);
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
    }

    public static void request(Request request) { CHANNEL.sendToServer(request); }
    public static void snapshot(ServerPlayer player, int id, CompoundTag view) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new Snapshot(id, view));
    }
}
