package com.thewinterframework.configurate.feedback.serializer;

import com.thewinterframework.configurate.feedback.media.FeedbackMedia;
import com.thewinterframework.configurate.feedback.media.MediaType;
import com.thewinterframework.configurate.feedback.media.impl.ActionBarMedia;
import com.thewinterframework.configurate.feedback.media.impl.BossBarMedia;
import com.thewinterframework.configurate.feedback.media.impl.ChatMedia;
import com.thewinterframework.configurate.feedback.media.impl.SoundMedia;
import com.thewinterframework.configurate.feedback.media.impl.TitleMedia;
import com.thewinterframework.configurate.serializer.ConfigurateSerializer;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;
import org.spongepowered.configurate.serialize.TypeSerializer;

import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Locale;

@ConfigurateSerializer
public class FeedbackMediaSpongeSerializer implements TypeSerializer<FeedbackMedia> {

    private final static EnumMap<MediaType, TypeSerializer<FeedbackMedia>> CHILD_SERIALIZERS = new EnumMap<>(MediaType.class);

    static  {
        CHILD_SERIALIZERS.put(MediaType.CHAT, new ChatMedia.ChatMediaSerializer());
        CHILD_SERIALIZERS.put(MediaType.BOSSBAR, new BossBarMedia.BossBarMediaSerializer());
        CHILD_SERIALIZERS.put(MediaType.ACTIONBAR, new ActionBarMedia.ActionBarMediaSerializer());
        CHILD_SERIALIZERS.put(MediaType.TITLE, new TitleMedia.TitleMediaSerializer());
        CHILD_SERIALIZERS.put(MediaType.SOUND, new SoundMedia.SoundMediaSerializer());
    }

    @Override
    public FeedbackMedia deserialize(final Type type, final ConfigurationNode node) throws SerializationException {
        final var typeNode = node.node("type");
        if (typeNode.virtual() || typeNode.raw() == null) {
            // Without a type, the value is a chat message: a string, a list of strings, or a map with a "message".
            if (node.isList()) {
                return new ChatMedia(node.getList(String.class));
            }
            if (node.isMap()) {
                return CHILD_SERIALIZERS.get(MediaType.CHAT).deserialize(type, node);
            }

            final var message = node.getString();
            if (message == null) {
                throw new SerializationException(node, type, "Expected a chat message, a list of messages, or a map with a 'type'");
            }
            return new ChatMedia(message);
        }

        final var typeName = typeNode.getString();
        if (typeName == null) {
            throw new SerializationException(typeNode, type, "Expected a single value for 'type'");
        }

        final MediaType mediaType;
        try {
            mediaType = MediaType.valueOf(typeName.strip().toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException exception) {
            throw new SerializationException(typeNode, type, "Unknown feedback type '" + typeName + "', expected one of "
                    + Arrays.toString(MediaType.values()));
        }

        final var serializer = CHILD_SERIALIZERS.get(mediaType);
        if (serializer == null) {
            throw new SerializationException(typeNode, type, "Feedback type '" + typeName + "' cannot be deserialized");
        }
        return serializer.deserialize(type, node);
    }

    @Override
    public void serialize(final Type type, @Nullable final FeedbackMedia obj, final ConfigurationNode node) throws SerializationException {
        if (obj == null) {
            return;
        }

        final var mediaType = obj.type();
        final var parent = node.parent();
        if (obj instanceof ChatMedia(java.util.List<String> messages) && (parent == null || !parent.isList())) { // simple format - node: "message"
            if (messages.size() == 1) {
                node.set(messages.get(0));
                return;
            }

            node.setList(String.class, messages);
            return;
        }

        CHILD_SERIALIZERS.get(mediaType).serialize(type, obj, node);
    }
}
