import type {
  EmoteAnimation,
  EmoteKeyframe,
  EmoteMetadata,
  EmoteNode,
  EmoteTimelineEvent,
} from "../format/emoteAnimation";
import type { ImportedAnimation, ImportedNode, ImportedProject } from "../import/types";
import { sanitizeNamespace, sanitizeResourcePath } from "../format/resourceLocation";
import { TICKS_PER_SECOND, requireTick } from "../format/time";
import { ConversionError } from "../import/errors";

export interface CompileOptions {
  minecraftVersion: string;
  namespace?: string;
  metadata?: EmoteMetadata;
  loop?: EmoteAnimation["timeline"]["loop"];
}

export function compileImportedProject(project: ImportedProject, options: CompileOptions): EmoteAnimation[] {
  const context = prepareCompile(project, options);
  return project.animations.map((animation) => compileAnimation(project, animation, context));
}

export function compileImportedAnimation(project: ImportedProject, options: CompileOptions, animationIndex: number): EmoteAnimation {
  const context = prepareCompile(project, options);
  const animation = project.animations[animationIndex];
  if (!animation) throw new ConversionError("unknown_animation", `Animation ${animationIndex + 1} does not exist.`);
  return compileAnimation(project, animation, context);
}

interface CompileContext {
  namespace: string;
  baseMetadata: EmoteMetadata;
  multiple: boolean;
  minecraftVersion: string;
  loop?: EmoteAnimation["timeline"]["loop"];
}

function prepareCompile(project: ImportedProject, options: CompileOptions): CompileContext {
  const importError = project.diagnostics.find((diagnostic) => diagnostic.severity === "error");
  if (importError) throw ConversionError.fromIssue(importError);
  const namespace = sanitizeNamespace(options.namespace ?? options.metadata?.name ?? project.suggestedMetadata.name);
  const baseMetadata = options.metadata ?? project.suggestedMetadata;
  const multiple = project.animations.length > 1;
  const ids = new Set<string>();
  for (const animation of project.animations) {
    const id = `${namespace}:${sanitizeResourcePath(animation.id)}`;
    if (ids.has(id)) throw new ConversionError("duplicate_animation_id", `Multiple animations normalize to the same id: ${id}`);
    ids.add(id);
  }
  return { namespace, baseMetadata, multiple, minecraftVersion: options.minecraftVersion, loop: options.loop };
}

function compileAnimation(
  project: ImportedProject,
  animation: ImportedAnimation,
  context: CompileContext,
): EmoteAnimation {
  return {
    schema_version: 1,
    minecraft_version: context.minecraftVersion,
    tick_rate: TICKS_PER_SECOND,
    id: `${context.namespace}:${sanitizeResourcePath(animation.id)}`,
    metadata: {
      ...context.baseMetadata,
      name: context.multiple ? `${context.baseMetadata.name} ${animation.name}` : context.baseMetadata.name,
    },
    transform_space: { coordinate_space: "root_local", matrix_layout: "row_major", matrix_size: 16 },
    nodes: compileNodes(project.nodes, animation),
    timeline: compileTimeline(animation, context.loop),
  };
}

function compileNodes(nodes: Record<string, ImportedNode>, animation: ImportedAnimation): Record<string, EmoteNode> {
  return Object.fromEntries(Object.entries(nodes).map(([id, node]) => {
    const defaultMatrix = animation.tracks[id]?.transforms.find((transform) => transform.tick === 0)?.matrix
      ?? node.defaultMatrix;
    if (node.type === "anchor") return [id, { type: "anchor", default_matrix: defaultMatrix }];
    const common = {
      ...(node.visible ? {} : { visible: false }),
      default_matrix: defaultMatrix,
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

function compileTimeline(
  animation: ImportedAnimation,
  loopOverride?: EmoteAnimation["timeline"]["loop"],
): EmoteAnimation["timeline"] {
  const durationTicks = requireTick(animation.durationTicks, `${animation.id} duration`);
  const keyframes = new Map<number, EmoteKeyframe>();
  for (const [nodeId, track] of Object.entries(animation.tracks)) {
    let previousTick = 0;
    for (const transform of track.transforms) {
      const tick = requireTick(transform.tick, `${animation.id}/${nodeId} transform`);
      const keyframe = keyframes.get(tick) ?? { tick };
      const explicitDuration = transform.interpolation.type === "linear"
        ? transform.interpolation.durationTicks
        : undefined;
      const duration = transform.interpolation.type === "step"
        ? 0
        : explicitDuration == null
          ? tick - previousTick
          : requireTick(explicitDuration, `${animation.id}/${nodeId} interpolation`);
      keyframe.node_transforms = {
        ...keyframe.node_transforms,
        [nodeId]: { matrix: transform.matrix, interpolation_duration_ticks: duration },
      };
      keyframes.set(tick, keyframe);
      previousTick = tick;
    }
    for (const state of track.visibility) {
      const tick = requireTick(state.tick, `${animation.id}/${nodeId} visibility`);
      const keyframe = keyframes.get(tick) ?? { tick };
      keyframe.node_states = { ...keyframe.node_states, [nodeId]: { visible: state.visible } };
      keyframes.set(tick, keyframe);
    }
  }

  const timelineEvents: EmoteTimelineEvent[] = animation.events.timeline.map((event) => ({
    ...event,
    tick: requireTick(event.tick, `${animation.id} event`),
  }));
  const loop = loopOverride ?? animation.loop;
  return {
    duration_ticks: durationTicks,
    loop,
    loop_delay_ticks: loop === "once" ? 0 : requireTick(animation.loopDelayTicks, `${animation.id} loop delay`),
    keyframes: [...keyframes.values()].sort((first, second) => first.tick - second.tick),
    events: {
      ...(animation.events.start.length ? { start: animation.events.start } : {}),
      ...(timelineEvents.length ? { timeline: timelineEvents } : {}),
      ...(animation.events.loop.length ? { loop: animation.events.loop } : {}),
      ...(animation.events.stop.length ? { stop: animation.events.stop } : {}),
    },
  };
}
