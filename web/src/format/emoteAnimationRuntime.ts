import type { EmoteAnimation } from "./emoteAnimation";
import {
  optionalBoolean,
  optionalNumber,
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
const LOOP_TYPES = ["once", "loop"] as const;
const SKIN_PARTS = ["head", "body", "left_arm", "right_arm", "left_leg", "right_leg"] as const;

export function requireEmoteAnimation(value: unknown): EmoteAnimation {
  const root = requireRecord(value, "animation");
  requireNumber(root.schema_version, "schema_version");
  requireString(root.minecraft_version, "minecraft_version");
  requireNumber(root.tick_rate, "tick_rate");
  requireString(root.id, "id");
  requireMetadata(root.metadata);
  requireTransformSpace(root.transform_space);
  requireNodes(root.nodes);
  requireTimeline(root.timeline);
  return value as EmoteAnimation;
}

function requireMetadata(value: unknown): void {
  const metadata = requireRecord(value, "metadata");
  requireString(metadata.name, "metadata.name");
  requireString(metadata.description, "metadata.description");
  requireBoolean(metadata.hide_player, "metadata.hide_player");
}

function requireTransformSpace(value: unknown): void {
  const transformSpace = requireRecord(value, "transform_space");
  requireString(transformSpace.coordinate_space, "transform_space.coordinate_space");
  requireString(transformSpace.matrix_layout, "transform_space.matrix_layout");
  requireNumber(transformSpace.matrix_size, "transform_space.matrix_size");
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
  requireNumber(timeline.duration_ticks, "timeline.duration_ticks");
  requireStringValue(timeline.loop, LOOP_TYPES, "timeline.loop");
  requireNumber(timeline.loop_delay_ticks, "timeline.loop_delay_ticks");
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
  requireNumber(keyframe.tick, `${path}.tick`);
  optionalNumber(keyframe.interpolation_duration_ticks, `${path}.interpolation_duration_ticks`);
  const transforms = optionalRecord(keyframe.node_transforms, `${path}.node_transforms`);
  for (const [nodeId, transformValue] of Object.entries(transforms ?? {})) {
    const transformPath = `${path}.node_transforms.${nodeId}`;
    const transform = requireRecord(transformValue, transformPath);
    requireNumberArray(transform.matrix, `${transformPath}.matrix`);
    optionalNumber(transform.interpolation_duration_ticks, `${transformPath}.interpolation_duration_ticks`);
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
    if (timeline) requireNumber(event.tick, `${eventPath}.tick`);
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
