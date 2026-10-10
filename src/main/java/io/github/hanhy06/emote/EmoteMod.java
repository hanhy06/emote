package io.github.hanhy06.emote;

import io.github.hanhy06.emote.application.*;
import io.github.hanhy06.emote.command.*;
import io.github.hanhy06.emote.config.ConfigManager;
import io.github.hanhy06.emote.content.EmoteCatalog;
import io.github.hanhy06.emote.content.loader.EmoteDirectoryLoader;
import io.github.hanhy06.emote.network.PlaybackStateSyncService;
import io.github.hanhy06.emote.network.WheelSyncService;
import io.github.hanhy06.emote.network.payload.PlaybackStatePayload;
import io.github.hanhy06.emote.network.payload.WheelSyncPayload;
import io.github.hanhy06.emote.permission.PermissionService;
import io.github.hanhy06.emote.playback.EntityPlaybackManager;
import io.github.hanhy06.emote.playback.PlaybackEngine;
import io.github.hanhy06.emote.playback.PlayerPlaybackManager;
import io.github.hanhy06.emote.resource.PolymerResourcePackDistributor;
import io.github.hanhy06.emote.server.IdlePlaybackService;
import io.github.hanhy06.emote.server.ReloadService;
import io.github.hanhy06.emote.server.ServerLifecycle;
import io.github.hanhy06.emote.skin.PlayerSkinBaker;
import io.github.hanhy06.emote.skin.PlayerSkinManager;
import io.github.hanhy06.emote.skin.SkinBakeCoordinator;
import io.github.hanhy06.emote.skin.SkinCache;
import io.github.hanhy06.emote.skin.account.*;
import io.github.hanhy06.emote.skin.mineskin.MineSkinClient;
import io.github.hanhy06.emote.skin.mineskin.MineSkinProvider;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.api.ModInitializer;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EmoteMod implements ModInitializer {
    public static final String MOD_ID = "emote";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static MinecraftServer SERVER;

    @Override
    public void onInitialize() {
        ConfigManager configManager = new ConfigManager(FabricLoader.getInstance().getConfigDir());
        EmoteCatalog catalog = new EmoteCatalog();
        PermissionService permissions = new PermissionService();

        MinecraftAccountManager accounts = new MinecraftAccountManager(
            new AccountCredentialStore(FabricLoader.getInstance().getConfigDir().resolve("emote/accounts.bin")),
            new MinecraftAccountClient()
        );
        PlayerSkinBaker skinBaker = new PlayerSkinBaker();
        SkinCache skinCache = new SkinCache();
        MineSkinProvider mineSkin = new MineSkinProvider(skinCache, new MineSkinClient());
        MinecraftSkinClient minecraftSkins = new MinecraftSkinClient();
        AccountBakeQueue accountQueue = new AccountBakeQueue(accounts, minecraftSkins);
        PlayerSkinManager skins = new PlayerSkinManager(
            new SkinBakeCoordinator(accounts, skinBaker, minecraftSkins, skinCache, accountQueue, mineSkin)
        );
        catalog.addListener(emotes -> skins.setModelBindings(emotes.stream().flatMap(emote -> emote.skinBindings().stream()).toList()));

        PlaybackEngine engine = new PlaybackEngine();
        PlayerPlaybackManager playback = new PlayerPlaybackManager(engine, skins);
        EntityPlaybackManager entityPlayback = new EntityPlaybackManager(engine, catalog, skins);
        PlaybackCooldownService cooldowns = new PlaybackCooldownService();
        PlaybackPolicyService playbackPolicy = new PlaybackPolicyService(permissions, catalog, cooldowns);
        EmoteQueryService queries = new EmoteQueryService(catalog, playbackPolicy);
        PlaybackStateSyncService playbackStateSync = new PlaybackStateSyncService();
        WheelSyncService wheelSync = new WheelSyncService(queries);
        playback.addStateListener(cooldowns);
        playback.addStateListener(playbackStateSync);

        ApiEventDispatcher apiEvents = new ApiEventDispatcher();
        EmotePlayService play = new EmotePlayService(catalog, playbackPolicy, playback, apiEvents);
        EmoteApiImpl api = new EmoteApiImpl(
            catalog,
            play,
            playback,
            engine,
            apiEvents,
            wheelSync::syncAll
        );
        engine.setStateListener(apiEvents);
        ExampleCallbacks.registerAll(api);

        IdlePlaybackService idlePlayback = new IdlePlaybackService(playbackPolicy, play, playback, catalog);
        configManager.addAccessConfigListener(playbackPolicy);
        configManager.addAccessConfigListener(idlePlayback);
        configManager.addListener(skins);
        configManager.addListener(engine);
        PolymerResourcePackDistributor resourcePackDistributor = new PolymerResourcePackDistributor(configManager);
        ReloadService reload = new ReloadService(
            configManager,
            catalog,
            new EmoteDirectoryLoader(),
            engine,
            wheelSync,
            resourcePackDistributor::rebuild,
            resourcePackDistributor::pushToOnlinePlayers
        );

        UserCommand userCommand = new UserCommand(playback, new EmoteMenu(configManager, catalog, queries, playback), queries, play);
        AdminCommand adminCommand = new AdminCommand(catalog, playback, permissions, reload, configManager, skins);
        AccountCommand accountCommand = new AccountCommand(accounts);
        CommandRegistrationCallback.EVENT.register((dispatcher, ignoredRegistryAccess, ignoredEnvironment) -> {
            var root = userCommand.createRoot();
            adminCommand.attachTo(root);
            root.then(accountCommand.createCommand());
            dispatcher.register(root);
        });

        ServerLifecycle lifecycle = new ServerLifecycle(
            skins, cooldowns, catalog, playback, entityPlayback, reload, wheelSync, idlePlayback,
            () -> !accounts.hasAccounts() && !mineSkin.available()
        );
        playback.register();
        entityPlayback.register();
        registerPayloads();
        ServerLifecycleEvents.SERVER_STARTED.register(server -> accounts.initialize());
        lifecycle.register();
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> accounts.close());
    }

    private static void registerPayloads() {
        PayloadTypeRegistry.clientboundPlay().register(PlaybackStatePayload.TYPE, PlaybackStatePayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(WheelSyncPayload.TYPE, WheelSyncPayload.STREAM_CODEC);
    }
}
