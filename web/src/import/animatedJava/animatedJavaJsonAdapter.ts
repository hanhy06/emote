import { Euler, MathUtils, Matrix4, Quaternion, Vector3 } from "three";
import { createDefaultPlayerBehavior, type Matrix16 } from "../../format/emoteAnimation";
import { IDENTITY_MATRIX, matrix4ToRowMajor } from "../../format/matrix";
import { parseResourceLocation, sanitizeResourcePath, type ResourceLocation } from "../../format/resourceLocation";
import { isRecord } from "../../format/runtimeValue";
import { serializeSnbtCompound, serializeSnbtString } from "../../format/snbt";
import { requireAnimationDurationTicks, secondsToTicks } from "../../format/time";
import type { ImportAdapter, ImportInput, ProbeResult } from "../adapter";
import { parseInputJson } from "../inputCache";
import type { ImportedAnimation, ImportedNode, ImportedProject, ImportedSkinPart, ImportedTransformKeyframe } from "../types";
import { ConversionError } from "../../foundation/diagnostics";
import { bakeAjNodeChannels, evaluateAjMolang, requiresAjBaking, type AjTransformValues } from "./animatedJavaAnimationBaker";
import { requireAjBlueprint, type AjAnimation, type AjBlueprint, type AjElement, type AjKeyframe, type AjNode, type AjNodeChannels } from "./animatedJavaSchema";
import { isAnimatedJavaProject, requireAnimatedJavaProject } from "./animatedJavaProjectSchema";
import { blockArgumentToSnbt, importAnimatedJavaProject, itemArgumentToSnbt } from "./animatedJavaProjectImporter";

const encoder = new TextEncoder();

interface ImportedAjNodes {
  nodes: Record<string, ImportedNode>;
  nodeIdsBySource: Map<string, string[]>;
}

interface AjSkinCandidate {
  nodeId: string;
  part: ImportedSkinPart["part"];
  centerY: number;
  sourceOrder: number;
}

interface IndexedAjKeyframes {
  time: number;
  position?: AjKeyframe;
  rotation?: AjKeyframe;
  scale?: AjKeyframe;
}

export const animatedJavaJsonAdapter: ImportAdapter = {
  id: "animated_java_json",
  label: "Animated Java project",
  extensions: ["ajblueprint", "json"],

  probe(input: ImportInput): ProbeResult {
    try {
      const parsed = parseInputJson(input);
      if (isAnimatedJavaProject(parsed)) {
        return { confidence: 100, reason: "matches an Animated Java blueprint project" };
      }
      const value = parsed as Partial<AjBlueprint>;
      return value.format_version === 1 && typeof value.settings?.id === "string" && isRecord(value.nodes) && isRecord(value.animations)
        ? { confidence: 100, reason: "matches Animated Java plugin blueprint format 1" }
        : { confidence: 0, reason: "does not match Animated Java plugin blueprint format 1" };
    } catch {
      return { confidence: 0, reason: "not JSON" };
    }
  },

  async import(input: ImportInput): Promise<ImportedProject> {
    const parsed = parseInputJson(input);
    if (isAnimatedJavaProject(parsed)) return importAnimatedJavaProject(input, requireAnimatedJavaProject(parsed));
    const blueprint = requireAjBlueprint(parsed);
    validateRoot(blueprint);
    const resource = parseResourceLocation(blueprint.settings.id, "Animated Java settings.id");
    const resources = new Map<string, Uint8Array>();
    const { nodes, nodeIdsBySource } = importNodes(blueprint, resource, resources);
    const animations = Object.entries(blueprint.animations ?? {}).map(([id, animation]) => importAnimation(id, animation, nodes, nodeIdsBySource));
    if (Object.keys(nodes).length === 0) throw new Error("Animated Java blueprint does not contain nodes.");
    if (animations.length === 0) throw new Error("Animated Java blueprint does not contain animations.");
    const name = prettify(resource.path.split("/").at(-1) ?? resource.path);
    return {
      source: "animated_java_json",
      sourceName: input.name,
      suggestedMetadata: { name, description: `${name} emote.` },
      suggestedPlayer: createDefaultPlayerBehavior(),
      nodes,
      animations,
      diagnostics: [],
      resources,
      ...(resources.size ? { resourceMinecraftVersion: "26.2" } : {}),
    };
  },
};

function validateRoot(blueprint: AjBlueprint): void {
  if (blueprint.format_version !== 1) throw new Error(`Unsupported Animated Java plugin blueprint version: ${blueprint.format_version}`);
  parseResourceLocation(blueprint.settings?.id, "Animated Java settings.id");
}

function importNodes(
  blueprint: AjBlueprint,
  resource: ResourceLocation,
  resources: Map<string, Uint8Array>,
): ImportedAjNodes {
  const nodes: Record<string, ImportedNode> = {};
  const nodeIdsBySource = new Map<string, string[]>();
  const skinCandidates: AjSkinCandidate[] = [];
  const usedIds = new Set(Object.keys(blueprint.nodes ?? {}));
  let sourceOrder = 0;
  for (const [id, node] of Object.entries(blueprint.nodes ?? {})) {
    if (node.type !== "bone") {
      nodes[id] = importDisplayNode(id, node);
      nodeIdsBySource.set(id, [id]);
      continue;
    }

    const defaultMatrix = readDefaultMatrix(node, id);
    const entityNbt = displayPropertiesToNbt(node.display_properties);
    const part = inferAjSkinPart(id);
    const elements = (node.elements ?? []).flatMap((element) => splitTallAjSkinElement(element, part));
    if (elements.length === 0) {
      nodes[id] = { id, type: "anchor", defaultMatrix };
      nodeIdsBySource.set(id, [id]);
      continue;
    }

    const generatedIds: string[] = [];
    for (const [elementIndex, element] of elements.entries()) {
      const nodeId = elementIndex === 0 ? id : uniqueAjElementNodeId(id, elementIndex, usedIds);
      const modelPath = [resource.path, nodeId].filter(Boolean).join("/");
      writeBoneResources(modelPath, id, [element], resource, blueprint, resources);
      const conversionMatrix = ajElementPlayerHeadMatrix(element, `${id}.elements[${elementIndex}]`);
      nodes[nodeId] = {
        id: nodeId,
        type: "item_display",
        defaultMatrix,
        visible: true,
        ...(entityNbt ? { entityNbt } : {}),
        itemDisplay: "none",
        itemStackSnbt: serializeSnbtCompound([
          ["id", serializeSnbtString("minecraft:paper")],
          ["count", "1"],
          ["components", serializeSnbtCompound([
            ["minecraft:item_model", serializeSnbtString(`${resource.namespace}:${modelPath}`)],
          ])],
        ]),
        ...(conversionMatrix ? { playerHeadConversion: { matrix: conversionMatrix } } : {}),
      };
      generatedIds.push(nodeId);
      if (conversionMatrix && part && isAjSkinSegment(element, part)) {
        skinCandidates.push({
          nodeId,
          part,
          centerY: (element.from[1] + element.to[1]) / 2,
          sourceOrder: sourceOrder++,
        });
      }
    }
    nodeIdsBySource.set(id, generatedIds);
  }
  assignSuggestedAjSkinParts(nodes, skinCandidates);
  return { nodes, nodeIdsBySource };
}

function importDisplayNode(id: string, node: AjNode): ImportedNode {
  const defaultMatrix = readDefaultMatrix(node, id);
  if (node.type === "locator" || node.type === "structure" || node.type === "camera") {
    return { id, type: "anchor", defaultMatrix };
  }
  const entityNbt = displayPropertiesToNbt(node.display_properties);
  const properties = node.display_properties ?? {};
  if (node.type === "item_display") {
    return {
      id,
      type: "item_display",
      defaultMatrix,
      visible: true,
      ...(entityNbt ? { entityNbt } : {}),
      itemDisplay: stringProperty(properties, "item_display", "none"),
      itemStackSnbt: itemArgumentToSnbt(stringProperty(properties, "item", "minecraft:air")),
    };
  }
  if (node.type === "block_display") {
    return {
      id,
      type: "block_display",
      defaultMatrix,
      visible: true,
      ...(entityNbt ? { entityNbt } : {}),
      blockStateSnbt: blockArgumentToSnbt(stringProperty(properties, "block_state", "minecraft:air")),
    };
  }
  return {
    id,
    type: "text_display",
    defaultMatrix,
    visible: true,
    ...(entityNbt ? { entityNbt } : {}),
    text: parseText(stringProperty(properties, "text", "")),
  };
}

function uniqueAjElementNodeId(sourceId: string, elementIndex: number, usedIds: Set<string>): string {
  const base = `${sourceId}_${elementIndex + 1}`;
  let id = base;
  for (let suffix = 2; usedIds.has(id); suffix++) id = `${base}_${suffix}`;
  usedIds.add(id);
  return id;
}

function inferAjSkinPart(nodeId: string): ImportedSkinPart["part"] | undefined {
  const name = nodeId.toLowerCase().replaceAll(/[^a-z0-9]/g, "");
  if (name.includes("left") && (name.includes("arm") || name.includes("hand") || name.includes("wing"))) return "left_arm";
  if (name.includes("right") && (name.includes("arm") || name.includes("hand") || name.includes("wing"))) return "right_arm";
  if (name.includes("left") && (name.includes("leg") || name.includes("foot"))) return "left_leg";
  if (name.includes("right") && (name.includes("leg") || name.includes("foot"))) return "right_leg";
  if (name.includes("head") || name.includes("face") || name.includes("skull")) return "head";
  if (name.includes("body") || name.includes("torso") || name.includes("chest") || name.includes("waist")) return "body";
  return undefined;
}

function splitTallAjSkinElement(element: AjElement, part: ImportedSkinPart["part"] | undefined): AjElement[] {
  if (!part || part === "head" || !isStandardAjSkinElement(element, part)) return [element];
  const height = element.to[1] - element.from[1];
  const upperHeight = height / 3;
  const splitY = element.to[1] - upperHeight;
  const [upperFaces, lowerFaces] = splitAjVerticalFaceUvs(element.faces, upperHeight / height);
  return [
    { ...element, from: [element.from[0], splitY, element.from[2]], faces: upperFaces },
    { ...element, to: [element.to[0], splitY, element.to[2]], faces: lowerFaces },
  ];
}

function splitAjVerticalFaceUvs(faces: AjElement["faces"], upperRatio: number): [AjElement["faces"], AjElement["faces"]] {
  const upperFaces: AjElement["faces"] = {};
  const lowerFaces: AjElement["faces"] = {};
  for (const [direction, face] of Object.entries(faces)) {
    if (!["north", "south", "east", "west"].includes(direction) || face.uv.length !== 4) {
      upperFaces[direction] = face;
      lowerFaces[direction] = face;
      continue;
    }
    const [minU, minV, maxU, maxV] = face.uv;
    const rotation = ((face.rotation ?? 0) % 360 + 360) % 360;
    if (rotation === 90) {
      const splitU = minU + (maxU - minU) * upperRatio;
      upperFaces[direction] = { ...face, uv: [splitU, minV, maxU, maxV] };
      lowerFaces[direction] = { ...face, uv: [minU, minV, splitU, maxV] };
    } else if (rotation === 180) {
      const splitV = maxV - (maxV - minV) * upperRatio;
      upperFaces[direction] = { ...face, uv: [minU, splitV, maxU, maxV] };
      lowerFaces[direction] = { ...face, uv: [minU, minV, maxU, splitV] };
    } else if (rotation === 270) {
      const splitU = maxU - (maxU - minU) * upperRatio;
      upperFaces[direction] = { ...face, uv: [minU, minV, splitU, maxV] };
      lowerFaces[direction] = { ...face, uv: [splitU, minV, maxU, maxV] };
    } else {
      const splitV = minV + (maxV - minV) * upperRatio;
      upperFaces[direction] = { ...face, uv: [minU, minV, maxU, splitV] };
      lowerFaces[direction] = { ...face, uv: [minU, splitV, maxU, maxV] };
    }
  }
  return [upperFaces, lowerFaces];
}

function isStandardAjSkinElement(element: AjElement, part: ImportedSkinPart["part"]): boolean {
  const size = element.to.map((value, axis) => Math.abs(value - element.from[axis]));
  const closeTo = (value: number, expected: number) => Math.abs(value - expected) <= 1e-3;
  if (part === "head") return closeTo(size[0], 8) && closeTo(size[1], 8) && closeTo(size[2], 8);
  if (part === "body") return closeTo(size[0], 8) && closeTo(size[1], 12) && closeTo(size[2], 4);
  return (closeTo(size[0], 3) || closeTo(size[0], 4)) && closeTo(size[1], 12) && closeTo(size[2], 4);
}

function isAjSkinSegment(element: AjElement, part: ImportedSkinPart["part"]): boolean {
  if (part === "head") return isStandardAjSkinElement(element, part);
  const size = element.to.map((value, axis) => Math.abs(value - element.from[axis]));
  const closeTo = (value: number, expected: number) => Math.abs(value - expected) <= 1e-3;
  const expectedWidth = part === "body" ? [8] : [3, 4];
  return expectedWidth.some((width) => closeTo(size[0], width))
    && (closeTo(size[1], 4) || closeTo(size[1], 8))
    && closeTo(size[2], 4);
}

function assignSuggestedAjSkinParts(nodes: Record<string, ImportedNode>, candidates: AjSkinCandidate[]): void {
  for (const part of ["head", "body", "left_arm", "right_arm", "left_leg", "right_leg"] as const) {
    candidates
      .filter((candidate) => candidate.part === part)
      .sort((first, second) => second.centerY - first.centerY || first.sourceOrder - second.sourceOrder)
      .forEach((candidate, order) => {
        const node = nodes[candidate.nodeId];
        if (node?.type === "item_display") node.suggestedSkin = { part, order };
      });
  }
}

function ajElementPlayerHeadMatrix(element: AjElement, path: string): Matrix16 | undefined {
  if (!Array.isArray(element.rotation) && !("axis" in element.rotation) && element.rotation.rescale) return undefined;
  const from = element.from.map((value) => (value - 8) / 16);
  const to = element.to.map((value) => (value - 8) / 16);
  const size = to.map((value, axis) => value - from[axis]);
  if (size.some((value) => !Number.isFinite(value) || value <= 0)) return undefined;
  const center = from.map((value, axis) => (value + to[axis]) / 2);
  const fit = new Matrix4()
    .makeTranslation(center[0], center[1], center[2])
    .scale(new Vector3(size[0] * 2, size[1] * 2, size[2] * 2))
    .multiply(new Matrix4().makeTranslation(0, 0.25, 0));
  const rotation = ajElementRotationMatrix(element);
  return matrix4ToRowMajor(rotation ? rotation.multiply(fit) : fit, `Animated Java ${path} player head conversion`);
}

function ajElementRotationMatrix(element: AjElement): Matrix4 | undefined {
  const rotation = element.rotation;
  if (Array.isArray(rotation)) {
    if (rotation.every((value) => Math.abs(value) <= 1e-7)) return undefined;
    const origin = element.from.map((value, axis) => ((value + element.to[axis]) / 2 - 8) / 16);
    return rotateAround(origin, rotation);
  }
  const origin = rotation.origin.map((value) => (value - 8) / 16);
  if ("axis" in rotation) {
    if (Math.abs(rotation.angle) <= 1e-7) return undefined;
    const axis = rotation.axis === "x" ? new Vector3(1, 0, 0) : rotation.axis === "y" ? new Vector3(0, 1, 0) : new Vector3(0, 0, 1);
    return new Matrix4().makeTranslation(origin[0], origin[1], origin[2])
      .multiply(new Matrix4().makeRotationAxis(axis, MathUtils.degToRad(rotation.angle)))
      .multiply(new Matrix4().makeTranslation(-origin[0], -origin[1], -origin[2]));
  }
  return rotateAround(origin, [rotation.x, rotation.y, rotation.z]);
}

function rotateAround(origin: number[], rotation: number[]): Matrix4 {
  const quaternion = new Quaternion().setFromEuler(new Euler(
    MathUtils.degToRad(rotation[0]),
    MathUtils.degToRad(rotation[1]),
    MathUtils.degToRad(rotation[2]),
    "ZYX",
  ));
  return new Matrix4().makeTranslation(origin[0], origin[1], origin[2])
    .multiply(new Matrix4().makeRotationFromQuaternion(quaternion))
    .multiply(new Matrix4().makeTranslation(-origin[0], -origin[1], -origin[2]));
}

function importAnimation(
  id: string,
  animation: AjAnimation,
  nodes: Record<string, ImportedNode>,
  nodeIdsBySource: ReadonlyMap<string, string[]>,
): ImportedAnimation {
  if (animation.loop_mode.type === "hold") throw new Error(`Animated Java animation ${id} uses hold mode, which the emote format cannot represent.`);
  const blendWeight = animation.blend_weight ?? "1";
  const startDelayTicks = secondsToTicks(numericExpression(animation.start_delay ?? "0", `${id}.start_delay`), `${id}.start_delay`);
  const animationDurationTicks = secondsToTicks(animation.length, `${id}.length`);
  const durationTicks = requireAnimationDurationTicks(animationDurationTicks + startDelayTicks, `${id} duration`);
  const global = animation.global_keyframes;
  if (global && (Object.keys(global.texture ?? {}).length || Object.keys(global.event ?? {}).length)) {
    throw new Error(`Animated Java animation ${id} contains texture or API event keyframes that the emote format cannot preserve.`);
  }

  const tracks: ImportedAnimation["tracks"] = {};
  for (const [sourceNodeId, channels] of Object.entries(animation.node_keyframes ?? {})) {
    const generatedNodeIds = nodeIdsBySource.get(sourceNodeId);
    const node = generatedNodeIds?.length ? nodes[generatedNodeIds[0]] : undefined;
    if (!node || !generatedNodeIds) throw new Error(`Animated Java animation ${id} references unknown node ${sourceNodeId}.`);
    const transforms = compileNodeChannels(id, sourceNodeId, channels, node.defaultMatrix, startDelayTicks, animationDurationTicks, blendWeight);
    for (const nodeId of generatedNodeIds) tracks[nodeId] = {
      transforms,
      visibility: [],
    };
  }
  return {
    id: sanitizeResourcePath(id, "default"),
    name: prettify(id),
    durationTicks,
    loop: animation.loop_mode.type,
    loopDelayTicks: animation.loop_mode.type === "loop"
      ? secondsToTicks(numericExpression(animation.loop_mode.loop_delay ?? "0", `${id}.loop_delay`), `${id}.loop_delay`)
      : 0,
    tracks,
    events: { start: [], timeline: [], loop: [], stop: [] },
  };
}

function compileNodeChannels(
  animationId: string,
  nodeId: string,
  channels: AjNodeChannels,
  defaultMatrix: Matrix16,
  startDelayTicks: number,
  durationTicks: number,
  blendWeight: string,
): ImportedTransformKeyframe[] {
  const matrix = new Matrix4().set(...defaultMatrix);
  const position = new Vector3();
  const rotation = new Quaternion();
  const scale = new Vector3();
  matrix.decompose(position, rotation, scale);
  const defaultTransform: AjTransformValues = {
    position: position.toArray() as [number, number, number],
    rotation: quaternionToAjRotation(rotation),
    scale: scale.toArray() as [number, number, number],
  };
  const current = {
    position: [...defaultTransform.position] as [number, number, number],
    rotation: [...defaultTransform.rotation] as [number, number, number],
    scale: [...defaultTransform.scale] as [number, number, number],
  };
  if (requiresAjBaking(channels, blendWeight)) {
    return bakeAjNodeChannels(channels, defaultTransform, durationTicks, `${animationId}/${nodeId}`).map((frame) => ({
      tick: frame.tick + startDelayTicks,
      matrix: composeBlendedAjMatrix(
        defaultTransform,
        frame,
        evaluateAjMolang(blendWeight, { animationTime: frame.time, keyframeLerpTime: 0 }, `${animationId}.blend_weight`),
      ),
      interpolation: frame.tick === 0 ? { type: "step" } : { type: "linear" },
    }));
  }
  const numericBlendWeight = numericExpression(blendWeight, `${animationId}.blend_weight`);
  const transforms = indexAjKeyframes(animationId, nodeId, channels).map(({ time, position, rotation, scale }): ImportedTransformKeyframe => {
    const entries = [position, rotation, scale];
    const [positionKeyframe, rotationKeyframe, scaleKeyframe] = entries;
    if (positionKeyframe) current.position = readBakedVector(positionKeyframe, `${animationId}/${nodeId}/position/${time}`);
    if (rotationKeyframe) current.rotation = readBakedVector(rotationKeyframe, `${animationId}/${nodeId}/rotation/${time}`);
    if (scaleKeyframe) current.scale = readBakedVector(scaleKeyframe, `${animationId}/${nodeId}/scale/${time}`);
    const step = entries.filter(Boolean).some((entry) => interpolation(entry!, `${animationId}/${nodeId}/${time}`) === "step");
    return {
      tick: secondsToTicks(time, `${animationId}/${nodeId} keyframe`) + startDelayTicks,
      matrix: composeBlendedAjMatrix(defaultTransform, current, numericBlendWeight),
      interpolation: step ? { type: "step" } : { type: "linear" },
    };
  });
  if (startDelayTicks > 0 && transforms.length > 0) {
    if (transforms[0].tick === startDelayTicks) {
      transforms[0] = { ...transforms[0], interpolation: { type: "step" } };
    } else {
      transforms.unshift({ tick: startDelayTicks, matrix: defaultMatrix, interpolation: { type: "step" } });
    }
  }
  return transforms;
}

function indexAjKeyframes(animationId: string, nodeId: string, channels: AjNodeChannels): IndexedAjKeyframes[] {
  const indexed = new Map<number, IndexedAjKeyframes>();
  const channelEntries = [
    ["position", channels.position],
    ["rotation", channels.rotation],
    ["scale", channels.scale],
  ] as const;
  for (const [channelName, channel] of channelEntries) {
    for (const [key, keyframe] of Object.entries(channel ?? {})) {
      const time = Number(key);
      if (!Number.isFinite(time) || time < 0) throw new Error(`${animationId}/${nodeId} has invalid keyframe time ${key}.`);
      secondsToTicks(time, `${animationId}/${nodeId} keyframe`);
      const entry = indexed.get(time) ?? { time };
      if (entry[channelName]) {
        throw new Error(`${animationId}/${nodeId}/${channelName} contains duplicate keyframe time ${time}.`);
      }
      entry[channelName] = keyframe;
      indexed.set(time, entry);
    }
  }
  return [...indexed.values()].sort((first, second) => first.time - second.time);
}

function composeBlendedAjMatrix(
  base: { position: number[]; rotation: number[]; scale: number[] },
  target: { position: number[]; rotation: number[]; scale: number[] },
  weight: number,
): Matrix16 {
  const position = base.position.map((value, index) => value + (target.position[index] - value) * weight);
  const scale = base.scale.map((value, index) => value + (target.scale[index] - value) * weight);
  const rotation = ajQuaternion(base.rotation).slerp(ajQuaternion(target.rotation), weight);
  return matrix4ToRowMajor(new Matrix4().compose(new Vector3(...position), rotation, new Vector3(...scale)), "Animated Java blended matrix");
}

function readBakedVector(keyframe: AjKeyframe, path: string): [number, number, number] {
  if (keyframe.post) throw new Error(`${path} contains a pre/post keyframe; enable baked animations in Animated Java.`);
  interpolation(keyframe, path);
  if (!Array.isArray(keyframe.value) || keyframe.value.length !== 3) throw new Error(`${path}.value must contain three values.`);
  return keyframe.value.map((value, index) => numericExpression(value, `${path}.value[${index}]`)) as [number, number, number];
}

function interpolation(keyframe: AjKeyframe, path: string): "linear" | "step" {
  if (keyframe.interpolation.type === "step") return "step";
  if (keyframe.interpolation.type === "linear" && keyframe.interpolation.easing === "step") return "step";
  if (keyframe.interpolation.type === "linear" && keyframe.interpolation.easing === "linear") return "linear";
  throw new Error(`${path} uses non-baked interpolation; enable baked animations in Animated Java.`);
}

function ajQuaternion(rotation: number[]): Quaternion {
  const euler = new Euler(
    MathUtils.degToRad(-rotation[0]),
    MathUtils.degToRad(180 - rotation[1]),
    MathUtils.degToRad(rotation[2]),
    "YXZ",
  );
  return new Quaternion().setFromEuler(euler);
}

function quaternionToAjRotation(quaternion: Quaternion): [number, number, number] {
  const euler = new Euler().setFromQuaternion(quaternion, "YXZ");
  return [-MathUtils.radToDeg(euler.x), 180 - MathUtils.radToDeg(euler.y), MathUtils.radToDeg(euler.z)];
}

function readDefaultMatrix(node: AjNode, id: string): Matrix16 {
  const values = node.default_transformation?.matrix ?? IDENTITY_MATRIX;
  if (values.length !== 16 || values.some((value) => !Number.isFinite(value))) throw new Error(`Animated Java node ${id} has an invalid default matrix.`);
  return matrix4ToRowMajor(new Matrix4().fromArray(values), `Animated Java node ${id} default matrix`);
}

function writeBoneResources(
  modelPath: string,
  nodeId: string,
  elements: AjElement[],
  resource: ResourceLocation,
  blueprint: AjBlueprint,
  resources: Map<string, Uint8Array>,
): void {
  const usedTextures = new Set<string>();
  const modelElements = elements.map((element) => ({
    from: element.from,
    to: element.to,
    rotation: element.rotation,
    ...(element.shade === undefined ? {} : { shade: element.shade }),
    ...(element.light_emission === undefined ? {} : { light_emission: element.light_emission }),
    faces: Object.fromEntries(Object.entries(element.faces).map(([direction, face]) => {
      const texture = resolveTexture(face.texture_provider, blueprint);
      usedTextures.add(texture);
      return [direction, {
        uv: face.uv,
        texture: `#${texture}`,
        ...(face.tintindex === undefined ? {} : { tintindex: face.tintindex }),
        ...(face.rotation === undefined ? {} : { rotation: face.rotation }),
      }];
    })),
  }));
  const textures = Object.fromEntries([...usedTextures].map((texture) => {
    const source = blueprint.textures?.[texture];
    if (!source) throw new Error(`Animated Java bone ${nodeId} references unknown texture ${texture}.`);
    if (source.type === "reference") return [texture, source.resource_location];
    const texturePath = [resource.path, texture].filter(Boolean).join("/");
    addResource(resources, `assets/${resource.namespace}/textures/item/${texturePath}.png`, decodeBase64(source.base64_string, texture));
    return [texture, `${resource.namespace}:item/${texturePath}`];
  }));
  addResource(resources, `assets/${resource.namespace}/models/item/${modelPath}.json`, jsonBytes({ textures, elements: modelElements }));
  addResource(
    resources,
    `assets/${resource.namespace}/items/${modelPath}.json`,
    jsonBytes({ model: { type: "minecraft:model", model: `${resource.namespace}:item/${modelPath}` } }),
  );
}

function resolveTexture(provider: AjElement["faces"][string]["texture_provider"], blueprint: AjBlueprint): string {
  if (provider.type === "texture") return provider.texture;
  const palette = blueprint.texture_palettes?.[provider.texture_palette];
  const texture = palette?.states?.[palette.active_state]?.texture;
  if (!texture) throw new Error(`Animated Java texture palette ${provider.texture_palette} has no active texture.`);
  return texture;
}

function displayPropertiesToNbt(properties: Record<string, unknown> | undefined): string | undefined {
  if (!properties) return undefined;
  const fields: [string, string][] = [];
  for (const key of ["billboard", "shadow_radius", "shadow_strength", "glow_color_override"] as const) {
    const value = properties[key];
    if (typeof value === "string") fields.push([key, serializeSnbtString(value)]);
    else if (typeof value === "number" && Number.isFinite(value)) fields.push([key, String(value)]);
  }
  if (properties.is_glowing === true) fields.push(["glowing", "1b"]);
  if (properties.is_custom_brightness_enabled === true && isRecord(properties.custom_brightness)) {
    const sky = numberProperty(properties.custom_brightness, "sky", 0);
    const block = numberProperty(properties.custom_brightness, "block", 0);
    fields.push(["brightness", serializeSnbtCompound([["sky", String(sky)], ["block", String(block)]])]);
  }
  return fields.length ? serializeSnbtCompound(fields) : undefined;
}

function parseText(value: string): unknown {
  try { return JSON.parse(value); } catch { return { text: value }; }
}

function numericExpression(value: string, path: string): number {
  if (typeof value !== "string" || !/^[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?$/.test(value.trim())) {
    throw new Error(`${path} contains a dynamic Molang expression; enable baked animations in Animated Java.`);
  }
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) throw new Error(`${path} is not finite.`);
  return parsed;
}

function decodeBase64(value: string, texture: string): Uint8Array {
  try {
    const binary = atob(value);
    return Uint8Array.from(binary, (character) => character.charCodeAt(0));
  } catch {
    throw new Error(`Animated Java texture ${texture} is not valid base64.`);
  }
}

function jsonBytes(value: unknown): Uint8Array {
  return encoder.encode(`${JSON.stringify(value, null, 2)}\n`);
}

function addResource(resources: Map<string, Uint8Array>, path: string, data: Uint8Array): void {
  const existing = resources.get(path);
  if (existing && (existing.length !== data.length || existing.some((value, index) => value !== data[index]))) {
    throw new ConversionError("resource_path_collision", `Generated resources contain different files at the same path: ${path}`, path);
  }
  resources.set(path, data);
}

function stringProperty(value: Record<string, unknown>, key: string, fallback: string): string {
  return typeof value[key] === "string" ? value[key] : fallback;
}

function numberProperty(value: Record<string, unknown>, key: string, fallback: number): number {
  return typeof value[key] === "number" && Number.isFinite(value[key]) ? value[key] : fallback;
}

function prettify(value: string): string {
  const result = value.replaceAll("_", " ").replaceAll("-", " ").trim();
  return result ? result[0].toUpperCase() + result.slice(1) : "Emote";
}
