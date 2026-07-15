import type {
  EmoteAnimation,
  EmoteKeyframe,
  EmoteMetadata,
  EmoteNode,
  EmoteTimelineEvent,
} from "../format/emoteAnimation";
import type { ImportedAnimation, ImportedNode, ImportedProject } from "../import/types";

export interface CompileOptions {
  minecraftVersion: string;
  namespace?: string;
  metadata?: EmoteMetadata;
}

export function compileImportedProject(project: ImportedProject, options: CompileOptions): EmoteAnimation[] {
  const importError = project.diagnostics.find((diagnostic) => diagnostic.severity === "error");
  if (importError) throw new Error(`${importError.message}${importError.sourcePath ? ` (${importError.sourcePath})` : ""}`);
  const namespace = sanitizeNamespace(options.namespace ?? options.metadata?.command_name ?? project.suggestedMetadata.command_name);
  const baseMetadata = options.metadata ?? project.suggestedMetadata;
  const multiple = project.animations.length > 1;

  return project.animations.map((animation, index) => ({
    schema_version: 1,
    minecraft_version: options.minecraftVersion,
    tick_rate: 20,
    id: `${namespace}:${sanitizeResourcePath(animation.id)}`,
    metadata: {
      ...baseMetadata,
      name: multiple ? `${baseMetadata.name} ${animation.name}` : baseMetadata.name,
      command_name: multiple ? `${sanitizeResourcePath(baseMetadata.command_name)}_${index + 1}` : sanitizeResourcePath(baseMetadata.command_name),
    },
    transform_space: { coordinate_space: "root_local", matrix_layout: "row_major", matrix_size: 16 },
    nodes: compileNodes(project.nodes),
    timeline: compileTimeline(animation),
  }));
}

function compileNodes(nodes: Record<string, ImportedNode>): Record<string, EmoteNode> {
  return Object.fromEntries(Object.entries(nodes).map(([id, node]) => {
    if (node.parentId) throw new Error(`Node hierarchy has not been flattened: ${id}`);
    if (node.type === "anchor") return [id, { type: "anchor", default_matrix: node.defaultMatrix }];
    const common = {
      ...(node.visible ? {} : { visible: false }),
      default_matrix: node.defaultMatrix,
      ...(node.entityNbt ? { entity_nbt: node.entityNbt } : {}),
    };
    if (node.type === "item_display") {
      return [id, {
        ...common,
        type: "item_display",
        item_stack_snbt: node.itemStackSnbt,
        item_display: node.itemDisplay,
        ...(node.skin ? { skin: node.skin } : {}),
      }];
    }
    if (node.type === "block_display") return [id, { ...common, type: "block_display", block_state_snbt: node.blockStateSnbt }];
    return [id, { ...common, type: "text_display", text: node.text }];
  }));
}

function compileTimeline(animation: ImportedAnimation): EmoteAnimation["timeline"] {
  const durationTicks = secondsToTicks(animation.durationSeconds, `${animation.id} duration`);
  const keyframes = new Map<number, EmoteKeyframe>();
  for (const [nodeId, track] of Object.entries(animation.tracks)) {
    let previousTick = 0;
    for (const transform of track.transforms) {
      const tick = secondsToTicks(transform.timeSeconds, `${animation.id}/${nodeId} transform`);
      const keyframe = keyframes.get(tick) ?? { tick };
      const explicitDuration = transform.interpolation.type === "linear"
        ? transform.interpolation.durationSeconds
        : undefined;
      const duration = transform.interpolation.type === "step"
        ? 0
        : explicitDuration == null
          ? tick - previousTick
          : secondsToTicks(explicitDuration, `${animation.id}/${nodeId} interpolation`);
      keyframe.node_transforms = {
        ...keyframe.node_transforms,
        [nodeId]: { matrix: transform.matrix, interpolation_duration_ticks: duration },
      };
      keyframes.set(tick, keyframe);
      previousTick = tick;
    }
    for (const state of track.visibility) {
      const tick = secondsToTicks(state.timeSeconds, `${animation.id}/${nodeId} visibility`);
      const keyframe = keyframes.get(tick) ?? { tick };
      keyframe.node_states = { ...keyframe.node_states, [nodeId]: { visible: state.visible } };
      keyframes.set(tick, keyframe);
    }
  }

  const timelineEvents: EmoteTimelineEvent[] = animation.events.timeline.map(({ timeSeconds, ...event }) => ({
    ...event,
    tick: secondsToTicks(timeSeconds, `${animation.id} event`),
  }));
  return {
    duration_ticks: durationTicks,
    loop: animation.loop === "loop" ? "loop" : "once",
    loop_delay_ticks: animation.loop === "loop" ? secondsToTicks(animation.loopDelaySeconds, `${animation.id} loop delay`) : 0,
    keyframes: [...keyframes.values()].sort((first, second) => first.tick - second.tick),
    events: {
      ...(animation.events.start.length ? { start: animation.events.start } : {}),
      ...(timelineEvents.length ? { timeline: timelineEvents } : {}),
      ...(animation.events.loop.length ? { loop: animation.events.loop } : {}),
      ...(animation.events.stop.length ? { stop: animation.events.stop } : {}),
    },
  };
}

export function secondsToTicks(seconds: number, label: string): number {
  const ticks = seconds * 20;
  const rounded = Math.round(ticks);
  if (!Number.isFinite(seconds) || seconds < 0 || Math.abs(ticks - rounded) > 1e-7) {
    throw new Error(`${label} does not fall on a 20 TPS tick: ${seconds}`);
  }
  return rounded;
}

function sanitizeResourcePath(value: string): string {
  return value.toLowerCase().replace(/[^a-z0-9_./-]+/g, "_").replace(/^_+|_+$/g, "") || "emote";
}

function sanitizeNamespace(value: string): string {
  return value.toLowerCase().replace(/[^a-z0-9_.-]+/g, "_").replace(/^_+|_+$/g, "") || "emote";
}
