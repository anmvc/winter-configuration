package com.thewinterframework.configurate.config;

/** Derives Java identifiers from configuration file names for generated code. */
final class ConfigurateIdentifiers {

	private ConfigurateIdentifiers() {}

	/**
	 * Turns a configuration file name such as {@code my-config.v2} into a valid Java identifier
	 * fragment such as {@code my_config_v2}. Characters outside ASCII letters and digits become
	 * underscores, and a leading digit gets an underscore prefix.
	 *
	 * @param fileName the configuration file name, without directories
	 * @return a non-empty identifier fragment
	 */
	static String toIdentifier(final String fileName) {
		final var builder = new StringBuilder(fileName.length() + 1);
		for (int i = 0; i < fileName.length(); i++) {
			final char c = fileName.charAt(i);
			final boolean valid = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
			builder.append(valid ? c : '_');
		}
		if (builder.isEmpty() || Character.isDigit(builder.charAt(0))) {
			builder.insert(0, '_');
		}
		return builder.toString();
	}
}
