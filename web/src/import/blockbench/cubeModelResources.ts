import { sanitizeResourcePath } from "../../format/resourceLocation";
import { ConversionError } from "../../foundation/diagnostics";
import type { GeneratedResource } from "../../domain/generatedResource";
import type { BbCube, BbTexture, BbmodelProject } from "./cubeProjectSchema";
import type { BoneEntry } from "./cubeProjectImporter";
import type { CubeProjectTransformConvention } from "./cubeProjectTransformConvention";

const SUPPORTED_FACES = new Set(["north", "south", "east", "west", "up", "down"]);
const TEXTURELESS_MODEL_TEXTURE = "minecraft:block/white_concrete";

export function writeSourceCubeResources(
  project: BbmodelProject,
  bones: BoneEntry[],
  namespace: string,
  projectPath: string,
  resources: Map<string, GeneratedResource>,
  transforms: CubeProjectTransformConvention,
): void {
  if (bones.some((bone) => bone.cubes.length > 0)) {
    writeEmbeddedTextures(project.textures, namespace, projectPath, resources);
    const resourceNodeIds = new Set(bones.map((bone) => bone.id));
    for (const bone of bones) {
      for (const [cubeIndex, cube] of bone.cubes.entries()) {
        const nodeId = cubeIndex === 0 ? bone.id : uniqueCubeNodeId(bone, cube, cubeIndex, resourceNodeIds);
        writeCubeResources(project, bone, cube, namespace, `${projectPath}/${nodeId}`, resources, transforms);
      }
    }
  }
}

export function uniqueCubeNodeId(bone: BoneEntry, cube: BbCube, cubeIndex: number, ids: Set<string>): string {
  const cubeName = sanitizeResourcePath(cube.name?.trim() || `cube_${cubeIndex + 1}`, `cube_${cubeIndex + 1}`).replaceAll("/", "_");
  const base = `${bone.id}_${cubeName}`;
  let id = base;
  for (let suffix = 2; ids.has(id); suffix++) id = `${base}_${suffix}`;
  ids.add(id);
  return id;
}

export function writeCubeResources(
  project: BbmodelProject,
  bone: BoneEntry,
  cube: BbCube,
  namespace: string,
  modelPath: string,
  resources: Map<string, GeneratedResource>,
  transforms: CubeProjectTransformConvention,
): void {
  const sourceTextures = project.textures.length > 0 ? project.textures : [{}];
  const textures = project.textures.length > 0
    ? Object.fromEntries(project.textures.map((_, index) => [
        `layer${index}`,
        `${namespace}:item/${modelPath.split("/").slice(0, -1).join("/")}/${textureFileStem(project.textures.length, index)}`,
      ]))
    : { layer0: TEXTURELESS_MODEL_TEXTURE };
  const model = {
    kind: "cuboid_model" as const,
    textures,
    elements: [cubeModelElement(cube, bone.group.origin, project.resolution, sourceTextures, transforms)],
  };
  resources.set(`assets/${namespace}/models/item/${modelPath}.json`, model);
  resources.set(`assets/${namespace}/items/${modelPath}.json`, { kind: "item_model", model: `${namespace}:item/${modelPath}` });
}

function cubeModelElement(cube: BbCube, boneOrigin: number[], resolution: { width: number; height: number }, textures: BbTexture[], transforms: CubeProjectTransformConvention): Record<string, unknown> {
  const inflate = cube.inflate ?? 0;
  const sourceFrom = cube.from.map((value, axis) => value - boneOrigin[axis] - inflate);
  const sourceTo = cube.to.map((value, axis) => value - boneOrigin[axis] + inflate);
  const canonical = transforms.bounds(sourceFrom, sourceTo);
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
    from: canonical.from.map((value) => value + 8),
    to: canonical.to.map((value) => value + 8),
    faces,
  };
}

function writeEmbeddedTextures(textures: BbTexture[], namespace: string, projectPath: string, resources: Map<string, GeneratedResource>): void {
  if (textures.length === 0) return;
  for (const [index, texture] of textures.entries()) {
    if (!texture.source?.startsWith("data:image/png;base64,")) {
      throw new ConversionError("geckolib_external_texture", "GeckoLib textures must be embedded in the bbmodel as PNG data.", `textures[${index}].source`);
    }
    const texturePath = `assets/${namespace}/textures/item/${projectPath}/${textureFileStem(textures.length, index)}.png`;
    const textureBytes = decodeTexture(texture, index);
    resources.set(texturePath, textureBytes);
    const textureMetadata = animatedTextureMetadata(texture, textureBytes);
    if (textureMetadata) resources.set(`${texturePath}.mcmeta`, { kind: "json", value: textureMetadata });
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
