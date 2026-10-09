import { itemModelResourcePath, type GeneratedResource } from "../../domain/generatedResource";
import type { BbCube, BbTexture, BlockbenchCubeProject } from "../common/blockbenchCubeSchema";
import type { BoneEntry } from "../common/blockbenchCubeModel";
import { referencedTextureIndexes, textureFileStem, resolveFaceTextureIndex } from "../common/blockbenchCubeResources";

const SUPPORTED_FACES = new Set(["north", "south", "east", "west", "up", "down"]);
const TEXTURELESS_MODEL_TEXTURE = "minecraft:block/white_concrete";

export function writeCubeResources(
  project: BlockbenchCubeProject,
  bone: BoneEntry,
  cube: BbCube,
  namespace: string,
  modelPath: string,
  resources: Map<string, GeneratedResource>,
): void {
  const sourceTextures = project.textures.length > 0 ? project.textures : [{}];
  const usedTextureIndexes = referencedTextureIndexes(cube, sourceTextures);
  const textures = project.textures.length > 0
    ? Object.fromEntries([...usedTextureIndexes].map((index) => [
        `layer${index}`,
        `${namespace}:item/${modelPath.split("/").slice(0, -1).join("/")}/${textureFileStem(project.textures.length, index)}`,
      ]))
    : { layer0: TEXTURELESS_MODEL_TEXTURE };
  const model = {
    kind: "cuboid_model" as const,
    textures,
    elements: [cubeModelElement(cube, bone.group.origin, project.resolution, sourceTextures)],
  };
  resources.set(`assets/${namespace}/models/item/${modelPath}.json`, model);
  resources.set(itemModelResourcePath(namespace, modelPath), { kind: "item_model", model: `${namespace}:item/${modelPath}` });
}

function cubeModelElement(cube: BbCube, boneOrigin: number[], resolution: { width: number; height: number }, textures: BbTexture[]): Record<string, unknown> {
  const inflate = cube.inflate ?? 0;
  const sourceFrom = cube.from.map((value, axis) => value - boneOrigin[axis] - inflate);
  const sourceTo = cube.to.map((value, axis) => value - boneOrigin[axis] + inflate);
  const canonical = {
    from: [sourceFrom[0], sourceFrom[1], sourceFrom[2]],
    to: [sourceTo[0], sourceTo[1], sourceTo[2]],
  };
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
