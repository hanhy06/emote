import type {
  EmoteAnimation,
  EmoteEvent,
  EmoteVectorKeyframe,
  MolangScalar,
} from "./emoteAnimation";
import { isResourceLocation } from "./resourceLocation";
import { MAX_ANIMATION_DURATION_TICKS } from "./time";
import { parseMinecraftTime } from "./minecraftTime";

const JAVA_INT_MAX = 2_147_483_647;
const ITEM_DISPLAY_VALUES = new Set([
  "none",
  "thirdperson_lefthand",
  "thirdperson_righthand",
  "firstperson_lefthand",
  "firstperson_righthand",
  "head",
  "gui",
  "ground",
  "fixed",
  "on_shelf",
]);

export interface ValidationIssue {
  path: string;
  message: string;
}

export function validateEmoteAnimation(animation: EmoteAnimation): ValidationIssue[] {
  const issues: ValidationIssue[] = [];
  if (animation.type !== "animation") add(issues, "type", "must be animation");
  if (animation.schema_version !== 4) add(issues, "schema_version", "must be 4");
  validateCommon(animation, issues);

  const nodeIds = new Set(Object.keys(animation.nodes));
  if (nodeIds.size === 0) add(issues, "nodes", "must not be empty");
  for (const [nodeId, node] of Object.entries(animation.nodes)) {
    const path = `nodes.${nodeId}`;
    if (!nodeId.trim()) add(issues, "nodes", "node id must not be blank");
    if (node.parent) {
      if (!nodeIds.has(node.parent)) add(issues, `${path}.parent`, "references an unknown node");
      if (node.space !== undefined) add(issues, `${path}.space`, "is not allowed on child nodes");
    } else if (!(node.space && (["scene", "initiator", "partner"] as const).includes(node.space))) {
      add(issues, `${path}.space`, "root node must define scene, initiator, or partner");
    }
    validateVec3(node.transform.position, `${path}.transform.position`, issues);
    validateVec3(node.transform.rotation, `${path}.transform.rotation`, issues);
    validateVec3(node.transform.scale, `${path}.transform.scale`, issues);
    if (node.type === "anchor" && ("visible" in node || "entity_nbt" in node)) {
      add(issues, path, "anchor cannot define visible or entity_nbt");
    }
    if (node.type === "item_display") validateItemNode(animation, nodeId, node, path, issues);
  }
  validateParentCycles(animation, issues);

  const durationTicks = validateTime(animation.timeline.duration, 1, "timeline.duration", issues);
  if (durationTicks !== null && durationTicks > MAX_ANIMATION_DURATION_TICKS) {
    add(issues, "timeline.duration", `must not exceed ${MAX_ANIMATION_DURATION_TICKS} ticks`);
  }
  for (const [nodeId, tracks] of Object.entries(animation.timeline.tracks)) {
    const path = `timeline.tracks.${nodeId}`;
    const node = animation.nodes[nodeId];
    if (!node) add(issues, path, "references an unknown node");
    const entries = [tracks.position, tracks.rotation, tracks.scale, tracks.visible].filter(Boolean);
    if (entries.length === 0) add(issues, path, "must contain at least one track");
    validateVectorTrack(tracks.position, `${path}.position`, durationTicks, issues);
    validateVectorTrack(tracks.rotation, `${path}.rotation`, durationTicks, issues);
    validateVectorTrack(tracks.scale, `${path}.scale`, durationTicks, issues);
    validateVisibilityTrack(tracks.visible, `${path}.visible`, durationTicks, issues);
    if (node?.type === "anchor" && tracks.visible) add(issues, `${path}.visible`, "anchor does not support visible state");
  }

  validateEvents(animation, durationTicks, issues);
  return issues;
}

function validateCommon(animation: EmoteAnimation, issues: ValidationIssue[]): void {
  if (!isResourceLocation(animation.id)) add(issues, "id", "must be a Minecraft resource location");
  if (!animation.metadata.name.trim()) add(issues, "metadata.name", "must not be empty");
  if (!Number.isFinite(animation.settings.player.stop_conditions.movement_distance)
    || animation.settings.player.stop_conditions.movement_distance < 0) {
    add(issues, "settings.player.stop_conditions.movement_distance", "must be a finite non-negative number");
  }
  if (!Number.isFinite(animation.settings.rotation_deadzone)
    || animation.settings.rotation_deadzone < 0 || animation.settings.rotation_deadzone > 180) {
    add(issues, "settings.rotation_deadzone", "must be a finite number between 0 and 180");
  }
  validateTime(animation.settings.cooldown, 0, "settings.cooldown", issues);
  const loopDelayTicks = validateTime(animation.settings.playback.loop_delay, 0, "settings.playback.loop_delay", issues);
  if (["once", "hold"].includes(animation.settings.playback.mode) && loopDelayTicks !== null && loopDelayTicks !== 0) {
    add(issues, "settings.playback.loop_delay", "must resolve to 0 ticks when mode is once or hold");
  }
}

function validateItemNode(
  animation: EmoteAnimation,
  nodeId: string,
  node: Extract<EmoteAnimation["nodes"][string], { type: "item_display" }>,
  path: string,
  issues: ValidationIssue[],
): void {
  if ((node.item_stack_snbt === undefined) === (node.item_source === undefined)) {
    add(issues, path, "must define exactly one of item_stack_snbt or item_source");
  }
  if (node.item_source && node.skin) add(issues, `${path}.skin`, "is not supported by participant hand items");
  if (!ITEM_DISPLAY_VALUES.has(node.item_display)) add(issues, `${path}.item_display`, "uses an unsupported item display context");
  if (node.skin && !isNonNegativeInt32(node.skin.order)) add(issues, `${path}.skin.order`, "must be a non-negative Java integer");
  if (node.skin) {
    const rootSpace = inheritedNodeSpace(animation, nodeId);
    if (rootSpace && node.skin.participant !== rootSpace) add(issues, `${path}.skin.participant`, "must match the node space");
  }
}

function inheritedNodeSpace(animation: EmoteAnimation, nodeId: string): EmoteAnimation["nodes"][string]["space"] {
  const seen = new Set<string>();
  let current = animation.nodes[nodeId];
  while (current?.parent && !seen.has(current.parent)) {
    seen.add(current.parent);
    current = animation.nodes[current.parent];
  }
  return current?.space;
}

function validateParentCycles(animation: EmoteAnimation, issues: ValidationIssue[]): void {
  for (const nodeId of Object.keys(animation.nodes)) {
    const seen = new Set<string>();
    let current: string | undefined = nodeId;
    while (current) {
      if (seen.has(current)) {
        add(issues, `nodes.${nodeId}.parent`, "creates a parent cycle");
        break;
      }
      seen.add(current);
      current = animation.nodes[current]?.parent;
    }
  }
}

function validateVectorTrack(
  frames: EmoteVectorKeyframe[] | undefined,
  path: string,
  durationTicks: number | null,
  issues: ValidationIssue[],
): void {
  if (!frames) return;
  if (frames.length === 0) {
    add(issues, path, "must not be empty");
    return;
  }
  let previousTick = -1;
  frames.forEach((frame, index) => {
    const framePath = `${path}[${index}]`;
    const tick = validateTime(frame.time, 0, `${framePath}.time`, issues);
    if (tick !== null) {
      if (index === 0 && tick !== 0) add(issues, `${framePath}.time`, "first keyframe must be at 0t");
      if (tick <= previousTick) add(issues, `${framePath}.time`, "must be strictly ascending");
      if (durationTicks !== null && tick > durationTicks) add(issues, `${framePath}.time`, "must be within 0..duration");
      previousTick = tick;
    }
    const hasValue = frame.value !== undefined;
    const hasPrePost = frame.pre !== undefined && frame.post !== undefined;
    if (hasValue === hasPrePost) add(issues, framePath, "must define either value or both pre and post");
    if (frame.value) validateMolangVec3(frame.value, `${framePath}.value`, issues);
    if (frame.pre) validateMolangVec3(frame.pre, `${framePath}.pre`, issues);
    if (frame.post) validateMolangVec3(frame.post, `${framePath}.post`, issues);
    if (index === frames.length - 1 && (frame.interpolation !== undefined || frame.easing !== undefined)) {
      add(issues, framePath, "last keyframe must not define interpolation or easing");
    }
    if (frame.interpolation === "step" && frame.easing !== undefined) add(issues, `${framePath}.easing`, "is not supported by step interpolation");
  });
}

function validateVisibilityTrack(
  frames: { time: string; value: boolean | string }[] | undefined,
  path: string,
  durationTicks: number | null,
  issues: ValidationIssue[],
): void {
  if (!frames) return;
  if (frames.length === 0) {
    add(issues, path, "must not be empty");
    return;
  }
  let previousTick = -1;
  frames.forEach((frame, index) => {
    const tick = validateTime(frame.time, 0, `${path}[${index}].time`, issues);
    if (tick === null) return;
    if (index === 0 && tick !== 0) add(issues, `${path}[${index}].time`, "first keyframe must be at 0t");
    if (tick <= previousTick) add(issues, `${path}[${index}].time`, "must be strictly ascending");
    if (durationTicks !== null && tick > durationTicks) add(issues, `${path}[${index}].time`, "must be within 0..duration");
    if (typeof frame.value === "string" && !frame.value.trim()) add(issues, `${path}[${index}].value`, "Molang must not be blank");
    previousTick = tick;
  });
}

function validateMolangVec3(values: readonly MolangScalar[], path: string, issues: ValidationIssue[]): void {
  if (values.length !== 3) add(issues, path, "must contain three values");
  values.forEach((value, index) => {
    if (typeof value === "number" && !Number.isFinite(value)) add(issues, `${path}[${index}]`, "must be finite");
    if (typeof value === "string" && !value.trim()) add(issues, `${path}[${index}]`, "Molang must not be blank");
  });
}

function validateVec3(values: readonly number[], path: string, issues: ValidationIssue[]): void {
  if (values.length !== 3 || values.some((value) => !Number.isFinite(value))) add(issues, path, "must contain three finite numbers");
}

function validateEvents(
  animation: EmoteAnimation,
  durationTicks: number | null,
  issues: ValidationIssue[],
): void {
  const events = animation.timeline.events;
  events?.start?.forEach((event, index) => validateEvent(event, `timeline.events.start[${index}]`, animation, issues));
  events?.loop?.forEach((event, index) => validateEvent(event, `timeline.events.loop[${index}]`, animation, issues));
  events?.stop?.forEach((event, index) => validateEvent(event, `timeline.events.stop[${index}]`, animation, issues));
  let previousEventTick = -1;
  events?.timeline?.forEach((event, index) => {
    const path = `timeline.events.timeline[${index}]`;
    const tick = validateTime(event.time, 0, `${path}.time`, issues);
    if (tick !== null && durationTicks !== null && tick >= durationTicks) add(issues, `${path}.time`, "must be within 0..duration - 1 tick");
    if (tick !== null && tick < previousEventTick) add(issues, `${path}.time`, "timeline events must be ordered by time");
    if (tick !== null) previousEventTick = tick;
    validateEvent(event, path, animation, issues);
  });
}

function validateEvent(event: EmoteEvent, path: string, animation: EmoteAnimation, issues: ValidationIssue[]): void {
  if (event.source.type === "node") {
    const node = animation.nodes[event.source.node];
    if (!node) add(issues, `${path}.source.node`, "references an unknown node");
    else if (node.type === "anchor") add(issues, `${path}.source.node`, "cannot reference an anchor");
  }
  if (event.origin.type === "node" && !animation.nodes[event.origin.node]) {
    add(issues, `${path}.origin.node`, "references an unknown node");
  }
  if (event.origin.offset && (event.origin.offset.length !== 3 || event.origin.offset.some((value) => !Number.isFinite(value)))) {
    add(issues, `${path}.origin.offset`, "must contain three finite numbers");
  }
  event.commands.forEach((command, index) => {
    if (!command.trim() || command.startsWith("/")) add(issues, `${path}.commands[${index}]`, "must be non-empty and omit the leading slash");
  });
}

function isNonNegativeInt32(value: number): boolean {
  return Number.isInteger(value) && value >= 0 && value <= JAVA_INT_MAX;
}

function validateTime(value: string, minimumTicks: number, path: string, issues: ValidationIssue[]): number | null {
  try {
    return parseMinecraftTime(value, minimumTicks);
  } catch (error) {
    add(issues, path, error instanceof Error ? error.message : "is not a valid Minecraft time");
    return null;
  }
}

function add(issues: ValidationIssue[], path: string, message: string): void {
  issues.push({ path, message });
}
