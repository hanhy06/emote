import { Euler, MathUtils, Matrix4, Quaternion, Vector3 } from "three";
import type { Matrix16 } from "../../format/emoteAnimation";
import { IDENTITY_MATRIX, matrix4ToRowMajor } from "../../format/matrix";
import { normalizeResourceLocation, parseResourceLocation, sanitizeResourcePath, type ResourceLocation } from "../../format/resourceLocation";
import { serializeSnbtCompound, serializeSnbtString, splitSnbtPair, splitSnbtTopLevel } from "../../format/snbt";
import { secondsToTicks } from "../../format/time";
import type { ImportAdapter, ImportInput, ProbeResult } from "../adapter";
import { parseInputJson } from "../inputCache";
import type { ImportedAnimation, ImportedNode, ImportedProject, ImportedTransformKeyframe } from "../types";
import { ConversionError } from "../errors";
import { requireAjBlueprint, type AjAnimation, type AjBlueprint, type AjElement, type AjKeyframe, type AjNode, type AjNodeChannels } from "./animatedJavaSchema";

const encoder = new TextEncoder();

export const animatedJavaJsonAdapter: ImportAdapter = {
  id: "animated_java_json",
  label: "Animated Java plugin blueprint",
  extensions: ["json"],

  probe(input: ImportInput): ProbeResult {
    try {
      const value = parseInputJson(input) as Partial<AjBlueprint>;
      return value.format_version === 1 && typeof value.settings?.id === "string" && isRecord(value.nodes) && isRecord(value.animations)
        ? { confidence: 100, reason: "matches Animated Java plugin blueprint format 1" }
        : { confidence: 0, reason: "does not match Animated Java plugin blueprint format 1" };
    } catch {
      return { confidence: 0, reason: "not JSON" };
    }
  },

  async import(input: ImportInput): Promise<ImportedProject> {
    const blueprint = requireAjBlueprint(parseInputJson(input));
    validateRoot(blueprint);
    const resource = parseResourceLocation(blueprint.settings.id, "Animated Java settings.id");
    const artifacts = new Map<string, Uint8Array>();
    const nodes = Object.fromEntries(Object.entries(blueprint.nodes ?? {}).map(([id, node]) => [id, importNode(id, node, resource, blueprint, artifacts)]));
    const animations = Object.entries(blueprint.animations ?? {}).map(([id, animation]) => importAnimation(id, animation, nodes));
    if (Object.keys(nodes).length === 0) throw new Error("Animated Java blueprint does not contain nodes.");
    if (animations.length === 0) throw new Error("Animated Java blueprint does not contain animations.");
    const name = prettify(resource.path.split("/").at(-1) ?? resource.path);
    return {
      source: "animated_java_json",
      sourceName: input.name,
      suggestedMetadata: { name, description: `${name} emote.`, command_name: sanitizeResourcePath(resource.path, "default"), hide_player: true },
      nodes,
      animations,
      diagnostics: [],
      artifacts,
      ...(artifacts.size ? { artifactMinecraftVersion: "26.2" } : {}),
    };
  },
};

function validateRoot(blueprint: AjBlueprint): void {
  if (blueprint.format_version !== 1) throw new Error(`Unsupported Animated Java plugin blueprint version: ${blueprint.format_version}`);
  parseResourceLocation(blueprint.settings?.id, "Animated Java settings.id");
}

function importNode(
  id: string,
  node: AjNode,
  resource: ResourceLocation,
  blueprint: AjBlueprint,
  artifacts: Map<string, Uint8Array>,
): ImportedNode {
  const defaultMatrix = readDefaultMatrix(node, id);
  if (node.type === "locator" || node.type === "structure" || node.type === "camera") {
    return { id, type: "anchor", defaultMatrix };
  }
  const entityNbt = displayPropertiesToNbt(node.display_properties);
  if (node.type === "bone") {
    const modelPath = [resource.path, id].filter(Boolean).join("/");
    writeBoneArtifacts(modelPath, id, node, resource, blueprint, artifacts);
    return {
      id,
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
    };
  }
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

function importAnimation(id: string, animation: AjAnimation, nodes: Record<string, ImportedNode>): ImportedAnimation {
  if (animation.loop_mode.type === "hold") throw new Error(`Animated Java animation ${id} uses hold mode, which the emote format cannot represent.`);
  const blendWeight = numericExpression(animation.blend_weight ?? "1", `${id}.blend_weight`);
  if (blendWeight !== 1) throw new Error(`Animated Java animation ${id} must use blend_weight 1.`);
  const startDelayTicks = secondsToTicks(numericExpression(animation.start_delay ?? "0", `${id}.start_delay`), `${id}.start_delay`);
  const durationTicks = secondsToTicks(animation.length, `${id}.length`) + startDelayTicks;
  const global = animation.global_keyframes;
  if (global && (Object.keys(global.texture ?? {}).length || Object.keys(global.event ?? {}).length)) {
    throw new Error(`Animated Java animation ${id} contains texture or API event keyframes that the emote format cannot preserve.`);
  }

  const tracks: ImportedAnimation["tracks"] = {};
  for (const [nodeId, channels] of Object.entries(animation.node_keyframes ?? {})) {
    const node = nodes[nodeId];
    if (!node) throw new Error(`Animated Java animation ${id} references unknown node ${nodeId}.`);
    tracks[nodeId] = { transforms: compileNodeChannels(id, nodeId, channels, node.defaultMatrix, startDelayTicks), visibility: [] };
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
): ImportedTransformKeyframe[] {
  const matrix = new Matrix4().set(...defaultMatrix);
  const position = new Vector3();
  const rotation = new Quaternion();
  const scale = new Vector3();
  matrix.decompose(position, rotation, scale);
  const current = {
    position: position.toArray() as [number, number, number],
    rotation: quaternionToAjRotation(rotation),
    scale: scale.toArray() as [number, number, number],
  };
  const times = new Set<number>();
  for (const channel of [channels.position, channels.rotation, channels.scale]) {
    for (const key of Object.keys(channel ?? {})) {
      const time = Number(key);
      if (!Number.isFinite(time) || time < 0) throw new Error(`${animationId}/${nodeId} has invalid keyframe time ${key}.`);
      secondsToTicks(time, `${animationId}/${nodeId} keyframe`);
      times.add(time);
    }
  }
  return [...times].sort((first, second) => first - second).map((time) => {
    const entries = [channels.position?.[String(time)] ?? channels.position?.[formatTimestamp(time)], channels.rotation?.[String(time)] ?? channels.rotation?.[formatTimestamp(time)], channels.scale?.[String(time)] ?? channels.scale?.[formatTimestamp(time)]];
    const [positionKeyframe, rotationKeyframe, scaleKeyframe] = entries;
    if (positionKeyframe) current.position = readBakedVector(positionKeyframe, `${animationId}/${nodeId}/position/${time}`);
    if (rotationKeyframe) current.rotation = readBakedVector(rotationKeyframe, `${animationId}/${nodeId}/rotation/${time}`);
    if (scaleKeyframe) current.scale = readBakedVector(scaleKeyframe, `${animationId}/${nodeId}/scale/${time}`);
    const step = entries.filter(Boolean).some((entry) => interpolation(entry!, `${animationId}/${nodeId}/${time}`) === "step");
    return {
      tick: secondsToTicks(time, `${animationId}/${nodeId} keyframe`) + startDelayTicks,
      matrix: composeAjMatrix(current.position, current.rotation, current.scale),
      interpolation: step ? { type: "step" } : { type: "linear" },
    };
  });
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

function composeAjMatrix(position: number[], rotation: number[], scale: number[]): Matrix16 {
  const euler = new Euler(
    MathUtils.degToRad(-rotation[0]),
    MathUtils.degToRad(180 - rotation[1]),
    MathUtils.degToRad(rotation[2]),
    "YXZ",
  );
  const matrix = new Matrix4().compose(new Vector3(...position), new Quaternion().setFromEuler(euler), new Vector3(...scale));
  return matrix4ToRowMajor(matrix, "Animated Java matrix");
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

function writeBoneArtifacts(
  modelPath: string,
  nodeId: string,
  node: AjNode,
  resource: ResourceLocation,
  blueprint: AjBlueprint,
  artifacts: Map<string, Uint8Array>,
): void {
  const usedTextures = new Set<string>();
  const elements = (node.elements ?? []).map((element) => ({
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
    addArtifact(artifacts, `assets/${resource.namespace}/textures/item/${texturePath}.png`, decodeBase64(source.base64_string, texture));
    return [texture, `${resource.namespace}:item/${texturePath}`];
  }));
  addArtifact(artifacts, `assets/${resource.namespace}/models/item/${modelPath}.json`, jsonBytes({ textures, elements }));
  addArtifact(
    artifacts,
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

function itemArgumentToSnbt(value: string): string {
  const match = /^([^\[]+)(?:\[(.*)\])?$/.exec(value.trim());
  const id = normalizeResourceLocation(match?.[1] ?? "air");
  const components = match?.[2] ? splitSnbtTopLevel(match[2]).flatMap((component): [string, string][] => {
    const pair = splitSnbtPair(component, "=");
    if (!pair?.[0] || !pair[1]) return [];
    return [[normalizeResourceLocation(pair[0]), pair[1]]];
  }) : [];
  return serializeSnbtCompound([
    ["id", serializeSnbtString(id)],
    ["count", "1"],
    ["components", components.length ? serializeSnbtCompound(components) : undefined],
  ]);
}

function blockArgumentToSnbt(value: string): string {
  const match = /^([^\[]+)(?:\[(.*)\])?$/.exec(value.trim());
  const id = normalizeResourceLocation(match?.[1] ?? "air");
  const properties = match?.[2] ? splitSnbtTopLevel(match[2]).flatMap((property): [string, string][] => {
    const pair = splitSnbtPair(property, "=");
    if (!pair?.[0] || !pair[1]) return [];
    return [[pair[0], serializeSnbtString(pair[1])]];
  }) : [];
  return serializeSnbtCompound([
    ["Name", serializeSnbtString(id)],
    ["Properties", properties.length ? serializeSnbtCompound(properties) : undefined],
  ]);
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

function addArtifact(artifacts: Map<string, Uint8Array>, path: string, data: Uint8Array): void {
  const existing = artifacts.get(path);
  if (existing && (existing.length !== data.length || existing.some((value, index) => value !== data[index]))) {
    throw new ConversionError("artifact_path_collision", `Generated resources contain different files at the same path: ${path}`, path);
  }
  artifacts.set(path, data);
}

function stringProperty(value: Record<string, unknown>, key: string, fallback: string): string {
  return typeof value[key] === "string" ? value[key] : fallback;
}

function numberProperty(value: Record<string, unknown>, key: string, fallback: number): number {
  return typeof value[key] === "number" && Number.isFinite(value[key]) ? value[key] : fallback;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function formatTimestamp(value: number): string {
  return Number.isInteger(value) ? `${value}.0` : String(value);
}

function prettify(value: string): string {
  const result = value.replaceAll("_", " ").replaceAll("-", " ").trim();
  return result ? result[0].toUpperCase() + result.slice(1) : "Emote";
}
