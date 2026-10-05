package org.patchbukkit.messaging;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

public class PatchBukkitMessenger implements Messenger {

    private final Map<String, Set<PluginMessageListenerRegistration>> incomingByChannel = new ConcurrentHashMap<>();
    private final Map<Plugin, Set<PluginMessageListenerRegistration>> incomingByPlugin = new ConcurrentHashMap<>();
    private final Map<String, Set<Plugin>> outgoingByChannel = new ConcurrentHashMap<>();
    private final Map<Plugin, Set<String>> outgoingByPlugin = new ConcurrentHashMap<>();

    /** Pseudo event type the Rust bridge maps to Pumpkin's custom payload event. */
    public static final String PLUGIN_MESSAGE_BRIDGE_EVENT = "patchbukkit:plugin_message";

    private final java.util.concurrent.atomic.AtomicBoolean bridgeRegistered = new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * Subscribes to the client's custom payloads once, so incoming plugin messages and the
     * client's channel registrations reach Java as soon as any plugin uses messaging.
     */
    private void ensureBridgeRegistered(Plugin plugin) {
        if (!bridgeRegistered.compareAndSet(false, true)) return;
        try {
            patchbukkit.bridge.NativeBridgeFfi.registerEvent(patchbukkit.events.RegisterEventRequest.newBuilder()
                .setEventType(PLUGIN_MESSAGE_BRIDGE_EVENT)
                .setPluginName(plugin.getName())
                .setPriority(2)
                .setBlocking(true)
                .build());
        } catch (Throwable t) {
            bridgeRegistered.set(false);
            throw t;
        }
    }

    /** Handles one custom payload sent by a client: tracks channel registrations, then dispatches it. */
    public void handleIncomingPayload(Player source, String channel, byte[] data) {
        if (channel.equals("minecraft:register") || channel.equals("minecraft:unregister")) {
            if (source instanceof org.patchbukkit.entity.PatchBukkitPlayer player) {
                player.handleChannelPayload(channel, data);
            }
            return;
        }
        dispatchIncomingMessage(source, channel, data);
    }

    private static void validateChannel(String channel) {
        if (channel == null) {
            throw new IllegalArgumentException("Channel cannot be null");
        }
        if (channel.length() > 64) {
            throw new IllegalArgumentException("Channel cannot be longer than 64 characters");
        }
    }

    @Override
    public boolean isReservedChannel(String channel) {
        validateChannel(channel);
        return channel.equalsIgnoreCase("REGISTER") || channel.equalsIgnoreCase("UNREGISTER") || channel.equalsIgnoreCase("FML") || channel.equalsIgnoreCase("FML|HS") || channel.equalsIgnoreCase("FML|MP");
    }

    @Override
    public void registerOutgoingPluginChannel(Plugin plugin, String channel) {
        if (plugin == null) throw new IllegalArgumentException("Plugin cannot be null");
        validateChannel(channel);
        if (isReservedChannel(channel)) throw new ReservedChannelException(channel);

        ensureBridgeRegistered(plugin);
        outgoingByChannel.computeIfAbsent(channel.toLowerCase(Locale.ENGLISH), k -> new CopyOnWriteArraySet<>()).add(plugin);
        outgoingByPlugin.computeIfAbsent(plugin, k -> new CopyOnWriteArraySet<>()).add(channel.toLowerCase(Locale.ENGLISH));
    }

    @Override
    public void unregisterOutgoingPluginChannel(Plugin plugin, String channel) {
        if (plugin == null) throw new IllegalArgumentException("Plugin cannot be null");
        validateChannel(channel);

        Set<Plugin> plugins = outgoingByChannel.get(channel.toLowerCase(Locale.ENGLISH));
        if (plugins != null) {
            plugins.remove(plugin);
        }
        Set<String> channels = outgoingByPlugin.get(plugin);
        if (channels != null) {
            channels.remove(channel.toLowerCase(Locale.ENGLISH));
        }
    }

    @Override
    public void unregisterOutgoingPluginChannel(Plugin plugin) {
        if (plugin == null) throw new IllegalArgumentException("Plugin cannot be null");
        Set<String> channels = outgoingByPlugin.remove(plugin);
        if (channels != null) {
            for (String channel : channels) {
                Set<Plugin> plugins = outgoingByChannel.get(channel);
                if (plugins != null) {
                    plugins.remove(plugin);
                }
            }
        }
    }

    @Override
    public PluginMessageListenerRegistration registerIncomingPluginChannel(Plugin plugin, String channel, PluginMessageListener listener) {
        if (plugin == null) throw new IllegalArgumentException("Plugin cannot be null");
        validateChannel(channel);
        if (listener == null) throw new IllegalArgumentException("Listener cannot be null");
        if (isReservedChannel(channel)) throw new ReservedChannelException(channel);

        ensureBridgeRegistered(plugin);
        PluginMessageListenerRegistration reg = new PluginMessageListenerRegistration(this, plugin, channel, listener);
        incomingByChannel.computeIfAbsent(channel.toLowerCase(Locale.ENGLISH), k -> new CopyOnWriteArraySet<>()).add(reg);
        incomingByPlugin.computeIfAbsent(plugin, k -> new CopyOnWriteArraySet<>()).add(reg);
        return reg;
    }

    @Override
    public void unregisterIncomingPluginChannel(Plugin plugin, String channel, PluginMessageListener listener) {
        if (plugin == null) throw new IllegalArgumentException("Plugin cannot be null");
        validateChannel(channel);
        if (listener == null) throw new IllegalArgumentException("Listener cannot be null");

        PluginMessageListenerRegistration reg = new PluginMessageListenerRegistration(this, plugin, channel, listener);
        Set<PluginMessageListenerRegistration> regs = incomingByChannel.get(channel.toLowerCase(Locale.ENGLISH));
        if (regs != null) {
            regs.remove(reg);
        }
        Set<PluginMessageListenerRegistration> pluginRegs = incomingByPlugin.get(plugin);
        if (pluginRegs != null) {
            pluginRegs.remove(reg);
        }
    }

    @Override
    public void unregisterIncomingPluginChannel(Plugin plugin, String channel) {
        if (plugin == null) throw new IllegalArgumentException("Plugin cannot be null");
        validateChannel(channel);

        Set<PluginMessageListenerRegistration> regs = incomingByChannel.get(channel.toLowerCase(Locale.ENGLISH));
        if (regs != null) {
            regs.removeIf(reg -> reg.getPlugin().equals(plugin));
        }
        Set<PluginMessageListenerRegistration> pluginRegs = incomingByPlugin.get(plugin);
        if (pluginRegs != null) {
            pluginRegs.removeIf(reg -> reg.getChannel().equalsIgnoreCase(channel));
        }
    }

    @Override
    public void unregisterIncomingPluginChannel(Plugin plugin) {
        if (plugin == null) throw new IllegalArgumentException("Plugin cannot be null");
        Set<PluginMessageListenerRegistration> pluginRegs = incomingByPlugin.remove(plugin);
        if (pluginRegs != null) {
            for (PluginMessageListenerRegistration reg : pluginRegs) {
                Set<PluginMessageListenerRegistration> channelRegs = incomingByChannel.get(reg.getChannel().toLowerCase(Locale.ENGLISH));
                if (channelRegs != null) {
                    channelRegs.remove(reg);
                }
            }
        }
    }

    @Override
    public Set<String> getOutgoingChannels() {
        return Collections.unmodifiableSet(outgoingByChannel.keySet());
    }

    @Override
    public Set<String> getOutgoingChannels(Plugin plugin) {
        if (plugin == null) throw new IllegalArgumentException("Plugin cannot be null");
        Set<String> channels = outgoingByPlugin.get(plugin);
        return channels != null ? Collections.unmodifiableSet(channels) : Collections.emptySet();
    }

    @Override
    public Set<String> getIncomingChannels() {
        return Collections.unmodifiableSet(incomingByChannel.keySet());
    }

    @Override
    public Set<String> getIncomingChannels(Plugin plugin) {
        if (plugin == null) throw new IllegalArgumentException("Plugin cannot be null");
        Set<PluginMessageListenerRegistration> regs = incomingByPlugin.get(plugin);
        if (regs == null || regs.isEmpty()) return Collections.emptySet();

        Set<String> result = new HashSet<>();
        for (PluginMessageListenerRegistration reg : regs) {
            result.add(reg.getChannel());
        }
        return Collections.unmodifiableSet(result);
    }

    @Override
    public Set<PluginMessageListenerRegistration> getIncomingChannelRegistrations(Plugin plugin) {
        if (plugin == null) throw new IllegalArgumentException("Plugin cannot be null");
        Set<PluginMessageListenerRegistration> regs = incomingByPlugin.get(plugin);
        return regs != null ? Collections.unmodifiableSet(regs) : Collections.emptySet();
    }

    @Override
    public Set<PluginMessageListenerRegistration> getIncomingChannelRegistrations(String channel) {
        validateChannel(channel);
        Set<PluginMessageListenerRegistration> regs = incomingByChannel.get(channel.toLowerCase(Locale.ENGLISH));
        return regs != null ? Collections.unmodifiableSet(regs) : Collections.emptySet();
    }

    @Override
    public Set<PluginMessageListenerRegistration> getIncomingChannelRegistrations(Plugin plugin, String channel) {
        if (plugin == null) throw new IllegalArgumentException("Plugin cannot be null");
        validateChannel(channel);

        Set<PluginMessageListenerRegistration> regs = incomingByChannel.get(channel.toLowerCase(Locale.ENGLISH));
        if (regs == null || regs.isEmpty()) return Collections.emptySet();

        Set<PluginMessageListenerRegistration> filtered = new HashSet<>();
        for (PluginMessageListenerRegistration reg : regs) {
            if (reg.getPlugin().equals(plugin)) {
                filtered.add(reg);
            }
        }
        return Collections.unmodifiableSet(filtered);
    }

    @Override
    public boolean isRegistrationValid(PluginMessageListenerRegistration registration) {
        if (registration == null) return false;
        Set<PluginMessageListenerRegistration> regs = incomingByChannel.get(registration.getChannel().toLowerCase(Locale.ENGLISH));
        return regs != null && regs.contains(registration);
    }

    @Override
    public boolean isIncomingChannelRegistered(Plugin plugin, String channel) {
        if (plugin == null) throw new IllegalArgumentException("Plugin cannot be null");
        validateChannel(channel);

        Set<PluginMessageListenerRegistration> regs = incomingByChannel.get(channel.toLowerCase(Locale.ENGLISH));
        if (regs == null) return false;
        for (PluginMessageListenerRegistration reg : regs) {
            if (reg.getPlugin().equals(plugin)) return true;
        }
        return false;
    }

    @Override
    public boolean isOutgoingChannelRegistered(Plugin plugin, String channel) {
        if (plugin == null) throw new IllegalArgumentException("Plugin cannot be null");
        validateChannel(channel);

        Set<Plugin> plugins = outgoingByChannel.get(channel.toLowerCase(Locale.ENGLISH));
        return plugins != null && plugins.contains(plugin);
    }

    @Override
    public void dispatchIncomingMessage(Player source, String channel, byte[] message) {
        if (source == null) throw new IllegalArgumentException("Player source cannot be null");
        validateChannel(channel);
        if (message == null) throw new IllegalArgumentException("Message cannot be null");

        Set<PluginMessageListenerRegistration> regs = incomingByChannel.get(channel.toLowerCase(Locale.ENGLISH));
        if (regs != null) {
            for (PluginMessageListenerRegistration reg : regs) {
                try {
                    reg.getListener().onPluginMessageReceived(channel, source, message);
                } catch (Throwable t) {
                    source.getServer().getLogger().severe("Error dispatching plugin message on " + channel + " to " + reg.getPlugin().getName() + ": " + t.getMessage());
                }
            }
        }
    }

    @Override
    public void dispatchIncomingMessage(io.papermc.paper.connection.PlayerConnection connection, String channel, byte[] message) {
        if (connection == null) throw new IllegalArgumentException("Connection cannot be null");
        // Non-player connection handling if applicable
    }
}
