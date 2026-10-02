# Mod API

Other Fabric mods can use `EmoteApi.getInstance()` to register and play emotes and listen for Emote events.

## Gradle dependency

Emote is available from Modrinth Maven. Add the Modrinth Maven repository:

```groovy title="build.gradle"
repositories {
    exclusiveContent {
        forRepository {
            maven {
                name = "Modrinth"
                url = "https://api.modrinth.com/maven"
            }
        }
        filter {
            includeGroup "maven.modrinth"
        }
    }
}
```

Replace `[VERSION_ID]` with the Modrinth version ID of the Emote file you want to use. Use the ID assigned to the version by Modrinth, not its display name.

```groovy title="build.gradle"
dependencies {
    implementation "maven.modrinth:qUF0jygw:[VERSION_ID]"
}
```

Use the [Emote version list](https://modrinth.com/mod/emote/versions) to find a file for your target Minecraft version and its version ID.

Declare the Emote dependency in `fabric.mod.json` as well:

```json title="fabric.mod.json"
{
  "depends": {
    "emote": "*"
  }
}
```

---

## API scope

The API currently provides:

- Runtime emote registration and removal
- Starting and stopping player emotes
- Current playback-state queries
- Cancellable playback-request listeners
- Playback start and stop lifecycle listeners
- Named lifecycle callbacks

API calls that change state must run on the Minecraft server thread.

`EmoteApi.play` is a trusted server-side playback entry point. The calling mod is responsible for applying any desired `standalone`, disabled-ID, player-permission, and cooldown policy. Emote still requires a loaded ID, dispatches cancellable playback-request events, and enforces playback-engine limits and failures.

## Lifecycle callbacks

Register a named `EmoteCallbacks` implementation on the server thread. Override the lifecycle methods you need: `onStart`, `onTick`, `onLoop`, or `onClose`.

```java
EmoteApi.getInstance().registerCallbacks(Identifier.parse("example:wave"), new EmoteCallbacks() {
    @Override
    public void onStart(PlaybackContext context) {
        context.setUserState(context.payload());
    }
});
```

Select the registered name in an Animation or Sequence's root `callbacks` array:

```json
"callbacks": [
  {"name": "example:wave", "payload": "right_hand"}
]
```

Store per-playback state in `PlaybackContext` with `userState()` and `setUserState(...)`. An unregistered name rejects playback.
