package com.thewinterframework.configurate.feedback;

import com.thewinterframework.configurate.feedback.media.FeedbackMedia;
import com.thewinterframework.configurate.feedback.media.impl.ActionBarMedia;
import com.thewinterframework.configurate.feedback.media.impl.BossBarMedia;
import com.thewinterframework.configurate.feedback.media.impl.ChatMedia;
import com.thewinterframework.configurate.feedback.media.impl.SoundMedia;
import com.thewinterframework.configurate.feedback.media.impl.TitleMedia;
import com.thewinterframework.configurate.feedback.serializer.FeedbackMediaSpongeSerializer;
import com.thewinterframework.configurate.feedback.serializer.FeedbackSpongeSerializer;
import net.kyori.adventure.bossbar.BossBar;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.BasicConfigurationNode;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.ConfigurationOptions;
import org.spongepowered.configurate.serialize.SerializationException;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FeedbackMediaSerializerTest {

    private final FeedbackMediaSpongeSerializer serializer = new FeedbackMediaSpongeSerializer();

    private static ConfigurationNode map(final Object... keyValues) throws SerializationException {
        final var node = BasicConfigurationNode.root();
        for (int i = 0; i < keyValues.length; i += 2) {
            node.node((String) keyValues[i]).set(keyValues[i + 1]);
        }
        return node;
    }

    @Test
    void untypedScalarIsAChatMessage() throws SerializationException {
        final var node = BasicConfigurationNode.root();
        node.set("Hello");

        assertEquals(new ChatMedia("Hello"), serializer.deserialize(FeedbackMedia.class, node));
    }

    @Test
    void untypedMapWithMessageIsAChatMessage() throws SerializationException {
        final var node = map("message", "Hello");

        assertEquals(new ChatMedia("Hello"), serializer.deserialize(FeedbackMedia.class, node));
    }

    @Test
    void untypedMapWithoutMessageFails() throws SerializationException {
        final var node = map("progress", 1.0);

        assertThrows(SerializationException.class, () -> serializer.deserialize(FeedbackMedia.class, node));
    }

    @Test
    void chatWithoutMessageFailsInsteadOfUsingThePath() throws SerializationException {
        final var node = map("type", "chat");

        assertThrows(SerializationException.class, () -> serializer.deserialize(FeedbackMedia.class, node));
    }

    @Test
    void chatWithAListOfMessagesIsDeserialized() throws SerializationException {
        final var node = map("type", "chat", "message", List.of("one", "two"));

        assertEquals(new ChatMedia(List.of("one", "two")), serializer.deserialize(FeedbackMedia.class, node));
    }

    @Test
    void unknownTypeFailsWithASerializationException() throws SerializationException {
        final var node = map("type", "hologram");

        assertThrows(SerializationException.class, () -> serializer.deserialize(FeedbackMedia.class, node));
    }

    @Test
    void typeNameIsReadIgnoringCase() throws SerializationException {
        final var node = map("type", " ActionBar ", "message", "Hi");

        assertEquals(new ActionBarMedia("Hi"), serializer.deserialize(FeedbackMedia.class, node));
    }

    @Test
    void actionBarWithoutMessageFails() throws SerializationException {
        final var node = map("type", "actionbar");

        assertThrows(SerializationException.class, () -> serializer.deserialize(FeedbackMedia.class, node));
    }

    @Test
    void titleKeepsSubSecondDurations() throws SerializationException {
        final var node = map("type", "title", "title", "Welcome", "fade-in", 0.5, "stay", 2, "fade-out", 0.25);

        final var media = (TitleMedia) serializer.deserialize(FeedbackMedia.class, node);

        assertEquals(Duration.ofMillis(500), media.fadeIn());
        assertEquals(Duration.ofSeconds(2), media.stay());
        assertEquals(Duration.ofMillis(250), media.fadeOut());
    }

    @Test
    void titleDurationsRoundTripWithoutLosingPrecision() throws SerializationException {
        final var original = new TitleMedia("Welcome", "sub", Duration.ofMillis(750), Duration.ofSeconds(3), Duration.ofMillis(1500));
        final var node = BasicConfigurationNode.root();

        serializer.serialize(FeedbackMedia.class, original, node);

        assertEquals(original, serializer.deserialize(FeedbackMedia.class, node));
    }

    @Test
    void titleWithNegativeDurationFails() throws SerializationException {
        final var node = map("type", "title", "title", "Welcome", "stay", -1);

        assertThrows(SerializationException.class, () -> serializer.deserialize(FeedbackMedia.class, node));
    }

    @Test
    void titleWithoutTitleFails() throws SerializationException {
        final var node = map("type", "title", "subtitle", "only");

        assertThrows(SerializationException.class, () -> serializer.deserialize(FeedbackMedia.class, node));
    }

    @Test
    void bossBarDefaultsAndValuesAreDeserialized() throws SerializationException {
        final var node = map("type", "bossbar", "message", "Boss", "progress", 0.5, "color", "red", "overlay", "notched_10");

        assertEquals(new BossBarMedia("Boss", 0.5f, BossBar.Color.RED, BossBar.Overlay.NOTCHED_10),
                serializer.deserialize(FeedbackMedia.class, node));
    }

    @Test
    void bossBarProgressOutsideZeroToOneFails() throws SerializationException {
        final var tooHigh = map("type", "bossbar", "message", "Boss", "progress", 1.5);
        final var negative = map("type", "bossbar", "message", "Boss", "progress", -0.1);

        assertThrows(SerializationException.class, () -> serializer.deserialize(FeedbackMedia.class, tooHigh));
        assertThrows(SerializationException.class, () -> serializer.deserialize(FeedbackMedia.class, negative));
    }

    @Test
    void bossBarRecordRejectsProgressOutsideZeroToOne() {
        assertThrows(IllegalArgumentException.class,
                () -> new BossBarMedia("Boss", Float.NaN, BossBar.Color.RED, BossBar.Overlay.PROGRESS));
        assertThrows(IllegalArgumentException.class,
                () -> new BossBarMedia("Boss", 2f, BossBar.Color.RED, BossBar.Overlay.PROGRESS));
    }

    @Test
    void bossBarWithUnknownColorFailsWithTheValidValues() throws SerializationException {
        final var node = map("type", "bossbar", "message", "Boss", "color", "teal");

        final var exception = assertThrows(SerializationException.class,
                () -> serializer.deserialize(FeedbackMedia.class, node));
        assertTrue(exception.getMessage().contains("teal"));
    }

    @Test
    void soundWithInvalidNameFailsInsteadOfThrowingAnUncheckedException() throws SerializationException {
        final var node = map("type", "sound", "name", "Bad Sound!");

        assertThrows(SerializationException.class, () -> serializer.deserialize(FeedbackMedia.class, node));
    }

    @Test
    void soundWithNegativeVolumeFails() throws SerializationException {
        final var node = map("type", "sound", "name", "minecraft:block.anvil.land", "volume", -1);

        assertThrows(SerializationException.class, () -> serializer.deserialize(FeedbackMedia.class, node));
    }

    @Test
    void soundRoundTripKeepsItsName() throws SerializationException {
        final var node = map("type", "sound", "name", "minecraft:entity.experience_orb.pickup", "source", "player");

        final var media = (SoundMedia) serializer.deserialize(FeedbackMedia.class, node);

        assertEquals("minecraft:entity.experience_orb.pickup", media.sound().name().asString());
        final var written = BasicConfigurationNode.root();
        serializer.serialize(FeedbackMedia.class, media, written);
        assertEquals("minecraft:entity.experience_orb.pickup", written.node("name").getString());
        assertEquals("player", written.node("source").getString());
    }

    @Test
    void feedbackAcceptsASingleMediaMap() throws SerializationException {
        final var node = BasicConfigurationNode.root(ConfigurationOptions.defaults()
                .serializers(builder -> builder.register(FeedbackMedia.class, serializer)));
        node.node("type").set("actionbar");
        node.node("message").set("Hi");

        final var feedback = new FeedbackSpongeSerializer().deserialize(Feedback.class, node);

        assertEquals(List.of(new ActionBarMedia("Hi")), ((FeedbackImpl) feedback).medias());
    }

    @Test
    void feedbackAcceptsAListOfMediaAndAScalarMessage() throws SerializationException {
        final var node = BasicConfigurationNode.root(ConfigurationOptions.defaults()
                .serializers(builder -> builder.register(FeedbackMedia.class, serializer)));
        node.node(0).set("Plain chat");
        node.node(1, "type").set("actionbar");
        node.node(1, "message").set("Hi");

        final var feedback = new FeedbackSpongeSerializer().deserialize(Feedback.class, node);

        assertEquals(List.of(new ChatMedia("Plain chat"), new ActionBarMedia("Hi")), ((FeedbackImpl) feedback).medias());
    }
}
