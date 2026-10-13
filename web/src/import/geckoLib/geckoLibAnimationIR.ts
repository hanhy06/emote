import type { AnimationIR, NodeIR, ScalarIR, TimelineEventIR } from "../../domain/animationIR";
import { affineCurveIR, scalarIR, sourceDelayIR } from "../../domain/animationIRConversion";
import { sourceSecondsTime } from "../../format/time";
import type { ImportedNode } from "../../domain/conversionSeed";
import { matrix4ToRowMajor } from "../../format/matrix";
import type { BbAnimation, BbAnimator } from "../common/blockbenchCubeSchema";
import type { BoneEntry } from "../common/blockbenchCubeModel";
import type { MolangScalar } from "../common/molangVector";
import { importedNodeIR, blockbenchCurveIR } from "../common/blockbenchAnimationIR";
import { PLAYER_RENDER_SCALE } from "../common/blockbenchCubeModel";
import { PLAYER_ROTATION_QUERY_VALUE_NAMES } from "../../format/molang/queryCatalog";
import { mapMolangResult, rewriteMolangIdentifiers } from "../../format/molang/sourceTransformer";

export interface GeckoLibAnimationSource {
  animation: BbAnimation;
  animationIndex: number;
  animators: ReadonlyMap<string, BbAnimator>;
  blendWeight: MolangScalar;
  playbackMode: "once" | "hold" | "loop";
  events: TimelineEventIR[];
}

export function createGeckoLibAnimationIR(source: GeckoLibAnimationSource, bones: readonly BoneEntry[], imported: Record<string, ImportedNode>): AnimationIR {
  const nodes: Record<string, NodeIR> = {
    scene: { transform: [{ id: "scale", op: "scale", value: [PLAYER_RENDER_SCALE, PLAYER_RENDER_SCALE, PLAYER_RENDER_SCALE] }] },
  };
  const ir: AnimationIR = {
    id: "emote:imported", metadata: { name: source.animation.name, description: `${source.animation.name} emote.` }, nodes,
    animation: { duration: sourceSecondsTime(typeof source.animation.length === "number" ? Math.max(0.05, source.animation.length, ...Object.values(source.animation.animators).flatMap((a) => (a.keyframes ?? []).map((f) => f.time))) : source.animation.length),
      playback: { mode: source.playbackMode, start_delay: sourceDelayIR(source.animation.start_delay || 0), loop_delay: sourceDelayIR(source.animation.loop_delay || 0) },
      tracks: [], events: { start: [], timeline: source.events, loop: [], stop: [] } },
  };
  const corrected = (value: MolangScalar): MolangScalar => typeof value !== "string" ? value : rewriteMolangIdentifiers(value, (identifier) => {
    const match = /^(?:q|query)\.([A-Za-z_][A-Za-z0-9_]*)$/i.exec(identifier);
    return match && PLAYER_ROTATION_QUERY_VALUE_NAMES.has(match[1].toLowerCase()) ? `-(${identifier})` : undefined;
  });
  const rawWeight = scalarIR(corrected(source.blendWeight));
  const blendWeight: ScalarIR = typeof rawWeight === "number" ? Math.max(0, rawWeight)
    : { molang: mapMolangResult(rawWeight.molang, (result) => `math.max(0, ${result})`) };
  for (const bone of bones) {
    const id = `group:${bone.uuid}`;
    const parentOrigin = bone.parent?.group.origin ?? [0, 0, 0];
    const position = [
      (bone.group.origin[0] - parentOrigin[0]) / 16,
      (bone.group.origin[1] - parentOrigin[1]) / 16,
      (bone.group.origin[2] - parentOrigin[2]) / 16,
    ];
    const rotation = [bone.group.rotation[0], bone.group.rotation[1], bone.group.rotation[2]];
    nodes[id] = { name: bone.group.name, parent: bone.parent ? `group:${bone.parent.uuid}` : "scene",
      source: { node_id: bone.uuid },
      transform: [
        { id: "position", op: "translate", value: position },
        { id: "rotation", op: "rotate_euler", order: "ZYX", value: rotation },
        { id: "scale", op: "scale", value: [1, 1, 1] },
      ],
    };
    for (const entry of bone.nodes) {
      const n = imported[entry.id]; if (!n && !bone.sourceElement) continue;
      const child = n ? importedNodeIR(n, id) : { parent: id, visible: true };
      nodes[entry.id] = { ...child, name: entry.locatorName ?? entry.id,
        source: { node_id: n?.binding.sourceNodeId ?? bone.sourceElement!.uuid, editor_node_id: entry.id, ...(bone.sourceElement ? { original_type: bone.sourceElement.type } : {}) },
        ...(entry.ignoreInheritedScale ? { inherit: { scale: false } } : {}),
        transform: [{ id: "content", op: "matrix", value: matrix4ToRowMajor(entry.localMatrix, `IR ${entry.id}`) }],
      };
    }
    const animator = source.animators.get(bone.uuid);
    if (!animator) continue;
    let weight = blendWeight;
    if (typeof weight !== "number" && animator.keyframes?.some((frame) => ["position", "rotation", "scale"].includes(frame.channel))) {
      const valueId = `blend_${Object.keys(ir.animation.values ?? {}).length}`;
      (ir.animation.values ??= {})[valueId] = { type: "expression", base: [1], value: [weight] };
      weight = { molang: `q.value('${valueId}', 0)` };
    }
    for (const channel of ["rotation", "position", "scale"] as const) {
      const frames = (animator.keyframes ?? []).filter((frame) => frame.channel === channel).map((frame) => ({ ...frame,
        data_points: frame.data_points.map((point) => ({ ...point, x: point.x === undefined ? undefined : corrected(point.x), y: point.y === undefined ? undefined : corrected(point.y), z: point.z === undefined ? undefined : corrected(point.z) })),
        easingArgs: frame.easingArgs?.map(corrected), bezier_left_time: frame.bezier_left_time?.map(corrected), bezier_right_time: frame.bezier_right_time?.map(corrected),
        bezier_left_value: frame.bezier_left_value?.map(corrected), bezier_right_value: frame.bezier_right_value?.map(corrected),
      }));
      const curve = blockbenchCurveIR(frames);
      if (!curve) continue;
      const base = channel === "position" ? position : channel === "rotation" ? rotation : [1, 1, 1];
      const driver = affineCurveIR(curve, channel === "position" ? [1 / 16, 1 / 16, 1 / 16] : [1, 1, 1], channel === "scale" ? [0, 0, 0] : base);
      ir.animation.tracks.push({ target: { node: id, operation: channel }, channel: "value", driver, ...(weight === 1 ? {} : { blend: { base, weight } }) });
    }
  }
  return ir;
}
