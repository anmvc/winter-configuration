package com.thewinterframework.configurate.feedback.media.impl;

import com.thewinterframework.configurate.feedback.media.FeedbackMedia;
import com.thewinterframework.configurate.feedback.media.MediaNodes;
import com.thewinterframework.configurate.feedback.media.MediaType;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.key.InvalidKeyException;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.sound.Sound.Emitter;
import net.kyori.adventure.sound.Sound.Source;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;
import org.spongepowered.configurate.serialize.TypeSerializer;
import java.lang.reflect.Type;

public record SoundMedia(Sound sound) implements FeedbackMedia {
    @Override
    public void sendMedia(final Audience audience, final TagResolver... resolvers) {
        audience.playSound(sound, Emitter.self());
    }

    @Override
    public MediaType type() {
        return MediaType.SOUND;
    }

    public static class SoundMediaSerializer implements TypeSerializer<FeedbackMedia> {
        @Override
        public FeedbackMedia deserialize(final Type type, final ConfigurationNode node) throws SerializationException {
            final var name = MediaNodes.requiredString(node, "name", type);
            final Key key;
            try {
                key = Key.key(name);
            } catch (final InvalidKeyException exception) {
                throw new SerializationException(node.node("name"), type, "Invalid sound name '" + name + "': " + exception.getMessage());
            }

            final var source = MediaNodes.enumValue(node, "source", Source.class, Source.MASTER, type);
            final var volume = node.node("volume").getFloat(1.0f);
            final var pitch = node.node("pitch").getFloat(1.0f);
            if (!(volume >= 0f)) {
                throw new SerializationException(node.node("volume"), type, "Sound volume must not be negative, but was " + volume);
            }
            if (!(pitch >= 0f)) {
                throw new SerializationException(node.node("pitch"), type, "Sound pitch must not be negative, but was " + pitch);
            }

            return new SoundMedia(Sound.sound(key, source, volume, pitch));
        }

        @Override
        public void serialize(final Type type, @Nullable final FeedbackMedia obj, final ConfigurationNode node) throws SerializationException {
            if (!(obj instanceof SoundMedia(Sound sound1))) {
                throw new SerializationException("Invalid media type");
            }

            node.node("type").set(MediaType.SOUND.name().toLowerCase());
            node.node("name").set(sound1.name().asString());
            node.node("source").set(sound1.source().name().toLowerCase());
            node.node("volume").set(sound1.volume());
            node.node("pitch").set(sound1.pitch());
        }
    }
}
