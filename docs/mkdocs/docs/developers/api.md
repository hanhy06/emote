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

Obtain the API with `EmoteApi.getInstance()`. State-changing calls and playback inspection must run on the Minecraft server thread. Unless another type is named, the tables below list methods on `EmoteApi`.

## Playback inspection

Inspect the current playback and its resolved timeline. Use `PlaybackInfo.sessionId()` to address a session. Missing sessions return `Optional.empty()`.

| API | Result |
|---|---|
| `getPlayback(player)` | The player's current `PlaybackInfo`. |
| `getPlayback(sessionId)` | The session's current `PlaybackInfo`. |
| `getTimeline(sessionId)` | `PlaybackTimeline` containing duration, loop mode, loop start, and resolved segments. |
| `getNodeWorldPosition(sessionId, nodeId)` | The node's transformed origin as an `Optional<Vec3>` in world coordinates. Supports anchors; an unknown node returns empty. |

| Playback data | Meaning |
|---|---|
| `PlaybackInfo.state()` | Current playback state. |
| `PlaybackInfo.elapsedTicks()` | Elapsed playback ticks. |
| `PlaybackInfo.timelineTick()` | Current tick in the complete playback timeline. |
| `PlaybackInfo.position()` | Current segment, Sequence step and repetition, phase, phase-local tick, and active Animation. |
| `PlaybackInfo.placement()` | Current root position, yaw, and placement mode. |
| `PlaybackPosition.animationTick()` | Animation-local tick; `null` outside the `ANIMATION` phase. |
| `PlaybackTimeline.segments()` | Resolved `ANIMATION`, `TRANSITION`, `WAIT`, `LOOP_DELAY`, or `HOLD` segments. Random choices and repeats are already resolved for this session. |

Segment ranges include the start tick and exclude the end tick. An indefinite hold has no end tick. Sequence step and repetition indices are zero-based and refer to the original step and repetition.

## Playback control

Start an emote for a player or stop an existing playback.

| API | Behavior |
|---|---|
| `play(player, emoteId)` | Starts playback with the default actor placement. |
| `play(player, emoteId, placement)` | Starts playback with the supplied `PlaybackPlacement`. |
| `stop(player)` | Stops the player's playback; returns whether a playback was stopped. |
| `stop(sessionId)` | Stops the session; returns whether a playback was stopped. |

`play` returns `PlayResult.Success` with `playback()`, or `PlayResult.Failure` with `errorMessage()`. API playback bypasses standalone visibility, disabled IDs, player permissions, and cooldown policy. The calling mod applies any desired policy; Emote still checks loaded IDs, cancellable play events, and playback-engine limits. Both stop methods use the manual stop reason.

## Playback position control

Move within a running playback without creating a new session.

| API | Behavior |
|---|---|
| `setTick(sessionId, tick)` | Moves to a tick in the complete playback timeline, including Sequence transitions and waits. |
| `setAnimationTick(sessionId, tick)` | Moves within the active Animation; returns `false` outside the Animation phase. |
| `setStep(sessionId, stepIndex, repeatIndex, tick)` | Moves to the resolved Animation at a Sequence step and repetition, using its local tick. |

Position changes are unavailable for `server_sync` playback. Target ticks start at zero and exclude the timeline or Animation end. Invalid indices or out-of-range ticks throw `IllegalArgumentException`; an absent session returns `false`.

Seeking applies the destination pose and its timeline events without replaying commands at skipped ticks. Changing the active Sequence Animation closes the outgoing segment and starts the incoming segment. Requests made during a callback or frame are applied after that processing completes.

## Playback placement

Choose whether the display root follows the actor or stays at an external position. External placement still uses the player's current world and retains player behavior and stop conditions.

| API | Behavior |
|---|---|
| `PlaybackPlacement.actor()` | Follows the actor's position and yaw. |
| `PlaybackPlacement.external(position, yaw)` | Fixes the root at a world `Vec3` position and yaw in degrees. Values must be finite. |
| `setPlacement(sessionId, placement)` | Changes position and yaw without restarting playback; returns `false` for an absent session. Actor placement resumes following the actor. |

## Emote registration and lookup

Register Animations and Sequences at runtime or inspect loaded emotes. Runtime registrations survive reloads.

| API | Result |
|---|---|
| `register(animation)` | Registers an `EmoteAnimation` and returns a `Registration`. Invalid content throws `EmoteAnimationLoadException`. |
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
| `addPlayListener(listener)` | Registers an `EmotePlayListener` receiving an `EmotePlayEvent` before playback; returns `ListenerRegistration`. |
| `EmotePlayEvent.player()`, `emote()`, `source()` | The requesting player, emote information, and playback source. |
| `EmotePlayEvent.cancel(message)` | Cancels the request with a Minecraft text component. |
| `addPlaybackListener(listener)` | Registers an `EmotePlaybackListener`; returns `ListenerRegistration`. |
| `EmotePlaybackListener.onStarted(playback)` | Receives the started session's `PlaybackInfo`. |
| `EmotePlaybackListener.onStopped(playback, reason)` | Receives the stopped session's `PlaybackInfo` and `PlaybackStopReason`. |
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

`PlaybackContext` provides the session, actors, nodes, and per-callback state. A Sequence and its Animation segments have separate callback contexts.

| API | Result or behavior |
|---|---|
| `getSessionId()` | Session UUID for `EmoteApi` queries and control. |
| `getPayload()` | The unchanged payload string from the JSON callback entry. |
| `getServer()`, `getWorld()` | The Minecraft server and playback world. |
| `getElapsedTicks()` | Elapsed ticks since this callback scope began. |
| `getTick()` | Current tick in the complete playback timeline. |
| `getAnimationTick()` | Animation-local tick. Animation callbacks use their segment's tick; root callbacks return `null` outside the Animation phase. |
| `setTick(tick)` | Moves within the complete playback timeline. |
| `setAnimationTick(tick)` | Moves within the active Animation. |
| `setStep(stepIndex, repeatIndex, tick)` | Moves to a resolved Sequence Animation. Position-control rules above apply. |
| `getActor(name)` | Actor entity as an `Optional<Entity>`; use `"actor"` for the playback actor. |
| `getNodeEntity(nodeId)` | Display entity as an `Optional<Entity>`; empty for anchors or unavailable entities. |
| `getNodeWorldPosition(nodeId)` | Transformed node origin in world coordinates; supports anchors. |
| `getRootPosition()` | Current root world position. |
| `getStopReason()` | Optional close reason. |
| `getUserState()`, `setUserState(state)` | Reads or replaces custom state for this callback context. |
