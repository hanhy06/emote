import { Euler, Matrix4, Quaternion, Vector3 } from "three";
import type { Matrix16 } from "../../format/emoteAnimation";
import { IDENTITY_MATRIX, asMatrix16, matrix4ToRowMajor } from "../../format/matrix";
import { normalizeResourceLocation, sanitizeResourcePath } from "../../format/resourceLocation";
import { parseSnbtCompound, serializeSnbtCompound, serializeSnbtString, splitSnbtPair, splitSnbtTopLevel } from "../../format/snbt";
import { TICKS_PER_SECOND } from "../../format/time";
import type { ImportAdapter, ImportInput, ProbeResult } from "../adapter";
import type { ImportedAnimation, ImportedNode, ImportedProject, ImportedTransformKeyframe } from "../types";
import { requireBdSceneNode, type BdSceneNode, type BdTransform, type VectorLike } from "./bdProjectSchema";
import { hasGzipHeader, readPrj2 } from "./prj2";

const decoder = new TextDecoder();
const BD_SAMPLES_PER_SECOND = 10;
const TICKS_PER_BD_SAMPLE = TICKS_PER_SECOND / BD_SAMPLES_PER_SECOND;

interface DisplayEntry {
  id: string;
  node: BdSceneNode;
  ancestors: BdSceneNode[];
}

export const bdProjectAdapter: ImportAdapter = {
  id: "bd_project",
  label: "BD Engine project",
  extensions: ["bdengine"],

  async probe(input: ImportInput): Promise<ProbeResult> {
    if (!hasGzipHeader(input.bytes)) return { confidence: 0, reason: "not gzip-compressed" };
    try {
      const archive = await readPrj2(input);
      return archive.files.has("scene.json")
        ? { confidence: 100, reason: "contains PRJ2 scene.json" }
        : { confidence: 0, reason: "does not contain scene.json" };
    } catch {
      return { confidence: 0, reason: "not a readable BD project" };
    }
  },

  async import(input: ImportInput): Promise<ImportedProject> {
    const archive = await readPrj2(input);
    if (archive.version !== 1) throw new Error(`Unsupported BD project PRJ2 version: ${archive.version}`);
    const sceneBytes = archive.files.get("scene.json");
    if (!sceneBytes) throw new Error("BD project does not contain scene.json.");
    const parsedValue = JSON.parse(decoder.decode(sceneBytes)) as unknown;
    const parsed = Array.isArray(parsedValue)
      ? parsedValue.map((node, index) => requireBdSceneNode(node, `scene[${index}]`))
      : requireBdSceneNode(parsedValue, "scene");
    const root = Array.isArray(parsed) ? parsed[0] : parsed;
    if (!root?.isCollection) throw new Error("BD project scene root is invalid.");
    if ((root.listAnim?.length ?? 0) > 1) throw new Error("BD projects with multiple animations are not supported yet.");
    if (root.listSound?.some((sound) => (sound.tracks?.length ?? 0) > 0)) {
      throw new Error("BD project sound tracks are not supported yet; export the project as a datapack to preserve them.");
    }

    const displays = collectDisplays(root);
    if (displays.length === 0) throw new Error("BD project does not contain display nodes.");
    const sampleTimes = collectSampleTimes(root);
    const frameTimes = sampleTimes.length > 0 ? sampleTimes : [0];
    const nodes = Object.fromEntries(displays.map((entry) => [entry.id, createImportedNode(entry, frameTimes[0])]));
    const tracks = Object.fromEntries(displays.map((entry) => [entry.id, {
      transforms: frameTimes.map((time, index): ImportedTransformKeyframe => ({
        tick: time * TICKS_PER_BD_SAMPLE,
        matrix: evaluateDisplayMatrix(entry, time),
        interpolation: index === 0 ? { type: "step" } : { type: "linear" },
      })),
      visibility: [],
    }]));
    const animationName = root.listAnim?.[0]?.name?.trim() || "Default";
    const animation: ImportedAnimation = {
      id: sanitizeResourcePath(animationName, "default"),
      name: animationName,
      durationTicks: (frameTimes[frameTimes.length - 1] + 1) * TICKS_PER_BD_SAMPLE,
      loop: "loop",
      loopDelayTicks: 0,
      tracks,
      events: { start: [], timeline: [], loop: [], stop: [] },
    };
    const sourceStem = input.name.replace(/\.bdengine$/i, "").trim() || "BD Project";
    return {
      source: "bd_project",
      sourceName: input.name,
      suggestedMetadata: {
        name: sourceStem,
        description: `${sourceStem} emote.`,
        command_name: sanitizeResourcePath(sourceStem, "default"),
        hide_player: true,
      },
      nodes,
      animations: [animation],
      diagnostics: [],
      artifacts: [],
    };
  },
};

function collectDisplays(root: BdSceneNode): DisplayEntry[] {
  const displays: DisplayEntry[] = [];
  const visit = (node: BdSceneNode, ancestors: BdSceneNode[]) => {
    if (node.isCollection) {
      for (const child of node.children ?? []) visit(child, [...ancestors, node]);
      return;
    }
    const typeCount = Number(Boolean(node.isItemDisplay)) + Number(Boolean(node.isBlockDisplay)) + Number(Boolean(node.isTextDisplay));
    if (typeCount !== 1) throw new Error(`BD project node ${node.name ?? "<unnamed>"} must have exactly one display type.`);
    displays.push({ id: `display_${displays.length}`, node, ancestors });
  };
  visit(root, []);
  return displays;
}

function collectSampleTimes(root: BdSceneNode): number[] {
  const times = new Set<number>();
  const visit = (node: BdSceneNode) => {
    for (const sample of node.animation ?? []) {
      if (!Number.isInteger(sample.time) || sample.time < 0) throw new Error(`BD animation contains an invalid sample time: ${sample.time}`);
      times.add(sample.time);
    }
    for (const child of node.children ?? []) visit(child);
  };
  visit(root);
  const sorted = [...times].sort((first, second) => first - second);
  sorted.forEach((time, index) => {
    if (time !== index) throw new Error("BD animation samples must be contiguous from time 0.");
  });
  return sorted;
}

function createImportedNode(entry: DisplayEntry, firstTime: number): ImportedNode {
  const node = entry.node;
  const defaultMatrix = evaluateDisplayMatrix(entry, firstTime);
  const entityNbt = createEntityNbt(node);
  if (node.isItemDisplay) {
    const item = parseItemName(node.name ?? "minecraft:air");
    return {
      id: entry.id,
      parentId: null,
      type: "item_display",
      defaultMatrix,
      visible: true,
      ...(entityNbt ? { entityNbt } : {}),
      itemDisplay: item.display,
      itemStackSnbt: createItemStack(item.id, node.tagHead?.Value),
    };
  }
  if (node.isBlockDisplay) {
    return {
      id: entry.id,
      parentId: null,
      type: "block_display",
      defaultMatrix,
      visible: true,
      ...(entityNbt ? { entityNbt } : {}),
      blockStateSnbt: createBlockState(node.name ?? "minecraft:air"),
    };
  }
  return {
    id: entry.id,
    parentId: null,
    type: "text_display",
    defaultMatrix,
    visible: true,
    ...(entityNbt ? { entityNbt } : {}),
    text: { text: node.name ?? "" },
  };
}

function evaluateDisplayMatrix(entry: DisplayEntry, time: number): Matrix16 {
  const matrix = new Matrix4();
  for (const ancestor of entry.ancestors) matrix.multiply(evaluateCollection(ancestor, time));
  const pivot = vector(entry.ancestors.at(-1)?.pivotCustom, [0, 0, 0]);
  matrix.multiply(new Matrix4().makeTranslation(-pivot[0], -pivot[1], -pivot[2]));
  matrix.multiply(matrixFromArray(entry.node.transforms, entry.node.name ?? entry.id));
  return matrix4ToRowMajor(matrix, "BD composed matrix");
}

function evaluateCollection(node: BdSceneNode, time: number): Matrix4 {
  const samples = node.animation ?? [];
  if (samples.length > 0) {
    const sample = samples.find((candidate) => candidate.time === time);
    if (!sample) throw new Error(`BD node ${node.name ?? "<unnamed>"} has no animation sample at ${time}.`);
    return matrixFromTransform(sample);
  }
  if (node.defaultTransform) return matrixFromTransform(node.defaultTransform);
  return matrixFromArray(node.transforms, node.name ?? "collection");
}

function matrixFromTransform(transform: BdTransform): Matrix4 {
  const position = vector(transform.position, [0, 0, 0]);
  const rotation = vector(transform.rotation, [0, 0, 0]);
  const scale = vector(transform.scale, [1, 1, 1]);
  return new Matrix4().compose(
    new Vector3(...position),
    new Quaternion().setFromEuler(new Euler(...rotation, "XYZ")),
    new Vector3(...scale),
  );
}

function matrixFromArray(values: number[] | undefined, label: string): Matrix4 {
  return new Matrix4().set(...asMatrix16(values ?? IDENTITY_MATRIX, `${label} transforms`));
}

function vector(value: VectorLike | undefined, fallback: [number, number, number]): [number, number, number] {
  if (Array.isArray(value)) return [number(value[0], fallback[0]), number(value[1], fallback[1]), number(value[2], fallback[2])];
  return [number(value?.x, fallback[0]), number(value?.y, fallback[1]), number(value?.z, fallback[2])];
}

function createEntityNbt(node: BdSceneNode): string | undefined {
  const fields: [string, string][] = [];
  const sky = node.brightness?.sky;
  const block = node.brightness?.block;
  if (Number.isInteger(sky) && Number.isInteger(block)) {
    fields.push(["brightness", serializeSnbtCompound([["sky", String(sky)], ["block", String(block)]])]);
  }
  if (node.nbt?.trim()) {
    const raw = node.nbt.trim();
    const compound = raw.startsWith("{") && raw.endsWith("}") ? raw : `{${raw}}`;
    fields.push(...parseSnbtCompound(compound, `BD node ${node.name ?? "<unnamed>"} NBT`).map(({ name, value }) => [name, value] as [string, string]));
  }
  return fields.length ? serializeSnbtCompound(fields) : undefined;
}

function parseItemName(name: string): { id: string; display: string } {
  const match = /^(.*?)(?:\[display=([^\]]+)\])?$/.exec(name.trim());
  return { id: normalizeResourceLocation(match?.[1] || "air"), display: match?.[2] || "none" };
}

function createItemStack(id: string, texture: string | undefined): string {
  if (id === "minecraft:player_head" && texture) {
    const property = serializeSnbtCompound([
      ["name", serializeSnbtString("textures")],
      ["value", serializeSnbtString(texture)],
    ]);
    const profile = serializeSnbtCompound([["properties", `[${property}]`]]);
    return serializeSnbtCompound([
      ["id", serializeSnbtString(id)],
      ["Count", "1"],
      ["components", serializeSnbtCompound([["minecraft:profile", profile]])],
    ]);
  }
  return serializeSnbtCompound([["id", serializeSnbtString(id)], ["Count", "1"]]);
}

function createBlockState(name: string): string {
  const match = /^([^\[]+)(?:\[([^\]]+)\])?$/.exec(name.trim());
  const id = normalizeResourceLocation(match?.[1] || "air");
  const properties = match?.[2] ? splitSnbtTopLevel(match[2]).flatMap((entry): [string, string][] => {
    const pair = splitSnbtPair(entry, "=");
    return pair?.[0] && pair[1] ? [[pair[0], serializeSnbtString(pair[1])]] : [];
  }) : [];
  return serializeSnbtCompound([
    ["Name", serializeSnbtString(id)],
    ["Properties", properties.length ? serializeSnbtCompound(properties) : undefined],
  ]);
}

function number(value: number | undefined, fallback: number): number {
  return Number.isFinite(value) ? value! : fallback;
}
