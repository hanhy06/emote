import { importedNodeHints } from "../../domain/conversionSeed";
import type { AnimationIR, CurveIR, TrackIR } from "../../domain/animationIR";
import type { ImportedAnimation, ImportedNode, ImportedProject } from "../../domain/conversionSeed";
import { createDefaultPlayerBehavior } from "../../domain/emoteDefinition";
import { sanitizeResourcePath } from "../../format/resourceLocation";
import { writeDisplayNbt } from "../../format/minecraftData";
import { minecraftVersionProfile } from "../../format/minecraftVersionProfiles";
import type { BdDatapackSource, BdSourceDisplay } from "./bdDatapackSource";
import { initialDisplayNbt } from "../common/runtimeOutput";
import { importedNodeIR } from "../common/blockbenchAnimationIR";
import { decomposeDisplayMatrix, interpolateDisplayTransformation, type DisplayTransformation } from "./displayTransformation";

export function importBdDatapack(source: BdDatapackSource, sourceName: string): ImportedProject {
  const name = prettify(source.namespace);
  const nodes = Object.fromEntries(source.displays.map((display) => [display.id, importNode(display)]));
  return {
    source: "bd_datapack",
    sourceName,
    suggestedMetadata: { name, description: `${name} emote.` },
    suggestedPlayer: createDefaultPlayerBehavior(),
    suggestedNamespace: source.namespace,
    nodeHints: importedNodeHints(nodes),
    animations: source.animations.map((animation): ImportedAnimation => {
      return {
        id: sanitizeResourcePath(animation.name, "default"),
        name: prettify(animation.name),
        ir: createBdAnimationIR(animation, nodes, source.displays),
      };
    }),
    diagnostics: [...source.diagnostics, ...source.displays.filter((display) => display.type === "anchor").map((display) => ({ severity: "warning" as const, code: "bd_datapack_logical_node", message: `${display.tag} is preserved as a logical node; geometry is unavailable.` })), ...(source.droppedCamera ? [{
      severity: "warning",
      code: "bd_datapack_camera_ignored",
      message: "BD Engine camera movement is not part of the emote format and was ignored.",
      sourcePath: "data/*/function/k/*/keyframe_*.mcfunction",
    } as const] : [])],
    resources: new Map(),
  };
}

function importNode(display: BdSourceDisplay): ImportedNode {
  if (display.type === "anchor") return { binding: { sourceNodeId: display.id }, type: "anchor" };
  const common = {
    binding: { sourceNodeId: display.id, skinGroupId: display.id },
    visible: true,
    ...(display.entityNbt ? { entityNbt: display.entityNbt } : {}),
  };
  if (display.type === "item_display") {
    return { ...common, type: "item_display", itemStack: display.itemStack, itemDisplay: display.itemDisplay };
  }
  if (display.type === "block_display") return { ...common, type: "block_display", blockState: display.blockState };
  return { ...common, type: "text_display", text: display.text };
}

function createBdAnimationIR(animation: BdDatapackSource["animations"][number], imported: Record<string, ImportedNode>, displays: readonly BdSourceDisplay[]): AnimationIR {
  const profile = minecraftVersionProfile("26.3");
  const name = prettify(animation.name);
  const nodes: AnimationIR["nodes"] = {};
  const tracks: TrackIR[] = [];

  for (const [id, node] of Object.entries(imported)) {
    const sourceTransforms = animation.transforms[id];
    const display = displays.find((display) => display.id === id)!;
    const interpolated = sourceTransforms.some((frame) => frame.interpolation.type === "linear");
    const initial = interpolated ? decomposeDisplayMatrix(display.defaultMatrix) : undefined;
    nodes[id] = {
      ...importedNodeIR(node),
      visible: node.type === "anchor" ? true : node.visible,
      inherit: { visibility: false },
      source: { node_id: display.tag, editor_node_id: id, entity_snbt: display.originalNbt, matrix: display.defaultMatrix,
        updates: structuredClone(sourceTransforms), state_updates: structuredClone(animation.nbt[id]) },
      transform: initial ? [
        { id: "position", op: "translate", value: initial.position },
        { id: "left_rotation", op: "rotate_quaternion", value: initial.left_rotation },
        { id: "scale", op: "scale", value: initial.scale },
        { id: "right_rotation", op: "rotate_quaternion", value: initial.right_rotation },
      ] : [{ id: "matrix", op: "matrix", value: display.defaultMatrix }],
    };

    if (sourceTransforms.length && !initial) tracks.push({ target: { node: id, operation: "matrix" }, channel: "value", driver: {
      type: "curve", before: "base", keys: sourceTransforms.map((frame) => ({ time: frame.tick / 20, value: frame.matrix })),
      segments: sourceTransforms.slice(1).map(() => ({ interpolation: "step" })),
    } });
    if (initial) {
      const frames: { time: number; pre: DisplayTransformation; post: DisplayTransformation; interpolation: "linear" | "step" }[] = [{ time: 0, pre: initial, post: initial, interpolation: "step" }];
      const put = (time: number, pre: DisplayTransformation, post: DisplayTransformation, interpolation: "linear" | "step") => {
        if (time > animation.durationTicks / 20) return;
        const previous = frames.at(-1)!;
        if (previous.time === time) { previous.post = post; previous.interpolation = interpolation; }
        else frames.push({ time, pre, post, interpolation });
      };
      let state = { from: initial, to: initial, start: (display.interpolationDelay ?? 0) / 20, duration: (display.interpolationDuration ?? 0) / 20 };
      const at = (time: number) => state.duration === 0 ? state.to : interpolateDisplayTransformation(state.from, state.to, (time - state.start) / state.duration);
      for (const frame of sourceTransforms) {
        const time = frame.tick / 20;
        const current = at(time);
        const end = state.start + state.duration;
        if (state.start > frames.at(-1)!.time && state.start < time) put(state.start, state.from, state.from, "linear");
        if (state.duration > 0 && end > frames.at(-1)!.time && end < time) put(end, state.to, state.to, "step");
        const target = decomposeDisplayMatrix(frame.matrix);
        const duration = frame.interpolation.type === "step" ? 0 : frame.interpolation.durationTicks / 20;
        const start = frame.startInterpolation === undefined ? state.start : time + frame.startInterpolation / 20;
        state = { from: current, to: target, start, duration };
        put(time, current, at(time), duration > 0 && time >= start && time < start + duration ? "linear" : "step");
      }
      const end = state.start + state.duration;
      if (state.start > frames.at(-1)!.time) put(state.start, state.from, state.from, "linear");
      if (state.duration > 0 && end > frames.at(-1)!.time) put(end, state.to, state.to, "step");
      const limit = animation.durationTicks / 20;
      if (frames.at(-1)!.time < limit) put(limit, at(limit), at(limit), "step");
      for (const channel of ["position", "left_rotation", "scale", "right_rotation"] as const) {
        const driver: CurveIR = { type: "curve", before: "base",
          keys: frames.map((frame) => ({ time: frame.time, pre: frame.pre[channel], post: frame.post[channel] })),
          segments: frames.slice(0, -1).map((frame) => ({ interpolation: frame.interpolation === "step" ? "step" : channel.endsWith("rotation") ? "slerp" : "linear" })),
        };
        tracks.push({ target: { node: id, operation: channel }, channel: "value", driver });
      }
    }

    const sourceNbt = animation.nbt[id];
    if (sourceNbt.length && node.type !== "anchor") {
      const initialNbt = initialDisplayNbt(node, sourceNbt.map((frame) => frame.value), sourceNbt[0].tick === 0 ? sourceNbt[0].value : undefined);
      const frames = sourceNbt.map((frame) => ({ tick: frame.tick, value: frame.value }));
      const initialFrame = { tick: 0, value: initialNbt };
      if (frames[0].tick === 0) frames[0] = initialFrame;
      else frames.unshift(initialFrame);
      tracks.push({ target: { node: id, attachment: "display" }, channel: "nbt", driver: { type: "state", keys: frames.map((frame) => ({ time: frame.tick / 20, value: { merge: writeDisplayNbt(frame.value, profile) } })) } });
    }
  }

  return {
    id: `emote:${sanitizeResourcePath(animation.name, "default")}`,
    metadata: { name, description: `${name} emote.` },
    nodes,
    source: { unresolved_commands: structuredClone(animation.unresolvedCommands) },
    animation: { duration: animation.durationTicks / 20, playback: { mode: "loop", loop_start: 0, loop_delay: 0 }, tracks,
      events: { timeline: animation.unresolvedCommands.map((event) => ({ time: event.tick / 20, source: { type: "player" }, origin: { type: "root" }, action: { type: "commands", commands: [event.command.replace(/^\//, "")] } })) },
    },
  };
}

function prettify(value: string): string {
  return value.replaceAll("_", " ").replace(/\b\w/g, (character) => character.toUpperCase());
}
