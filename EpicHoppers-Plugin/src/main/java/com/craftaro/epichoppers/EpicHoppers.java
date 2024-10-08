package com.craftaro.epichoppers;

import com.craftaro.core.SongodaCore;
import com.craftaro.core.SongodaPlugin;
import com.craftaro.core.commands.CommandManager;
import com.craftaro.core.configuration.Config;
import com.craftaro.core.database.DatabaseConnector;
import com.craftaro.core.gui.GuiManager;
import com.craftaro.core.hooks.EconomyManager;
import com.craftaro.core.hooks.ProtectionManager;
import com.craftaro.third_party.com.cryptomorin.xseries.XMaterial;
import com.craftaro.core.third_party.de.tr7zw.nbtapi.NBTItem;
import com.craftaro.core.utils.TextUtils;
import com.craftaro.epichoppers.boost.BoostDataImpl;
import com.craftaro.epichoppers.boost.BoostManager;
import com.craftaro.epichoppers.boost.BoostManagerImpl;
import com.craftaro.epichoppers.commands.CommandBoost;
import com.craftaro.epichoppers.commands.CommandGive;
import com.craftaro.epichoppers.commands.CommandReload;
import com.craftaro.epichoppers.commands.CommandSettings;
import com.craftaro.epichoppers.containers.ContainerManager;
import com.craftaro.epichoppers.containers.ContainerManagerImpl;
import com.craftaro.epichoppers.database.migrations._1_InitialMigration;
import com.craftaro.epichoppers.hopper.HopperImpl;
import com.craftaro.epichoppers.hopper.HopperManager;
import com.craftaro.epichoppers.hopper.levels.Level;
import com.craftaro.epichoppers.hopper.levels.LevelManager;
import com.craftaro.epichoppers.hopper.levels.LevelManagerImpl;
import com.craftaro.epichoppers.hopper.levels.modules.Module;
import com.craftaro.epichoppers.hopper.levels.modules.ModuleAutoCrafting;
import com.craftaro.epichoppers.hopper.levels.modules.ModuleAutoSell;
import com.craftaro.epichoppers.hopper.levels.modules.ModuleAutoSmelter;
import com.craftaro.epichoppers.hopper.levels.modules.ModuleBlockBreak;
import com.craftaro.epichoppers.hopper.levels.modules.ModuleMobHopper;
import com.craftaro.epichoppers.hopper.levels.modules.ModuleSuction;
import com.craftaro.epichoppers.hopper.teleport.TeleportHandler;
import com.craftaro.epichoppers.listeners.BlockListeners;
import com.craftaro.epichoppers.listeners.EntityListeners;
import com.craftaro.epichoppers.listeners.HopperListeners;
import com.craftaro.epichoppers.listeners.InteractListeners;
import com.craftaro.epichoppers.listeners.InventoryListeners;
import com.craftaro.epichoppers.player.PlayerDataManager;
import com.craftaro.epichoppers.player.PlayerDataManagerImpl;
import com.craftaro.epichoppers.settings.Settings;
import com.craftaro.epichoppers.tasks.HopTask;
import com.craftaro.epichoppers.utils.Methods;
import com.craftaro.epichoppers.hopper.teleport.TeleportHandlerImpl;
import com.craftaro.skyblock.SkyBlock;
import com.craftaro.skyblock.permission.BasicPermission;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.PluginManager;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class EpicHoppers extends SongodaPlugin {
    private final GuiManager guiManager = new GuiManager(this);
    private final Config levelsConfig = new Config(this, "levels.yml");
    private HopperManager hopperManager;
    private CommandManager commandManager;
    private LevelManager levelManager;
    private BoostManagerImpl boostManager;
    private PlayerDataManager playerDataManager;
    private ContainerManager containerManager;

    private TeleportHandler teleportHandler;

    @Override
    public void onPluginLoad() {
    }

    @Override
    public void onPluginDisable() {
        getDataManager().shutdown();
        saveModules();
    }

    @Override
    public void onPluginEnable() {
        SongodaCore.registerPlugin(this, 15, XMaterial.HOPPER);

        // Load Economy
        EconomyManager.load();

        // Load protection hook
        ProtectionManager.load(this);

        // Setup Config
        Settings.setupConfig();
        this.setLocale(Settings.LANGUGE_MODE.getString(), false);

        // Set Economy & Hologram preference
        EconomyManager.getManager().setPreferredHook(Settings.ECONOMY_PLUGIN.getString());

        // Register commands
        this.commandManager = new CommandManager(this);
        this.commandManager.addMainCommand("eh")
                .addSubCommands(
                        new CommandBoost(this),
                        new CommandGive(this),
                        new CommandReload(this),
                        new CommandSettings(this)
                );

        this.hopperManager = new HopperManager(this);
        this.playerDataManager = new PlayerDataManagerImpl();
        this.containerManager = new ContainerManagerImpl();
        this.boostManager = new BoostManagerImpl();


        initDatabase(Collections.singletonList(new _1_InitialMigration(this)));

        this.loadLevelManager();

        new HopTask(this);
        this.teleportHandler = new TeleportHandlerImpl(this);

        // Register Listeners
        this.guiManager.init();
        PluginManager pluginManager = Bukkit.getPluginManager();
        pluginManager.registerEvents(new HopperListeners(this), this);
        pluginManager.registerEvents(new EntityListeners(), this);
        pluginManager.registerEvents(new BlockListeners(this), this);
        pluginManager.registerEvents(new InteractListeners(this), this);
        pluginManager.registerEvents(new InventoryListeners(), this);

        EpicHoppersApi.initApi(this.levelManager, this.boostManager, this.containerManager, this.teleportHandler, this.playerDataManager);

        // Start auto save
        int saveInterval = Settings.AUTOSAVE.getInt() * 60 * 20;
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::saveModules, saveInterval, saveInterval);

        // Hotfix for EH loading before FSB
        Bukkit.getScheduler().runTask(this, () -> {
            if (pluginManager.isPluginEnabled("FabledSkyBlock")) {
                try {
                    SkyBlock.getInstance().getPermissionManager().registerPermission(
                            (BasicPermission) Class.forName("com.craftaro.epichoppers.compatibility.EpicHoppersPermission").getDeclaredConstructor().newInstance());
                } catch (ReflectiveOperationException ex) {
                    ex.printStackTrace();
                }
            }
        });
    }

    @Override
    public void onDataLoad() {
        // Load data from DB
        this.dataManager.getAsyncPool().execute(() -> {
            getLogger().info("loading data...");
            long start = System.currentTimeMillis();
            this.hopperManager.addHoppers(this.dataManager.loadBatch(HopperImpl.class, "placed_hoppers"));
            this.boostManager.loadBoosts(this.dataManager.loadBatch(BoostDataImpl.class, "boosted_players"));
            getLogger().info("Loaded " + hopperManager.getHoppers().size() + " hoppers in " + (System.currentTimeMillis() - start) + "ms");
            this.hopperManager.setReady();
        });
    }

    @Override
    public void onConfigReload() {
        this.setLocale(getConfig().getString("System.Language Mode"), true);
        this.locale.reloadMessages();
        loadLevelManager();
    }

    @Override
    public List<Config> getExtraConfig() {
        return Collections.singletonList(this.levelsConfig);
    }

    private void loadLevelManager() {
        if (!new File(this.getDataFolder(), "levels.yml").exists()) {
            this.saveResource("levels.yml", false);
        }
        this.levelsConfig.load();

        // Load an instance of LevelManager
        this.levelManager = new LevelManagerImpl();
        /*
         * Register Levels into LevelManager from configuration.
         */
        this.levelManager.clear();
        for (String levelName : this.levelsConfig.getKeys(false)) {
            int level = Integer.parseInt(levelName.split("-")[1]);

            ConfigurationSection levels = this.levelsConfig.getConfigurationSection(levelName);

            int radius = levels.getInt("Range");
            int amount = levels.getInt("Amount");
            int linkAmount = levels.getInt("Link-amount", 1);
            boolean filter = levels.getBoolean("Filter");
            boolean teleport = levels.getBoolean("Teleport");
            int costExperience = levels.getInt("Cost-xp", -1);
            int costEconomy = levels.getInt("Cost-eco", -1);
            int autoSell = levels.getInt("AutoSell");

            ArrayList<Module> modules = new ArrayList<>();

            for (String key : levels.getKeys(false)) {
                if (key.equals("Suction") && levels.getInt("Suction") != 0) {
                    modules.add(new ModuleSuction(this, getGuiManager(), levels.getInt("Suction")));
                } else if (key.equals("BlockBreak") && levels.getInt("BlockBreak") != 0) {
                    modules.add(new ModuleBlockBreak(this, getGuiManager(), levels.getInt("BlockBreak")));
                } else if (key.equals("AutoCrafting")) {
                    modules.add(new ModuleAutoCrafting(this, getGuiManager()));
                } else if (key.equals("AutoSell")) {
                    modules.add(new ModuleAutoSell(this, getGuiManager(), autoSell));
                } else if (key.equals("MobHopper")) {
                    modules.add(new ModuleMobHopper(this, getGuiManager(), levels.getInt("MobHopper")));
                } else if (key.equals("AutoSmelting")) {
                    modules.add(new ModuleAutoSmelter(this, getGuiManager(), levels.getInt("AutoSmelting")));
                }
            }
            this.levelManager.addLevel(level, costExperience, costEconomy, radius, amount, filter, teleport, linkAmount, modules);
        }
    }

    private void saveModules() {
        if (this.levelManager != null) {
            for (Level level : this.levelManager.getLevels().values()) {
                for (Module module : level.getRegisteredModules()) {
                    module.saveDataToFile();
                }
            }
        }
    }

    public ItemStack newHopperItem(Level level) {
        ItemStack item = XMaterial.HOPPER.parseItem();
        ItemMeta itemmeta = item.getItemMeta();
        itemmeta.setDisplayName(TextUtils.formatText(Methods.formatName(level.getLevel())));
        String line = getLocale().getMessage("general.nametag.lore").toText();
        if (!line.isEmpty()) {
            itemmeta.setLore(Arrays.asList(line.split("\n")));
        }
        item.setItemMeta(itemmeta);

        NBTItem nbtItem = new NBTItem(item);
        nbtItem.setInteger("level", level.getLevel());
        return nbtItem.getItem();
    }

    public TeleportHandler getTeleportHandler() {
        return this.teleportHandler;
    }

    public BoostManager getBoostManager() {
        return this.boostManager;
    }

    public CommandManager getCommandManager() {
        return this.commandManager;
    }

    public LevelManager getLevelManager() {
        return this.levelManager;
    }

    public HopperManager getHopperManager() {
        return this.hopperManager;
    }

    public PlayerDataManager getPlayerDataManager() {
        return this.playerDataManager;
    }

    public GuiManager getGuiManager() {
        return this.guiManager;
    }

    public ContainerManager getContainerManager() {
        return this.containerManager;
    }


    /**
     * @deprecated Use {@link #getPlugin(Class)} instead
     */
    @Deprecated
    public static EpicHoppers getInstance() {
        return EpicHoppers.getPlugin(EpicHoppers.class);
    }
}
