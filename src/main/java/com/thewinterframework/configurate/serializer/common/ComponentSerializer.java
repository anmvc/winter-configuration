package com.thewinterframework.configurate.serializer.common;

import com.thewinterframework.component.ComponentUtils;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;
import org.spongepowered.configurate.serialize.TypeSerializer;

import java.lang.reflect.Type;

/**
 * A {@link TypeSerializer} for {@link Component}s.
 */
public class ComponentSerializer implements TypeSerializer<Component> {
	@Override
	public Component deserialize(@NotNull final Type type, final ConfigurationNode node) throws SerializationException {
		if (node.virtual() || node.raw() == null) {
			return null;
		}
		if (node.isList() || node.isMap()) {
			throw new SerializationException(node, type, "Expected a single MiniMessage text value");
		}

		final var stringValue = node.getString();
		if (stringValue == null) {
			throw new SerializationException(node, type, "Expected a single MiniMessage text value");
		}

		return ComponentUtils.miniMessage(stringValue);
	}

	@Override
	public void serialize(@NotNull final Type type, @Nullable final Component obj, @NotNull final ConfigurationNode node) throws SerializationException {
		if (obj == null) {
			node.set(null);
			return;
		}

		node.set(String.class, ComponentUtils.miniMessage(obj));
	}
}
