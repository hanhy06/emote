import type {
  EmoteAnimation,
  EmoteKeyframe,
  EmoteNode,
  EmoteTimelineEvent,
  Matrix16,
} from "../format/emoteAnimation";
import { ConversionError } from "../foundation/diagnostics";
import {
  documentMetadata,
  type AnimationOutputSettings,
  type ConversionDocument,
  type ConversionNode,
} from "../domain/conversionDocument";
import { multiplyMatrix16 } from "../format/matrix";
import { formatMinecraftTime, parseMinecraftTime } from "../format/minecraftTime";
import { sanitizeNamespace, sanitizeResourcePath } from "../format/resourceLocation";
import { serializeSnbtCompound, serializeSnbtString } from "../format/snbt";
import { requireTick } from "../format/time";
import type { ImportedAnimation } from "../import/types";

const PLAYER_HEAD_SNBT = serializeSnbtCompound([
  ["id", serializeSnbtString("minecraft:player_head")],
  ["count", "1"],
]);

export function compileConversionAnimation(
  document: ConversionDocument,
  animationIndex: number,
  outputOverride?: Partial<AnimationOutputSettings>,
): EmoteAnimation {
  const entry = document.animations[animationIndex];
  if (!entry) throw new ConversionError("unknown_animation", `Animation ${animationIndex + 1} does not exist.`);
  const importError = document.diagnostics.find((diagnostic) => diagnostic.severity === "error");
  if (importError) throw ConversionError.fromIssue(importError);
  validateAnimationIds(document);

  const output = { ...entry.output, ...outputOverride };
  const namespace = sanitizeNamespace(output.namespace || output.displayName);
  const animation = entry.source;
  const mode = output.playbackMode === "source" ? animation.loop : output.playbackMode;
  return {
    type: "animation",
    schema_version: 3,
    id: `${namespace}:${sanitizeResourcePath(animation.id)}`,
    metadata: documentMetadata(output),
    settings: {
      standalone: output.standalone,
      cooldown: formatMinecraftTime(parseMinecraftTime(output.cooldown)),
      player: output.player,
      playback: {
        mode,
        loop_delay: formatMinecraftTime(mode === "once" ? 0 : parseMinecraftTime(output.loopDelay)),
      },
    },
    nodes: compileNodes(document, animation),
    timeline: compileTimeline(document, animation),
  };
}

function validateAnimationIds(document: ConversionDocument): void {
  const ids = new Set<string>();
  for (const animation of document.animations) {
    const id = `${sanitizeNamespace(animation.output.namespace || animation.output.displayName)}:${sanitizeResourcePath(animation.source.id)}`;
    if (ids.has(id)) throw new ConversionError("duplicate_animation_id", `Multiple animations normalize to the same id: ${id}`);
    ids.add(id);
  }
}

function compileNodes(document: ConversionDocument, animation: ImportedAnimation): Record<string, EmoteNode> {
  return Object.fromEntries(Object.entries(document.nodes).map(([id, node]) => {
    const sourceMatrix = animation.tracks[id]?.transforms.find((transform) => transform.tick === 0)?.matrix ?? node.defaultMatrix;
    const defaultMatrix = compileNodeMatrix(document, id, node, sourceMatrix);
    if (node.type === "anchor") return [id, { type: "anchor", space: node.space, default_matrix: defaultMatrix }];
    const common = {
      space: node.space,
      ...(node.visible ? {} : { visible: false }),
      default_matrix: defaultMatrix,
      ...(node.entityNbt ? { entity_nbt: node.entityNbt } : {}),
    };
    if (node.type === "item_display") {
      const assignment = node.skinGroupId ? document.skinGroups[node.skinGroupId]?.assignment : null;
      return [id, {
        ...common,
        type: "item_display",
        item_stack_snbt: assignment && node.playerHeadConversion ? PLAYER_HEAD_SNBT : node.itemStackSnbt,
        item_display: node.itemDisplay,
        ...(assignment ? {
          skin: {
            participant: node.space === "partner" ? "partner" : "initiator",
            part: assignment.part,
            order: assignment.order,
          },
        } : {}),
      }];
    }
    if (node.type === "block_display") return [id, { ...common, type: "block_display", block_state_snbt: node.blockStateSnbt }];
    return [id, { ...common, type: "text_display", text: node.text }];
  }));
}

function compileNodeMatrix(
  document: ConversionDocument,
  nodeId: string,
  node: ConversionNode,
  matrix: Matrix16,
): Matrix16 {
  if (node.type !== "item_display" || !node.skinGroupId || !node.playerHeadConversion) return matrix;
  if (!document.skinGroups[node.skinGroupId]?.assignment) return matrix;
  return multiplyMatrix16(matrix, node.playerHeadConversion.matrix, `Player head node ${nodeId}`);
}

function compileTimeline(document: ConversionDocument, animation: ImportedAnimation): EmoteAnimation["timeline"] {
  const durationTicks = requireTick(animation.durationTicks, `${animation.id} duration`);
  const keyframes = new Map<number, EmoteKeyframe>();
  for (const [nodeId, track] of Object.entries(animation.tracks)) {
    const node = document.nodes[nodeId];
    let previousTick = 0;
    for (const transform of track.transforms) {
      const tick = requireTick(transform.tick, `${animation.id}/${nodeId} transform`);
      const keyframe = keyframes.get(tick) ?? { time: formatMinecraftTime(tick) };
      const explicitDuration = transform.interpolation.type === "linear" ? transform.interpolation.durationTicks : undefined;
      const duration = transform.interpolation.type === "step"
        ? 0
        : explicitDuration == null
          ? tick - previousTick
          : requireTick(explicitDuration, `${animation.id}/${nodeId} interpolation`);
      keyframe.node_transforms = {
        ...keyframe.node_transforms,
        [nodeId]: {
          matrix: node ? compileNodeMatrix(document, nodeId, node, transform.matrix) : transform.matrix,
          interpolation_duration: formatMinecraftTime(duration),
        },
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
