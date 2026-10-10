import { Matrix4 } from "three";
import type { ImportedNode } from "../../domain/conversionSeed";
import { itemModelResourcePath, type GeneratedResource } from "../../domain/generatedResource";
import { composeDegreesTransform } from "../../format/matrix";
import { sanitizeNamespace, sanitizeResourcePath } from "../../format/resourceLocation";
import { serializeSnbtString } from "../../format/snbt";
import { ConversionError } from "../../foundation/diagnostics";
import type { BoneEntry } from "../common/blockbenchCubeModel";
import type { BbCube, BbTexture } from "../common/blockbenchCubeSchema";
import { cubePlayerHeadMatrix, isHiddenAccessoryBone, prepareCubeModels } from "../common/blockbenchCubeSkin";
import { referencedTextureIndexes, resolveFaceTextureIndex, textureFileStem, uniqueCubeNodeId, writeEmbeddedTextures } from "../common/blockbenchCubeResources";
import type { AjProject, AjProjectCube, AjProjectLocator, ProjectTransformGraph } from "./animatedJavaProjectSchema";

export interface AnimatedJavaCubes {
  namespace: string;
  projectPath: string;
  textures: BbTexture[];
  bones: BoneEntry[];
  nodes: Record<string, ImportedNode>;
  nodeIdsBySourceUuid: ReadonlyMap<string, readonly string[]>;
  resources: Map<string, GeneratedResource>;
}

export function importAnimatedJavaCubes(project: AjProject, sourceStem: string, graph: ProjectTransformGraph): AnimatedJavaCubes | undefined {
  const elements = project.elements.filter((element): element is AjProjectCube | (AjProjectLocator & { type: "locator" }) => element.type === "cube" || element.type === "locator");
  if (elements.length === 0 && graph.groups.size === 0) return undefined;
  const namespace = sanitizeNamespace(sourceStem, "animated_java");
  const projectPath = sanitizeResourcePath(project.name?.trim() || sourceStem, "model");
  const bones: BoneEntry[] = [];
  const bonesByUuid = new Map<string, BoneEntry>();
  const ids = new Set<string>();
  for (const [uuid, group] of graph.groups) {
    const base = sanitizeResourcePath(group.name, "bone").replaceAll("/", "_");
    let id = base;
    for (let suffix = 2; ids.has(id); suffix++) id = `${base}_${suffix}`;
    ids.add(id);
    const parentId = graph.groupParents.get(uuid);
    const bone: BoneEntry = { id, uuid, group, parent: parentId ? bonesByUuid.get(parentId) : undefined, cubes: [], locators: [], nodes: [] };
    bones.push(bone);
    bonesByUuid.set(uuid, bone);
  }
  const elementsByUuid = new Map(elements.map((element) => [element.uuid, element]));
  for (const [uuid, parentId] of graph.elementParents) {
    const element = elementsByUuid.get(uuid);
    if (!element) continue;
    const bone = parentId ? bonesByUuid.get(parentId) : undefined;
    if (!bone) throw new ConversionError("invalid_animated_java_cube", `Animated Java ${element.name} is not parented to a group.`, `elements.${element.uuid}`);
    if (element.type === "locator") bone.locators.push(element);
    else bone.cubes.push(element);
  }
  const textures: BbTexture[] = project.textures.map((texture) => ({ ...texture,
    frame_order_type: ["loop", "backwards", "back_and_forth", "custom"].includes(texture.frame_order_type ?? "") ? texture.frame_order_type as BbTexture["frame_order_type"] : undefined,
  }));
  const resources = new Map<string, GeneratedResource>();
  if (bones.some((bone) => bone.cubes.length > 0)) writeEmbeddedTextures(textures, namespace, projectPath, resources);
  const { playableCubesByBone, skinAssignments } = prepareCubeModels(bones);
  const nodes: Record<string, ImportedNode> = {};
  const nodeIdsBySourceUuid = new Map<string, string[]>();
  const bindNode = (source: string, id: string) => nodeIdsBySourceUuid.set(source, [...(nodeIdsBySourceUuid.get(source) ?? []), id]);
  for (const bone of bones) {
    const cubes = playableCubesByBone.get(bone.uuid) ?? [];
    if (cubes.length === 0) {
      nodes[bone.id] = { binding: { sourceNodeId: bone.id }, type: "anchor" };
      bone.nodes.push({ id: bone.id, localMatrix: new Matrix4() });
      bindNode(bone.uuid, bone.id);
    }
    for (const [index, cube] of cubes.entries()) {
      const id = index === 0 ? bone.id : uniqueCubeNodeId(bone, cube, index, ids);
      const inflate = cube.inflate ?? 0;
      const bounds = { from: cube.from.map((value, axis) => value - bone.group.origin[axis] - inflate), to: cube.to.map((value, axis) => value - bone.group.origin[axis] + inflate) };
      const hiddenAccessory = isHiddenAccessoryBone(bone);
      const conversion = hiddenAccessory ? undefined : cubePlayerHeadMatrix(cube, bone, bounds);
      if (!hiddenAccessory && !conversion) throw new ConversionError("invalid_animated_java_cube", `Cube ${cube.name ?? cube.uuid} cannot be fitted to a player head.`, cube.uuid);
      const rotation = cube.rotation ?? [0, 0, 0];
      const origin = (cube.origin ?? bone.group.origin).map((value, axis) => (value - bone.group.origin[axis]) / 16);
      const cubeMatrix = rotation.every((value) => Math.abs(value) <= 1e-7) ? new Matrix4()
        : composeDegreesTransform(origin, rotation, [1, 1, 1]).multiply(new Matrix4().makeTranslation(-origin[0], -origin[1], -origin[2]));
      bone.nodes.push({ id, localMatrix: cubeMatrix });
      const modelPath = `${projectPath}/${id}`;
      writeCubeResource(cube, bone, project.resolution, textures, namespace, modelPath, resources);
      const skin = hiddenAccessory ? undefined : skinAssignments.get(cube.uuid);
      nodes[id] = {
        binding: { sourceNodeId: id, ...(skin ? { skinGroupId: `${skin.part}_${skin.order}` } : {}) },
        type: "item_display",
        visible: true, itemDisplay: "none",
        itemStack: { id: "minecraft:paper", count: 1, components: [{ name: "minecraft:item_model", value: serializeSnbtString(`${namespace}:${modelPath}`) }] },
        ...(conversion ? { playerHeadConversionMatrix: conversion } : {}), ...(skin ? { suggestedSkin: skin } : {}),
      };
      bindNode(bone.uuid, id);
      bindNode(cube.uuid, id);
    }
    for (const [index, locator] of bone.locators.entries()) {
      const name = sanitizeResourcePath(locator.name.trim() || `locator_${index + 1}`, `locator_${index + 1}`).replaceAll("/", "_");
      const base = `${bone.id}_${name}`;
      let id = base;
      for (let suffix = 2; ids.has(id); suffix++) id = `${base}_${suffix}`;
      ids.add(id);
      const locatorMatrix = composeDegreesTransform(locator.position.map((value, axis) => (value - bone.group.origin[axis]) / 16), locator.rotation, [1, 1, 1]);
      bone.nodes.push({ id, localMatrix: locatorMatrix, ignoreInheritedScale: locator.ignore_inherited_scale, locatorName: locator.name });
      nodes[id] = { binding: { sourceNodeId: id }, type: "anchor" };
      bindNode(bone.uuid, id);
      bindNode(locator.uuid, id);
    }
  }
  return { namespace, projectPath, textures, bones, nodes, nodeIdsBySourceUuid, resources };
}

export function writeReferencedAnimatedJavaCubeResources(cubes: AnimatedJavaCubes, resolution: AjProject["resolution"], references: ReadonlySet<string>): void {
  const ids = new Set(cubes.bones.map((bone) => bone.id));
  for (const bone of cubes.bones) for (const [index, cube] of bone.cubes.entries()) {
    const id = index === 0 ? bone.id : uniqueCubeNodeId(bone, cube, index, ids);
    const modelPath = `${cubes.projectPath}/${id}`;
    const path = itemModelResourcePath(cubes.namespace, modelPath);
    if (references.has(path) && !cubes.resources.has(path)) writeCubeResource(cube, bone, resolution, cubes.textures, cubes.namespace, modelPath, cubes.resources);
  }
}

function writeCubeResource(cube: BbCube, bone: BoneEntry, resolution: AjProject["resolution"], textures: BbTexture[], namespace: string, modelPath: string, resources: Map<string, GeneratedResource>): void {
  const sourceTextures = textures.length ? textures : [{}];
  const used = referencedTextureIndexes(cube, sourceTextures);
  const modelTextures = textures.length ? Object.fromEntries([...used].map((index) => [`layer${index}`, `${namespace}:item/${modelPath.split("/").slice(0, -1).join("/")}/${textureFileStem(textures.length, index)}`])) : { layer0: "minecraft:block/white_concrete" };
  const inflate = cube.inflate ?? 0;
  const faces = Object.fromEntries(Object.entries(cube.faces).flatMap(([direction, face]) => {
    if (!["north", "south", "east", "west", "up", "down"].includes(direction) || face.enabled === false || face.texture === null || face.uv == null) return [];
    return [[direction, {
      uv: [face.uv[0] * 16 / resolution.width, face.uv[1] * 16 / resolution.height, face.uv[2] * 16 / resolution.width, face.uv[3] * 16 / resolution.height],
      texture: `#layer${resolveFaceTextureIndex(face.texture, sourceTextures)}`, ...(face.rotation == null || face.rotation === 0 ? {} : { rotation: face.rotation }),
    }]];
  }));
  resources.set(`assets/${namespace}/models/item/${modelPath}.json`, { kind: "cuboid_model", textures: modelTextures, elements: [{
    from: cube.from.map((value, axis) => value - bone.group.origin[axis] - inflate + 8),
    to: cube.to.map((value, axis) => value - bone.group.origin[axis] + inflate + 8), faces,
  }] });
  resources.set(itemModelResourcePath(namespace, modelPath), { kind: "item_model", model: `${namespace}:item/${modelPath}` });
}
