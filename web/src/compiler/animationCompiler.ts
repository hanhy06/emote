import type {
  EmoteAnimation,
  EmoteNode,
  EmoteNodeTracks,
  EmoteTimelineEvent,
  EmoteVectorKeyframe,
  LocalTransform,
  Matrix16,
  Vec3,
} from "../format/emoteAnimation";
import { ConversionError } from "../foundation/diagnostics";
import {
  documentMetadata,
  type AnimationOutputSettings,
  type ConversionDocument,
  type ConversionNode,
} from "../domain/conversionDocument";
import { multiplyMatrix16 } from "../format/matrix";
import { matrixToLocalTransform } from "../format/localTransform";
import { formatMinecraftTime, parseMinecraftTime } from "../format/minecraftTime";
import { sanitizeNamespace, sanitizeResourcePath } from "../format/resourceLocation";
import { serializeSnbtCompound, serializeSnbtString } from "../format/snbt";
import { requireTick } from "../format/time";
import type { ImportedAnimation } from "../domain/conversionSeed";

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
    schema_version: 4,
    id: `${namespace}:${sanitizeResourcePath(animation.id)}`,
    metadata: documentMetadata(output),
    settings: {
      standalone: output.standalone,
      cooldown: formatMinecraftTime(parseMinecraftTime(output.cooldown)),
      player: output.player,
      playback: {
        mode,
        loop_delay: formatMinecraftTime(mode === "once" || mode === "hold" ? 0 : parseMinecraftTime(output.loopDelay)),
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
    const transform = matrixToLocalTransform(compileNodeMatrix(document, id, node, sourceMatrix), `${animation.id}/${id} default transform`);
    if (node.type === "anchor") return [id, { type: "anchor", space: node.space, transform }];
    const common = {
      space: node.space,
      ...(node.visible ? {} : { visible: false }),
      transform,
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
  const tracks: Record<string, EmoteNodeTracks> = {};
  for (const [nodeId, track] of Object.entries(animation.tracks)) {
    const node = document.nodes[nodeId];
    if (!node) throw new ConversionError("unknown_animation_node", `${animation.id} references unknown node ${nodeId}.`);
    const nodeTracks: EmoteNodeTracks = {};
    if (track.transforms.length > 0) {
      const sourceMatrix = track.transforms.find((transform) => transform.tick === 0)?.matrix ?? node.defaultMatrix;
      const initial = matrixToLocalTransform(compileNodeMatrix(document, nodeId, node, sourceMatrix), `${animation.id}/${nodeId}/0t`);
      const frames = compileTransformFrames(document, animation, nodeId, initial);
      nodeTracks.position = frames.map((frame) => vectorFrame(frame, frame.transform.position));
      nodeTracks.rotation = frames.map((frame) => vectorFrame(frame, frame.transform.rotation));
      nodeTracks.scale = frames.map((frame) => vectorFrame(frame, frame.transform.scale));
    }
    if (track.visibility.length > 0) {
      const visibility = new Map<number, boolean>([[0, node.type === "anchor" ? true : node.visible]]);
      for (const state of track.visibility) {
        visibility.set(requireTick(state.tick, `${animation.id}/${nodeId} visibility`), state.visible);
      }
      nodeTracks.visible = [...visibility.entries()].sort(([first], [second]) => first - second).map(([tick, value]) => ({
        time: formatMinecraftTime(tick),
        value,
      }));
    }
    if (Object.keys(nodeTracks).length > 0) tracks[nodeId] = nodeTracks;
  }

  const timelineEvents: EmoteTimelineEvent[] = animation.events.timeline.map(({ tick, ...event }) => ({
    ...event,
    time: formatMinecraftTime(requireTick(tick, `${animation.id} event`)),
  }));
  return {
    duration: formatMinecraftTime(durationTicks),
    tracks,
    events: {
      ...(animation.events.start.length ? { start: animation.events.start } : {}),
      ...(timelineEvents.length ? { timeline: timelineEvents } : {}),
      ...(animation.events.loop.length ? { loop: animation.events.loop } : {}),
      ...(animation.events.stop.length ? { stop: animation.events.stop } : {}),
    },
  };
}

interface TransformFrame {
  tick: number;
  transform: LocalTransform;
  interpolation?: "step" | "linear";
}

function compileTransformFrames(
  document: ConversionDocument,
  animation: ImportedAnimation,
  nodeId: string,
  initial: LocalTransform,
): TransformFrame[] {
  const node = document.nodes[nodeId];
  const sourceFrames = animation.tracks[nodeId]?.transforms ?? [];
  const result: TransformFrame[] = [{ tick: 0, transform: initial }];
  let previousTargetTick = 0;

  for (const source of sourceFrames) {
    const tick = requireTick(source.tick, `${animation.id}/${nodeId} transform`);
    const matrix = node ? compileNodeMatrix(document, nodeId, node, source.matrix) : source.matrix;
    const transform = matrixToLocalTransform(matrix, `${animation.id}/${nodeId}/${tick}t`);
    if (tick === 0) {
      result[0] = { tick: 0, transform };
      continue;
    }
    if (tick <= previousTargetTick) {
      throw new ConversionError("unordered_animation_track", `${animation.id}/${nodeId} transform times must be strictly ascending.`);
    }

    const gap = tick - previousTargetTick;
    const duration = source.interpolation.type === "step"
      ? 0
      : source.interpolation.durationTicks == null
        ? gap
        : requireTick(source.interpolation.durationTicks, `${animation.id}/${nodeId} interpolation`);
    if (duration > gap) {
      throw new ConversionError("invalid_interpolation_duration", `${animation.id}/${nodeId} interpolation exceeds the previous transform interval.`);
    }

    const previous = result.at(-1)!;
    if (duration === 0) {
      previous.interpolation = "step";
    } else {
      const transitionStart = tick - duration;
      if (transitionStart > previous.tick) {
        previous.interpolation = "step";
        result.push({ tick: transitionStart, transform: previous.transform, interpolation: "linear" });
      } else {
        previous.interpolation = "linear";
      }
    }
    result.push({ tick, transform });
    previousTargetTick = tick;
  }
  return result;
}

function vectorFrame(frame: TransformFrame, value: Vec3): EmoteVectorKeyframe {
  return {
    time: formatMinecraftTime(frame.tick),
    value,
    ...(frame.interpolation ? { interpolation: frame.interpolation } : {}),
  };
}
