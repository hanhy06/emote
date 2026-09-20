import type {
  EmoteAnimation,
  EmoteNbtValue,
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
  documentSkinAssignments,
  type AnimationOutputSettings,
  type ConversionDocument,
  type ConversionNode,
} from "../domain/conversionDocument";
import { multiplyMatrix16 } from "../format/matrix";
import { localTransformToMatrix, matrixToContinuousLocalTransform, matrixToLocalTransform } from "../format/localTransform";
import { formatMinecraftTime, parseMinecraftTime, requireTick } from "../format/time";
import { sanitizeNamespace, sanitizeResourcePath } from "../format/resourceLocation";
import type { DisplayNbtPatch, DisplayNbtValue, ItemStackData, RuntimeNode, RuntimeNodeTracks } from "../domain/minecraftData";
import { readDisplayNbt, writeBlockState, writeDisplayNbt, writeItemStack } from "../format/minecraftData";
import { minecraftVersionProfile, type MinecraftVersionProfile } from "../format/minecraftVersionProfiles";
import { animationExportAvailability, type ImportedAnimation, type ImportedNodeTrack } from "../domain/conversionSeed";
import type { NativeRuntimeBindings } from "../domain/nodeBindings";
import { rewriteMolangStringLiterals } from "../format/molang/sourceTransformer";

const PLAYER_HEAD: ItemStackData = { id: "minecraft:player_head", count: 1 };

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
  const availability = animationExportAvailability(animation);
  if (!availability.exportable) {
    throw new ConversionError("animation_export_unavailable", availability.reason ?? `${animation.name} cannot be exported.`);
  }
  const mode = output.playbackMode === "source" ? animation.playbackMode : output.playbackMode;
  const loopStartTicks = mode === "loop" ? parseMinecraftTime(output.loopStart) : 0;
  const loopEndTicks = mode === "loop" ? parseMinecraftTime(output.loopEnd) : 0;
  const loopDelayTicks = mode === "once" || mode === "hold" ? 0 : parseMinecraftTime(output.loopDelay);
  const profile = minecraftVersionProfile(document.targetMinecraftVersion);
  const runtime = animation.runtime;
  return {
    type: "animation",
    schema_version: 4,
    target_minecraft_version: document.targetMinecraftVersion,
    id: `${namespace}:${sanitizeResourcePath(animation.id)}`,
    metadata: documentMetadata(output),
    settings: {
      standalone: output.standalone,
      cooldown: formatMinecraftTime(parseMinecraftTime(output.cooldown)),
      rotation_deadzone: output.rotationDeadzone,
      display_interpolation: formatMinecraftTime(parseMinecraftTime(output.displayInterpolation)),
      player: output.player,
      playback: {
        mode,
        ...(loopStartTicks === 0 ? {} : { loop_start: formatMinecraftTime(loopStartTicks) }),
        ...(mode === "loop" && loopEndTicks !== 0 ? { loop_end: formatMinecraftTime(loopEndTicks) } : {}),
        ...(loopDelayTicks === 0 ? {} : { loop_delay: formatMinecraftTime(loopDelayTicks) }),
      },
    },
    ...(runtime.kind === "native" && runtime.molang ? { molang: runtime.molang } : {}),
    nodes: runtime.kind === "native"
      ? compileRuntimeNodes(document, runtime.nodes, runtime.bindings, profile)
      : compileNodes(document, animation, runtime.tracks, entry.nodeIds, profile),
    timeline: runtime.kind === "native"
      ? compileRuntimeTimeline(document, animation, runtime.tracks, runtime.bindings, profile)
      : compileTimeline(document, animation, runtime.tracks, profile),
  };
}

function compileRuntimeNodes(
  document: ConversionDocument,
  sourceNodes: Record<string, RuntimeNode>,
  bindings: NativeRuntimeBindings,
  profile: MinecraftVersionProfile,
): Record<string, EmoteNode> {
  const assignments = documentSkinAssignments(document);
  return Object.fromEntries(Object.entries(sourceNodes).map(([id, sourceNode]): [string, EmoteNode] => {
    const editorNodeId = bindings.editorNodeByRuntimeNode[id];
    const editorNode = editorNodeId ? document.nodes[editorNodeId] : undefined;
    if (sourceNode.type !== "anchor" && !editorNode) {
      throw new ConversionError("missing_runtime_node_binding", `Runtime display ${id} is not bound to an editor node.`, id);
    }
    const spaceGroupId = bindings.editorSpaceGroupByRuntimeRoot[id];
    const space = !sourceNode.parent && spaceGroupId ? documentSpaceGroup(document, spaceGroupId) : sourceNode.space;
    const common = {
      ...(sourceNode.parent ? { parent: sourceNode.parent } : {}),
      ...(space ? { space } : {}),
      transform: sourceNode.transform,
    };
    if (sourceNode.type === "anchor") return [id, { type: "anchor", ...common }];
    const displayCommon = {
      ...common,
      ...(sourceNode.visible === undefined ? {} : { visible: sourceNode.visible }),
      ...(sourceNode.entityNbt ? { entity_nbt: sourceNode.entityNbt } : {}),
    };
    if (sourceNode.type === "block_display") {
      return [id, { type: "block_display", ...displayCommon, block_state_snbt: writeBlockState(sourceNode.blockState, profile) }];
    }
    if (sourceNode.type === "text_display") return [id, { type: "text_display", ...displayCommon, text: sourceNode.text }];
    const assignment = editorNodeId ? assignments[editorNodeId] : undefined;
    const outputItem = assignment ? PLAYER_HEAD : sourceNode.itemStack;
    const transform = assignment && editorNode?.type === "item_display" && editorNode.playerHeadConversion
      ? matrixToLocalTransform(
          multiplyMatrix16(localTransformToMatrix(sourceNode.transform, `Runtime node ${id}`), editorNode.playerHeadConversion.matrix, `Runtime player head node ${id}`),
          `Runtime player head node ${id}`,
        )
      : sourceNode.transform;
    return [id, {
      type: "item_display",
      ...displayCommon,
      transform,
      item_stack_snbt: writeItemStack(outputItem, profile),
      item_display: sourceNode.itemDisplay,
      skin: assignment ? { participant: assignment.participant ?? "initiator", part: assignment.part, order: assignment.order } : undefined,
    }];
  }));
}

function documentSpaceGroup(document: ConversionDocument, groupId: string): EmoteNode["space"] {
  const spaces = new Set(Object.entries(document.nodes)
    .filter(([nodeId, node]) => (node.binding.spaceGroupId ?? nodeId) === groupId)
    .map(([, node]) => node.space));
  if (spaces.size !== 1) {
    throw new ConversionError("invalid_runtime_space_binding", `Runtime space group ${groupId} must resolve to exactly one editor space.`, groupId);
  }
  return spaces.values().next().value!;
}

function validateAnimationIds(document: ConversionDocument): void {
  const ids = new Set<string>();
  for (const animation of document.animations) {
    const id = `${sanitizeNamespace(animation.output.namespace || animation.output.displayName)}:${sanitizeResourcePath(animation.source.id)}`;
    if (ids.has(id)) throw new ConversionError("duplicate_animation_id", `Multiple animations normalize to the same id: ${id}`);
    ids.add(id);
  }
}

function compileNodes(document: ConversionDocument, animation: ImportedAnimation, tracks: Record<string, ImportedNodeTrack>, nodeIds: readonly string[], profile: MinecraftVersionProfile): Record<string, EmoteNode> {
  return Object.fromEntries(nodeIds.map((id) => [id, document.nodes[id]] as const).filter((entry): entry is readonly [string, ConversionNode] => Boolean(entry[1])).map(([id, node]) => {
    const sourceMatrix = tracks[id]?.transforms.find((transform) => transform.tick === 0)?.matrix ?? node.defaultMatrix;
    const transform = matrixToLocalTransform(compileNodeMatrix(document, id, node, sourceMatrix), `${animation.id}/${id} default transform`);
    if (node.type === "anchor") return [id, { type: "anchor", space: node.space, transform }];
    const common = {
      space: node.space,
      ...(node.visible ? {} : { visible: false }),
      transform,
      ...(node.entityNbt ? { entity_nbt: node.entityNbt } : {}),
    };
    if (node.type === "item_display") {
      const assignment = node.binding.skinGroupId ? document.skinGroups[node.binding.skinGroupId]?.assignment : null;
      return [id, {
        ...common,
        type: "item_display",
        item_stack_snbt: writeItemStack(assignment && node.playerHeadConversion ? PLAYER_HEAD : node.itemStack, profile),
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
    if (node.type === "block_display") return [id, { ...common, type: "block_display", block_state_snbt: writeBlockState(node.blockState, profile) }];
    return [id, { ...common, type: "text_display", text: node.text }];
  }));
}

function compileNodeMatrix(
  document: ConversionDocument,
  nodeId: string,
  node: ConversionNode,
  matrix: Matrix16,
): Matrix16 {
  if (node.type !== "item_display" || !node.binding.skinGroupId || !node.playerHeadConversion) return matrix;
  if (!document.skinGroups[node.binding.skinGroupId]?.assignment) return matrix;
  return multiplyMatrix16(matrix, node.playerHeadConversion.matrix, `Player head node ${nodeId}`);
}

function compileTimeline(document: ConversionDocument, animation: ImportedAnimation, sourceTracks: Record<string, ImportedNodeTrack>, profile: MinecraftVersionProfile): EmoteAnimation["timeline"] {
  const durationTicks = requireTick(animation.durationTicks, `${animation.id} duration`);
  const tracks: Record<string, EmoteNodeTracks> = {};
  for (const [nodeId, track] of Object.entries(sourceTracks)) {
    const node = document.nodes[nodeId];
    if (!node) throw new ConversionError("unknown_animation_node", `${animation.id} references unknown node ${nodeId}.`);
    const nodeTracks: EmoteNodeTracks = {};
    if (track.transforms.length > 0) {
      const sourceMatrix = track.transforms.find((transform) => transform.tick === 0)?.matrix ?? node.defaultMatrix;
      const initial = matrixToLocalTransform(compileNodeMatrix(document, nodeId, node, sourceMatrix), `${animation.id}/${nodeId}/0t`);
      const frames = compileTransformFrames(document, animation, sourceTracks, nodeId, initial);
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
    if (track.nbt.length > 0) {
      const nbt = track.nbt.flatMap((frame) => {
        const value = compileNodeNbt(document, nodeId, frame.value, profile);
        return value === undefined ? [] : [{
          time: formatMinecraftTime(requireTick(frame.tick, `${animation.id}/${nodeId} nbt`)),
          value,
        }];
      });
      if (nbt.length > 0) nodeTracks.nbt = nbt;
    }
    if (Object.keys(nodeTracks).length > 0) tracks[nodeId] = nodeTracks;
  }

  return {
    duration: formatMinecraftTime(durationTicks),
    tracks,
    events: compileEvents(animation),
  };
}

function compileRuntimeTimeline(
  document: ConversionDocument,
  animation: ImportedAnimation,
  sourceTracks: Record<string, RuntimeNodeTracks>,
  bindings: NativeRuntimeBindings,
  profile: MinecraftVersionProfile,
): EmoteAnimation["timeline"] {
  const tracks: Record<string, EmoteNodeTracks> = Object.fromEntries(Object.entries(sourceTracks).map(([nodeId, track]): [string, EmoteNodeTracks] => {
    const compileVectorFrames = (frames: NonNullable<typeof track.position>, channel: string): EmoteVectorKeyframe[] => frames.map(({ tick, ...frame }) => ({
      ...frame,
      time: formatMinecraftTime(requireTick(tick, `${animation.id}/${nodeId} ${channel}`)),
    }));
    const output: EmoteNodeTracks = {
      ...(track.position ? { position: compileVectorFrames(track.position, "position") } : {}),
      ...(track.rotation ? { rotation: compileVectorFrames(track.rotation, "rotation") } : {}),
      ...(track.scale ? { scale: compileVectorFrames(track.scale, "scale") } : {}),
      ...(track.visible ? { visible: track.visible.map(({ tick, value }) => ({
        time: formatMinecraftTime(requireTick(tick, `${animation.id}/${nodeId} visibility`)),
        value,
      })) } : {}),
    };
    if (!track.nbt) return [nodeId, output];
    const nbt = track.nbt.flatMap((frame) => {
      const editorNodeId = bindings.editorNodeByRuntimeNode[nodeId];
      if (!editorNodeId) {
        throw new ConversionError("missing_runtime_node_binding", `Runtime NBT track ${nodeId} is not bound to an editor node.`, nodeId);
      }
      const value = compileRuntimeNbtValue(document, editorNodeId, frame.value, profile);
      return value === undefined ? [] : [{
        time: formatMinecraftTime(requireTick(frame.tick, `${animation.id}/${nodeId} nbt`)),
        value,
      }];
    });
    return [nodeId, nbt.length > 0 ? { ...output, nbt } : output];
  }));
  return {
    duration: formatMinecraftTime(requireTick(animation.durationTicks, `${animation.id} duration`)),
    tracks,
    events: compileEvents(animation),
  };
}

function compileEvents(animation: ImportedAnimation): NonNullable<EmoteAnimation["timeline"]["events"]> {
  const timeline: EmoteTimelineEvent[] = animation.events.timeline.map(({ tick, ...event }) => ({
    ...event,
    time: formatMinecraftTime(requireTick(tick, `${animation.id} event`)),
  }));
  return {
    ...(animation.events.start.length ? { start: animation.events.start } : {}),
    ...(timeline.length ? { timeline } : {}),
    ...(animation.events.loop.length ? { loop: animation.events.loop } : {}),
    ...(animation.events.stop.length ? { stop: animation.events.stop } : {}),
  };
}

function compileRuntimeNbtValue(
  document: ConversionDocument,
  nodeId: string,
  value: DisplayNbtValue,
  profile: MinecraftVersionProfile,
): EmoteNbtValue | undefined {
  if ("molang" in value) return { molang: compileMolangNbtLiterals(document, nodeId, value.molang, profile) };
  return compileNodeNbt(document, nodeId, value, profile);
}

function compileMolangNbtLiterals(
  document: ConversionDocument,
  nodeId: string,
  source: string,
  profile: MinecraftVersionProfile,
): string {
  return rewriteMolangStringLiterals(source, (value) => {
    if (!value.trimStart().startsWith("{")) return undefined;
    try {
      return compileNodeNbt(document, nodeId, readDisplayNbt(value), profile) ?? "{}";
    } catch {
      return undefined;
    }
  });
}

function compileNodeNbt(document: ConversionDocument, nodeId: string, value: DisplayNbtPatch, profile: MinecraftVersionProfile): string | undefined {
  const node = document.nodes[nodeId];
  if (node?.type !== "item_display" || !node.playerHeadConversion || !node.binding.skinGroupId
    || !document.skinGroups[node.binding.skinGroupId]?.assignment) return writeDisplayNbt(value, profile);
  const { itemStack: _item, ...remaining } = value;
  if (!remaining.blockState && remaining.rawFields.length === 0) return undefined;
  return writeDisplayNbt(remaining, profile);
}

interface TransformFrame {
  tick: number;
  transform: LocalTransform;
  interpolation?: "step" | "linear";
}

function compileTransformFrames(
  document: ConversionDocument,
  animation: ImportedAnimation,
  tracks: Record<string, ImportedNodeTrack>,
  nodeId: string,
  initial: LocalTransform,
): TransformFrame[] {
  const node = document.nodes[nodeId];
  const sourceFrames = tracks[nodeId]?.transforms ?? [];
  const result: TransformFrame[] = [{ tick: 0, transform: initial }];
  let previousTargetTick = 0;

  for (const source of sourceFrames) {
    const tick = requireTick(source.tick, `${animation.id}/${nodeId} transform`);
    const matrix = node ? compileNodeMatrix(document, nodeId, node, source.matrix) : source.matrix;
    const transform = matrixToContinuousLocalTransform(matrix, result.at(-1)!.transform.rotation, `${animation.id}/${nodeId}/${tick}t`);
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
