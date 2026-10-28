package com.thewinterframework.configurate;

import io.leangen.geantyref.TypeToken;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.ConfigurationOptions;
import org.spongepowered.configurate.objectmapping.ObjectMapper;
import org.spongepowered.configurate.serialize.SerializationException;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * A container for a configuration file.
 *
 * <p>String values support {@code ${VARIABLE}} environment references and
 * {@code ${VARIABLE:-default}} fallbacks for missing or empty variables.
 * Use {@code $${VARIABLE}} for a literal reference. Resolution happens before
 * deserialization on load and reload, including nested maps and lists.
 * Missing variables without a fallback fail the load. Values are resolved
 * once and are never parsed as YAML.</p>
 *
 * <p>The backing node retains the original references. Saving or updating
 * unrelated fields preserves them; explicitly changing a referenced field
 * through {@link #update(UnaryOperator)} or {@link #save()} replaces its reference.</p>
 *
 * <p>{@link #save()} also persists changes made in place to the object returned by {@link #get()}.
 * Changes are detected by comparing the object with the state it had when it was last loaded, reloaded,
 * or saved. Values are written to the file with their existing comments and formatting kept. Only
 * changed values are rewritten.</p>
 *
 * <p>Instances are safe to use from several threads: {@link #reload()}, {@link #update(UnaryOperator)}
 * and {@link #save()} are serialized. A failed update or save leaves the previous configuration in
 * place.</p>
 *
 * @param <C> the type of the configuration object
 */
public final class Container<C> {
	private final AtomicReference<C> config;
	private final YamlConfigurationLoader loader;
	private final ConfigurationNode node;
	private final Class<C> clazz;
	private final TypeToken<C> typeToken;
	private final ObjectMapper<C> mapper;
	private final Logger logger;
	private final Path configPath;
	private final List<Consumer<? super C>> changeListeners = new CopyOnWriteArrayList<>();
	/** Object-derived state at the last load, reload, update, or save. Guarded by {@code this}. */
	private ConfigurationNode snapshot;

	private Container(
			final C config,
			final Class<C> clazz,
			final TypeToken<C> typeToken,
			final YamlConfigurationLoader loader,
			final ConfigurationNode node,
			final ObjectMapper<C> mapper,
			final Logger logger,
			final Path configPath,
			final ConfigurationNode snapshot
	) {
		this.config = new AtomicReference<>(config);
		this.loader = loader;
		this.clazz = clazz;
		this.typeToken = typeToken;
		this.node = node;
		this.mapper = mapper;
		this.logger = logger;
		this.configPath = configPath;
		this.snapshot = snapshot;
	}

	/**
	 * Reloads the configuration file.
	 * @return {@code true} if the reload was successful, {@code false} otherwise
	 */
	public synchronized boolean reload() {
		try {
			final ConfigurationNode reloadedNode = loader.load();
			final C newConfig = deserialize(EnvironmentVariables.resolve(reloadedNode), typeToken, mapper);
			this.node.from(reloadedNode);
			config.set(newConfig);
			snapshot = snapshotOf(node, typeToken, newConfig);
			notifyChanged(newConfig);
			return true;
		} catch (final Exception exception) {
			logger.error("Could not reload {} configuration file", clazz.getSimpleName(), exception);
			return false;
		}
	}

	/** Gets the configuration object. */
	public @NotNull C get() {
		return this.config.get();
	}

	/**
	 * Registers a listener that is called with the new configuration object after it is replaced by
	 * {@link #reload()} or {@link #update(UnaryOperator)}, including when a failed update restores the
	 * previous object. Use it to keep derived state, such as expression contexts, in sync.
	 *
	 * @param listener the listener to register
	 */
	public void addChangeListener(final Consumer<? super C> listener) {
		this.changeListeners.add(listener);
	}

	private void notifyChanged(final C value) {
		for (final var listener : changeListeners) {
			try {
				listener.accept(value);
			} catch (final RuntimeException exception) {
				logger.error("A {} configuration change listener failed", clazz.getSimpleName(), exception);
			}
		}
	}

	/**
	 * Gets the configuration node, retaining environment references for saving.
	 * @return the configuration node
	 */
	public ConfigurationNode getNode() {
		return this.node;
	}

	/**
	 * Updates the configuration object using the provided updater function and saves it.
	 *
	 * <p>If the updater or the save fails, the previous configuration and the file's node are restored.</p>
	 *
	 * @param updater the function to apply to the current config
	 * @return {@code true} if the update and save were successful, {@code false} otherwise
	 */
	public synchronized boolean update(final UnaryOperator<C> updater) {
		final var previousNode = node.copy();
		final var previousSnapshot = snapshot;
		try {
			final C newConfig = updater.apply(config.get());
			final var updatedSnapshot = snapshotOf(node, typeToken, newConfig);
			ConfigurationValues.merge(node, previousSnapshot, updatedSnapshot);
			config.set(newConfig);
			snapshot = updatedSnapshot;
			writeFile();
			notifyChanged(newConfig);
			return true;
		} catch (final Exception exception) {
			logger.error("Could not update {} configuration", clazz.getSimpleName(), exception);
			restore(previousNode, previousSnapshot);
			return false;
		}
	}

	/**
	 * Saves the current configuration to the file, including changes made in place to the
	 * object returned by {@link #get()} since it was last loaded, reloaded, or saved.
	 * @return {@code true} if the save was successful, {@code false} otherwise
	 */
	public synchronized boolean save() {
		final var previousNode = node.copy();
		final var previousSnapshot = snapshot;
		try {
			final var current = snapshotOf(node, typeToken, config.get());
			ConfigurationValues.merge(node, previousSnapshot, current);
			snapshot = current;
			writeFile();
			return true;
		} catch (final Exception exception) {
			logger.error("Could not save {} configuration file", clazz.getSimpleName(), exception);
			// Keep the object's in-place changes so the save can be retried; only the file's node is restored.
			node.from(previousNode);
			snapshot = previousSnapshot;
			return false;
		}
	}

	private void writeFile() throws IOException {
		YamlCommentWriter.writeWithComments(configPath, node);
	}

	/** Restores the node and the configuration object after a failed update. */
	private void restore(final ConfigurationNode previousNode, final ConfigurationNode previousSnapshot) {
		node.from(previousNode);
		snapshot = previousSnapshot;
		// The updater may have changed the object in place, so rebuild it from the restored node.
		try {
			final C restored = deserialize(EnvironmentVariables.resolve(node.copy()), typeToken, mapper);
			config.set(restored);
			notifyChanged(restored);
		} catch (final Exception exception) {
			logger.error("Could not restore {} configuration after a failed update", clazz.getSimpleName(), exception);
		}
	}

	/**
	 * Loads a configuration file.
	 * @param logger the logger
	 * @param path the path to the configuration file
	 * @param clazz the class of the configuration object
	 * @param file the name of the configuration file
	 * @param options the configuration options
	 * @return the container
	 * @param <C> the type of the configuration object
	 * @throws IOException if an I/O error occurs
	 */
	public static <C> Container<C> load(
			final Logger logger,
			final Path path,
			final Class<C> clazz,
			final String file,
			final UnaryOperator<ConfigurationOptions> options
	) throws IOException {
		return load(logger, path, clazz, file, clazz, options);
	}

	/**
	 * Loads a configuration file.
	 * @param logger the logger
	 * @param path the path to the configuration file
	 * @param clazz the class of the configuration object
	 * @param file the name of the configuration file
	 * @return the container
	 * @param <C> the type of the configuration object
	 * @throws IOException if an I/O error occurs
	 */
	public static <C> Container<C> load(
			final Logger logger,
			final Path path,
			final Class<C> clazz,
			final String file
	) throws IOException {
		return load(logger, path, clazz, file, opts -> opts.shouldCopyDefaults(false));
	}

	/**
	 * Loads a configuration file, using the class loader of {@code resourceClass} to find the default template.
	 * @param logger the logger
	 * @param path the path to the configuration file
	 * @param clazz the class of the configuration object
	 * @param file the name of the configuration file
	 * @param resourceClass the class whose class loader provides the default resource template
	 * @return the container
	 * @param <C> the type of the configuration object
	 * @throws IOException if an I/O error occurs
	 */
	public static <C> Container<C> load(
			final Logger logger,
			final Path path,
			final Class<C> clazz,
			final String file,
			final Class<?> resourceClass
	) throws IOException {
		return load(logger, path, clazz, file, resourceClass, opts -> opts.shouldCopyDefaults(false));
	}

	/**
	 * Loads a configuration file, using the class loader of {@code resourceClass} to find the default template.
	 * @param logger the logger
	 * @param path the path to the configuration file
	 * @param clazz the class of the configuration object
	 * @param file the name of the configuration file
	 * @param resourceClass the class whose class loader provides the default resource template
	 * @param options the configuration options
	 * @return the container
	 * @param <C> the type of the configuration object
	 * @throws IOException if an I/O error occurs
	 */
	public static <C> Container<C> load(
			final Logger logger,
			final Path path,
			final Class<C> clazz,
			final String file,
			final Class<?> resourceClass,
			final UnaryOperator<ConfigurationOptions> options
	) throws IOException {
		try {
			final var mapper = ObjectMapper.factory().get(clazz);
			final var typeToken = TypeToken.get(clazz);
			final var fileName = file.endsWith(".yml") ? file : file + ".yml";
			if (!Files.exists(path)) {
				Files.createDirectories(path);
			}

			final var configPath = generateFile(resourceClass, path.resolve(fileName), fileName);
			final var loader = YamlConfigurationLoader.builder()
					.defaultOptions(options)
					.nodeStyle(NodeStyle.BLOCK)
					.path(configPath)
					.build();

			final var node = loader.load();
			final C newConfig = deserialize(EnvironmentVariables.resolve(node), typeToken, mapper);
			final var snapshot = snapshotOf(node, typeToken, newConfig);

			return new Container<>(newConfig, clazz, typeToken, loader, node, mapper, logger, configPath, snapshot);
		} catch (final IOException exception) {
			logger.error("Could not load {} configuration file", clazz.getSimpleName(), exception);
			throw exception;
		}
	}

	/**
	 * Loads a configuration node.
	 * Environment references in string values are resolved in the returned node.
	 * @param logger the logger
	 * @param path the path to the configuration file
	 * @param file the name of the configuration file
	 * @param options the configuration options
	 * @return the configuration node
	 * @throws IOException if an I/O error occurs
	 */
	public static ConfigurationNode loadNode(
			final Class<?> clazz,
			final Logger logger,
			final Path path,
			final String file,
			final UnaryOperator<ConfigurationOptions> options
	) throws IOException {
		try {
			final var fileName = file.endsWith(".yml") ? file : file + ".yml";
			if (!Files.exists(path)) {
				Files.createDirectories(path);
			}

			final var configPath = generateFile(clazz, path.resolve(fileName), fileName);
			final YamlConfigurationLoader loader = YamlConfigurationLoader.builder()
					.defaultOptions(options)
					.nodeStyle(NodeStyle.BLOCK)
					.path(configPath)
					.build();

			return EnvironmentVariables.resolve(loader.load());
		} catch (final IOException exception) {
			logger.error("Could not load configuration file", exception);
			throw exception;
		}
	}

	/**
	 * Deserializes a resolved node. An empty document yields the object's defaults instead of {@code null}.
	 */
	private static <C> C deserialize(
			final ConfigurationNode resolved,
			final TypeToken<C> typeToken,
			final ObjectMapper<C> mapper
	) throws SerializationException {
		final C value = resolved.get(typeToken);
		return value != null ? value : mapper.load(resolved);
	}

	/** Captures the object's state as node values, keeping the node's other keys. */
	private static <C> ConfigurationNode snapshotOf(
			final ConfigurationNode node,
			final TypeToken<C> typeToken,
			final C value
	) throws SerializationException {
		final var snapshot = node.copy();
		snapshot.set(typeToken, value);
		return snapshot;
	}

	private static Path generateFile(final Class<?> clazz, final Path path, final String name) throws IOException {
		if (Files.exists(path)) {
			return path;
		}

		try (final var rsc = clazz.getClassLoader().getResourceAsStream(name)) {
			if (rsc == null) {
				if (!Files.exists(path)) {
					throw new IOException("Could not find resource " + path.getFileName());
				}
				return path;
			}

			Files.copy(rsc, path);
			return path;
		}
	}
}
