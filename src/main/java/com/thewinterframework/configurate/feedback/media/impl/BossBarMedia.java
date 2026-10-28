package com.thewinterframework.configurate.feedback.media.impl;

import com.thewinterframework.component.ComponentUtils;
import com.thewinterframework.configurate.feedback.media.FeedbackMedia;
import com.thewinterframework.configurate.feedback.media.MediaNodes;
import com.thewinterframework.configurate.feedback.media.MediaType;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.bossbar.BossBar.Color;
import net.kyori.adventure.bossbar.BossBar.Overlay;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;
import org.spongepowered.configurate.serialize.TypeSerializer;
import java.lang.reflect.Type;

public record BossBarMedia(String message, float progress, Color color, Overlay overlay) implements FeedbackMedia {

    /**
     * Creates a boss bar media.
     *
     * @throws IllegalArgumentException if the progress is not between 0 and 1
     */
    public BossBarMedia {
        if (!(progress >= 0f && progress <= 1f)) {
            throw new IllegalArgumentException("Boss bar progress must be between 0 and 1, but was " + progress);
        }
    }

    @Override
    public void sendMedia(final Audience audience, final TagResolver... resolvers) {
        final var name = ComponentUtils.miniMessage(message, resolvers);
        final var bossBar = BossBar.bossBar(name, progress, color, overlay);
        audience.showBossBar(bossBar);
    }

    @Override
    public MediaType type() {
        return MediaType.BOSSBAR;
    }

    public static class BossBarMediaSerializer implements TypeSerializer<FeedbackMedia> {
        @Override
        public FeedbackMedia deserialize(final Type type, final ConfigurationNode node) throws SerializationException {
            final var message = MediaNodes.requiredString(node, "message", type);
            final var progress = node.node("progress").getFloat(1.0f);
            final var color = MediaNodes.enumValue(node, "color", Color.class, Color.PURPLE, type);
            final var overlay = MediaNodes.enumValue(node, "overlay", Overlay.class, Overlay.PROGRESS, type);
            try {
                return new BossBarMedia(message, progress, color, overlay);
            } catch (final IllegalArgumentException exception) {
                throw new SerializationException(node.node("progress"), type, exception.getMessage());
            }
        }

        @Override
        public void serialize(final Type type, @Nullable final FeedbackMedia obj, final ConfigurationNode node) throws SerializationException {
            if (!(obj instanceof BossBarMedia(String message1, float progress1, Color color1, Overlay overlay1))) {
                throw new SerializationException("Invalid media type");
            }

            node.node("type").set(MediaType.BOSSBAR.name().toLowerCase());
            node.node("message").set(message1);
            node.node("progress").set(progress1);
            node.node("color").set(color1.name().toLowerCase());
            node.node("overlay").set(overlay1.name().toLowerCase());
        }
    }
}
