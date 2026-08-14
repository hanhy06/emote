import { Euler, MathUtils, Matrix4, Quaternion, Vector3 } from "three";
import { createDefaultPlayerBehavior, type Matrix16 } from "../../format/emoteAnimation";
import { matrix4ToRowMajor } from "../../format/matrix";
import { sanitizeNamespace, sanitizeResourcePath } from "../../format/resourceLocation";
import { serializeSnbtCompound, serializeSnbtString } from "../../format/snbt";
import { requireAnimationDurationTicks, TICKS_PER_SECOND } from "../../format/time";
import { ConversionError } from "../../foundation/diagnostics";
import type { ImportedAnimation, ImportedNode, ImportedProject, ImportedSkinPart, ImportedTransformKeyframe } from "../../domain/conversionSeed";
import {
  type BbAnimation,
  type BbAnimator,
  type BbCube,
  type BbDataPoint,
  type BbGroup,
  type BbKeyframe,
  type BbOutlinerEntry,
  type BbOutlinerGroup,
  type BbTexture,
  type BbmodelProject,
} from "./cubeProjectSchema";
import { cubeEasingProgress } from "./cubeEasing";

const encoder = new TextEncoder();
const SUPPORTED_FACES = new Set(["north", "south", "east", "west", "up", "down"]);
const PLAYER_RENDER_SCALE = 0.9375;

interface BoneNodeEntry {
  id: string;
}

interface BoneEntry {
  id: string;
  uuid: string;
  group: BbGroup;
  parent?: BoneEntry;
  cubes: BbCube[];
  nodes: BoneNodeEntry[];
}

export function importBlockbenchCubeProject(project: BbmodelProject, sourceName: string): ImportedProject {
  if (project.meta.model_format !== "geckolib_model") throw new Error(`Unsupported Blockbench model format: ${project.meta.model_format}`);
  if (project.elements.some((element) => element.type && element.type !== "cube")) {
    throw new ConversionError("unsupported_geckolib_element", "GeckoLib meshes and non-cube elements are not supported.", "elements");
  }

  const sourceStem = sourceName.replace(/\.bbmodel$/i, "").trim() || project.name?.trim() || "GeckoLib Model";
  const namespace = validNamespace(project.geckolib_modid) ?? sanitizeNamespace(sourceStem);
  const projectPath = sanitizeResourcePath(project.name?.trim() || sourceStem, "geckolib_model");
  const resources = new Map<string, Uint8Array>();
  const bones = buildBoneEntries(project);
  if (bones.length === 0) throw new Error("GeckoLib bbmodel does not contain bones.");
  if (bones.some((bone) => bone.cubes.length > 0)) {
    const texture = requireEmbeddedTexture(project.textures);
    const texturePath = `assets/${namespace}/textures/item/${projectPath}/texture.png`;
    resources.set(texturePath, decodeTexture(texture));
    const resourceNodeIds = new Set(bones.map((bone) => bone.id));
    for (const bone of bones) {
      for (const [cubeIndex, cube] of bone.cubes.entries()) {
        const nodeId = cubeIndex === 0 ? bone.id : uniqueCubeNodeId(bone, cube, cubeIndex, resourceNodeIds);
        writeCubeResources(project, bone, cube, namespace, `${projectPath}/${nodeId}`, resources);
      }
    }
  }
  const playableCubesByBone = new Map(bones.map((bone) => [
    bone.uuid,
    splitTallSkinCubes(bone, removeDuplicateSkinLayers(bone.cubes)),
  ]));
  const skinAssignments = inferSkinAssignments(bones, playableCubesByBone);
  const nodes: Record<string, ImportedNode> = {};
  const nodeIds = new Set(bones.map((bone) => bone.id));
  for (const bone of bones) {
    const defaultMatrix = boneWorldMatrix(bone, new Map());
    const playableCubes = playableCubesByBone.get(bone.uuid) ?? [];
    if (playableCubes.length === 0) {
      nodes[bone.id] = { id: bone.id, type: "anchor", defaultMatrix };
      bone.nodes.push({ id: bone.id });
      continue;
    }
    for (const [cubeIndex, cube] of playableCubes.entries()) {
      const nodeId = cubeIndex === 0 ? bone.id : uniqueCubeNodeId(bone, cube, cubeIndex, nodeIds);
      const conversionMatrix = cubePlayerHeadMatrix(cube, bone);
      if (!conversionMatrix) throw new ConversionError("invalid_geckolib_cube", `Cube ${cube.name ?? cube.uuid} cannot be fitted to a player head.`, cube.uuid);
      const skin = skinAssignments.get(cube.uuid);
      bone.nodes.push({ id: nodeId });
      const modelPath = `${projectPath}/${nodeId}`;
      writeCubeResources(project, bone, cube, namespace, modelPath, resources);
      nodes[nodeId] = {
        id: nodeId,
        type: "item_display",
        defaultMatrix,
        visible: true,
        itemDisplay: "none",
        itemStackSnbt: serializeSnbtCompound([
          ["id", serializeSnbtString("minecraft:paper")],
          ["count", "1"],
          ["components", serializeSnbtCompound([
            ["minecraft:item_model", serializeSnbtString(`${namespace}:${modelPath}`)],
          ])],
        ]),
        playerHeadConversion: { matrix: conversionMatrix },
        ...(skin ? { suggestedSkin: skin } : {}),
      };
    }
  }

  if (project.animations.length === 0) throw new Error("GeckoLib bbmodel does not contain animations.");
  const animations = project.animations.map((animation, index) => importAnimation(animation, index, bones));
  return {
    source: "geckolib_bbmodel",
    sourceName,
    suggestedMetadata: { name: sourceStem, description: `${sourceStem} emote.` },
    suggestedPlayer: createDefaultPlayerBehavior(),
    suggestedNamespace: namespace,
    nodes,
    animations,
    diagnostics: [],
    resources,
    ...(resources.size ? { resourceMinecraftVersion: "26.2" } : {}),
  };
}

function buildBoneEntries(project: BbmodelProject): BoneEntry[] {
  const groups = new Map(project.groups.map((group) => [group.uuid, group]));
  const cubes = new Map(project.elements.map((cube) => [cube.uuid, cube]));
  const entries: BoneEntry[] = [];
  const ids = new Set<string>();
  const visit = (entry: BbOutlinerEntry, parent?: BoneEntry) => {
    if (typeof entry === "string") {
      if (!parent) throw new Error(`GeckoLib cube ${entry} is not parented to a bone.`);
      const cube = cubes.get(entry);
      if (!cube) throw new Error(`GeckoLib outliner references unknown element ${entry}.`);
      parent.cubes.push(cube);
      return;
    }
    const saved = groups.get(entry.uuid);
    const group = mergeGroup(saved, entry);
    let id = sanitizeResourcePath(group.name, "bone").replaceAll("/", "_");
    const base = id;
    for (let suffix = 2; ids.has(id); suffix++) id = `${base}_${suffix}`;
    ids.add(id);
    const bone: BoneEntry = { id, uuid: group.uuid, group, parent, cubes: [], nodes: [] };
    entries.push(bone);
    entry.children.forEach((child) => visit(child, bone));
  };
  project.outliner.forEach((entry) => visit(entry));
  return entries;
}

function mergeGroup(saved: BbGroup | undefined, outliner: BbOutlinerGroup): BbGroup {
  const name = outliner.name ?? saved?.name;
  const origin = outliner.origin ?? saved?.origin;
  const rotation = outliner.rotation ?? saved?.rotation ?? [0, 0, 0];
  if (!name || !origin) throw new Error(`GeckoLib bone ${outliner.uuid} is missing its saved group data.`);
  return { uuid: outliner.uuid, name, origin, rotation };
}

function uniqueCubeNodeId(bone: BoneEntry, cube: BbCube, cubeIndex: number, ids: Set<string>): string {
  const cubeName = sanitizeResourcePath(cube.name?.trim() || `cube_${cubeIndex + 1}`, `cube_${cubeIndex + 1}`).replaceAll("/", "_");
  const base = `${bone.id}_${cubeName}`;
  let id = base;
  for (let suffix = 2; ids.has(id); suffix++) id = `${base}_${suffix}`;
  ids.add(id);
  return id;
}

function boneWorldMatrix(bone: BoneEntry, cache: Map<string, Matrix16>): Matrix16 {
  const cached = cache.get(bone.uuid);
  if (cached) return cached;
  const local = bindLocalMatrix(bone);
  const world = bone.parent
    ? new Matrix4().set(...boneWorldMatrix(bone.parent, cache)).multiply(local)
    : new Matrix4().makeScale(PLAYER_RENDER_SCALE, PLAYER_RENDER_SCALE, PLAYER_RENDER_SCALE).multiply(local);
  const result = matrix4ToRowMajor(world, `GeckoLib bone ${bone.id}`);
  cache.set(bone.uuid, result);
  return result;
}

function bindLocalMatrix(bone: BoneEntry): Matrix4 {
  const parentOrigin = bone.parent?.group.origin ?? [0, 0, 0];
  return composeTransform(
    bone.group.origin.map((value, index) => (value - parentOrigin[index]) / 16),
    bone.group.rotation,
    [1, 1, 1],
  );
}

function importAnimation(animation: BbAnimation, index: number, bones: BoneEntry[]): ImportedAnimation {
  const loop = animation.loop ?? "once";
  if (loop === "hold" || loop === "hold_on_last_frame") {
    throw new ConversionError("unsupported_geckolib_loop", `GeckoLib animation ${animation.name} uses hold mode.`, `animations[${index}].loop`);
  }
  if (loop !== "once" && loop !== "loop") throw new Error(`GeckoLib animation ${animation.name} has unsupported loop mode ${loop}.`);
  if (!Number.isFinite(animation.length) || animation.length < 0) throw new Error(`GeckoLib animation ${animation.name} has an invalid length.`);
  const durationTicks = requireAnimationDurationTicks(
    Math.max(1, Math.round(animation.length * TICKS_PER_SECOND)),
    `${animation.name}.length`,
  );
  const boneAnimators = resolveBoneAnimators(animation, index, bones);
  const tracks: ImportedAnimation["tracks"] = {};
  for (const bone of bones) {
    validateBoneAnimator(animation, index, bone, boneAnimators.get(bone.uuid));
    const transforms: ImportedTransformKeyframe[] = [];
    for (let tick = 0; tick <= durationTicks; tick++) {
      const cache = new Map<string, Matrix4>();
      transforms.push({
        tick,
        matrix: matrix4ToRowMajor(animatedWorldMatrix(bone, animation, boneAnimators, tick / TICKS_PER_SECOND, cache, index), `${animation.name}/${bone.id}/${tick}`),
        interpolation: tick === 0 ? { type: "step" } : { type: "linear", durationTicks: 1 },
      });
    }
    for (const node of bone.nodes) tracks[node.id] = { transforms, visibility: [] };
  }
  return {
    id: sanitizeResourcePath(animation.name, `animation_${index + 1}`),
    name: animation.name,
    durationTicks,
    loop,
    loopDelayTicks: loop === "loop"
      ? Math.round(numericValue(animation.loop_delay ?? 0, `animations[${index}].loop_delay`) * TICKS_PER_SECOND)
      : 0,
    tracks,
    events: { start: [], timeline: [], loop: [], stop: [] },
  };
}

function resolveBoneAnimators(animation: BbAnimation, animationIndex: number, bones: BoneEntry[]): Map<string, BbAnimator> {
  const result = new Map<string, BbAnimator>();
  const boneByUuid = new Map(bones.map((bone) => [bone.uuid, bone]));
  for (const [animatorId, animator] of Object.entries(animation.animators)) {
    if (boneByUuid.has(animatorId)) result.set(animatorId, animator);
  }

  for (const [animatorId, animator] of Object.entries(animation.animators)) {
    if (boneByUuid.has(animatorId) || (animator.keyframes?.length ?? 0) === 0) continue;
    const normalizedName = normalizeBoneName(animator.name);
    const matchingBones = normalizedName
      ? bones.filter((bone) => !result.has(bone.uuid) && normalizeBoneName(bone.group.name) === normalizedName)
      : [];
    if (matchingBones.length === 1) {
      result.set(matchingBones[0].uuid, animator);
      continue;
    }
    throw new ConversionError(
      "unsupported_geckolib_animator",
      `GeckoLib animation ${animation.name} contains a non-bone animator (${animator.name ?? animatorId}).`,
      `animations[${animationIndex}].animators.${animatorId}`,
    );
  }
  return result;
}

function normalizeBoneName(name: string | undefined): string | undefined {
  const normalized = name?.toLowerCase().replaceAll(/[^a-z0-9]/g, "");
  return normalized || undefined;
}

function removeDuplicateSkinLayers(cubes: BbCube[]): BbCube[] {
  return cubes.filter((cube, cubeIndex) => !cubes.some((other, otherIndex) => {
    if (cubeIndex === otherIndex || !sameCubeBounds(cube, other)) return false;
    const nameMarksLayer = normalizeBoneName(cube.name)?.includes("layer") ?? false;
    return nameMarksLayer || (cube.inflate ?? 0) > (other.inflate ?? 0);
  }));
}

function splitTallSkinCubes(bone: BoneEntry, cubes: BbCube[]): BbCube[] {
  const part = inferSkinPart(bone);
  return cubes.flatMap((cube) => {
    if (!part || part === "head" || !isStandardPlayerSkinCube(cube, part)) return [cube];
    const height = cube.to[1] - cube.from[1];
    const upperHeight = height / 3;
    const splitY = cube.to[1] - upperHeight;
    const [upperFaces, lowerFaces] = splitVerticalFaceUvs(cube.faces, upperHeight / height);
    return [
      {
        ...cube,
        uuid: `${cube.uuid}_skin_upper`,
        name: `${cube.name ?? "Cube"} Upper`,
        from: [cube.from[0], splitY, cube.from[2]],
        faces: upperFaces,
      },
      {
        ...cube,
        uuid: `${cube.uuid}_skin_lower`,
        name: `${cube.name ?? "Cube"} Lower`,
        to: [cube.to[0], splitY, cube.to[2]],
        faces: lowerFaces,
      },
    ];
  });
}

function splitVerticalFaceUvs(faces: BbCube["faces"], upperRatio: number): [BbCube["faces"], BbCube["faces"]] {
  const upperFaces: BbCube["faces"] = {};
  const lowerFaces: BbCube["faces"] = {};
  for (const [direction, face] of Object.entries(faces)) {
    if (!["north", "south", "east", "west"].includes(direction) || face.uv?.length !== 4) {
      upperFaces[direction] = face;
      lowerFaces[direction] = face;
      continue;
    }

    const [minU, minV, maxU, maxV] = face.uv;
    const rotation = ((face.rotation ?? 0) % 360 + 360) % 360;
    if (rotation === 90) {
      const splitU = minU + (maxU - minU) * upperRatio;
      upperFaces[direction] = { ...face, uv: [minU, minV, splitU, maxV] };
      lowerFaces[direction] = { ...face, uv: [splitU, minV, maxU, maxV] };
    } else if (rotation === 180) {
      const splitV = maxV + (minV - maxV) * upperRatio;
      upperFaces[direction] = { ...face, uv: [minU, splitV, maxU, maxV] };
      lowerFaces[direction] = { ...face, uv: [minU, minV, maxU, splitV] };
    } else if (rotation === 270) {
      const splitU = maxU + (minU - maxU) * upperRatio;
      upperFaces[direction] = { ...face, uv: [splitU, minV, maxU, maxV] };
      lowerFaces[direction] = { ...face, uv: [minU, minV, splitU, maxV] };
    } else {
      const splitV = minV + (maxV - minV) * upperRatio;
      upperFaces[direction] = { ...face, uv: [minU, minV, maxU, splitV] };
      lowerFaces[direction] = { ...face, uv: [minU, splitV, maxU, maxV] };
    }
  }
  return [upperFaces, lowerFaces];
}

function sameCubeBounds(first: BbCube, second: BbCube): boolean {
  return first.from.every((value, axis) => Math.abs(value - second.from[axis]) <= 1e-7)
    && first.to.every((value, axis) => Math.abs(value - second.to[axis]) <= 1e-7);
}

function inferSkinAssignments(
  bones: BoneEntry[],
  playableCubesByBone: ReadonlyMap<string, BbCube[]>,
): Map<string, ImportedSkinPart> {
  const candidates: { cube: BbCube; part: ImportedSkinPart["part"]; centerY: number; sourceOrder: number }[] = [];
  let sourceOrder = 0;
  for (const bone of bones) {
    const part = inferSkinPart(bone);
    for (const cube of playableCubesByBone.get(bone.uuid) ?? []) {
      const isSplitSkinCube = cube.uuid.endsWith("_skin_upper") || cube.uuid.endsWith("_skin_lower");
      if (!part || (!isSplitSkinCube && !isStandardPlayerSkinCube(cube, part))) continue;
      candidates.push({ cube, part, centerY: (cube.from[1] + cube.to[1]) / 2, sourceOrder: sourceOrder++ });
    }
  }

  const result = new Map<string, ImportedSkinPart>();
  for (const part of ["head", "body", "left_arm", "right_arm", "left_leg", "right_leg"] as const) {
    candidates
      .filter((candidate) => candidate.part === part)
      .sort((first, second) => second.centerY - first.centerY || first.sourceOrder - second.sourceOrder)
      .forEach((candidate, order) => result.set(candidate.cube.uuid, { part, order }));
  }
  return result;
}

function isStandardPlayerSkinCube(cube: BbCube, part: ImportedSkinPart["part"]): boolean {
  const size = cube.to.map((value, axis) => Math.abs(value - cube.from[axis]));
  const closeTo = (value: number, expected: number) => Math.abs(value - expected) <= 1e-3;
  if (part === "head") return closeTo(size[0], 8) && closeTo(size[1], 8) && closeTo(size[2], 8);
  if (part === "body") return closeTo(size[0], 8) && closeTo(size[1], 12) && closeTo(size[2], 4);
  return (closeTo(size[0], 3) || closeTo(size[0], 4)) && closeTo(size[1], 12) && closeTo(size[2], 4);
}

function inferSkinPart(bone: BoneEntry): ImportedSkinPart["part"] | undefined {
  for (let current: BoneEntry | undefined = bone; current; current = current.parent) {
    const name = normalizeBoneName(current.group.name) ?? "";
    if (name.includes("left") && (name.includes("arm") || name.includes("hand") || name.includes("wing"))) return "left_arm";
    if (name.includes("right") && (name.includes("arm") || name.includes("hand") || name.includes("wing"))) return "right_arm";
    if (name.includes("left") && (name.includes("leg") || name.includes("foot"))) return "left_leg";
    if (name.includes("right") && (name.includes("leg") || name.includes("foot"))) return "right_leg";
    if (name.includes("head") || name.includes("face") || name.includes("skull")) return "head";
    if (name.includes("body") || name.includes("torso") || name.includes("chest") || name.includes("waist")) return "body";
  }
  return undefined;
}

function validateBoneAnimator(animation: BbAnimation, animationIndex: number, bone: BoneEntry, animator: BbAnimator | undefined): void {
  for (const [keyframeIndex, keyframe] of (animator?.keyframes ?? []).entries()) {
    if (!["position", "rotation", "scale"].includes(keyframe.channel)) {
      throw new ConversionError(
        "unsupported_geckolib_channel",
        `GeckoLib bone ${bone.group.name} uses unsupported channel ${keyframe.channel}.`,
        `animations[${animationIndex}].animators.${bone.uuid}.keyframes[${keyframeIndex}].channel`,
      );
    }
  }
}

function animatedWorldMatrix(
  bone: BoneEntry,
  animation: BbAnimation,
  boneAnimators: Map<string, BbAnimator>,
  time: number,
  cache: Map<string, Matrix4>,
  animationIndex: number,
): Matrix4 {
  const cached = cache.get(bone.uuid);
  if (cached) return cached;
  const animator = boneAnimators.get(bone.uuid);
  const position = evaluateChannel(animator?.keyframes ?? [], "position", time, [0, 0, 0], animationIndex, bone.uuid).map((value) => value / 16);
  const rotationDelta = evaluateChannel(animator?.keyframes ?? [], "rotation", time, [0, 0, 0], animationIndex, bone.uuid);
  const scale = evaluateChannel(animator?.keyframes ?? [], "scale", time, [1, 1, 1], animationIndex, bone.uuid);
  const parentOrigin = bone.parent?.group.origin ?? [0, 0, 0];
  const basePosition = bone.group.origin.map((value, index) => (value - parentOrigin[index]) / 16);
  const baseRotation = bone.group.rotation.map((value, index) => value + rotationDelta[index]);
  const local = composeTransform(basePosition.map((value, index) => value + position[index]), baseRotation, scale);
  const world = bone.parent
    ? animatedWorldMatrix(bone.parent, animation, boneAnimators, time, cache, animationIndex).clone().multiply(local)
    : new Matrix4().makeScale(PLAYER_RENDER_SCALE, PLAYER_RENDER_SCALE, PLAYER_RENDER_SCALE).multiply(local);
  cache.set(bone.uuid, world);
  return world;
}

function evaluateChannel(
  keyframes: BbKeyframe[],
  channel: string,
  time: number,
  fallback: number[],
  animationIndex: number,
  animatorId: string,
): number[] {
  const frames = keyframes.filter((frame) => frame.channel === channel).sort((first, second) => first.time - second.time);
  if (frames.length === 0) return [...fallback];
  const nextIndex = frames.findIndex((frame) => frame.time > time);
  if (nextIndex === 0) return [...fallback];
  if (nextIndex < 0) return keyframeVector(frames[frames.length - 1], animationIndex, animatorId);
  const previous = frames[nextIndex - 1];
  const next = frames[nextIndex];
  const previousValue = keyframeVector(previous, animationIndex, animatorId);
  const nextValue = keyframeVector(next, animationIndex, animatorId);
  const interpolation = next.interpolation ?? "linear";
  const easing = next.easing ?? "linear";
  if (interpolation === "step") return previousValue;
  if (interpolation !== "linear") {
    throw new ConversionError(
      "unsupported_geckolib_interpolation",
      `GeckoLib ${channel} keyframe uses unsupported ${interpolation} interpolation; only linear and step interpolation are supported.`,
      `animations[${animationIndex}].animators.${animatorId}`,
    );
  }
  const progress = (time - previous.time) / (next.time - previous.time);
  const easedProgress = cubeEasingProgress(easing, progress);
  if (easedProgress === undefined) {
    throw new ConversionError(
      "unsupported_geckolib_easing",
      `GeckoLib ${channel} keyframe uses unsupported easing ${easing}.`,
      `animations[${animationIndex}].animators.${animatorId}`,
    );
  }
  return previousValue.map((value, index) => value + (nextValue[index] - value) * easedProgress);
}

function keyframeVector(keyframe: BbKeyframe, animationIndex: number, animatorId: string): number[] {
  if (keyframe.data_points.length !== 1) {
    throw new ConversionError(
      "unsupported_geckolib_keyframe",
      "GeckoLib pre/post keyframes are not supported in the first adapter version.",
      `animations[${animationIndex}].animators.${animatorId}`,
    );
  }
  const point: BbDataPoint = keyframe.data_points[0];
  return [point.x, point.y, point.z].map((value, axis) => numericValue(value, `animations[${animationIndex}].animators.${animatorId}.${keyframe.channel}[${axis}]`));
}

function composeTransform(position: number[], rotation: number[], scale: number[]): Matrix4 {
  return new Matrix4().compose(
    new Vector3(position[0], position[1], position[2]),
    new Quaternion().setFromEuler(new Euler(
      MathUtils.degToRad(rotation[0]),
      MathUtils.degToRad(rotation[1]),
      MathUtils.degToRad(rotation[2]),
      "ZYX",
    )),
    new Vector3(scale[0], scale[1], scale[2]),
  );
}

function writeCubeResources(
  project: BbmodelProject,
  bone: BoneEntry,
  cube: BbCube,
  namespace: string,
  modelPath: string,
  resources: Map<string, Uint8Array>,
): void {
  const model = {
    textures: { layer0: `${namespace}:item/${modelPath.split("/").slice(0, -1).join("/")}/texture` },
    elements: [cubeModelElement(cube, bone.group.origin, project.resolution)],
  };
  resources.set(`assets/${namespace}/models/item/${modelPath}.json`, jsonBytes(model));
  resources.set(`assets/${namespace}/items/${modelPath}.json`, jsonBytes({ model: { type: "minecraft:model", model: `${namespace}:item/${modelPath}` } }));
}

function cubePlayerHeadMatrix(cube: BbCube, bone: BoneEntry): Matrix16 | undefined {
  const inflate = cube.inflate ?? 0;
  const from = cube.from.map((value, axis) => (value - bone.group.origin[axis] - inflate) / 16);
  const to = cube.to.map((value, axis) => (value - bone.group.origin[axis] + inflate) / 16);
  const size = to.map((value, axis) => value - from[axis]);
  if (size.some((value) => value <= 0)) return undefined;

  const center = from.map((value, axis) => (value + to[axis]) / 2);
  const fit = new Matrix4()
    .makeTranslation(center[0], center[1], center[2])
    .scale(new Vector3(size[0] * 2, size[1] * 2, size[2] * 2))
    .multiply(new Matrix4().makeTranslation(0, 0.25, 0));
  const rotation = cube.rotation ?? [0, 0, 0];
  if (rotation.every((value) => Math.abs(value) <= 1e-7)) {
    return matrix4ToRowMajor(fit, `GeckoLib cube ${cube.name ?? cube.uuid} player head conversion`);
  }

  const origin = (cube.origin ?? bone.group.origin).map((value, axis) => (value - bone.group.origin[axis]) / 16);
  const rotated = composeTransform(origin, rotation, [1, 1, 1])
    .multiply(new Matrix4().makeTranslation(-origin[0], -origin[1], -origin[2]))
    .multiply(fit);
  return matrix4ToRowMajor(rotated, `GeckoLib cube ${cube.name ?? cube.uuid} player head conversion`);
}

function cubeModelElement(cube: BbCube, boneOrigin: number[], resolution: { width: number; height: number }): Record<string, unknown> {
  const inflate = cube.inflate ?? 0;
  const offset = (value: number, axis: number, direction: -1 | 1) => value - boneOrigin[axis] + 8 + inflate * direction;
  const faces = Object.fromEntries(Object.entries(cube.faces).flatMap(([direction, face]) => {
    if (!SUPPORTED_FACES.has(direction) || face.enabled === false || face.texture === null || face.uv == null) return [];
    return [[direction, {
      uv: [
        face.uv[0] * 16 / resolution.width,
        face.uv[1] * 16 / resolution.height,
        face.uv[2] * 16 / resolution.width,
        face.uv[3] * 16 / resolution.height,
      ],
      texture: "#layer0",
      ...(face.rotation == null || face.rotation === 0 ? {} : { rotation: face.rotation }),
    }]];
  }));
  const rotation = cubeRotation(cube, boneOrigin);
  return {
    from: cube.from.map((value, axis) => offset(value, axis, -1)),
    to: cube.to.map((value, axis) => offset(value, axis, 1)),
    ...(rotation ? { rotation } : {}),
    faces,
  };
}

function cubeRotation(cube: BbCube, boneOrigin: number[]): Record<string, unknown> | undefined {
  const values = cube.rotation ?? [0, 0, 0];
  const axes = values.map((value, index) => ({ value, index })).filter(({ value }) => Math.abs(value) > 1e-7);
  if (axes.length === 0) return undefined;
  if (axes.length > 1) throw new ConversionError("unsupported_geckolib_cube_rotation", `Cube ${cube.name ?? cube.uuid} rotates around multiple axes.`, cube.uuid);
  const { value, index } = axes[0];
  if (![22.5, -22.5, 45, -45].includes(value)) {
    throw new ConversionError("unsupported_geckolib_cube_rotation", `Cube ${cube.name ?? cube.uuid} uses unsupported item-model rotation ${value}.`, cube.uuid);
  }
  const origin = cube.origin ?? boneOrigin;
  return {
    origin: origin.map((coordinate, axis) => coordinate - boneOrigin[axis] + 8),
    axis: ["x", "y", "z"][index],
    angle: value,
  };
}

function requireEmbeddedTexture(textures: BbTexture[]): BbTexture {
  if (textures.length !== 1) throw new Error(`GeckoLib bbmodel must contain exactly one texture; found ${textures.length}.`);
  const texture = textures[0];
  if (!texture.source?.startsWith("data:image/png;base64,")) {
    throw new ConversionError("geckolib_external_texture", "GeckoLib texture must be embedded in the bbmodel as PNG data.", "textures[0].source");
  }
  return texture;
}

function decodeTexture(texture: BbTexture): Uint8Array {
  const base64 = texture.source!.slice("data:image/png;base64,".length);
  try {
    return Uint8Array.from(atob(base64), (character) => character.charCodeAt(0));
  } catch (error) {
    throw new ConversionError("invalid_geckolib_texture", "GeckoLib embedded texture is not valid base64.", "textures[0].source", { cause: error });
  }
}

function numericValue(value: string | number, path: string): number {
  const number = typeof value === "number" ? value : Number(value.trim());
  if (!Number.isFinite(number)) throw new ConversionError("unsupported_geckolib_molang", `GeckoLib expression ${String(value)} is not a numeric constant.`, path);
  return number;
}

function validNamespace(value: string | undefined): string | undefined {
  if (!value) return undefined;
  return /^[a-z0-9_.-]+$/.test(value) ? value : undefined;
}

function jsonBytes(value: unknown): Uint8Array {
  return encoder.encode(`${JSON.stringify(value, null, 2)}\n`);
}
