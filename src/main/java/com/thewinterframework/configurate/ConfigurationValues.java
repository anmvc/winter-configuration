package com.thewinterframework.configurate;

import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;

import java.util.ArrayList;
import java.util.Objects;

/** Helpers for comparing and merging configuration node trees by value. */
final class ConfigurationValues {

	private ConfigurationValues() {}

	/**
	 * Compares two nodes by value, ignoring their keys and list indices.
	 * A missing (virtual) node only equals another missing node.
	 */
	static boolean sameValue(final ConfigurationNode a, final ConfigurationNode b) {
		if (a == null || b == null) {
			return false;
		}
		if (a.virtual() || b.virtual()) {
			return a.virtual() && b.virtual();
		}
		if (a.isMap() && b.isMap()) {
			final var aChildren = a.childrenMap();
			final var bChildren = b.childrenMap();
			if (aChildren.size() != bChildren.size()) {
				return false;
			}
			for (final var entry : aChildren.entrySet()) {
				final var other = bChildren.get(entry.getKey());
				if (other == null || !sameValue(entry.getValue(), other)) {
					return false;
				}
			}
			return true;
		}
		if (a.isList() && b.isList()) {
			final var aChildren = a.childrenList();
			final var bChildren = b.childrenList();
			if (aChildren.size() != bChildren.size()) {
				return false;
			}
			for (int i = 0; i < aChildren.size(); i++) {
				if (!sameValue(aChildren.get(i), bChildren.get(i))) {
					return false;
				}
			}
			return true;
		}
		if (a.isMap() || b.isMap() || a.isList() || b.isList()) {
			return false;
		}
		return Objects.equals(a.raw(), b.raw());
	}

	/**
	 * Applies the changes made between {@code base} and {@code updated} to {@code root}.
	 *
	 * <p>Only parts of {@code root} whose value differs from {@code base} are touched, so node-level
	 * edits and environment references in unchanged parts are kept. Changed scalars and lists take
	 * the updated value, keeping references to the unchanged list elements.</p>
	 *
	 * @param root the node to update in place
	 * @param base the object-derived state that {@code root} was last synchronized with
	 * @param updated the object-derived state after the change
	 * @throws SerializationException if a value cannot be stored
	 */
	static void merge(
			final ConfigurationNode root,
			final ConfigurationNode base,
			final ConfigurationNode updated
	) throws SerializationException {
		if (root.isMap() && base.isMap() && updated.isMap()) {
			mergeMaps(root, base, updated);
		} else {
			mergeValue(root, base, updated);
		}
	}

	private static void mergeValue(
			final ConfigurationNode target,
			final ConfigurationNode base,
			final ConfigurationNode updated
	) throws SerializationException {
		if (sameValue(base, updated)) {
			return;
		}
		if (target.isMap() && base.isMap() && updated.isMap()) {
			mergeMaps(target, base, updated);
			return;
		}
		final var original = target.copy();
		final var next = updated.copy();
		EnvironmentVariables.preserveExpressions(original, base, next);
		target.from(next);
	}

	private static void mergeMaps(
			final ConfigurationNode target,
			final ConfigurationNode base,
			final ConfigurationNode updated
	) throws SerializationException {
		// Keys the object no longer has (for example fields set to null) are removed.
		for (final var key : new ArrayList<>(base.childrenMap().keySet())) {
			if (!updated.childrenMap().containsKey(key)) {
				target.removeChild(key);
			}
		}
		for (final var entry : updated.childrenMap().entrySet()) {
			final var key = entry.getKey();
			final var updatedChild = entry.getValue();
			final var baseChild = base.childrenMap().get(key);
			final var targetChild = target.childrenMap().get(key);
			if (baseChild != null && targetChild != null) {
				mergeValue(targetChild, baseChild, updatedChild);
			} else {
				target.node(key).set(updatedChild.copy());
			}
		}
	}
}
