import type { EmoteAnimation, EmoteEvent, Matrix16 } from "./emoteAnimation";
import { isResourceLocation } from "./resourceLocation";
import { MAX_ANIMATION_DURATION_TICKS, TICKS_PER_SECOND } from "./time";

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
  if (animation.schema_version !== 1) add(issues, "schema_version", "must be 1");
  if (animation.tick_rate !== TICKS_PER_SECOND) add(issues, "tick_rate", `must be ${TICKS_PER_SECOND}`);
  if (!animation.minecraft_version.trim()) add(issues, "minecraft_version", "must not be empty");
  if (!isResourceLocation(animation.id)) add(issues, "id", "must be a Minecraft resource location");
  if (!animation.metadata.name.trim()) add(issues, "metadata.name", "must not be empty");
  if (!Number.isFinite(animation.player.stop_conditions.movement_distance)
    || animation.player.stop_conditions.movement_distance < 0) {
    add(issues, "player.stop_conditions.movement_distance", "must be a finite non-negative number");
  }
  if (animation.transform_space.coordinate_space !== "root_local") add(issues, "transform_space.coordinate_space", "must be root_local");
  if (animation.transform_space.matrix_layout !== "row_major") add(issues, "transform_space.matrix_layout", "must be row_major");
  if (animation.transform_space.matrix_size !== 16) add(issues, "transform_space.matrix_size", "must be 16");
  if (!isPositiveInt32(animation.timeline.duration_ticks)) {
    add(issues, "timeline.duration_ticks", "must be a positive Java integer");
  } else if (animation.timeline.duration_ticks > MAX_ANIMATION_DURATION_TICKS) {
    add(issues, "timeline.duration_ticks", `must not exceed ${MAX_ANIMATION_DURATION_TICKS}`);
  }
  if (!isNonNegativeInt32(animation.timeline.loop_delay_ticks)) {
    add(issues, "timeline.loop_delay_ticks", "must be a non-negative Java integer");
  }
  if (animation.timeline.loop === "once" && animation.timeline.loop_delay_ticks !== 0) {
    add(issues, "timeline.loop_delay_ticks", "must be 0 when loop is once");
  }

  const nodeIds = new Set(Object.keys(animation.nodes));
  for (const [nodeId, node] of Object.entries(animation.nodes)) {
    if (!nodeId.trim()) add(issues, "nodes", "node id must not be blank");
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
    }
  }

  let previousTick = -1;
  const lastTransformTick = new Map<string, number>();
  animation.timeline.keyframes.forEach((keyframe, index) => {
    const path = `timeline.keyframes[${index}]`;
    if (!isNonNegativeInt32(keyframe.tick) || keyframe.tick > animation.timeline.duration_ticks) {
      add(issues, `${path}.tick`, "must be within 0..duration_ticks");
    }
    if (keyframe.tick <= previousTick) add(issues, `${path}.tick`, "must be strictly ascending");
    previousTick = keyframe.tick;
    const defaultDuration = keyframe.interpolation_duration_ticks ?? 0;
    if (!isNonNegativeInt32(defaultDuration)) {
      add(issues, `${path}.interpolation_duration_ticks`, "must be a non-negative Java integer");
    }
    for (const [nodeId, transform] of Object.entries(keyframe.node_transforms ?? {})) {
      if (!nodeIds.has(nodeId)) add(issues, `${path}.node_transforms.${nodeId}`, "references an unknown node");
      validateMatrix(transform.matrix, `${path}.node_transforms.${nodeId}.matrix`, issues);
      const duration = transform.interpolation_duration_ticks ?? defaultDuration;
      const previous = lastTransformTick.get(nodeId) ?? 0;
      if (!isNonNegativeInt32(duration) || duration > keyframe.tick - previous) {
        add(issues, `${path}.node_transforms.${nodeId}.interpolation_duration_ticks`, "exceeds the time since the previous node transform");
      }
      lastTransformTick.set(nodeId, keyframe.tick);
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
    if (!isNonNegativeInt32(event.tick) || event.tick >= animation.timeline.duration_ticks) {
      add(issues, `${path}.tick`, "must be within 0..duration_ticks - 1");
    }
    if (event.tick < previousEventTick) add(issues, `${path}.tick`, "timeline events must be ordered by tick");
    previousEventTick = event.tick;
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

function isPositiveInt32(value: number): boolean {
  return value > 0 && isNonNegativeInt32(value);
}

function add(issues: ValidationIssue[], path: string, message: string): void {
  issues.push({ path, message });
}
