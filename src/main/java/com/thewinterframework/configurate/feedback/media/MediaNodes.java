package com.thewinterframework.configurate.feedback.media;

import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;

import java.lang.reflect.Type;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;

/**
 * Reads values of feedback media from configuration nodes. Problems are reported as
 * {@link SerializationException}s that name the offending key, instead of unchecked exceptions.
 */
public final class MediaNodes {

    private MediaNodes() {}

    /**
     * Reads a required scalar value as a string.
     *
     * @param node the node that contains the key
     * @param key the key to read
     * @param type the type being deserialized, for error messages
     * @return the string value
     * @throws SerializationException if the value is missing or not a scalar
     */
    public static String requiredString(final ConfigurationNode node, final String key, final Type type) throws SerializationException {
        final var child = node.node(key);
        if (child.virtual() || child.raw() == null) {
            throw new SerializationException(node, type, "Missing required '" + key + "'");
        }
        final var value = child.getString();
        if (value == null) {
            throw new SerializationException(child, type, "Expected a single value for '" + key + "'");
        }
        return value;
    }

    /**
     * Reads an enum constant by name, ignoring case. A missing value yields the fallback.
     *
     * @param node the node that contains the key
     * @param key the key to read
     * @param enumType the enum class
     * @param fallback the value to use when the key is missing
     * @param type the type being deserialized, for error messages
     * @return the enum constant
     * @throws SerializationException if the value does not name a constant
     */
    public static <E extends Enum<E>> E enumValue(
            final ConfigurationNode node,
            final String key,
            final Class<E> enumType,
            final E fallback,
            final Type type
    ) throws SerializationException {
        final var child = node.node(key);
        if (child.virtual() || child.raw() == null) {
            return fallback;
        }
        final var name = child.getString();
        if (name == null) {
            throw new SerializationException(child, type, "Expected a single value for '" + key + "'");
        }
        try {
            return Enum.valueOf(enumType, name.strip().toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException exception) {
            throw new SerializationException(child, type, "Invalid " + key + " '" + name + "', expected one of "
                    + Arrays.toString(enumType.getEnumConstants()));
        }
    }

    /**
     * Reads a non-negative number of seconds, which may have a fraction (for example {@code 0.5}).
     *
     * @param node the node that contains the key
     * @param key the key to read
     * @param fallbackSeconds the number of seconds to use when the key is missing
     * @param type the type being deserialized, for error messages
     * @return the duration, rounded to the millisecond
     * @throws SerializationException if the value is not a finite, non-negative number
     */
    public static Duration seconds(
            final ConfigurationNode node,
            final String key,
            final double fallbackSeconds,
            final Type type
    ) throws SerializationException {
        final var child = node.node(key);
        if (child.virtual() || child.raw() == null) {
            return Duration.ofMillis(Math.round(fallbackSeconds * 1000));
        }
        final var raw = child.raw();
        final double seconds;
        if (raw instanceof Number number) {
            seconds = number.doubleValue();
        } else {
            try {
                seconds = Double.parseDouble(String.valueOf(raw).strip());
            } catch (final NumberFormatException exception) {
                throw new SerializationException(child, type, "Expected a number of seconds for '" + key + "' but was '" + raw + "'");
            }
        }
        if (!Double.isFinite(seconds) || seconds < 0) {
            throw new SerializationException(child, type, "'" + key + "' must be a non-negative number of seconds");
        }
        return Duration.ofMillis(Math.round(seconds * 1000));
    }

    /**
     * Converts a duration to seconds for writing: a whole number when possible, otherwise a decimal.
     *
     * @param duration the duration to convert
     * @return the number of seconds
     */
    public static Object toSeconds(final Duration duration) {
        final long millis = duration.toMillis();
        return millis % 1000 == 0 ? (Object) (millis / 1000) : (Object) (millis / 1000.0d);
    }
}
