import type {
  EmoteAnimation,
  EmoteKeyframe,
  EmoteMetadata,
  EmoteNode,
  EmotePlayerBehavior,
  EmoteTimelineEvent,
} from "../format/emoteAnimation";
import type { ImportedAnimation, ImportedNode, ImportedProject } from "../import/types";
import { sanitizeNamespace, sanitizeResourcePath } from "../format/resourceLocation";
import { requireTick } from "../format/time";
import { formatMinecraftTime, parseMinecraftTime } from "../format/minecraftTime";
import { ConversionError } from "../import/errors";

export interface CompileOptions {
  minecraftVersion: string;
  namespace?: string;
  metadata?: EmoteMetadata;
  player?: EmotePlayerBehavior;
  loop?: EmoteAnimation["settings"]["playback"]["mode"];
  standalone?: boolean;
  cooldown?: string;
  loopDelay?: string;
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
  player: EmotePlayerBehavior;
  multiple: boolean;
  loop?: EmoteAnimation["settings"]["playback"]["mode"];
  standalone: boolean;
  cooldown: string;
  loopDelay?: string;
}

function prepareCompile(project: ImportedProject, options: CompileOptions): CompileContext {
  const importError = project.diagnostics.find((diagnostic) => diagnostic.severity === "error");
  if (importError) throw ConversionError.fromIssue(importError);
  const namespace = sanitizeNamespace(options.namespace ?? options.metadata?.name ?? project.suggestedMetadata.name);
  const baseMetadata = options.metadata ?? project.suggestedMetadata;
  const player = options.player ?? project.suggestedPlayer;
  const multiple = project.animations.length > 1;
  const ids = new Set<string>();
  for (const animation of project.animations) {
    const id = `${namespace}:${sanitizeResourcePath(animation.id)}`;
    if (ids.has(id)) throw new ConversionError("duplicate_animation_id", `Multiple animations normalize to the same id: ${id}`);
    ids.add(id);
  }
  return {
    namespace,
    baseMetadata,
    player,
    multiple,
    loop: options.loop,
    standalone: options.standalone ?? true,
    cooldown: formatMinecraftTime(parseMinecraftTime(options.cooldown ?? "0t")),
    loopDelay: options.loopDelay,
  };
}

function compileAnimation(
  project: ImportedProject,
  animation: ImportedAnimation,
  context: CompileContext,
): EmoteAnimation {
  return {
    type: "animation",
    schema_version: 3,
    id: `${context.namespace}:${sanitizeResourcePath(animation.id)}`,
    metadata: {
      ...context.baseMetadata,
      name: context.multiple ? `${context.baseMetadata.name} ${animation.name}` : context.baseMetadata.name,
    },
    settings: {
      standalone: context.standalone,
      cooldown: context.cooldown,
      player: context.player,
      playback: {
        mode: context.loop ?? animation.loop,
        loop_delay: formatMinecraftTime((context.loop ?? animation.loop) === "once"
          ? 0
          : context.loopDelay === undefined
            ? requireTick(animation.loopDelayTicks, `${animation.id} loop delay`)
            : parseMinecraftTime(context.loopDelay)),
      },
    },
    nodes: compileNodes(project.nodes, animation),
    timeline: compileTimeline(animation),
  };
}

function compileNodes(nodes: Record<string, ImportedNode>, animation: ImportedAnimation): Record<string, EmoteNode> {
  return Object.fromEntries(Object.entries(nodes).map(([id, node]) => {
    const defaultMatrix = animation.tracks[id]?.transforms.find((transform) => transform.tick === 0)?.matrix
      ?? node.defaultMatrix;
    const space = node.space ?? (node.type === "item_display" && node.skin
      ? node.skin.participant ?? "initiator"
      : "scene");
    if (node.type === "anchor") return [id, { type: "anchor", space, default_matrix: defaultMatrix }];
    const common = {
      space,
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
        ...(node.skin ? { skin: { ...node.skin, participant: node.skin.participant ?? "initiator" } } : {}),
      }];
    }
    if (node.type === "block_display") return [id, { ...common, type: "block_display", block_state_snbt: node.blockStateSnbt }];
    return [id, { ...common, type: "text_display", text: node.text }];
  }));
}

function compileTimeline(animation: ImportedAnimation): EmoteAnimation["timeline"] {
  const durationTicks = requireTick(animation.durationTicks, `${animation.id} duration`);
  const keyframes = new Map<number, EmoteKeyframe>();
  for (const [nodeId, track] of Object.entries(animation.tracks)) {
    let previousTick = 0;
    for (const transform of track.transforms) {
      const tick = requireTick(transform.tick, `${animation.id}/${nodeId} transform`);
      const keyframe = keyframes.get(tick) ?? { time: formatMinecraftTime(tick) };
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
        [nodeId]: { matrix: transform.matrix, interpolation_duration: formatMinecraftTime(duration) },
      };
      keyframes.set(tick, keyframe);
      previousTick = tick;
    }
    for (const state of track.visibility) {
      const tick = requireTick(state.tick, `${animation.id}/${nodeId} visibility`);
      const keyframe = keyframes.get(tick) ?? { time: formatMinecraftTime(tick) };
      keyframe.node_states = { ...keyframe.node_states, [nodeId]: { visible: state.visible } };
      keyframes.set(tick, keyframe);
    }
  }

  const timelineEvents: EmoteTimelineEvent[] = animation.events.timeline.map(({ tick, ...event }) => ({
    ...event,
    time: formatMinecraftTime(requireTick(tick, `${animation.id} event`)),
  }));
  return {
    duration: formatMinecraftTime(durationTicks),
    keyframes: [...keyframes.values()].sort((first, second) => parseInt(first.time) - parseInt(second.time)),
    events: {
      ...(animation.events.start.length ? { start: animation.events.start } : {}),
      ...(timelineEvents.length ? { timeline: timelineEvents } : {}),
      ...(animation.events.loop.length ? { loop: animation.events.loop } : {}),
      ...(animation.events.stop.length ? { stop: animation.events.stop } : {}),
    },
  };
}
