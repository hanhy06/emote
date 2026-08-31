import { Matrix4, Vector3 } from "three";
import type { Matrix16 } from "../../format/emoteAnimation";
import { matrix4ToRowMajor } from "../../format/matrix";
import { sanitizeResourcePath } from "../../format/resourceLocation";
import { ConversionError } from "../../foundation/diagnostics";
import type { ImportedSkinPart } from "../../domain/conversionSeed";
import type { BbCube, BbTexture, BbmodelProject } from "./cubeProjectSchema";
import type { BoneEntry } from "./cubeProjectImporter";
import { humanoidJointFillMatrix, humanoidRenderPieces, humanoidSkinPartHeight, inferHumanoidPart, isStandardHumanoidPartSize, sliceVerticalUv, type HumanoidPart } from "../humanoid/humanoidPlayerRig";

const encoder = new TextEncoder();
const SUPPORTED_FACES = new Set(["north", "south", "east", "west", "up", "down"]);
const HIDDEN_ACCESSORY_BONES = new Set(["leftitem", "rightitem", "cape"]);
const SPLIT_SKIN_CUBE_PATTERN = /_skin_(upper|lower|(\d+)|joint_(upper|lower)_(\d+))$/;
const TEXTURELESS_MODEL_TEXTURE = "minecraft:block/white_concrete";

interface SplitSkinCube {
  order: number;
  jointSide?: "upper" | "lower";
}

interface SkinCubeSource {
  bone: BoneEntry;
  cube: BbCube;
  part: HumanoidPart;
}

export interface PreparedCubeModels {
  playableCubesByBone: ReadonlyMap<string, BbCube[]>;
  skinAssignments: ReadonlyMap<string, ImportedSkinPart>;
}

export function prepareCubeModels(
  project: BbmodelProject,
  bones: BoneEntry[],
  namespace: string,
  projectPath: string,
  resources: Map<string, Uint8Array>,
): PreparedCubeModels {
  if (bones.some((bone) => bone.cubes.length > 0)) {
    writeEmbeddedTextures(project.textures, namespace, projectPath, resources);
    const resourceNodeIds = new Set(bones.map((bone) => bone.id));
    for (const bone of bones) {
      for (const [cubeIndex, cube] of bone.cubes.entries()) {
        const nodeId = cubeIndex === 0 ? bone.id : uniqueCubeNodeId(bone, cube, cubeIndex, resourceNodeIds);
        writeCubeResources(project, bone, cube, namespace, `${projectPath}/${nodeId}`, resources);
      }
    }
  }
  const playableCubesByBone = splitPlayerSkinCubesByPoseBone(bones);
  return { playableCubesByBone, skinAssignments: inferSkinAssignments(bones, playableCubesByBone) };
}

export function uniqueCubeNodeId(bone: BoneEntry, cube: BbCube, cubeIndex: number, ids: Set<string>): string {
  const cubeName = sanitizeResourcePath(cube.name?.trim() || `cube_${cubeIndex + 1}`, `cube_${cubeIndex + 1}`).replaceAll("/", "_");
  const base = `${bone.id}_${cubeName}`;
  let id = base;
  for (let suffix = 2; ids.has(id); suffix++) id = `${base}_${suffix}`;
  ids.add(id);
  return id;
}

export function normalizeBlockbenchName(name: string | undefined): string | undefined {
  const normalized = name?.toLowerCase().replaceAll(/[^a-z0-9]/g, "");
  return normalized || undefined;
}

export function isHiddenAccessoryName(name: string | undefined): boolean {
  const normalized = normalizeBlockbenchName(name);
  return normalized !== undefined && HIDDEN_ACCESSORY_BONES.has(normalized);
}

export function isHiddenAccessoryBone(bone: BoneEntry): boolean {
  for (let current: BoneEntry | undefined = bone; current; current = current.parent) {
    if (isHiddenAccessoryName(current.group.name)) return true;
  }
  return false;
}

export function writeCubeResources(
  project: BbmodelProject,
  bone: BoneEntry,
  cube: BbCube,
  namespace: string,
  modelPath: string,
  resources: Map<string, Uint8Array>,
): void {
  const sourceTextures = project.textures.length > 0 ? project.textures : [{}];
  const textures = project.textures.length > 0
    ? Object.fromEntries(project.textures.map((_, index) => [
        `layer${index}`,
        `${namespace}:item/${modelPath.split("/").slice(0, -1).join("/")}/${textureFileStem(project.textures.length, index)}`,
      ]))
    : { layer0: TEXTURELESS_MODEL_TEXTURE };
  const model = {
    textures,
    elements: [cubeModelElement(cube, bone.group.origin, project.resolution, sourceTextures)],
  };
  resources.set(`assets/${namespace}/models/item/${modelPath}.json`, jsonBytes(model));
  resources.set(`assets/${namespace}/items/${modelPath}.json`, jsonBytes({ model: { type: "minecraft:model", model: `${namespace}:item/${modelPath}` } }));
}

export function cubePlayerHeadMatrix(cube: BbCube, bone: BoneEntry): Matrix16 | undefined {
  const inflate = cube.inflate ?? 0;
  const from = cube.from.map((value, axis) => (value - bone.group.origin[axis] - inflate) / 16);
  const to = cube.to.map((value, axis) => (value - bone.group.origin[axis] + inflate) / 16);
  const size = to.map((value, axis) => value - from[axis]);
  if (size.some((value) => value < 0)) return undefined;

  const center = from.map((value, axis) => (value + to[axis]) / 2);
  const fit = new Matrix4()
    .makeTranslation(center[0], center[1], center[2])
    .scale(new Vector3(size[0] * 2, size[1] * 2, size[2] * 2))
    .multiply(new Matrix4().makeTranslation(0, 0.25, 0));
  const jointSide = parseSplitSkinCube(cube.uuid)?.jointSide;
  const part = jointSide ? inferSkinPart(bone) : undefined;
  const conversion = jointSide && part ? humanoidJointFillMatrix(fit, part, jointSide) : fit;
  return matrix4ToRowMajor(conversion, `GeckoLib cube ${cube.name ?? cube.uuid} player head conversion`);
}

function removeDuplicateSkinLayers(cubes: BbCube[]): BbCube[] {
  return cubes.filter((cube, cubeIndex) => !cubes.some((other, otherIndex) => {
    if (cubeIndex === otherIndex) return false;
    if (sameCubeBounds(cube, other)) return isSkinLayerCube(cube) || (cube.inflate ?? 0) > (other.inflate ?? 0);
    return isSkinLayerCube(cube) && !isSkinLayerCube(other) && nearbySkinLayerBounds(cube, other);
  }));
}

function splitPlayerSkinCubesByPoseBone(bones: BoneEntry[]): Map<string, BbCube[]> {
  const result = new Map(bones.map((bone) => [bone.uuid, [] as BbCube[]]));
  const cubesByBone = new Map(bones.map((bone) => [bone.uuid, removeDuplicateSkinLayers(bone.cubes)]));
  const consumed = splitPresegmentedSkinCubes(bones, cubesByBone, result);
  for (const bone of bones) {
    const part = inferSkinPart(bone);
    for (const cube of cubesByBone.get(bone.uuid) ?? []) {
      if (consumed.has(cube.uuid)) continue;
      if (!part || !isStandardPlayerSkinCube(cube, part)) {
        result.get(bone.uuid)!.push(cube);
        continue;
      }
      const lowerBone = findLowerJointBone(bone, cube, part, bones);
      const skinHeight = humanoidSkinPartHeight(part);
      const pieces = humanoidRenderPieces(part, lowerBone !== undefined);
      for (const piece of pieces) {
        const height = cube.to[1] - cube.from[1];
        const suffix = pieces.length === 2
          ? (piece.order === 0 ? "upper" : "lower")
          : piece.kind === "joint_fill" ? `joint_${piece.jointSide}_${piece.order}` : `${piece.order}`;
        const sliced: BbCube = {
          ...cube,
          uuid: `${cube.uuid}_skin_${suffix}`,
          name: `${cube.name ?? "Cube"} ${suffix === "upper" ? "Upper" : suffix === "lower" ? "Lower" : `Skin ${suffix}`}`,
          from: [cube.from[0], cube.to[1] - height * piece.endY / skinHeight, cube.from[2]],
          to: [cube.to[0], cube.to[1] - height * piece.startY / skinHeight, cube.to[2]],
          faces: sliceVerticalFaceUvs(cube.faces, piece.startY / skinHeight, piece.endY / skinHeight),
        };
        result.get(piece.motion === "lower" && lowerBone ? lowerBone.uuid : bone.uuid)!.push(sliced);
      }
    }
  }
  return result;
}

function splitPresegmentedSkinCubes(
  bones: BoneEntry[],
  cubesByBone: ReadonlyMap<string, BbCube[]>,
  result: Map<string, BbCube[]>,
): Set<string> {
  const consumed = new Set<string>();
  const sources: SkinCubeSource[] = [];
  for (const bone of bones) {
    const part = inferSkinPart(bone);
    if (!part || part === "head") continue;
    for (const cube of cubesByBone.get(bone.uuid) ?? []) {
      const height = cube.to[1] - cube.from[1];
      if (height > 0 && height < humanoidSkinPartHeight(part)) sources.push({ bone, cube, part });
    }
  }

  const pending = new Set(sources);
  while (pending.size > 0) {
    const first = pending.values().next().value as SkinCubeSource;
    const component: SkinCubeSource[] = [];
    const queue = [first];
    pending.delete(first);
    while (queue.length > 0) {
      const source = queue.pop()!;
      component.push(source);
      for (const candidate of pending) {
        if (!samePresegmentedColumn(source, candidate)) continue;
        pending.delete(candidate);
        queue.push(candidate);
      }
    }

    if (!isCompleteSkinColumn(component)) continue;
    const part = component[0].part;
    const top = Math.max(...component.map((source) => source.cube.to[1]));
    for (const piece of humanoidRenderPieces(part, component.some((source) => source.bone !== component[0].bone))) {
      const pieceTop = top - piece.startY;
      const pieceBottom = top - piece.endY;
      const source = component.find((candidate) => candidate.cube.from[1] <= pieceBottom + 1e-3 && candidate.cube.to[1] >= pieceTop - 1e-3);
      if (!source) continue;
      const height = source.cube.to[1] - source.cube.from[1];
      const startRatio = (source.cube.to[1] - pieceTop) / height;
      const endRatio = (source.cube.to[1] - pieceBottom) / height;
      const suffix = piece.kind === "joint_fill" ? `joint_${piece.jointSide}_${piece.order}` : `${piece.order}`;
      result.get(source.bone.uuid)!.push({
        ...source.cube,
        uuid: `${source.cube.uuid}_skin_${suffix}`,
        name: `${source.cube.name ?? "Cube"} Skin ${suffix}`,
        from: [source.cube.from[0], pieceBottom, source.cube.from[2]],
        to: [source.cube.to[0], pieceTop, source.cube.to[2]],
        faces: sliceVerticalFaceUvs(source.cube.faces, startRatio, endRatio),
      });
    }
    component.forEach((source) => consumed.add(source.cube.uuid));
  }
  return consumed;
}

function samePresegmentedColumn(first: SkinCubeSource, second: SkinCubeSource): boolean {
  if (first.part !== second.part || (!isRelatedBone(first.bone, second.bone) && first.bone !== second.bone)) return false;
  if (![0, 2].every((axis) => Math.abs(first.cube.from[axis] - second.cube.from[axis]) <= 1e-3
    && Math.abs(first.cube.to[axis] - second.cube.to[axis]) <= 1e-3)) return false;
  return Math.abs(first.cube.to[1] - second.cube.from[1]) <= 1e-3 || Math.abs(second.cube.to[1] - first.cube.from[1]) <= 1e-3;
}

function isCompleteSkinColumn(component: SkinCubeSource[]): boolean {
  if (component.length < 2 || component.some((source) => source.part !== component[0].part)) return false;
  const fromY = Math.min(...component.map((source) => source.cube.from[1]));
  const toY = Math.max(...component.map((source) => source.cube.to[1]));
  const first = component[0].cube;
  const width = first.to[0] - first.from[0];
  const depth = first.to[2] - first.from[2];
  const coveredHeight = component.reduce((sum, source) => sum + source.cube.to[1] - source.cube.from[1], 0);
  return Math.abs(coveredHeight - (toY - fromY)) <= 1e-3
    && isStandardHumanoidPartSize(component[0].part, [width, toY - fromY, depth]);
}

function sliceVerticalFaceUvs(faces: BbCube["faces"], startRatio: number, endRatio: number): BbCube["faces"] {
  const slicedFaces: BbCube["faces"] = {};
  for (const [direction, face] of Object.entries(faces)) {
    if (!["north", "south", "east", "west"].includes(direction) || face.uv?.length !== 4) {
      slicedFaces[direction] = face;
      continue;
    }

    slicedFaces[direction] = { ...face, uv: sliceVerticalUv(face.uv, face.rotation ?? 0, startRatio, endRatio, "low") };
  }
  return slicedFaces;
}

function findLowerJointBone(bone: BoneEntry, cube: BbCube, part: HumanoidPart, bones: BoneEntry[]): BoneEntry | undefined {
  if (part === "head") return undefined;
  const names = part === "body"
    ? ["lowerbody", "abdomen"]
    : part.endsWith("arm") ? ["forearm", "lowerarm", "elbow"] : ["lowerleg", "shin", "knee"];
  const expectedY = (cube.from[1] + cube.to[1]) / 2;
  const candidates = bones.filter((candidate) => candidate !== bone
    && isDescendantOf(candidate, bone)
    && names.some((name) => normalizeBlockbenchName(candidate.group.name)?.includes(name))
    && Math.abs(candidate.group.origin[1] - expectedY) <= 2);
  return candidates.length === 1 ? candidates[0] : undefined;
}

function isDescendantOf(candidate: BoneEntry, ancestor: BoneEntry): boolean {
  for (let current = candidate.parent; current; current = current.parent) {
    if (current === ancestor) return true;
  }
  return false;
}

function isRelatedBone(first: BoneEntry, second: BoneEntry): boolean {
  return isDescendantOf(first, second) || isDescendantOf(second, first);
}

function sameCubeBounds(first: BbCube, second: BbCube): boolean {
  return first.from.every((value, axis) => Math.abs(value - second.from[axis]) <= 1e-7)
    && first.to.every((value, axis) => Math.abs(value - second.to[axis]) <= 1e-7);
}

function isSkinLayerCube(cube: BbCube): boolean {
  const name = normalizeBlockbenchName(cube.name) ?? "";
  return (cube.inflate ?? 0) > 0 || ["layer", "overlay", "sleeve", "pants", "jacket", "headwear", "hat"].some((marker) => name.includes(marker));
}

function nearbySkinLayerBounds(layer: BbCube, base: BbCube): boolean {
  return [0, 1, 2].every((axis) => {
    const layerCenter = (layer.from[axis] + layer.to[axis]) / 2;
    const baseCenter = (base.from[axis] + base.to[axis]) / 2;
    const layerSize = layer.to[axis] - layer.from[axis] + (layer.inflate ?? 0) * 2;
    const baseSize = base.to[axis] - base.from[axis] + (base.inflate ?? 0) * 2;
    return Math.abs(layerCenter - baseCenter) <= 0.25 && Math.abs(layerSize - baseSize) <= 0.5;
  });
}

function inferSkinAssignments(
  bones: BoneEntry[],
  playableCubesByBone: ReadonlyMap<string, BbCube[]>,
): Map<string, ImportedSkinPart> {
  const candidates: { cube: BbCube; part: ImportedSkinPart["part"]; centerY: number; sourceOrder: number; explicitOrder?: number }[] = [];
  let sourceOrder = 0;
  for (const bone of bones) {
    const part = inferSkinPart(bone);
    for (const cube of playableCubesByBone.get(bone.uuid) ?? []) {
      const split = parseSplitSkinCube(cube.uuid);
      if (!part || (split === null && !isStandardPlayerSkinCube(cube, part))) continue;
      candidates.push({
        cube,
        part,
        centerY: (cube.from[1] + cube.to[1]) / 2,
        sourceOrder: sourceOrder++,
        explicitOrder: split?.order,
      });
    }
  }

  const result = new Map<string, ImportedSkinPart>();
  for (const part of ["head", "body", "left_arm", "right_arm", "left_leg", "right_leg"] as const) {
    candidates
      .filter((candidate) => candidate.part === part)
      .sort((first, second) => second.centerY - first.centerY || first.sourceOrder - second.sourceOrder)
      .forEach((candidate, order) => result.set(candidate.cube.uuid, { part, order: candidate.explicitOrder ?? order }));
  }
  return result;
}

function parseSplitSkinCube(uuid: string): SplitSkinCube | null {
  const match = uuid.match(SPLIT_SKIN_CUBE_PATTERN);
  if (!match) return null;
  if (match[1] === "upper") return { order: 0 };
  if (match[1] === "lower") return { order: 1 };
  if (match[2] !== undefined) return { order: Number(match[2]) };
  return { order: Number(match[4]), jointSide: match[3] as "upper" | "lower" };
}

function isStandardPlayerSkinCube(cube: BbCube, part: ImportedSkinPart["part"]): boolean {
  const size = cube.to.map((value, axis) => Math.abs(value - cube.from[axis]));
  return isStandardHumanoidPartSize(part, size);
}

function inferSkinPart(bone: BoneEntry): ImportedSkinPart["part"] | undefined {
  for (let current: BoneEntry | undefined = bone; current; current = current.parent) {
    const part = inferHumanoidPart(current.group.name);
    if (part) return part;
  }
  return undefined;
}

function cubeModelElement(cube: BbCube, boneOrigin: number[], resolution: { width: number; height: number }, textures: BbTexture[]): Record<string, unknown> {
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
      texture: `#layer${resolveFaceTextureIndex(face.texture, textures)}`,
      ...(face.rotation == null || face.rotation === 0 ? {} : { rotation: face.rotation }),
    }]];
  }));
  return {
    from: cube.from.map((value, axis) => offset(value, axis, -1)),
    to: cube.to.map((value, axis) => offset(value, axis, 1)),
    faces,
  };
}

function writeEmbeddedTextures(textures: BbTexture[], namespace: string, projectPath: string, resources: Map<string, Uint8Array>): void {
  if (textures.length === 0) return;
  for (const [index, texture] of textures.entries()) {
    if (!texture.source?.startsWith("data:image/png;base64,")) {
      throw new ConversionError("geckolib_external_texture", "GeckoLib textures must be embedded in the bbmodel as PNG data.", `textures[${index}].source`);
    }
    const texturePath = `assets/${namespace}/textures/item/${projectPath}/${textureFileStem(textures.length, index)}.png`;
    const textureBytes = decodeTexture(texture, index);
    resources.set(texturePath, textureBytes);
    const textureMetadata = animatedTextureMetadata(texture, textureBytes);
    if (textureMetadata) resources.set(`${texturePath}.mcmeta`, jsonBytes(textureMetadata));
  }
}

function textureFileStem(textureCount: number, index: number): string {
  return textureCount === 1 ? "texture" : `texture_${index}`;
}

function resolveFaceTextureIndex(reference: number | string | null | undefined, textures: BbTexture[]): number {
  if (reference === undefined && textures.length === 1) return 0;
  const numeric = typeof reference === "number" ? reference : typeof reference === "string" && /^#?\d+$/.test(reference) ? Number(reference.replace(/^#/, "")) : undefined;
  const index = numeric ?? textures.findIndex((texture) => reference === texture.uuid || reference === texture.id || reference === texture.name);
  if (!Number.isInteger(index) || index < 0 || index >= textures.length) {
    throw new ConversionError("invalid_geckolib_face_texture", `Cube face references unknown texture ${String(reference)}.`, "elements.faces.texture");
  }
  return index;
}

function decodeTexture(texture: BbTexture, index: number): Uint8Array {
  const base64 = texture.source!.slice("data:image/png;base64,".length);
  try {
    return Uint8Array.from(atob(base64), (character) => character.charCodeAt(0));
  } catch (error) {
    throw new ConversionError("invalid_geckolib_texture", "GeckoLib embedded texture is not valid base64.", `textures[${index}].source`, { cause: error });
  }
}

function animatedTextureMetadata(texture: BbTexture, bytes: Uint8Array): Record<string, unknown> | undefined {
  const configured = texture.frame_time !== undefined
    || texture.frame_interpolate !== undefined
    || texture.frame_order_type !== undefined
    || texture.frame_order !== undefined;
  if (!configured) return undefined;
  const frameTime = texture.frame_time ?? 1;
  if (!Number.isInteger(frameTime) || frameTime < 1) throw new ConversionError("invalid_geckolib_texture_animation", "GeckoLib texture frame time must be a positive integer.");
  const orderType = texture.frame_order_type ?? "loop";
  const frames = textureFrames(orderType, texture.frame_order, pngFrameCount(bytes));
  return {
    animation: {
      frametime: frameTime,
      ...(texture.frame_interpolate ? { interpolate: true } : {}),
      ...(frames ? { frames } : {}),
    },
  };
}

function textureFrames(orderType: NonNullable<BbTexture["frame_order_type"]>, frameOrder: string | undefined, frameCount: number | undefined): number[] | undefined {
  if (orderType === "loop") return undefined;
  if (orderType === "custom") {
    const frames = (frameOrder ?? "").trim().split(/\s+/).filter(Boolean).map(Number);
    if (frames.length === 0 || frames.some((frame) => !Number.isInteger(frame) || frame < 0)) {
      throw new ConversionError("invalid_geckolib_texture_animation", "GeckoLib custom texture frame order must contain non-negative frame numbers.");
    }
    return frames;
  }
  if (frameCount === undefined || frameCount < 2) {
    throw new ConversionError("invalid_geckolib_texture_animation", `GeckoLib ${orderType} texture animation requires a vertical PNG sprite sheet.`);
  }
  const forward = Array.from({ length: frameCount }, (_, index) => index);
  if (orderType === "backwards") return forward.reverse();
  return [...forward, ...forward.slice(1, -1).reverse()];
}

function pngFrameCount(bytes: Uint8Array): number | undefined {
  if (bytes.length < 24 || bytes[0] !== 0x89 || bytes[1] !== 0x50 || bytes[2] !== 0x4e || bytes[3] !== 0x47) return undefined;
  const width = readUint32(bytes, 16);
  const height = readUint32(bytes, 20);
  return width > 0 && height >= width && height % width === 0 ? height / width : undefined;
}

function readUint32(bytes: Uint8Array, offset: number): number {
  return ((bytes[offset] << 24) | (bytes[offset + 1] << 16) | (bytes[offset + 2] << 8) | bytes[offset + 3]) >>> 0;
}

function jsonBytes(value: unknown): Uint8Array {
  return encoder.encode(`${JSON.stringify(value, null, 2)}\n`);
}
