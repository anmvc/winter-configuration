package com.thewinterframework.configurate.serializer.common;

import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.BasicConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ComponentSerializerTest {

    private final ComponentSerializer serializer = new ComponentSerializer();

    @Test
    void mapInsteadOfTextFails() throws SerializationException {
        final var node = BasicConfigurationNode.root();
        node.node("text").set("hello");

        assertThrows(SerializationException.class, () -> serializer.deserialize(net.kyori.adventure.text.Component.class, node));
    }

    @Test
    void listInsteadOfTextFails() throws SerializationException {
        final var node = BasicConfigurationNode.root();
        node.set(List.of("one", "two"));

        assertThrows(SerializationException.class, () -> serializer.deserialize(net.kyori.adventure.text.Component.class, node));
    }

    @Test
    void missingValueIsNull() throws SerializationException {
        final var node = BasicConfigurationNode.root().node("missing");

        assertNull(serializer.deserialize(net.kyori.adventure.text.Component.class, node));
    }
}
