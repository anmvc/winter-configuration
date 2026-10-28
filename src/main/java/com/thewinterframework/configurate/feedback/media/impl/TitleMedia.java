package com.thewinterframework.configurate.feedback.media.impl;

import com.thewinterframework.component.ComponentUtils;
import com.thewinterframework.configurate.feedback.media.FeedbackMedia;
import com.thewinterframework.configurate.feedback.media.MediaNodes;
import com.thewinterframework.configurate.feedback.media.MediaType;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.title.Title.Times;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;
import org.spongepowered.configurate.serialize.TypeSerializer;
import java.lang.reflect.Type;
import java.time.Duration;

public record TitleMedia(String title, @Nullable String subtitle, Duration fadeIn, Duration stay, Duration fadeOut) implements FeedbackMedia {

    @Override
    public void sendMedia(final Audience audience, final TagResolver... resolvers) {
        final var titleComponent = ComponentUtils.miniMessage(title, resolvers);
        final var subtitleComponent = subtitle != null ? ComponentUtils.miniMessage(subtitle, resolvers) : Component.text("");

        final var title = Title.title(titleComponent, subtitleComponent, Times.times(fadeIn, stay, fadeOut));
        audience.showTitle(title);
    }

    @Override
    public MediaType type() {
        return MediaType.TITLE;
    }

    public static class TitleMediaSerializer implements TypeSerializer<FeedbackMedia> {

        @Override
        public FeedbackMedia deserialize(final Type type, final ConfigurationNode node) throws SerializationException {
            return new TitleMedia(
                    MediaNodes.requiredString(node, "title", type),
                    node.node("subtitle").getString(),
                    MediaNodes.seconds(node, "fade-in", 1, type),
                    MediaNodes.seconds(node, "stay", 1, type),
                    MediaNodes.seconds(node, "fade-out", 1, type)
            );
        }

        @Override
        public void serialize(final Type type, @Nullable final FeedbackMedia obj, final ConfigurationNode node) throws SerializationException {
            if (!(obj instanceof TitleMedia(String title1, String subtitle1, Duration in, Duration stay1, Duration out))) {
                throw new SerializationException("Invalid media type");
            }

            node.node("type").set(MediaType.TITLE.name().toLowerCase());
            node.node("title").set(title1);
            node.node("subtitle").set(subtitle1);
            node.node("fade-in").set(MediaNodes.toSeconds(in));
            node.node("stay").set(MediaNodes.toSeconds(stay1));
            node.node("fade-out").set(MediaNodes.toSeconds(out));
        }
    }
}
