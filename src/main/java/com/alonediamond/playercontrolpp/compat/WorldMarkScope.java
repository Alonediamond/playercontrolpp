package com.alonediamond.playercontrolpp.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

/** 标记只属于一个存档或服务器的一个维度。 */
public record WorldMarkScope(UUID world, String dimension) {
    public static WorldMarkScope current(Minecraft client) {
        if (client.level == null) return null;
        String dimension = client.level.dimension().identifier().toString();
        IntegratedServer integrated = client.getSingleplayerServer();
        if (integrated != null) {
            String path = integrated.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().toString();
            return fromIdentity("save:" + path, dimension);
        }

        ServerData server = client.getCurrentServer();
        // Realms 会分配临时地址，不能用该地址推断世界归属。
        if (server == null || server.isRealm() || !ServerAddress.isValidAddress(server.ip)) return null;
        ServerAddress address = ServerAddress.parseString(server.ip);
        String endpoint = address.getHost().toLowerCase(Locale.ROOT) + ":" + address.getPort();
        return fromIdentity("server:" + endpoint, dimension);
    }

    private static WorldMarkScope fromIdentity(String identity, String dimension) {
        UUID key = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
        return new WorldMarkScope(key, dimension);
    }
}
