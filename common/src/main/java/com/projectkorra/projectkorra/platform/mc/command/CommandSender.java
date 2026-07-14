package com.projectkorra.projectkorra.platform.mc.command;

import com.projectkorra.projectkorra.platform.chat.BaseComponent;
import com.projectkorra.projectkorra.platform.chat.ChatMessageType;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

public interface CommandSender {
    default String getName() {
        return "Console";
    }

    default void sendMessage(String message) {
    }

    default boolean hasPermission(String permission) {
        return true;
    }

    default Spigot spigot() {
        return new Spigot(this::sendMessage);
    }

    class Spigot {
        private final BiConsumer<ChatMessageType, String> sink;

        public Spigot() {
            this((type, message) -> {
            });
        }

        public Spigot(Consumer<String> sink) {
            this((type, message) -> sink.accept(message));
        }

        public Spigot(BiConsumer<ChatMessageType, String> sink) {
            this.sink = sink;
        }

        public void sendMessage(BaseComponent... components) {
            sendMessage(ChatMessageType.CHAT, components);
        }

        public void sendMessage(ChatMessageType type, BaseComponent... components) {
            StringBuilder message = new StringBuilder();
            if (components != null)
                for (var component : components) if (component != null) message.append(component.toLegacyText());
            sink.accept(type, message.toString());
        }
    }
}
