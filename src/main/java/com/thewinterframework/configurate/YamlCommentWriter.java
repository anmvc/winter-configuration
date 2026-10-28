package com.thewinterframework.configurate;

import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Writes YAML files while preserving the user's comments, custom keys, blank lines, and
 * formatting.
 *
 * <p>The existing file is parsed into an ordered tree of raw lines and key entries, so the output
 * keeps the original order. Only values that differ from the file are rewritten: unchanged entries
 * are copied verbatim, changed scalars are re-emitted on their key line, changed lists and maps are
 * re-emitted in full, keys removed from the configuration are dropped, and new keys are appended at
 * the end of their parent mapping. Files are replaced atomically.</p>
 */
final class YamlCommentWriter {

	/** Plain scalars that YAML 1.1 parsers would not read back as strings. */
	private static final Pattern RESERVED_PLAIN = Pattern.compile(
			"(?i)(true|false|yes|no|on|off|y|n|null|~|=|<<)"
					+ "|[-+]?(\\.[0-9]+|[0-9][0-9_]*(\\.[0-9_]*)?)([eE][-+]?[0-9]+)?"
					+ "|[-+]?0[xX][0-9a-fA-F_]+|[-+]?0[bB][01_]+|[-+]?0[oO]?[0-7_]+"
					+ "|[-+]?[0-9][0-9_]*(:[0-5]?[0-9])+(\\.[0-9_]*)?"
					+ "|[-+]?\\.(inf|Inf|INF)|\\.(nan|NaN|NAN)"
					+ "|[0-9]{4}-[0-9]{1,2}-[0-9]{1,2}.*"
	);

	private YamlCommentWriter() {}

	static void writeWithComments(
			final Path configPath,
			final ConfigurationNode configNode
	) throws IOException {
		final List<String> userLines = Files.exists(configPath)
				? Files.readAllLines(configPath, StandardCharsets.UTF_8)
				: List.of();
		// The file's own values are the baseline: a value that differs from them has been changed.
		final ConfigurationNode fileNode = YamlConfigurationLoader.builder()
				.buildAndLoadString(String.join("\n", userLines));

		final Mapping document = parseMapping(userLines, 0, userLines.size());
		final List<String> out = new ArrayList<>();
		renderMapping(document, fileNode, configNode, 0, out);
		writeAtomically(configPath, out);
	}

	// ----------------------------------------------------------------------------------------
	// Document model
	// ----------------------------------------------------------------------------------------

	/** A block mapping: its items keep the order of the file. */
	private static final class Mapping {
		final List<Item> items = new ArrayList<>();
		int indent = -1;
	}

	private sealed interface Item permits Raw, Entry {}

	/** A line kept as is: blank lines, comments, directives and anything not understood. */
	private record Raw(String line) implements Item {}

	/** A {@code key:} line with the lines that belong to its value. */
	private static final class Entry implements Item {
		final String key;
		final String keyLine;
		/** Inline comment on the key line, including the leading {@code #}, or null. */
		final String comment;
		/** Raw lines of the value when it is not a block mapping (scalars, block scalars, lists, flows). */
		final List<String> body;
		/** Nested block mapping, when the value is one. */
		final Mapping child;

		Entry(final String key, final String keyLine, final String comment, final List<String> body, final Mapping child) {
			this.key = key;
			this.keyLine = keyLine;
			this.comment = comment;
			this.body = body;
			this.child = child;
		}
	}

	private static Mapping parseMapping(final List<String> lines, final int start, final int end) {
		final var mapping = new Mapping();
		int i = start;
		while (i < end) {
			final var line = lines.get(i);
			final var trimmed = line.strip();
			final var indent = countLeadingSpaces(line);
			final var colon = trimmed.startsWith("-") || trimmed.startsWith("#") ? -1 : findYamlKeyColon(trimmed);
			if (colon < 0 || (mapping.indent >= 0 && indent != mapping.indent)) {
				mapping.items.add(new Raw(line));
				i++;
				continue;
			}
			if (mapping.indent < 0) {
				mapping.indent = indent;
			}

			final var key = unquote(trimmed.substring(0, colon).strip());
			final var afterColon = trimmed.substring(colon + 1);
			final var hasValue = hasInlineValue(afterColon);

			// The value extends over deeper-indented lines. A block sequence may also sit at the key's indent.
			int bodyEnd = i + 1;
			int scan = i + 1;
			while (scan < end) {
				final var candidate = lines.get(scan);
				final var candidateTrimmed = candidate.strip();
				if (candidateTrimmed.isEmpty()) {
					scan++;
					continue;
				}
				final var candidateIndent = countLeadingSpaces(candidate);
				final boolean belongs = candidateIndent > indent
						|| (candidateIndent == indent && !hasValue && candidateTrimmed.startsWith("-"));
				if (!belongs) {
					break;
				}
				scan++;
				bodyEnd = scan;
			}

			final var body = new ArrayList<>(lines.subList(i + 1, bodyEnd));
			Mapping child = null;
			if (!hasValue) {
				final var firstContent = firstContentLine(body);
				if (firstContent != null) {
					final var contentIndent = countLeadingSpaces(firstContent);
					final var contentTrimmed = firstContent.strip();
					if (contentIndent > indent && !contentTrimmed.startsWith("-") && findYamlKeyColon(contentTrimmed) >= 0) {
						child = parseMapping(lines, i + 1, bodyEnd);
						body.clear();
					}
				}
			}

			mapping.items.add(new Entry(key, line, extractInlineComment(afterColon), body, child));
			i = bodyEnd;
		}
		return mapping;
	}

	private static String firstContentLine(final List<String> lines) {
		for (final var line : lines) {
			final var trimmed = line.strip();
			if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
				return line;
			}
		}
		return null;
	}

	/** Returns true if text after the key colon holds a value rather than only a comment or nothing. */
	private static boolean hasInlineValue(final String afterColon) {
		final var comment = inlineCommentIndex(afterColon);
		final var value = comment >= 0 ? afterColon.substring(0, comment) : afterColon;
		return !value.isBlank();
	}

	// ----------------------------------------------------------------------------------------
	// Rendering
	// ----------------------------------------------------------------------------------------

	private static void renderMapping(
			final Mapping mapping,
			final ConfigurationNode file,
			final ConfigurationNode live,
			final int defaultIndent,
			final List<String> out
	) {
		final var indent = mapping.indent >= 0 ? mapping.indent : defaultIndent;
		final var written = new HashSet<String>();
		int lastEntry = -1;
		for (int i = 0; i < mapping.items.size(); i++) {
			if (mapping.items.get(i) instanceof Entry) {
				lastEntry = i;
			}
		}

		for (int i = 0; i < mapping.items.size(); i++) {
			final var item = mapping.items.get(i);
			if (item instanceof Raw raw) {
				out.add(raw.line());
			} else if (item instanceof Entry entry) {
				written.add(entry.key);
				renderEntry(entry, file, live, indent, out);
			}
			if (i == lastEntry) {
				renderNewKeys(live, written, indent, out);
			}
		}
		if (lastEntry < 0) {
			renderNewKeys(live, written, indent, out);
		}
	}

	private static void renderEntry(
			final Entry entry,
			final ConfigurationNode file,
			final ConfigurationNode live,
			final int indent,
			final List<String> out
	) {
		if (!live.isMap()) {
			return; // nothing left in the configuration for this mapping
		}
		final var current = live.node(entry.key);
		if (current.virtual()) {
			// Removed from the configuration (for example a field set to null): drop the key.
			return;
		}
		final var fileChild = file != null && file.isMap() ? file.node(entry.key) : null;

		if (entry.child != null && current.isMap()) {
			out.add(entry.keyLine);
			renderMapping(entry.child, fileChild, current, indent + 2, out);
		} else if (sameValue(fileChild, current)) {
			out.add(entry.keyLine);
			out.addAll(entry.body);
		} else {
			emitEntry(entry.comment, entry.key, current, indent, out);
		}
	}

	private static void renderNewKeys(
			final ConfigurationNode live,
			final Set<String> written,
			final int indent,
			final List<String> out
	) {
		if (!live.isMap()) {
			return;
		}
		for (final Map.Entry<Object, ? extends ConfigurationNode> child : live.childrenMap().entrySet()) {
			final var key = String.valueOf(child.getKey());
			if (!written.contains(key)) {
				emitEntry(null, key, child.getValue(), indent, out);
			}
		}
	}

	/** Emits {@code key: value} (or a block for maps and lists), optionally keeping an existing inline comment. */
	private static void emitEntry(
			final String comment,
			final String key,
			final ConfigurationNode value,
			final int indent,
			final List<String> out
	) {
		final var suffix = comment == null ? "" : " " + comment;
		final var prefix = " ".repeat(indent) + formatKey(key) + ":";
		if (value.isMap()) {
			if (value.childrenMap().isEmpty()) {
				out.add(prefix + " {}" + suffix);
			} else {
				out.add(prefix + suffix);
				emitMapBody(value, indent + 2, out);
			}
		} else if (value.isList()) {
			if (value.childrenList().isEmpty()) {
				out.add(prefix + " []" + suffix);
			} else {
				out.add(prefix + suffix);
				emitListBody(value, indent + 2, out);
			}
		} else {
			out.add(prefix + " " + formatScalar(value.raw()) + suffix);
		}
	}

	private static void emitMapBody(final ConfigurationNode map, final int indent, final List<String> out) {
		for (final Map.Entry<Object, ? extends ConfigurationNode> child : map.childrenMap().entrySet()) {
			emitEntry(null, String.valueOf(child.getKey()), child.getValue(), indent, out);
		}
	}

	private static void emitListBody(final ConfigurationNode list, final int indent, final List<String> out) {
		for (final var child : list.childrenList()) {
			emitListItem(child, indent, out);
		}
	}

	private static void emitListItem(final ConfigurationNode value, final int indent, final List<String> out) {
		final var pad = " ".repeat(indent);
		final boolean nonEmptyMap = value.isMap() && !value.childrenMap().isEmpty();
		final boolean nonEmptyList = value.isList() && !value.childrenList().isEmpty();
		if (nonEmptyMap || nonEmptyList) {
			final var nested = new ArrayList<String>();
			if (nonEmptyMap) {
				emitMapBody(value, indent + 2, nested);
			} else {
				emitListBody(value, indent + 2, nested);
			}
			// The first nested line starts at indent + 2, so it can carry the dash.
			nested.set(0, pad + "- " + nested.get(0).substring(indent + 2));
			out.addAll(nested);
		} else if (value.isMap()) {
			out.add(pad + "- {}");
		} else if (value.isList()) {
			out.add(pad + "- []");
		} else {
			out.add(pad + "- " + formatScalar(value.raw()));
		}
	}

	/** Compares values semantically; a file value that is missing or differs is reported as changed. */
	private static boolean sameValue(final ConfigurationNode fileValue, final ConfigurationNode liveValue) {
		return ConfigurationValues.sameValue(fileValue, liveValue);
	}

	// ----------------------------------------------------------------------------------------
	// Scalars and keys
	// ----------------------------------------------------------------------------------------

	private static String formatKey(final Object key) {
		final var str = String.valueOf(key);
		return needsQuoting(str) ? doubleQuote(str) : str;
	}

	static String formatScalar(final Object value) {
		if (value == null) {
			return "null";
		}
		if (value instanceof Boolean || value instanceof Integer || value instanceof Long
				|| value instanceof Short || value instanceof Byte) {
			return value.toString();
		}
		if (value instanceof Double || value instanceof Float) {
			final double number = ((Number) value).doubleValue();
			if (Double.isNaN(number)) {
				return ".nan";
			}
			if (Double.isInfinite(number)) {
				return number > 0 ? ".inf" : "-.inf";
			}
			return value.toString();
		}
		if (value instanceof Number) {
			return value.toString();
		}
		final var str = value.toString();
		return needsQuoting(str) ? doubleQuote(str) : str;
	}

	private static boolean needsQuoting(final String value) {
		if (value.isEmpty() || !value.equals(value.strip())) {
			return true;
		}
		if ("-?:,[]{}#&*!|>'\"%@`".indexOf(value.charAt(0)) >= 0) {
			return true;
		}
		for (int i = 0; i < value.length(); i++) {
			final char c = value.charAt(i);
			if (c < 0x20 || c == 0x7f || c == ':' || c == '#' || c == '[' || c == ']' || c == '{' || c == '}'
					|| c == ',' || c == '&' || c == '*' || c == '!' || c == '|' || c == '>' || c == '\''
					|| c == '"' || c == '%' || c == '@' || c == '`') {
				return true;
			}
		}
		return RESERVED_PLAIN.matcher(value).matches();
	}

	private static String doubleQuote(final String value) {
		final var builder = new StringBuilder("\"");
		for (int i = 0; i < value.length(); i++) {
			final char c = value.charAt(i);
			switch (c) {
				case '\\' -> builder.append("\\\\");
				case '"' -> builder.append("\\\"");
				case '\n' -> builder.append("\\n");
				case '\r' -> builder.append("\\r");
				case '\t' -> builder.append("\\t");
				default -> {
					if (c < 0x20 || c == 0x7f) {
						builder.append(String.format("\\x%02X", (int) c));
					} else {
						builder.append(c);
					}
				}
			}
		}
		return builder.append('"').toString();
	}

	private static String unquote(final String str) {
		if (str.length() >= 2) {
			if ((str.startsWith("'") && str.endsWith("'")) || (str.startsWith("\"") && str.endsWith("\""))) {
				return str.substring(1, str.length() - 1);
			}
		}
		return str;
	}

	// ----------------------------------------------------------------------------------------
	// Lexical helpers
	// ----------------------------------------------------------------------------------------

	private static int countLeadingSpaces(final String line) {
		int count = 0;
		while (count < line.length() && line.charAt(count) == ' ') {
			count++;
		}
		return count;
	}

	/**
	 * Finds a colon that is a valid YAML key separator: not inside quotes,
	 * and followed by a space or at the end of the string.
	 * This prevents matching colons inside values like MiniMessage tags or URLs.
	 */
	private static int findYamlKeyColon(final String str) {
		boolean inSingleQuote = false;
		boolean inDoubleQuote = false;
		for (int i = 0; i < str.length(); i++) {
			final char c = str.charAt(i);
			if (c == '#' && !inSingleQuote && !inDoubleQuote && (i == 0 || str.charAt(i - 1) == ' ')) {
				return -1;
			}
			if (c == '\'' && !inDoubleQuote) {
				inSingleQuote = !inSingleQuote;
			} else if (c == '"' && !inSingleQuote) {
				inDoubleQuote = !inDoubleQuote;
			} else if (c == ':' && !inSingleQuote && !inDoubleQuote) {
				if (i + 1 == str.length() || str.charAt(i + 1) == ' ') {
					return i;
				}
			}
		}
		return -1;
	}

	/** Returns the index of a {@code #} that starts an inline comment, or -1. */
	private static int inlineCommentIndex(final String value) {
		boolean inSingleQuote = false;
		boolean inDoubleQuote = false;
		for (int i = 0; i < value.length(); i++) {
			final char c = value.charAt(i);
			if (c == '\'' && !inDoubleQuote) {
				inSingleQuote = !inSingleQuote;
			} else if (c == '"' && !inSingleQuote) {
				inDoubleQuote = !inDoubleQuote;
			} else if (c == '#' && !inSingleQuote && !inDoubleQuote && (i == 0 || value.charAt(i - 1) == ' ')) {
				return i;
			}
		}
		return -1;
	}

	private static String extractInlineComment(final String value) {
		final var index = inlineCommentIndex(value);
		return index < 0 ? null : value.substring(index);
	}

	// ----------------------------------------------------------------------------------------
	// Output
	// ----------------------------------------------------------------------------------------

	/** Writes to a temporary file in the same directory and moves it into place, so readers never see a partial file. */
	private static void writeAtomically(final Path path, final List<String> lines) throws IOException {
		final var absolute = path.toAbsolutePath();
		final var parent = absolute.getParent();
		final var content = lines.isEmpty() ? "" : String.join("\n", lines) + "\n";
		final var temp = Files.createTempFile(parent, absolute.getFileName().toString(), ".tmp");
		try {
			Files.writeString(temp, content, StandardCharsets.UTF_8);
			if (Files.exists(absolute)) {
				try {
					Files.setPosixFilePermissions(temp, Files.getPosixFilePermissions(absolute));
				} catch (final UnsupportedOperationException | IOException ignored) {
					// Not a POSIX file system; keep the default permissions.
				}
			}
			try {
				Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (final AtomicMoveNotSupportedException exception) {
				Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(temp);
		}
	}
}
