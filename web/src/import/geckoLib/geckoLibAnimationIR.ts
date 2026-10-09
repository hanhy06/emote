import type { AnimationIR, NodeIR, TimelineEventIR } from "../../domain/animationIR";
import { scalarIR } from "../../domain/animationIRConversion";
import type { ImportedNode } from "../../domain/conversionSeed";
import { matrix4ToRowMajor } from "../../format/matrix";
import type { BbAnimation, BbAnimator } from "../common/blockbenchCubeSchema";
import type { BoneEntry } from "../common/blockbenchCubeModel";
import { affineMolang, type MolangScalar, type MolangVector } from "../common/molangVector";
import { importedNodeIR, blockbenchCurveIR } from "../common/blockbenchAnimationIR";
import { PLAYER_RENDER_SCALE } from "../common/blockbenchCubeModel";

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
    animation: { duration: Math.max(0.05, source.animation.length, ...Object.values(source.animation.animators).flatMap((a) => (a.keyframes ?? []).map((f) => f.time))),
      playback: { mode: source.playbackMode, start_delay: scalarIR(source.animation.start_delay || 0), loop_delay: scalarIR(source.animation.loop_delay || 0) },
      tracks: [], events: { start: [], timeline: source.events, loop: [], stop: [] } },
  };
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
        source: { editor_node_id: entry.id, ...(bone.sourceElement ? { original_type: bone.sourceElement.type } : {}) },
        ...(entry.ignoreInheritedScale ? { inherit: { scale: false } } : {}),
        transform: [{ id: "content", op: "matrix", value: matrix4ToRowMajor(entry.localMatrix, `IR ${entry.id}`) }],
      };
    }
    const animator = source.animators.get(bone.uuid);
    if (!animator) continue;
    for (const channel of ["position", "rotation", "scale"] as const) {
      const convert = (values: MolangVector): MolangVector => {
        if (channel === "position") return values.map((v, axis) => affineMolang(v, affineMolang(source.blendWeight, 1 / 16, 0), position[axis])) as MolangVector;
        if (channel === "rotation") return values.map((v, axis) => affineMolang(v, source.blendWeight, rotation[axis])) as MolangVector;
        return values.map((v) => affineMolang(v, source.blendWeight, affineMolang(source.blendWeight, -1, 1))) as MolangVector;
      };
      const driver = blockbenchCurveIR((animator.keyframes ?? []).filter((f) => f.channel === channel), convert);
      if (driver) ir.animation.tracks.push({ target: { node: id, operation: channel }, channel: "value", driver });
    }
  }
  return ir;
}
