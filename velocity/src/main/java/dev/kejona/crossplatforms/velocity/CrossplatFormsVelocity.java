package dev.kejona.crossplatforms.velocity;

import cloud.commandframework.execution.CommandExecutionCoordinator;
import cloud.commandframework.velocity.VelocityCommandManager;
import com.google.inject.Inject;
import com.google.inject.Module;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.kejona.crossplatforms.Constants;
import dev.kejona.crossplatforms.CrossplatForms;
import dev.kejona.crossplatforms.CrossplatFormsBootstrap;
import dev.kejona.crossplatforms.Logger;
import dev.kejona.crossplatforms.action.ActionSerializer;
import dev.kejona.crossplatforms.action.ServerAction;
import dev.kejona.crossplatforms.command.CommandOrigin;
import dev.kejona.crossplatforms.config.ConfigId;
import dev.kejona.crossplatforms.config.ConfigManager;
import dev.kejona.crossplatforms.handler.BasicPlaceholders;
import dev.kejona.crossplatforms.handler.Placeholders;
import dev.kejona.crossplatforms.handler.ServerHandler;
import dev.kejona.crossplatforms.permission.LuckPermsHook;
import dev.kejona.crossplatforms.permission.Permissions;
import dev.kejona.crossplatforms.proxy.CloseMenuAction;
import dev.kejona.crossplatforms.proxy.ProtocolizeModule;
import dev.kejona.crossplatforms.velocity.handler.VelocityCommandOrigin;
import dev.kejona.crossplatforms.velocity.handler.VelocityHandler;
import lombok.Getter;
import org.bstats.charts.CustomChart;
import org.bstats.velocity.Metrics;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class CrossplatFormsVelocity implements CrossplatFormsBootstrap {

    private static final int BSTATS_ID = 26242;
    private static CrossplatFormsVelocity INSTANCE;

    static {
        // load information from build.properties
        Constants.fetch();
        Constants.setId("crossplatformsvelocity");
    }

    @Getter
    private final ProxyServer server;
    private final PluginContainer container;
    private final Path dataFolder;
    private final Logger logger;
    private final Metrics.Factory metricsFactory;

    private CrossplatForms crossplatForms;
    private Metrics metrics;
    private boolean protocolizePresent;

    @Inject
    public CrossplatFormsVelocity(ProxyServer server, PluginContainer container, @DataDirectory Path dataFolder, org.slf4j.Logger logger, Metrics.Factory metricsFactory) {
        INSTANCE = this;
        this.server = server;
        this.container = container;
        this.dataFolder = dataFolder;
        this.logger = new SLF4JLogger(logger);
        this.metricsFactory = metricsFactory;
    }

    @Subscribe
    public void load(ProxyInitializeEvent event) {
        if (crossplatForms != null) {
            logger.warn("Initializing already occurred");
        }
        metrics = metricsFactory.make(this, BSTATS_ID);

        ServerHandler serverHandler = new VelocityHandler(server);
        Permissions permissions = pluginPresent("luckperms") ? new LuckPermsHook() : Permissions.empty();

        VelocityCommandManager<CommandOrigin> commandManager;
        try {
            commandManager = new VelocityCommandManager<>(
                    container,
                    server,
                    CommandExecutionCoordinator.simpleCoordinator(),
                    (VelocityCommandOrigin::new),
                    (origin -> (CommandSource) origin.getHandle())
            );
        } catch (Exception e) {
            logger.severe("Failed to create CommandManager, stopping");
            e.printStackTrace();
            return;
        }

        logger.warn("CrossplatForms-Velocity does not yet support placeholder plugins, only %player_name% and %player_uuid% and internal placeholders will work.");
        Placeholders placeholders = new BasicPlaceholders();

        protocolizePresent = server.getPluginManager().isLoaded("protocolize");

        crossplatForms = new CrossplatForms(
                logger,
                dataFolder,
                serverHandler,
                permissions,
                "formsv",
                commandManager,
                placeholders,
                this
        );

        server.getEventManager().register(this, serverHandler); // events for catching proxy commands
    }

    @Override
    public List<Module> configModules() {
        List<Module> modules = new ArrayList<>();

        if (protocolizePresent) {
            modules.add(new ProtocolizeModule());
        }

        return modules;
    }

    @Override
    public void preConfigLoad(ConfigManager configManager) {
        ActionSerializer actionSerializer = configManager.getActionSerializer();
        ServerAction.register(actionSerializer);

        if (protocolizePresent) {
            configManager.register(ConfigId.JAVA_MENUS);
            CloseMenuAction.register(actionSerializer); // Close Java Menus
        }
    }

    @Override
    public void addCustomChart(CustomChart chart) {
        metrics.addCustomChart(chart);
    }

    @Subscribe
    public void onDisable(ProxyShutdownEvent event) {
        server.getEventManager().unregisterListeners(this);
    }

    public boolean pluginPresent(String id) {
        return server.getPluginManager().isLoaded(id);
    }

    public static CrossplatFormsVelocity getInstance() {
        return INSTANCE;
    }
}
