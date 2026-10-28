package <PACKAGE_NAME>;

import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.ProvisionException;
import com.google.inject.TypeLiteral;
import com.thewinterframework.configurate.Container;
import com.thewinterframework.configurate.module.ConfigurateModule;
import com.thewinterframework.configurate.serializer.ConfigurateSerializersRegistry;
import com.thewinterframework.plugin.DataFolder;
import com.thewinterframework.plugin.WinterPlugin;
import com.thewinterframework.plugin.module.PluginModule;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.List;

public class ConfigurationsModule extends AbstractModule implements PluginModule {

    @Override
    public List<Class<? extends PluginModule>> depends(WinterPlugin plugin) {
        return List.of(com.thewinterframework.configurate.module.ConfigurateModule.class);
    }

    @Override
    protected void configure() {
        <el>
                bind(new TypeLiteral<Container<<CONFIG_OBJECT>>>() {}).toProvider(<CONFIG_IDENTIFIER>ContainerProvider.class).asEagerSingleton();
        </el>
    }
    <el>
    private static class <CONFIG_IDENTIFIER>ContainerProvider implements Provider<Container<<CONFIG_OBJECT>>> {

        private final WinterPlugin plugin;
        private final Logger logger;
        private final Path dataFolder;
        private final ConfigurateSerializersRegistry registry;
        private final com.thewinterframework.service.ReloadServiceManager reloadManager;

        @Inject
        public <CONFIG_IDENTIFIER>ContainerProvider(WinterPlugin plugin, Logger logger, @DataFolder Path dataFolder, ConfigurateSerializersRegistry registry, com.thewinterframework.service.ReloadServiceManager reloadManager) {
            this.plugin = plugin;
            this.logger = logger;
            this.dataFolder = dataFolder;
            this.registry = registry;
            this.reloadManager = reloadManager;
        }

        @Override
        public Container<<CONFIG_OBJECT>> get() {
            try {
                final var container = Container.load(
                        logger,
                        dataFolder,
                        <CONFIG_OBJECT>.class, "<CONFIG_NAME>.yml",
                        options -> options.serializers(builder -> builder.registerAll(registry.getSerializers()))
                );

                plugin.getExpressionResolver().addContext("<CONFIG_NAME>", container.get());
                // Keep the expression context on the current object after reloads and updates.
                container.addChangeListener(config -> plugin.getExpressionResolver().addContext("<CONFIG_NAME>", config));
                reloadManager.addOnReload(com.thewinterframework.configurate.module.ConfigurateModule.class, container::reload);
                return container;
            } catch (Exception e) {
                throw new ProvisionException("Error while loading <CONFIG_NAME>", e);
            }
        }
    }
    </el>
}