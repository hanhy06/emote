# Mod API

Other Fabric mods can use `EmoteApi.getInstance()` to register and play emotes and listen for Emote events.

For a working callback example, see [ExampleCallbacks.java](https://github.com/hanhy06/emote/blob/HEAD/src/main/java/io/github/hanhy06/emote/ExampleCallbacks.java).

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

Obtain the API with `EmoteApi.getInstance()`. State-changing calls and playback inspection must run on the Minecraft server thread. Use `EmoteApi` to start or look up a playback session, then use the returned `Playback` to inspect or control it.

## Playback control

Start an emote for a player or at a fixed position in the world, or stop an existing session.

| API | Behavior |
|---|---|
| `EmoteApi.play(player, emoteId)` | Starts playback with the default actor placement. |
| `EmoteApi.play(player, emoteId, placement)` | Starts playback with the supplied `PlaybackPlacement`. |
| `EmoteApi.play(world, emoteId, position, yaw)` | Starts playback at a fixed position without an actor. Emotes with player-skin attachments require a prepared default skin. |
| `EmoteApi.stop(player)` | Stops the player's playback with the manual stop reason and reports whether it was stopped. |
| `Playback.stop()`, `Playback.stop(reason)` | Stops this session with the manual or supplied stop reason and reports whether it was stopped. |

`play` returns `PlayResult.Success` with a `Playback` from `playback()`, or `PlayResult.Failure` with `errorMessage()`. API playback bypasses standalone visibility, disabled IDs, player permissions, and cooldowns. Emote checks that the ID is loaded and applies playback-engine limits. Playback started for a player also dispatches cancellable play events.

## Playback inspection

Use `Playback.getSessionId()` to obtain the session ID. Inspect the live session through `Playback` or call `snapshot()` to capture its current state.

| API | Result |
|---|---|
| `EmoteApi.getPlayback(player)`, `EmoteApi.getPlayback(sessionId)` | An `Optional<Playback>` containing the active session if found. |
| `Playback.snapshot()` | A `PlaybackInfo` snapshot of the session's current state, elapsed ticks, timeline tick, cursor, and placement. |
| `Playback.getTimeline()` | Resolved `PlaybackTimeline` containing duration, loop mode, loop start, and segments. Returns `null` before the first frame is applied. |
| `Playback.getNodeWorldPosition(nodeId)` | The node's transformed origin as an `Optional<Vec3>` in world coordinates, including nodes without entity attachments. An unknown node returns empty. |

| Playback data | Meaning |
|---|---|
| `Playback.getState()`, `Playback.isActive()` | Current playback state and whether the session is pending or running. |
| `Playback.getElapsedTicks()` | Ticks applied since the session began. The count is monotonic across seeks and loops. |
| `Playback.getTimelineTick()` | Current tick in the complete playback timeline. |
| `Playback.getCursor()` | Current segment, Sequence step and repetition, phase, segment-local tick, and active Animation. |
| `Playback.getPlacement()` | Current root position, yaw, and placement mode. |
| `Playback.getAnimationTick()` | Animation-local tick, or `null` outside the `ANIMATION` phase. |
| `PlaybackTimeline.segments()` | Resolved `START_DELAY`, `ANIMATION`, `TRANSITION`, `WAIT`, `EMPTY`, `LOOP_DELAY`, or `HOLD` segments. Random choices and repeats are already resolved for this session. |

Segment ranges include the start tick and exclude the end tick. An indefinite hold has no end tick. Sequence step and repetition indices are zero-based and refer to the original step and repetition.

## Playback position control

Move within a running playback without creating a new session.

| API | Behavior |
|---|---|
| `Playback.seekTimeline(tick)` | Queues a move to a timeline tick, including Sequence transitions and waits. |
| `Playback.seekAnimation(tick)` | Queues a move within the active Animation. Timeline position and segment progress are preserved. The remaining duration is recalculated when the clip uses a fixed clock. Returns `false` outside the Animation phase. |
| `Playback.seekStep(stepIndex, repeatIndex, tick)` | Queues a move to a Sequence step and repetition at the given Animation tick. Use tick `0` for `emote:empty`. |

These methods report whether the move was queued. Position changes are unavailable for `server_sync` playback. Target ticks start at zero. Invalid indices, out-of-range ticks, and timeline positions that cannot be mapped to the clip throw `IllegalArgumentException`. Use `seekAnimation` or `seekStep` for a custom Animation clock when the timeline position cannot be reconstructed.

Seeking applies the destination pose. Changing the active Sequence Animation closes the outgoing segment and starts the incoming segment.

## Playback placement

Choose whether the display root follows the actor or stays at an external position. External placement still uses the player's current world and retains player behavior and stop conditions.

| API | Behavior |
|---|---|
| `PlaybackPlacement.actor()` | Follows the actor's position and yaw. |
| `PlaybackPlacement.external(position, yaw)` | Fixes the root at a world `Vec3` position and yaw in degrees. Values must be finite. |
| `Playback.setPlacement(placement)` | Queues a placement change for an active session. Actor placement resumes following the actor when the session has one. |

## Emote registration and lookup

Register Animations and Sequences at runtime or inspect loaded emotes. Runtime registrations survive reloads.

| API | Result |
|---|---|
| `register(animation)` | Registers an `EmoteAnimation` and returns a `Registration`. Invalid content throws `EmoteLoadException`. |
| `register(sequence)` | Registers an `EmoteSequence` and returns a `Registration`. Referenced Animations must already be registered or loaded and compatible. |
| `get(emoteId)` | `Optional<EmoteInfo>`; empty when the ID is not loaded. |
| `getAll()` | A list of all loaded `EmoteInfo` entries. |
| `Registration.getId()` | Registered identifier. |
| `Registration.isRegistered()` | Whether this registration is still active. |
| `Registration.unregister()` | Removes the registration; returns whether it was removed. |

## Event listeners

Listen for playback requests or session start and stop events. Keep the returned registration to remove a listener later.

| API | Behavior |
|---|---|
| `addPlayListener(listener)` | Registers an `EmotePlayListener` and returns a `ListenerRegistration`. |
| `EmotePlayListener.onStarting(event)` | Receives an `EmotePlayEvent` before playback for a player. |
| `EmotePlayEvent.player()`, `emote()`, `source()` | The requesting player, emote information, and playback source. |
| `EmotePlayEvent.cancel(message)` | Cancels the request with a Minecraft text component. |
| `addPlaybackListener(listener)` | Registers an `EmotePlaybackListener`; returns `ListenerRegistration`. |
| `EmotePlaybackListener.onStarted(playback)` | Receives the started session's `Playback`. |
| `EmotePlaybackListener.onStopped(playback, reason)` | Receives the stopped session's `Playback` and `PlaybackStopReason`. |
| `ListenerRegistration.unregister()` | Removes the listener; returns whether it was removed. |

## Lifecycle callbacks

Register a named callback and select it in an Animation or Sequence's root `callbacks` array. See [Animation callbacks](animation.md#callbacks) for the JSON format. An unregistered name rejects playback.

| API | Behavior |
|---|---|
| `registerCallbacks(name, callbacks)` | Registers an `EmoteCallbacks` implementation under an identifier and returns a `Registration`. |
| `EmoteCallbacks.onStart(context)` | Runs when the callback's playback scope begins. |
| `EmoteCallbacks.onTick(context)` | Runs on playback ticks within that scope. |
| `EmoteCallbacks.onLoop(context)` | Runs on loop callbacks within that scope. |
| `EmoteCallbacks.onClose(context)` | Runs when that scope closes. |

### Callback context

`PlaybackContext` extends `Playback` with data for the current callback scope. A Sequence and its Animation segments have separate callback contexts.

| API | Result or behavior |
|---|---|
| `getPayload()` | The unchanged payload string from the JSON callback entry. |
| `getScopeEmoteId()` | ID of the Animation or Sequence that owns this callback scope. |
| `getScopeElapsedTicks()` | Elapsed ticks since this callback scope began. The inherited `getElapsedTicks()` counts from the beginning of the session. |
| `getCloseReason()` | Optional reason this callback scope closed. The inherited `getStopReason()` reports the session's stop reason. |
| `getUserState()`, `setUserState(state)` | Reads or replaces custom state for this callback context. |

Callbacks can also use these inherited `Playback` methods.

| API | Result |
|---|---|
| `getServer()`, `getWorld()` | The Minecraft server and playback world. |
| `getActor(name)` | Actor entity as an `Optional<Entity>`; use `"actor"` for the playback actor. |
| `getAttachmentEntity(nodeId, attachmentId)` | Display entity as an `Optional<Entity>`; empty for attachments without an entity or unavailable entities. |
| `getRootPosition()` | Current root world position. |
