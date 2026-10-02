import { isResourceLocation } from "./resourceLocation";
import type { EmoteAnimation, EmoteCallback } from "./emoteAnimation";
import {
  optionalBoolean,
  optionalRecord,
  optionalString,
  requireArray,
  requireBoolean,
  requireNumber,
  requireNumberArray,
  requireRecord,
  requireString,
  requireStringArray,
  requireStringValue,
  type RuntimeRecord,
} from "./runtimeValue";

const NODE_TYPES = ["anchor", "item_display", "block_display", "text_display"] as const;
const LOOP_TYPES = ["once", "hold", "loop", "server_sync"] as const;
const SKIN_PARTS = ["head", "body", "left_arm", "right_arm", "left_leg", "right_leg"] as const;
const NODE_SPACES = ["scene", "actor"] as const;

export function requireEmoteAnimation(value: unknown): EmoteAnimation {
  const root = requireRecord(value, "animation");
  requireStringValue(root.type, ["animation"] as const, "type");
  if (requireNumber(root.schema_version, "schema_version") !== 4) throw new Error("schema_version must be 4.");
  requireString(root.id, "id");
  requireMetadata(root.metadata);
  requireSettings(root.settings);
  const molang = optionalRecord(root.molang, "molang");
  if (molang) {
    optionalString(molang.initialize, "molang.initialize");
    optionalString(molang.tick, "molang.tick");
  }
  requireSchema4Nodes(root.nodes);
  requireSchema4Timeline(root.timeline);
  return normalizeSchemaDefaults({ ...root, ...(root.callbacks === undefined ? {} : { callbacks: parseCallbacks(root.callbacks) }) });
}

export function parseCallbacks(value: unknown): EmoteCallback[] {
  if (value === undefined) return [];
  return requireArray(value, "callbacks").map((value, index) => {
    const path = `callbacks[${index}]`;
    const callback = requireRecord(value, path);
    const name = requireString(callback.name, `${path}.name`);
    if (!isResourceLocation(name)) throw new Error(`${path}.name must be a Minecraft resource location.`);
    const payload = callback.payload === undefined ? "" : requireString(callback.payload, `${path}.payload`);
    return { name, payload };
  });
}

function requireMetadata(value: unknown): void {
  const metadata = requireRecord(value, "metadata");
  requireString(metadata.name, "metadata.name");
  requireString(metadata.description, "metadata.description");
}

function requireSettings(value: unknown): void {
  const settings = requireRecord(value, "settings");
  requireBoolean(settings.standalone, "settings.standalone");
  requireString(settings.cooldown, "settings.cooldown");
  requireNumber(settings.rotation_deadzone, "settings.rotation_deadzone");
  optionalString(settings.display_interpolation, "settings.display_interpolation");
  const player = requireRecord(settings.player, "settings.player");
  requireBoolean(player.hidden, "settings.player.hidden");
  const stopConditions = requireRecord(player.stop_conditions, "settings.player.stop_conditions");
  requireNumber(stopConditions.movement_distance, "settings.player.stop_conditions.movement_distance");
  requireBoolean(stopConditions.jump, "settings.player.stop_conditions.jump");
  requireBoolean(stopConditions.submerge, "settings.player.stop_conditions.submerge");
  requireBoolean(stopConditions.ride, "settings.player.stop_conditions.ride");
  requireBoolean(stopConditions.damage, "settings.player.stop_conditions.damage");
  requireBoolean(stopConditions.attack, "settings.player.stop_conditions.attack");
  requireBoolean(stopConditions.game_mode_change, "settings.player.stop_conditions.game_mode_change");
  const playback = requireRecord(settings.playback, "settings.playback");
  requireStringValue(playback.mode, LOOP_TYPES, "settings.playback.mode");
  optionalString(playback.loop_start, "settings.playback.loop_start");
  optionalString(playback.loop_end, "settings.playback.loop_end");
  optionalString(playback.loop_delay, "settings.playback.loop_delay");
}

function requireSchema4Nodes(value: unknown): void {
  const nodes = requireRecord(value, "nodes");
  for (const [nodeId, nodeValue] of Object.entries(nodes)) {
    const path = `nodes.${nodeId}`;
    const node = requireRecord(nodeValue, path);
    const type = requireStringValue(node.type, NODE_TYPES, `${path}.type`);
    optionalString(node.parent, `${path}.parent`);
    if (node.space !== undefined) requireStringValue(node.space, NODE_SPACES, `${path}.space`);
    requireLocalTransform(node.transform, `${path}.transform`);
    if (type === "anchor") {
      optionalAnchorFields(node, path);
      continue;
    }
    optionalBoolean(node.visible, `${path}.visible`);
    optionalString(node.entity_nbt, `${path}.entity_nbt`);
    if (type === "item_display") {
      requireString(node.item_stack_snbt, `${path}.item_stack_snbt`);
      requireString(node.item_display, `${path}.item_display`);
      const skin = optionalRecord(node.skin, `${path}.skin`);
      if (skin) {
        requireStringValue(skin.part, SKIN_PARTS, `${path}.skin.part`);
        if (skin.participant !== undefined) throw new Error(`${path}.skin.participant is no longer supported.`);
        requireNumber(skin.order, `${path}.skin.order`);
      }
    } else if (type === "block_display") {
      requireString(node.block_state_snbt, `${path}.block_state_snbt`);
    } else if (node.text === undefined) {
      throw new Error(`${path}.text is required.`);
    }
  }
}

function normalizeSchemaDefaults(root: RuntimeRecord): EmoteAnimation {
  return root as unknown as EmoteAnimation;
}

function requireLocalTransform(value: unknown, path: string): void {
  const transform = requireRecord(value, path);
  requireNumberArray(transform.position, `${path}.position`);
  requireNumberArray(transform.rotation, `${path}.rotation`);
  requireNumberArray(transform.scale, `${path}.scale`);
}

function optionalAnchorFields(node: RuntimeRecord, path: string): void {
  optionalBoolean(node.visible, `${path}.visible`);
  optionalString(node.entity_nbt, `${path}.entity_nbt`);
}

function requireSchema4Timeline(value: unknown): void {
  const timeline = requireRecord(value, "timeline");
  requireString(timeline.duration, "timeline.duration");
  const tracks = requireRecord(timeline.tracks, "timeline.tracks");
  for (const [nodeId, trackValue] of Object.entries(tracks)) {
    const path = `timeline.tracks.${nodeId}`;
    const track = requireRecord(trackValue, path);
    requireVectorTrack(track.position, `${path}.position`);
    requireVectorTrack(track.rotation, `${path}.rotation`);
    requireVectorTrack(track.scale, `${path}.scale`);
    requireVisibilityTrack(track.visible, `${path}.visible`);
    requireNbtTrack(track.nbt, `${path}.nbt`);
  }
  const events = optionalRecord(timeline.events, "timeline.events");
  if (!events) return;
  requireEvents(events.start, "timeline.events.start", false);
  requireEvents(events.timeline, "timeline.events.timeline", true);
  requireEvents(events.loop, "timeline.events.loop", false);
  requireEvents(events.stop, "timeline.events.stop", false);
}

function requireVectorTrack(value: unknown, path: string): void {
  if (value === undefined) return;
  requireArray(value, path).forEach((frameValue, index) => {
    const framePath = `${path}[${index}]`;
    const frame = requireRecord(frameValue, framePath);
    requireString(frame.time, `${framePath}.time`);
    requireMolangVector(frame.value, `${framePath}.value`);
    requireMolangVector(frame.pre, `${framePath}.pre`);
    requireMolangVector(frame.post, `${framePath}.post`);
    optionalString(frame.interpolation, `${framePath}.interpolation`);
    optionalString(frame.easing, `${framePath}.easing`);
  });
}

function requireMolangVector(value: unknown, path: string): void {
  if (value === undefined) return;
  requireArray(value, path).forEach((entry, index) => {
    if (typeof entry !== "number" && typeof entry !== "string") throw new Error(`${path}[${index}] must be a number or string.`);
  });
}

function requireVisibilityTrack(value: unknown, path: string): void {
  if (value === undefined) return;
  requireArray(value, path).forEach((frameValue, index) => {
    const framePath = `${path}[${index}]`;
    const frame = requireRecord(frameValue, framePath);
    requireString(frame.time, `${framePath}.time`);
    if (typeof frame.value !== "boolean" && typeof frame.value !== "string") throw new Error(`${framePath}.value must be a boolean or string.`);
  });
}

function requireNbtTrack(value: unknown, path: string): void {
  if (value === undefined) return;
  requireArray(value, path).forEach((frameValue, index) => {
    const framePath = `${path}[${index}]`;
    const frame = requireRecord(frameValue, framePath);
    requireString(frame.time, `${framePath}.time`);
    const valuePath = `${framePath}.value`;
    if (typeof frame.value === "string") return;
    const molang = requireRecord(frame.value, valuePath);
    requireString(molang.molang, `${valuePath}.molang`);
  });
}

function requireEvents(value: unknown, path: string, timeline: boolean): void {
  if (value === undefined) return;
  requireArray(value, path).forEach((eventValue, index) => {
    const eventPath = `${path}[${index}]`;
    const event = requireRecord(eventValue, eventPath);
    if (timeline) requireString(event.time, `${eventPath}.time`);
    requireEventSource(event.source, `${eventPath}.source`);
    requireEventOrigin(event.origin, `${eventPath}.origin`);
    requireStringArray(event.commands, `${eventPath}.commands`);
    if ("callbacks" in event) throw new Error(`${eventPath}.callbacks is no longer supported.`);
  });
}

function requireEventSource(value: unknown, path: string): void {
  const source = requireRecord(value, path);
  const type = requireStringValue(source.type, ["player", "server", "node"] as const, `${path}.type`);
  if (type === "node") requireString(source.node, `${path}.node`);
}

function requireEventOrigin(value: unknown, path: string): void {
  const origin = requireRecord(value, path);
  const type = requireStringValue(origin.type, ["root", "node"] as const, `${path}.type`);
  if (type === "node") requireString(origin.node, `${path}.node`);
  if (origin.offset !== undefined) requireNumberArray(origin.offset, `${path}.offset`);
}
