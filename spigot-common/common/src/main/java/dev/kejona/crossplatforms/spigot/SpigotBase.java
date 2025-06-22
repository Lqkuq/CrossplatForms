package dev.kejona.crossplatforms.spigot;

import cloud.commandframework.bukkit.BukkitCommandManager;
import cloud.commandframework.execution.CommandExecutionCoordinator;
import cloud.commandframework.paper.PaperCommandManager;
import com.google.inject.AbstractModule;
import com.google.inject.Module;
import dev.kejona.crossplatforms.Constants;
import dev.kejona.crossplatforms.CrossplatForms;
import dev.kejona.crossplatforms.CrossplatFormsBootstrap;
import dev.kejona.crossplatforms.JavaUtilLogger;
import dev.kejona.crossplatforms.Logger;
import dev.kejona.crossplatforms.accessitem.AccessItemConfig;
import dev.kejona.crossplatforms.accessitem.GiveCommand;
import dev.kejona.crossplatforms.accessitem.InspectItemCommand;
import dev.kejona.crossplatforms.action.ActionSerializer;
import dev.kejona.crossplatforms.action.ServerAction;
import dev.kejona.crossplatforms.command.CommandOrigin;
import dev.kejona.crossplatforms.config.ConfigId;
import dev.kejona.crossplatforms.config.ConfigManager;
import dev.kejona.crossplatforms.handler.BasicPlaceholders;
import dev.kejona.crossplatforms.handler.Placeholders;
import dev.kejona.crossplatforms.handler.ServerHandler;
import dev.kejona.crossplatforms.inventory.InventoryController;
import dev.kejona.crossplatforms.inventory.InventoryFactory;
import dev.kejona.crossplatforms.permission.LuckPermsHook;
import dev.kejona.crossplatforms.permission.Permissions;
import dev.kejona.crossplatforms.spigot.adapter.SpigotAdapter;
import dev.kejona.crossplatforms.spigot.adapter.Versioned;
import dev.kejona.crossplatforms.spigot.handler.PlaceholderAPIHandler;
import dev.kejona.crossplatforms.spigot.handler.SpigotCommandOrigin;
import dev.kejona.crossplatforms.spigot.handler.SpigotHandler;
import dev.kejona.crossplatforms.spigot.handler.SpigotPermissions;
import dev.kejona.crossplatforms.spigot.item.SpigotInventoryController;
import dev.kejona.crossplatforms.spigot.item.SpigotInventoryFactory;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.kyori.adventure.platform.bukkit.BukkitComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.CustomChart;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public abstract class SpigotBase extends JavaPlugin implements CrossplatFormsBootstrap {
    
    private static final int METRICS_ID = 26240;

    public static final LegacyComponentSerializer LEGACY_SERIALIZER = BukkitComponentSerializer.legacy();
    private static SpigotBase INSTANCE;

    static {
        // load information from build.properties
        Constants.fetch();
    }

    protected Logger logger;
    private BukkitAudiences audiences;
    private Metrics metrics;
    private SpigotAdapter spigotAdapter;

    protected SpigotBase() {
        INSTANCE = this;
    }

    @Override
    public void onEnable() {
        Server server = getServer();

        logger = new JavaUtilLogger(getLogger());
        audiences = BukkitAudiences.create(this);
        metrics = new Metrics(this, METRICS_ID);

        Versioned<SpigotAdapter> result = findVersionAdapter();
        result.betterVersion().ifPresent(v -> logger.warn("Consider using server version " + v + " or higher instead."));
        if (result.value().isPresent()) {
            spigotAdapter = result.value().get();
        } else {
            logger.severe("This server version is unsupported. If you believe this is incorrect, please contact us.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // For ServerAction
        server.getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");

        convertGeyserHubConfig();

        ServerHandler serverHandler = new SpigotHandler(this, audiences);
        Permissions permissions = server.getPluginManager().isPluginEnabled("LuckPerms") ? new LuckPermsHook() : new SpigotPermissions(this);

        // Yes, this is not Paper-exclusive plugin. Cloud handles this gracefully.
        PaperCommandManager<CommandOrigin> commandManager;
        try {
            commandManager = new PaperCommandManager<>(
                this,
                CommandExecutionCoordinator.simpleCoordinator(),
                (SpigotCommandOrigin::new),
                origin -> (CommandSender) origin.getHandle()
            );
        } catch (Exception e) {
            logger.severe("Failed to create CommandManager, stopping");
            e.printStackTrace();
            return;
        }

        if (brigadierAvailable()) {
            try {
                // Brigadier is ideal if possible. Allows for much more readable command options, especially on BE.
                commandManager.registerBrigadier();
                logger.info("Successfully registered Brigadier mappings");
            } catch (BukkitCommandManager.BrigadierFailureException e) {
                logger.warn("Failed to initialize Brigadier support: " + e.getMessage());
                if (e.getReason() == BukkitCommandManager.BrigadierFailureReason.VERSION_TOO_HIGH) {
                    // Commodore brig only supports Spigot 1.13 - 1.18.2
                    logger.warn("Using Paper instead of Spigot will likely fix this.");
                }
            }
        }

        Placeholders placeholders;
        if (server.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            placeholders = new PlaceholderAPIHandler(this);
        } else {
            logger.warn("This plugin works best with PlaceholderAPI! Since you don't have it installed, only %player_name% and %player_uuid% will work (typically).");
            placeholders = new BasicPlaceholders();
        }

        CrossplatForms crossplatForms = new CrossplatForms(
            logger,
            getDataFolder().toPath(),
            serverHandler,
            permissions,
            "forms",
            commandManager,
            placeholders,
            this
        );

        // Wait for debug to be set or not
        logger.debug("Using " + spigotAdapter.getClass().getSimpleName() + " for server version " + ClassNames.NMS_VERSION);

        SpigotAccessItems accessItems = new SpigotAccessItems(
            this,
            spigotAdapter,
            crossplatForms.getConfigManager(),
            crossplatForms.getPermissions(),
            crossplatForms.getBedrockHandler(),
            crossplatForms.getPlaceholders()
        );
        server.getPluginManager().registerEvents(accessItems, this);
        spigotAdapter.registerAuxiliaryEvents(this, accessItems); // Events for versions above 1.8

        // Commands added by access items
        new GiveCommand(crossplatForms, accessItems).register(commandManager, crossplatForms.getCommandBuilder());
        new InspectItemCommand(crossplatForms, accessItems).register(commandManager, crossplatForms.getCommandBuilder());

        permissions.notifyPluginLoaded();
    }

    @Override
    public List<Module> configModules() {
        List<Module> modules = new ArrayList<>();

        SpigotInventoryController controller = new SpigotInventoryController();
        getServer().getPluginManager().registerEvents(controller, this);
        SpigotInventoryFactory factory = new SpigotInventoryFactory(spigotAdapter);

        modules.add(new AbstractModule() {
            @Override
            public void configure() {
                bind(InventoryController.class).toInstance(controller);
                bind(InventoryFactory.class).toInstance(factory);
            }
        });

        return modules;
    }

    @Override
    public void preConfigLoad(ConfigManager configManager) {
        configManager.register(ConfigId.JAVA_MENUS);
        configManager.register(AccessItemConfig.asConfigId());

        ActionSerializer actionSerializer = configManager.getActionSerializer();
        ServerAction.register(actionSerializer);
        CloseMenuAction.register(actionSerializer);
    }

    @Override
    public void onDisable() {
        if (audiences != null) {
            audiences.close();
        }
        // note: server var might be null here in case plugin is disabled early
        Bukkit.getMessenger().unregisterOutgoingPluginChannel(this);
    }

    @Override
    public void addCustomChart(CustomChart chart) {
        metrics.addCustomChart(chart);
    }

    private void convertGeyserHubConfig() {
        File selector = new File(getDataFolder(), "selector.yml");
        if (selector.exists()) {
            try {
                GeyserHubConverter.convert(selector);
            } catch (IOException e) {
                logger.warn("Failed to convert " + selector.getName() + ":");
                if (logger.isDebug()) {
                    e.printStackTrace();
                } else {
                    logger.warn(e.getClass().getName() + ": " + e.getMessage());
                }
            }
        }
    }
    
    private boolean brigadierAvailable() {
        try {
            // Brigadier is available on 1.13 and above
            Class.forName("org.bukkit.entity.Dolphin");
            return true;
        } catch (ClassNotFoundException ignored) {
            // no-op
        }

        return false;
    }

    public SpigotAdapter adapter() {
        return spigotAdapter;
    }

    public abstract Versioned<SpigotAdapter> findVersionAdapter();

    public static SpigotBase getInstance() {
        return INSTANCE;
    }
}
