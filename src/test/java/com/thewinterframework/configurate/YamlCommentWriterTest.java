package com.thewinterframework.configurate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.spongepowered.configurate.BasicConfigurationNode;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class YamlCommentWriterTest {

	private static final String DOCUMENT = """
			# Header comment

			server:
			  # The port to listen on
			  port: 25565 # default
			  motd: |
			    Hello
			    world
			  # Comment before the trailing key
			  endpoint: http://localhost:8080/api
			names:
			  - "${WINTER_CONFIG_TEST_VALUE}"
			  - plain  # inline comment
			replicas:
			  - host: first
			    weight: 1
			  - host: second
			    weight: 2
			custom: keep
			# Trailing comment
			""";

	@TempDir
	Path directory;

	private Path file;

	@BeforeEach
	void setUp() {
		file = directory.resolve("config.yml");
	}

	@Test
	void unchangedFileIsKeptByteForByte() throws IOException {
		write(DOCUMENT);

		YamlCommentWriter.writeWithComments(file, load());

		assertEquals(DOCUMENT, Files.readString(file));
	}

	@Test
	void changedScalarKeepsItsCommentAndPosition() throws IOException {
		write(DOCUMENT);
		final var node = load();

		node.node("server", "port").set(25570);
		YamlCommentWriter.writeWithComments(file, node);

		final var text = Files.readString(file);
		assertTrue(text.contains("  # The port to listen on\n  port: 25570 # default\n  motd: |\n"), text);
		assertEquals(25570, saved().node("server", "port").getInt());
		assertEquals("Hello\nworld\n", saved().node("server", "motd").getString());
	}

	@Test
	void changedListIsRewrittenAndOtherLinesAreKept() throws IOException {
		write(DOCUMENT);
		final var node = load();

		node.node("names").set(List.of("first", "second", "third"));
		YamlCommentWriter.writeWithComments(file, node);

		assertEquals(List.of("first", "second", "third"), saved().node("names").getList(String.class));
		assertTrue(Files.readString(file).contains("# Trailing comment"));
		assertEquals("http://localhost:8080/api", saved().node("server", "endpoint").getString());
		assertEquals("keep", saved().node("custom").getString());
	}

	@Test
	void listOfMapsIsRewritten() throws IOException {
		write(DOCUMENT);
		final var node = load();

		node.node("replicas").set(List.of(Map.of("host", "alpha", "weight", 3)));
		YamlCommentWriter.writeWithComments(file, node);

		final var replicas = saved().node("replicas");
		assertEquals(1, replicas.childrenList().size());
		assertEquals("alpha", replicas.node(0, "host").getString());
		assertEquals(3, replicas.node(0, "weight").getInt());
	}

	@Test
	void keyRemovedFromTheNodeIsDroppedFromTheFile() throws IOException {
		write(DOCUMENT);
		final var node = load();

		node.node("custom").set(null);
		YamlCommentWriter.writeWithComments(file, node);

		assertFalse(Files.readString(file).contains("custom:"));
		assertTrue(saved().node("custom").virtual());
		assertEquals("plain", saved().node("names", 1).getString());
	}

	@Test
	void newKeysAreAppendedToTheirMappingBeforeTrailingComments() throws IOException {
		write("a: 1\n# end comment\n");
		final var node = load();

		node.node("b").set(2);
		YamlCommentWriter.writeWithComments(file, node);

		assertEquals("a: 1\nb: 2\n# end comment\n", Files.readString(file));
	}

	@Test
	void nestedNewKeysAreAppendedToTheNestedMapping() throws IOException {
		write("server:\n  port: 1\nother: 2\n");
		final var node = load();

		node.node("server", "host").set("localhost");
		YamlCommentWriter.writeWithComments(file, node);

		assertEquals("server:\n  port: 1\n  host: localhost\nother: 2\n", Files.readString(file));
	}

	@Test
	void changedBlockScalarIsRewrittenAsAValidValue() throws IOException {
		write(DOCUMENT);
		final var node = load();

		node.node("server", "motd").set("Bye\nnow: soon");
		YamlCommentWriter.writeWithComments(file, node);

		assertEquals("Bye\nnow: soon", saved().node("server", "motd").getString());
		assertEquals(25565, saved().node("server", "port").getInt());
	}

	@Test
	void ambiguousScalarsAreWrittenAsStrings() throws IOException {
		write("placeholder: 1\n");
		final var node = load();
		final List<String> values = List.of(
				"yes", "on", "off", "no", "y", "123", "1.5", "0x1F", "2024-01-01", "true", "null", "~",
				"", " padded ", "a: b", "# hash", "tab\there", "multi\nline", "quote\"s", "back\\slash", "ends:"
		);
		for (int i = 0; i < values.size(); i++) {
			node.node("value" + i).set(values.get(i));
		}
		node.node("number").set(123);

		YamlCommentWriter.writeWithComments(file, node);

		final var reloaded = saved();
		for (int i = 0; i < values.size(); i++) {
			assertEquals(values.get(i), reloaded.node("value" + i).raw(), "value" + i);
		}
		assertEquals(123, reloaded.node("number").getInt());
	}

	@Test
	void emptyFileReceivesTheWholeNode() throws IOException {
		write("");
		final var node = BasicConfigurationNode.root();
		node.node("name").set("example");
		node.node("nested", "enabled").set(true);
		node.node("list").set(List.of("a", "b"));

		YamlCommentWriter.writeWithComments(file, node);

		final var reloaded = saved();
		assertEquals("example", reloaded.node("name").getString());
		assertTrue(reloaded.node("nested", "enabled").getBoolean());
		assertEquals(List.of("a", "b"), reloaded.node("list").getList(String.class));
	}

	@Test
	void writingLeavesNoTemporaryFilesBehind() throws IOException {
		write(DOCUMENT);
		final var node = load();
		node.node("server", "port").set(1);

		YamlCommentWriter.writeWithComments(file, node);

		try (final var files = Files.list(directory)) {
			assertEquals(List.of(file), files.toList());
		}
	}

	private void write(final String yaml) throws IOException {
		Files.writeString(file, yaml);
	}

	private ConfigurationNode load() throws IOException {
		return YamlConfigurationLoader.builder().path(file).build().load();
	}

	private ConfigurationNode saved() throws IOException {
		return load();
	}
}
