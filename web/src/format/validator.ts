import type { EmoteAnimation, EmoteEvent, Matrix16 } from "./emoteAnimation";
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
  if (animation.schema_version !== 3) add(issues, "schema_version", "must be 3");
  if (!isResourceLocation(animation.id)) add(issues, "id", "must be a Minecraft resource location");
  if (!animation.metadata.name.trim()) add(issues, "metadata.name", "must not be empty");
  if (!Number.isFinite(animation.settings.player.stop_conditions.movement_distance)
    || animation.settings.player.stop_conditions.movement_distance < 0) {
    add(issues, "settings.player.stop_conditions.movement_distance", "must be a finite non-negative number");
  }
  validateTime(animation.settings.cooldown, 0, "settings.cooldown", issues);
  const durationTicks = validateTime(animation.timeline.duration, 1, "timeline.duration", issues);
  if (durationTicks !== null && durationTicks > MAX_ANIMATION_DURATION_TICKS) {
    add(issues, "timeline.duration", `must not exceed ${MAX_ANIMATION_DURATION_TICKS} ticks`);
  }
  const loopDelayTicks = validateTime(animation.settings.playback.loop_delay, 0, "settings.playback.loop_delay", issues);
  if (animation.settings.playback.mode === "once" && loopDelayTicks !== null && loopDelayTicks !== 0) {
    add(issues, "settings.playback.loop_delay", "must resolve to 0 ticks when mode is once");
  }

  const nodeIds = new Set(Object.keys(animation.nodes));
  for (const [nodeId, node] of Object.entries(animation.nodes)) {
    if (!nodeId.trim()) add(issues, "nodes", "node id must not be blank");
    if (!(["scene", "initiator", "partner"] as const).includes(node.space)) {
      add(issues, `nodes.${nodeId}.space`, "must be scene, initiator, or partner");
    }
    validateMatrix(node.default_matrix, `nodes.${nodeId}.default_matrix`, issues);
    if (node.type === "anchor" && ("visible" in node || "entity_nbt" in node)) {
      add(issues, `nodes.${nodeId}`, "anchor cannot define visible or entity_nbt");
    }
    if (node.type === "item_display") {
      if (!ITEM_DISPLAY_VALUES.has(node.item_display)) {
        add(issues, `nodes.${nodeId}.item_display`, "uses an unsupported item display context");
      }
      if (node.skin && !isNonNegativeInt32(node.skin.order)) {
        add(issues, `nodes.${nodeId}.skin.order`, "must be a non-negative Java integer");
      }
      if (node.skin && node.skin.participant !== node.space) {
        add(issues, `nodes.${nodeId}.skin.participant`, "must match the node space");
      }
    }
  }

  let previousTick = -1;
  const lastTransformTick = new Map<string, number>();
  animation.timeline.keyframes.forEach((keyframe, index) => {
    const path = `timeline.keyframes[${index}]`;
    const tick = validateTime(keyframe.time, 0, `${path}.time`, issues);
    if (tick === null) return;
    if (durationTicks !== null && tick > durationTicks) add(issues, `${path}.time`, "must be within 0..duration");
    if (tick <= previousTick) add(issues, `${path}.time`, "must be strictly ascending");
    previousTick = tick;
    const defaultDuration = keyframe.interpolation_duration === undefined
      ? 0
      : validateTime(keyframe.interpolation_duration, 0, `${path}.interpolation_duration`, issues);
    for (const [nodeId, transform] of Object.entries(keyframe.node_transforms ?? {})) {
      if (!nodeIds.has(nodeId)) add(issues, `${path}.node_transforms.${nodeId}`, "references an unknown node");
      validateMatrix(transform.matrix, `${path}.node_transforms.${nodeId}.matrix`, issues);
      const duration = transform.interpolation_duration === undefined
        ? defaultDuration
        : validateTime(transform.interpolation_duration, 0, `${path}.node_transforms.${nodeId}.interpolation_duration`, issues);
      const previous = lastTransformTick.get(nodeId) ?? 0;
      if (duration !== null && duration > tick - previous) {
        add(issues, `${path}.node_transforms.${nodeId}.interpolation_duration`, "exceeds the time since the previous node transform");
      }
      lastTransformTick.set(nodeId, tick);
    }
    for (const nodeId of Object.keys(keyframe.node_states ?? {})) {
      if (!nodeIds.has(nodeId)) add(issues, `${path}.node_states.${nodeId}`, "references an unknown node");
      else if (animation.nodes[nodeId]?.type === "anchor") {
        add(issues, `${path}.node_states.${nodeId}`, "anchor does not support visible state");
      }
    }
  });

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
  return issues;
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

function validateMatrix(matrix: Matrix16, path: string, issues: ValidationIssue[]): void {
  if (matrix.length !== 16 || matrix.some((value) => !Number.isFinite(value))) {
    add(issues, path, "must contain 16 finite numbers");
  }
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
