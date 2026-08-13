import type { EmoteAnimation } from "./emoteAnimation";
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
const LOOP_TYPES = ["once", "loop", "server_sync"] as const;
const SKIN_PARTS = ["head", "body", "left_arm", "right_arm", "left_leg", "right_leg"] as const;

export function requireEmoteAnimation(value: unknown): EmoteAnimation {
  const root = requireRecord(value, "animation");
  requireStringValue(root.type, ["animation"] as const, "type");
  requireNumber(root.schema_version, "schema_version");
  requireString(root.id, "id");
  requireMetadata(root.metadata);
  requireSettings(root.settings);
  requireNodes(root.nodes);
  requireTimeline(root.timeline);
  return value as EmoteAnimation;
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
  requireString(playback.loop_delay, "settings.playback.loop_delay");
}

function requireNodes(value: unknown): void {
  const nodes = requireRecord(value, "nodes");
  for (const [nodeId, nodeValue] of Object.entries(nodes)) {
    const path = `nodes.${nodeId}`;
    const node = requireRecord(nodeValue, path);
    const type = requireStringValue(node.type, NODE_TYPES, `${path}.type`);
    requireNumberArray(node.default_matrix, `${path}.default_matrix`);
    if (type === "anchor") {
      optionalAnchorFields(node, path);
      continue;
    }
    optionalBoolean(node.visible, `${path}.visible`);
    optionalString(node.entity_nbt, `${path}.entity_nbt`);
    if (type === "item_display") {
      requireString(node.item_stack_snbt, `${path}.item_stack_snbt`);
      requireString(node.item_display, `${path}.item_display`);
      requireSkin(node.skin, `${path}.skin`);
    } else if (type === "block_display") {
      requireString(node.block_state_snbt, `${path}.block_state_snbt`);
    } else {
      if (node.text === undefined) throw new Error(`${path}.text is required.`);
    }
  }
}

function optionalAnchorFields(node: RuntimeRecord, path: string): void {
  optionalBoolean(node.visible, `${path}.visible`);
  optionalString(node.entity_nbt, `${path}.entity_nbt`);
}

function requireSkin(value: unknown, path: string): void {
  const skin = optionalRecord(value, path);
  if (!skin) return;
  requireStringValue(skin.part, SKIN_PARTS, `${path}.part`);
  requireNumber(skin.order, `${path}.order`);
}

function requireTimeline(value: unknown): void {
  const timeline = requireRecord(value, "timeline");
  requireString(timeline.duration, "timeline.duration");
  requireArray(timeline.keyframes, "timeline.keyframes").forEach(requireKeyframe);
  const events = optionalRecord(timeline.events, "timeline.events");
  if (!events) return;
  requireEvents(events.start, "timeline.events.start", false);
  requireEvents(events.timeline, "timeline.events.timeline", true);
  requireEvents(events.loop, "timeline.events.loop", false);
  requireEvents(events.stop, "timeline.events.stop", false);
}

function requireKeyframe(value: unknown, index: number): void {
  const path = `timeline.keyframes[${index}]`;
  const keyframe = requireRecord(value, path);
  requireString(keyframe.time, `${path}.time`);
  optionalString(keyframe.interpolation_duration, `${path}.interpolation_duration`);
  const transforms = optionalRecord(keyframe.node_transforms, `${path}.node_transforms`);
  for (const [nodeId, transformValue] of Object.entries(transforms ?? {})) {
    const transformPath = `${path}.node_transforms.${nodeId}`;
    const transform = requireRecord(transformValue, transformPath);
    requireNumberArray(transform.matrix, `${transformPath}.matrix`);
    optionalString(transform.interpolation_duration, `${transformPath}.interpolation_duration`);
  }
  const states = optionalRecord(keyframe.node_states, `${path}.node_states`);
  for (const [nodeId, stateValue] of Object.entries(states ?? {})) {
    const state = requireRecord(stateValue, `${path}.node_states.${nodeId}`);
    requireBoolean(state.visible, `${path}.node_states.${nodeId}.visible`);
  }
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
