import type { EmoteAnimation, EmoteEvent, Matrix16 } from "./emoteAnimation";
import { isResourceLocation } from "./resourceLocation";
import { TICKS_PER_SECOND } from "./time";

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
  if (!animation.metadata.description.trim()) add(issues, "metadata.description", "must not be empty");
  if (animation.transform_space.coordinate_space !== "root_local") add(issues, "transform_space.coordinate_space", "must be root_local");
  if (animation.transform_space.matrix_layout !== "row_major") add(issues, "transform_space.matrix_layout", "must be row_major");
  if (animation.transform_space.matrix_size !== 16) add(issues, "transform_space.matrix_size", "must be 16");
  if (!Number.isInteger(animation.timeline.duration_ticks) || animation.timeline.duration_ticks < 1) {
    add(issues, "timeline.duration_ticks", "must be a positive integer");
  }
  if (!Number.isInteger(animation.timeline.loop_delay_ticks) || animation.timeline.loop_delay_ticks < 0) {
    add(issues, "timeline.loop_delay_ticks", "must be a non-negative integer");
  }
  if (animation.timeline.loop === "once" && animation.timeline.loop_delay_ticks !== 0) {
    add(issues, "timeline.loop_delay_ticks", "must be 0 when loop is once");
  }

  const nodeIds = new Set(Object.keys(animation.nodes));
  for (const [nodeId, node] of Object.entries(animation.nodes)) {
    validateMatrix(node.default_matrix, `nodes.${nodeId}.default_matrix`, issues);
    if (node.type === "anchor" && ("visible" in node || "entity_nbt" in node)) {
      add(issues, `nodes.${nodeId}`, "anchor cannot define visible or entity_nbt");
    }
  }

  let previousTick = -1;
  const lastTransformTick = new Map<string, number>();
  animation.timeline.keyframes.forEach((keyframe, index) => {
    const path = `timeline.keyframes[${index}]`;
    if (!Number.isInteger(keyframe.tick) || keyframe.tick < 0 || keyframe.tick > animation.timeline.duration_ticks) {
      add(issues, `${path}.tick`, "must be within 0..duration_ticks");
    }
    if (keyframe.tick <= previousTick) add(issues, `${path}.tick`, "must be strictly ascending");
    previousTick = keyframe.tick;
    const defaultDuration = keyframe.interpolation_duration_ticks ?? 0;
    if (!isNonNegativeInteger(defaultDuration)) add(issues, `${path}.interpolation_duration_ticks`, "must be a non-negative integer");
    for (const [nodeId, transform] of Object.entries(keyframe.node_transforms ?? {})) {
      if (!nodeIds.has(nodeId)) add(issues, `${path}.node_transforms.${nodeId}`, "references an unknown node");
      validateMatrix(transform.matrix, `${path}.node_transforms.${nodeId}.matrix`, issues);
      const duration = transform.interpolation_duration_ticks ?? defaultDuration;
      const previous = lastTransformTick.get(nodeId) ?? 0;
      if (!isNonNegativeInteger(duration) || duration > keyframe.tick - previous) {
        add(issues, `${path}.node_transforms.${nodeId}.interpolation_duration_ticks`, "exceeds the time since the previous node transform");
      }
      lastTransformTick.set(nodeId, keyframe.tick);
    }
    for (const nodeId of Object.keys(keyframe.node_states ?? {})) {
      if (!nodeIds.has(nodeId)) add(issues, `${path}.node_states.${nodeId}`, "references an unknown node");
    }
  });

  const events = animation.timeline.events;
  events?.start?.forEach((event, index) => validateEvent(event, `timeline.events.start[${index}]`, animation, issues));
  events?.loop?.forEach((event, index) => validateEvent(event, `timeline.events.loop[${index}]`, animation, issues));
  events?.stop?.forEach((event, index) => validateEvent(event, `timeline.events.stop[${index}]`, animation, issues));
  events?.timeline?.forEach((event, index) => {
    const path = `timeline.events.timeline[${index}]`;
    if (!Number.isInteger(event.tick) || event.tick < 0 || event.tick >= animation.timeline.duration_ticks) {
      add(issues, `${path}.tick`, "must be within 0..duration_ticks - 1");
    }
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
  if (event.commands.length === 0) add(issues, `${path}.commands`, "must not be empty");
  event.commands.forEach((command, index) => {
    if (!command.trim() || command.startsWith("/")) add(issues, `${path}.commands[${index}]`, "must be non-empty and omit the leading slash");
  });
}

function validateMatrix(matrix: Matrix16, path: string, issues: ValidationIssue[]): void {
  if (matrix.length !== 16 || matrix.some((value) => !Number.isFinite(value))) {
    add(issues, path, "must contain 16 finite numbers");
  }
}

function isNonNegativeInteger(value: number): boolean {
  return Number.isInteger(value) && value >= 0;
}

function add(issues: ValidationIssue[], path: string, message: string): void {
  issues.push({ path, message });
}
