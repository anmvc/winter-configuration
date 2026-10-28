package com.thewinterframework.configurate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.NOPLogger;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContainerSaveTest {

	private static final String YAML = """
			# Settings for the plugin
			label: original # shown in chat
			host: "${WINTER_CONFIG_TEST_VALUE}"
			port: 25565
			servers:
			  - "${WINTER_CONFIG_TEST_VALUE}"
			  - plain
			  - "${WINTER_CONFIG_TEST_PORT}"
			""";

	@TempDir
	Path directory;

	private Path file;

	@BeforeEach
	void setUp() {
		file = directory.resolve("config.yml");
	}

	@Test
	void saveWritesChangesMadeInPlaceToTheObject() throws IOException {
		write(YAML);
		final var container = load();

		container.get().label = "changed in place";
		assertTrue(container.save());

		final var text = Files.readString(file);
		assertEquals("changed in place", saved().node("label").getString());
		assertTrue(text.contains("# Settings for the plugin"), text);
		assertTrue(text.contains("label: \"changed in place\" # shown in chat") || text.contains("label: changed in place # shown in chat"), text);
		assertEquals("${WINTER_CONFIG_TEST_VALUE}", saved().node("host").getString());
	}

	@Test
	void saveKeepsNodeLevelEditsAndInPlaceChangesTogether() throws IOException {
		write(YAML);
		final var container = load();

		container.getNode().node("port").set(25570);
		container.get().label = "in place";
		assertTrue(container.save());

		assertEquals(25570, saved().node("port").getInt());
		assertEquals("in place", saved().node("label").getString());
	}

	@Test
	void saveWithoutChangesLeavesTheFileUntouched() throws IOException {
		write(YAML);
		final var container = load();

		assertTrue(container.save());

		assertEquals(YAML, Files.readString(file));
	}

	@Test
	void updateRestoresThePreviousStateWhenTheUpdaterFails() throws IOException {
		write(YAML);
		final var container = load();

		assertFalse(container.update(config -> {
			config.label = "partial";
			throw new IllegalStateException("boom");
		}));

		assertEquals("original", container.get().label);
		assertEquals("original", container.getNode().node("label").getString());
		assertEquals("original", saved().node("label").getString());
	}

	@Test
	void listenersAreNotifiedWithTheNewObjectAfterAReload() throws IOException {
		write(YAML);
		final var container = load();
		final List<TestConfig> seen = new ArrayList<>();
		container.addChangeListener(seen::add);

		write(YAML.replace("label: original", "label: reloaded"));
		assertTrue(container.reload());

		assertEquals(1, seen.size());
		assertSame(container.get(), seen.get(0));
		assertEquals("reloaded", seen.get(0).label);
	}

	@Test
	void listenersAreNotifiedWithTheRestoredObjectWhenAnUpdateFails() throws IOException {
		write(YAML);
		final var container = load();
		final List<String> labels = new ArrayList<>();
		container.addChangeListener(config -> labels.add(config.label));

		assertFalse(container.update(config -> {
			config.label = "partial";
			throw new IllegalStateException("boom");
		}));

		assertEquals(List.of("original"), labels);
	}

	@Test
	void listenersAreNotifiedAfterASuccessfulUpdate() throws IOException {
		write(YAML);
		final var container = load();
		final List<String> labels = new ArrayList<>();
		container.addChangeListener(config -> labels.add(config.label));

		assertTrue(container.update(config -> {
			config.label = "updated";
			return config;
		}));

		assertEquals(List.of("updated"), labels);
	}

	@Test
	void updateRestoresThePreviousStateWhenTheFileCannotBeWritten() throws IOException {
		write(YAML);
		final var container = load();
		final var previousLabel = container.get().label;
		Files.delete(file);
		Files.delete(directory);

		assertFalse(container.update(config -> {
			config.label = "not saved";
			return config;
		}));

		assertEquals(previousLabel, container.get().label);
		assertEquals("original", container.getNode().node("label").getString());
	}

	@Test
	void emptyDocumentYieldsTheDefaultsInsteadOfNull() throws IOException {
		write("");

		final var container = load();

		assertNotNull(container.get());
		assertEquals("default", container.get().label);
	}

	@Test
	void referencesOfRemainingListElementsSurviveARemoval() throws IOException {
		write(YAML);
		final var container = load();

		assertTrue(container.update(config -> {
			config.servers.remove(1);
			return config;
		}));

		final var servers = saved().node("servers");
		assertEquals("${WINTER_CONFIG_TEST_VALUE}", servers.node(0).getString());
		assertEquals("${WINTER_CONFIG_TEST_PORT}", servers.node(1).getString());
		assertEquals(2, servers.childrenList().size());
		assertFalse(Files.readString(file).contains("test-secret"));
	}

	@Test
	void referencesSurviveAnInsertionAtTheStartOfAList() throws IOException {
		write(YAML);
		final var container = load();

		assertTrue(container.update(config -> {
			config.servers.addFirst("new");
			return config;
		}));

		final var servers = saved().node("servers");
		assertEquals("new", servers.node(0).getString());
		assertEquals("${WINTER_CONFIG_TEST_VALUE}", servers.node(1).getString());
		assertEquals("plain", servers.node(2).getString());
		assertEquals("${WINTER_CONFIG_TEST_PORT}", servers.node(3).getString());
	}

	@Test
	void reloadAfterSaveReadsTheSavedValues() throws IOException {
		write(YAML);
		final var container = load();
		container.get().label = "reloaded";
		assertTrue(container.save());

		assertTrue(container.reload());

		assertEquals("reloaded", container.get().label);
		assertEquals("test-secret", container.get().host);
	}

	private void write(final String yaml) throws IOException {
		Files.writeString(file, yaml);
	}

	private org.spongepowered.configurate.ConfigurationNode saved() throws IOException {
		return YamlConfigurationLoader.builder().path(file).build().load();
	}

	private Container<TestConfig> load() throws IOException {
		return Container.load(NOPLogger.NOP_LOGGER, directory, TestConfig.class, "config");
	}

	@ConfigSerializable
	public static class TestConfig {
		public String label = "default";
		public String host;
		public int port;
		public List<String> servers = new ArrayList<>();
	}
}
