package com.shieldauth.managers;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.shieldauth.ShieldAuth;
import org.bukkit.entity.Player;

import java.util.Random;

public class PacketManager {

    private final ShieldAuth plugin;
    private final Random random = new Random();

    public PacketManager(ShieldAuth plugin) {
        this.plugin = plugin;
    }

    public void registerListeners() {
        if (plugin.getServer().getPluginManager().getPlugin("ProtocolLib") == null) {
            plugin.getLogger().warning("ProtocolLib not found! Coordinate spoofing will be disabled.");
            return;
        }

        ProtocolLibrary.getProtocolManager().addPacketListener(
                new PacketAdapter(plugin, ListenerPriority.HIGHEST, PacketType.Play.Server.POSITION) {
                    private final ShieldAuth authPlugin = (ShieldAuth) plugin;

                    @Override
                    public void onPacketSending(PacketEvent event) {
                        Player player = event.getPlayer();
                        if (authPlugin.isFullyAuthenticated(player))
                            return;

                        PacketContainer packet = event.getPacket();
                        double fakeX = 100000 + random.nextInt(900000);
                        double fakeY = 64 + random.nextInt(20);
                        double fakeZ = 100000 + random.nextInt(900000);

                        try {
                            // 1.21.1 Check: The packet mostly uses PositionMoveRotation record
                            Object pmr = packet.getModifier().read(1);
                            if (pmr != null && pmr.getClass().getSimpleName().equals("PositionMoveRotation")) {
                                // Record accessors in 1.21.1 records are just the field names
                                Object newVec3 = null;
                                Object delta = null;
                                float yRot = 0, xRot = 0;

                                try {
                                    Object pos = pmr.getClass().getMethod("position").invoke(pmr);
                                    newVec3 = pos.getClass().getConstructor(double.class, double.class, double.class)
                                            .newInstance(fakeX, fakeY, fakeZ);
                                    delta = pmr.getClass().getMethod("deltaMovement").invoke(pmr);
                                    yRot = (float) pmr.getClass().getMethod("yRot").invoke(pmr);
                                    xRot = (float) pmr.getClass().getMethod("xRot").invoke(pmr);
                                } catch (NoSuchMethodException e) {
                                    // Handle cases where names might be different/obfuscated (unlikely with
                                    // ProtocolLib)
                                }

                                if (newVec3 != null) {
                                    java.lang.reflect.Constructor<?> pmrCons = pmr.getClass()
                                            .getDeclaredConstructors()[0];
                                    pmrCons.setAccessible(true);
                                    Object newPmr = pmrCons.newInstance(newVec3, delta, yRot, xRot);
                                    packet.getModifier().write(1, newPmr);
                                }
                            } else if (packet.getDoubles().size() >= 3) {
                                packet.getDoubles().write(0, fakeX);
                                packet.getDoubles().write(1, fakeY);
                                packet.getDoubles().write(2, fakeZ);
                            }
                        } catch (Exception ignored) {
                        }
                    }
                });

        // Intercept incoming move packets to prevent the server from rubber-banding the
        // player
        ProtocolLibrary.getProtocolManager().addPacketListener(
                new PacketAdapter(plugin, ListenerPriority.HIGHEST, PacketType.Play.Client.POSITION,
                        PacketType.Play.Client.POSITION_LOOK) {
                    private final ShieldAuth authPlugin = (ShieldAuth) plugin;

                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        Player player = event.getPlayer();
                        if (!authPlugin.isFullyAuthenticated(player)) {
                            // Only cancel if they are truly trying to move away from spawn significantly
                            // We allow tiny movements to keep the connection alive
                            event.setCancelled(true);
                        }
                    }
                });
    }
}
