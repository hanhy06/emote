import { sourceSecondsTime } from "../../format/time";
import type { AnimationIR, NodeIR, ScalarIR, Vector3IR } from "../../domain/animationIR";
import { affineCurveIR, scalarIR, sourceDelayIR } from "../../domain/animationIRConversion";
import type { ImportedNode } from "../../domain/conversionSeed";
import type { DisplayNbtPatch } from "../../domain/minecraftData";
import { readDisplayNbt, writeDisplayNbt } from "../../format/minecraftData";
import { minecraftVersionProfile } from "../../format/minecraftVersionProfiles";
import { importedNodeIR, blockbenchCurveIR } from "../common/blockbenchAnimationIR";
import { mapMolangResult } from "../../format/molang/sourceTransformer";
import { initialDisplayNbt } from "../common/runtimeOutput";
import type { AjProject, AjProjectAnimation, ProjectTransformGraph } from "./animatedJavaProjectSchema";
import type { AnimatedJavaCubes } from "./animatedJavaCubes";
import { matrix4ToRowMajor } from "../../format/matrix";
import { isRecord } from "../../format/runtimeValue";

export interface ProjectNodeStateFrame {
  nodeId: string;
  time: number;
  visible?: boolean | string;
  nbt?: DisplayNbtPatch;
}

export function createAnimatedJavaAnimationIR(source: AjProjectAnimation, project: AjProject, hierarchy: ProjectTransformGraph, imported: Record<string, ImportedNode>, scale: number, states: readonly ProjectNodeStateFrame[], cubes?: AnimatedJavaCubes): AnimationIR {
  const playbackMode = source.loop === "hold_on_last_frame" ? "hold" : source.loop;
  const ir: AnimationIR = {
    id: "emote:imported", metadata: { name: source.name, description: `${source.name} emote.` },
    nodes: { scene: { transform: [{ id: "scale", op: "scale", value: [scale, scale, scale] }] } },
    animation: { duration: sourceSecondsTime(typeof source.length === "number" ? Math.max(0.05, source.length, ...Object.values(source.animators).flatMap((a) => (a.keyframes ?? []).map((f) => f.time))) : source.length), tracks: [] },
  };
  ir.animation.playback = { mode: playbackMode as "once" | "hold" | "loop", start_delay: sourceDelayIR(source.start_delay || 0), loop_delay: sourceDelayIR(source.loop_delay || 0) };
  const rawWeight = scalarIR(source.blend_weight === undefined || source.blend_weight === "" ? 1 : source.blend_weight);
  const blendWeight: ScalarIR = typeof rawWeight === "number" ? Math.max(0, rawWeight)
    : { molang: mapMolangResult(rawWeight.molang, (result) => `math.max(0, ${result})`) };
  const groupIds = [...new Set([...project.groups.map((group) => group.uuid), ...hierarchy.groups.keys()])];
  for (const uuid of groupIds) {
    const group = hierarchy.groups.get(uuid);
    if (!group) continue;
    const id = `group:${uuid}`;
    const parent = hierarchy.groupParents.get(uuid);
    const origin = parent ? hierarchy.groups.get(parent)?.origin ?? [0, 0, 0] : [0, 0, 0];
    const position = group.origin.map((v, axis) => (v - origin[axis]) / 16) as Vector3IR;
    ir.nodes[id] = { name: group.name, parent: parent ? `group:${parent}` : "scene",
      source: { node_id: uuid },
      transform: [ { id: "position", op: "translate", value: position },
        { id: "rotation", op: "rotate_euler", order: "ZYX", value: group.rotation as Vector3IR }, { id: "scale", op: "scale", value: [1, 1, 1] } ],
    };
    let weight = blendWeight;
    if (typeof weight !== "number" && source.animators[uuid]?.keyframes?.some((frame) => ["position", "rotation", "scale"].includes(frame.channel))) {
      const valueId = `blend_${Object.keys(ir.animation.values ?? {}).length}`;
      (ir.animation.values ??= {})[valueId] = { type: "expression", base: [1], value: [weight] };
      weight = { molang: `q.value('${valueId}', 0)` };
    }
    for (const channel of ["rotation", "position", "scale"] as const) {
      const frames = (source.animators[uuid]?.keyframes?.filter((frame) => frame.channel === channel) ?? []).map((frame) => ({ ...frame,
        ...(/bounce$/i.test(frame.easing ?? "") && frame.easingArgs?.[0] === undefined ? { easingArgs: [0.25] } : {}),
      }));
      const curve = blockbenchCurveIR(frames);
      if (!curve) continue;
      const base: Vector3IR = channel === "position" ? position : channel === "rotation" ? group.rotation as Vector3IR : [1, 1, 1];
      const driver = affineCurveIR(curve, channel === "position" ? [1 / 16, 1 / 16, 1 / 16] : [1, 1, 1], channel === "scale" ? [0, 0, 0] : base);
      ir.animation.tracks.push({ target: { node: id, operation: channel }, channel: "value", driver, ...(weight === 1 ? {} : { blend: { base, weight } }) });
    }
  }
  for (const bone of cubes?.bones ?? []) for (const entry of bone.nodes) {
    const node = imported[entry.id];
    if (!node) continue;
    const content = importedNodeIR(node, `group:${bone.uuid}`);
    ir.nodes[entry.id] = { ...content, name: entry.locatorName ?? entry.id,
      source: { node_id: node.binding.sourceNodeId, editor_node_id: entry.id }, ...(entry.ignoreInheritedScale ? { inherit: { scale: false } } : {}),
      transform: [{ id: "content", op: "matrix", value: matrix4ToRowMajor(entry.localMatrix, `IR ${entry.id}`) }, ...(content.transform ?? [])],
    };
  }
  for (const element of project.elements) {
    if (element.type === "cube" || element.type === "locator") continue;
    const parentUuid = hierarchy.elementParents.get(element.uuid);
    const parent = parentUuid ? `group:${parentUuid}` : "scene";
    const parentOrigin = parentUuid ? hierarchy.groups.get(parentUuid)?.origin ?? [0, 0, 0] : [0, 0, 0];
    const raw = element as unknown as Record<string, unknown>;
    const position = (Array.isArray(raw.position) ? raw.position : Array.isArray(raw.origin) ? raw.origin : [0, 0, 0]) as Vector3IR;
    const rotation = (Array.isArray(raw.rotation) ? raw.rotation : [0, 0, 0]) as Vector3IR;
    const size = (Array.isArray(raw.scale) ? raw.scale : [1, 1, 1]) as Vector3IR;
    const n = imported[element.uuid];
    const node: NodeIR = n ? importedNodeIR(n, parent) : { parent };
    if (!n) {
      const configs = isRecord(raw.configs) ? raw.configs : undefined;
      const config = { ...(isRecord(raw.config) ? raw.config : {}), ...(isRecord(configs?.default) ? configs.default : {}) };
      const groupConfig = project.groups.find((group) => group.uuid === parentUuid)?.configs?.default;
      node.visible = raw.visibility !== false && config.invisible !== true && groupConfig?.invisible !== true;
    }
    node.name = element.name; node.source = { node_id: element.uuid, editor_node_id: element.uuid, original_type: element.type };
    if (raw.ignore_inherited_scale) node.inherit = { scale: false };
    const basePosition = position.map((v, axis) => (v - parentOrigin[axis]) / 16) as Vector3IR;
    node.transform = [ { id: "position", op: "translate", value: basePosition },
      { id: "rotation", op: "rotate_euler", order: "XYZ", value: rotation }, { id: "scale", op: "scale", value: size } ];
    if (n?.type === "text_display") node.transform.push({ id: "text_orientation", op: "rotate_euler", order: "XYZ", value: [0, 180, 0] });
    ir.nodes[element.uuid] = node;
    let weight = blendWeight;
    if (typeof weight !== "number" && source.animators[element.uuid]?.keyframes?.some((frame) => ["position", "rotation", "scale"].includes(frame.channel))) {
      const valueId = `blend_${Object.keys(ir.animation.values ?? {}).length}`;
      (ir.animation.values ??= {})[valueId] = { type: "expression", base: [1], value: [weight] };
      weight = { molang: `q.value('${valueId}', 0)` };
    }
    for (const channel of ["rotation", "position", "scale"] as const) {
      const frames = (source.animators[element.uuid]?.keyframes?.filter((frame) => frame.channel === channel) ?? []).map((frame) => ({ ...frame,
        ...(/bounce$/i.test(frame.easing ?? "") && frame.easingArgs?.[0] === undefined ? { easingArgs: [0.25] } : {}),
      }));
      const curve = blockbenchCurveIR(frames);
      if (!curve) continue;
      const displayScale: Vector3IR = !n || n.type === "item_display" ? [1, 1, 1] : size;
      const base: Vector3IR = channel === "position" ? basePosition : channel === "rotation" ? rotation : displayScale;
      const factors = channel === "position" ? [-1 / 16, 1 / 16, 1 / 16] : channel === "rotation" ? [-1, -1, 1] : displayScale;
      const driver = affineCurveIR(curve, factors, channel === "scale" ? [0, 0, 0] : base);
      ir.animation.tracks.push({ target: { node: element.uuid, operation: channel }, channel: "value", driver, ...(weight === 1 ? {} : { blend: { base, weight } }) });
    }
  }
  const statesByNode = new Map<string, ProjectNodeStateFrame[]>();
  for (const frame of states) {
    const frames = statesByNode.get(frame.nodeId) ?? [];
    frames.push(frame);
    statesByNode.set(frame.nodeId, frames);
  }
  const profile = minecraftVersionProfile("26.3");
  for (const [id, frames] of statesByNode) {
    const node = ir.nodes[id];
    if (!node) continue;
    const visible = [...new Map(frames.flatMap((frame) => frame.visible === undefined ? [] : [[frame.time, {
      time: frame.time, value: typeof frame.visible === "boolean" ? frame.visible : { molang: frame.visible },
    }] as const])).values()].sort((a, b) => a.time - b.time);
    if (visible.length) {
      if (visible[0].time !== 0) visible.unshift({ time: 0, value: node.visible ?? true });
      ir.animation.tracks.push({ target: { node: id }, channel: "visible", driver: { type: "state", keys: visible.map((frame) => ({ ...frame, time: sourceSecondsTime(frame.time) })) } });
    }
    const nbt = frames.flatMap((frame) => frame.nbt ? [{ time: frame.time, value: frame.nbt }] : []).sort((a, b) => a.time - b.time);
    const importedNode = imported[id];
    if (nbt.length && node.attachments?.display && importedNode.type !== "anchor") {
      const atZero = nbt[0].time === 0 ? nbt[0].value : undefined;
      const removeBrightness = nbt.some((frame) => frame.value.rawFields.some((field) => field.name === "brightness"))
        && ![...readDisplayNbt(importedNode.entityNbt ?? "{}").rawFields, ...(atZero?.rawFields ?? [])].some((field) => field.name === "brightness");
      const initial = { time: 0, value: initialDisplayNbt(importedNode, nbt.map((frame) => removeBrightness
        ? { ...frame.value, rawFields: frame.value.rawFields.filter((field) => field.name !== "brightness") } : frame.value), atZero) };
      if (nbt[0].time === 0) nbt[0] = initial;
      else nbt.unshift(initial);
      ir.animation.tracks.push({ target: { node: id, attachment: "display" }, channel: "nbt", driver: {
        type: "state", keys: nbt.map((frame, index) => ({ time: sourceSecondsTime(frame.time), value: {
          merge: writeDisplayNbt(frame.value, profile), ...(index === 0 && removeBrightness ? { remove: ["brightness"] } : {}),
        } })),
      } });
    }
  }
  return ir;
}
