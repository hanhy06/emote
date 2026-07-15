import JSZip from "jszip";
import type { Matrix16 } from "../../format/emoteAnimation";
import { IDENTITY_MATRIX, asMatrix16 } from "../../format/matrix";
import {
  findMatchingSnbtDelimiter,
  omitSnbtFields,
  readSnbtCompoundField,
  readSnbtRawField,
  readSnbtStringField,
} from "../../format/snbt";
import type { ImportAdapter, ImportInput, ProbeResult } from "../adapter";
import type {
  ImportDiagnostic,
  ImportedAnimation,
  ImportedNode,
  ImportedProject,
  ImportedTimelineEvent,
  ImportedTransformKeyframe,
} from "../types";

const decoder = new TextDecoder();
const DISPLAY_START = /\{id:"minecraft:(item_display|block_display|text_display)"/g;
const KEYFRAME_PATH = /^data\/([^/]+)\/(function|functions)\/k\/([^/]+)\/keyframe_(\d+)\.mcfunction$/;
const CREATE_PATH = /^data\/([^/]+)\/(function|functions)\/_\/create\.mcfunction$/;
const STRUCTURAL_KEYFRAME_COMMAND = /^(data merge entity .*?\{transformation:|schedule function )/;

interface LoadedPack {
  name: string;
  files: Map<string, Uint8Array>;
}

export const bdDatapackAdapter: ImportAdapter = {
  id: "bd_datapack",
  label: "BD Engine datapack",
  extensions: ["zip"],

  async probe(input: ImportInput): Promise<ProbeResult> {
    if (!isZip(input.bytes)) return { confidence: 0, reason: "not a ZIP archive" };
    try {
      const pack = await loadPack(input);
      const hasCreate = [...pack.files].some(([path, data]) => CREATE_PATH.test(path) && decoder.decode(data).includes("created via BDEngine"));
      return hasCreate
        ? { confidence: 100, reason: "contains a BD Engine create function" }
        : { confidence: 0, reason: "does not contain a BD Engine create function" };
    } catch {
      return { confidence: 0, reason: "not a readable datapack" };
    }
  },

  async import(input: ImportInput): Promise<ImportedProject> {
    const pack = await loadPack(input);
    const models = [...pack.files]
      .flatMap(([path, data]) => {
        const match = CREATE_PATH.exec(path);
        return match && decoder.decode(data).includes("created via BDEngine")
          ? [{ namespace: match[1], functionFolder: match[2], createPath: path, createText: decoder.decode(data) }]
          : [];
      })
      .sort((first, second) => first.namespace.localeCompare(second.namespace));
    if (models.length === 0) throw new Error("No BD Engine create function was found.");
    if (models.length > 1) throw new Error("BD datapacks containing multiple model namespaces are not supported yet.");

    const model = models[0];
    const diagnostics: ImportDiagnostic[] = [];
    const nodes = parseNodes(model.createText, model.namespace);
    const metadata = readMetadata(pack.files, model.namespace);
    const animations = parseAnimations(pack.files, model.namespace, nodes, metadata.entrypoint, diagnostics);
    if (animations.length === 0) throw new Error(`No BD Engine keyframes were found for ${model.namespace}.`);
    return {
      source: "bd_datapack",
      sourceName: input.name,
      suggestedMetadata: {
        name: metadata.name,
        description: metadata.description,
        command_name: metadata.commandName,
        hide_player: metadata.hidePlayer,
      },
      nodes,
      animations,
      diagnostics,
      artifacts: [],
    };
  },
};

async function loadPack(input: ImportInput): Promise<LoadedPack> {
  const zip = await JSZip.loadAsync(input.bytes);
  const rawFiles = new Map<string, Uint8Array>();
  await Promise.all(Object.values(zip.files).filter((entry) => !entry.dir).map(async (entry) => {
    rawFiles.set(entry.name.replaceAll("\\", "/").replace(/^\.\//, ""), await entry.async("uint8array"));
  }));
  const root = findPackRoot(rawFiles.keys());
  return {
    name: input.name,
    files: new Map([...rawFiles].filter(([path]) => path.startsWith(root)).map(([path, data]) => [path.slice(root.length), data])),
  };
}

function findPackRoot(paths: Iterable<string>): string {
  const candidates = [...paths]
    .filter((path) => path === "pack.mcmeta" || path.endsWith("/pack.mcmeta"))
    .map((path) => path.slice(0, -"pack.mcmeta".length))
    .sort((first, second) => first.split("/").length - second.split("/").length || first.localeCompare(second));
  if (candidates.length === 0) throw new Error("Could not find pack.mcmeta in the ZIP file.");
  return candidates[0];
}

function parseNodes(createText: string, namespace: string): Record<string, ImportedNode> {
  const nodes: Record<string, ImportedNode> = {};
  for (const compound of extractDisplayCompounds(createText)) {
    const type = readSnbtStringField(compound, "id")?.replace("minecraft:", "");
    const tag = readNodeTag(compound, namespace);
    if (!tag || (type !== "item_display" && type !== "block_display" && type !== "text_display")) continue;
    const defaultMatrix = readMatrix(compound, `${tag} transformation`);
    const entityNbt = remainingEntityNbt(compound);
    if (type === "item_display") {
      const itemStack = readSnbtCompoundField(compound, "item");
      if (!itemStack) throw new Error(`Item display ${tag} does not contain an item stack.`);
      const marker = /name\s*:\s*"emote:(head|body|left_arm|right_arm|left_leg|right_leg)(\d+)?"/.exec(itemStack);
      nodes[tag] = {
        id: tag,
        parentId: null,
        type,
        defaultMatrix,
        visible: true,
        ...(entityNbt ? { entityNbt } : {}),
        itemStackSnbt: itemStack,
        itemDisplay: readSnbtStringField(compound, "item_display") ?? "none",
        ...(marker ? { skin: { part: marker[1] as NonNullable<Extract<ImportedNode, { type: "item_display" }>["skin"]>["part"], order: Number.parseInt(marker[2] ?? "0", 10) } } : {}),
      };
    } else if (type === "block_display") {
      const blockState = readSnbtCompoundField(compound, "block_state");
      if (!blockState) throw new Error(`Block display ${tag} does not contain block_state.`);
      nodes[tag] = { id: tag, parentId: null, type, defaultMatrix, visible: true, ...(entityNbt ? { entityNbt } : {}), blockStateSnbt: blockState };
    } else {
      const text = readSnbtRawField(compound, "text");
      nodes[tag] = { id: tag, parentId: null, type, defaultMatrix, visible: true, ...(entityNbt ? { entityNbt } : {}), text: parseTextComponent(text) };
    }
  }
  if (Object.keys(nodes).length === 0) throw new Error("No BD Engine display nodes were found in create.mcfunction.");
  return nodes;
}

function parseAnimations(
  files: Map<string, Uint8Array>,
  namespace: string,
  nodes: Record<string, ImportedNode>,
  entrypoint: string,
  diagnostics: ImportDiagnostic[],
): ImportedAnimation[] {
  const groups = new Map<string, { index: number; path: string; text: string }[]>();
  for (const [path, data] of files) {
    const match = KEYFRAME_PATH.exec(path);
    if (!match || match[1] !== namespace) continue;
    const frames = groups.get(match[3]) ?? [];
    frames.push({ index: Number.parseInt(match[4], 10), path, text: decoder.decode(data) });
    groups.set(match[3], frames);
  }
  return [...groups].sort(([first], [second]) => first.localeCompare(second)).map(([name, frames]) => {
    frames.sort((first, second) => first.index - second.index);
    const tracks = Object.fromEntries(Object.keys(nodes).map((nodeId) => [nodeId, { transforms: [], visibility: [] }]));
    const timeline: ImportedTimelineEvent[] = [];
    let tick = 0;
    frames.forEach((frame, position) => {
      if (frame.index !== position) throw new Error(`BD keyframes for ${name} must be contiguous from frame 0.`);
      parseFrame(frame.text, frame.path, namespace, tick / 20, tracks, timeline, diagnostics);
      tick += readFrameDelayTicks(frame.text, frame.path);
    });
    return {
      id: name,
      name,
      durationSeconds: tick / 20,
      loop: entrypoint.includes(`${name}/play_anim_loop`) ? "loop" : "once",
      loopDelaySeconds: 0,
      tracks,
      events: { start: [], timeline, loop: [], stop: [] },
    };
  });
}

function parseFrame(
  text: string,
  path: string,
  namespace: string,
  timeSeconds: number,
  tracks: ImportedAnimation["tracks"],
  events: ImportedTimelineEvent[],
  diagnostics: ImportDiagnostic[],
): void {
  for (const line of commandLines(text)) {
    if (line.includes("transformation:[") && line.startsWith("data merge entity ")) {
      const tag = /tag="?([a-z0-9_.-]+_\d+)"?(?:,|\])/.exec(line)?.[1];
      if (!tag || !tracks[tag]) {
        diagnostics.push({ severity: "error", code: "unknown_transform_target", message: `Could not resolve a transformation target in ${line}`, sourcePath: path });
        continue;
      }
      const duration = Number.parseInt(/interpolation_duration:(\d+)/.exec(line)?.[1] ?? "0", 10);
      const keyframe: ImportedTransformKeyframe = {
        timeSeconds,
        matrix: readMatrix(line, `${path}/${tag}`),
        interpolation: duration === 0 ? { type: "step" } : { type: "linear" },
      };
      tracks[tag].transforms.push(keyframe);
      continue;
    }
    if (STRUCTURAL_KEYFRAME_COMMAND.test(line)) continue;
    const event = parseRootEvent(line, namespace, timeSeconds);
    if (event) events.push(event);
    else diagnostics.push({ severity: "error", code: "unsupported_bd_command", message: `Unsupported BD keyframe command: ${line}`, sourcePath: path });
  }
}

function parseRootEvent(line: string, namespace: string, timeSeconds: number): ImportedTimelineEvent | null {
  const escaped = escapeRegExp(namespace);
  const match = new RegExp(`^execute as @e\\[tag=${escaped}_root,type=block_display\\] at @s positioned ~ ~(-?\\d+(?:\\.\\d+)?) ~ run (.+)$`).exec(line);
  if (!match) return null;
  const verticalOffset = Number.parseFloat(match[1]);
  let command = match[2];
  let forwardOffset = 0;
  const localParticle = /^(particle\s+\S+)\s+\^\s+\^\s+\^(-?\d+(?:\.\d+)?)\s+(.+)$/.exec(command);
  if (localParticle) {
    forwardOffset = Number.parseFloat(localParticle[2]);
    command = `${localParticle[1]} ~ ~ ~ ${localParticle[3]}`;
  } else if (command.includes("^")) {
    return null;
  }
  return {
    timeSeconds,
    source: { type: "server" },
    origin: { type: "root", offset: [0, verticalOffset, forwardOffset] },
    commands: [command],
  };
}

function readFrameDelayTicks(text: string, path: string): number {
  const match = /^schedule function .+? (\d+(?:\.\d+)?)([st])$/m.exec(text);
  if (!match) throw new Error(`BD keyframe does not schedule its next step: ${path}`);
  const value = Number.parseFloat(match[1]);
  const ticks = match[2] === "t" ? value : value * 20;
  if (!Number.isInteger(ticks) || ticks < 1) throw new Error(`BD keyframe delay is not a positive whole tick: ${path}`);
  return ticks;
}

function readMetadata(files: Map<string, Uint8Array>, namespace: string): { name: string; description: string; commandName: string; hidePlayer: boolean; entrypoint: string } {
  const data = files.get(`data/${namespace}/emote.json`);
  if (!data) return { name: namespace, description: `${namespace} emote.`, commandName: namespace, hidePlayer: true, entrypoint: "a/default/play_anim_loop" };
  const value = JSON.parse(decoder.decode(data)) as Record<string, unknown>;
  return {
    name: typeof value.name === "string" ? value.name : namespace,
    description: typeof value.description === "string" ? value.description : `${namespace} emote.`,
    commandName: typeof value.command_name === "string" ? value.command_name : namespace,
    hidePlayer: typeof value.hide_player === "boolean" ? value.hide_player : true,
    entrypoint: typeof value.entrypoint === "string" ? value.entrypoint : "a/default/play_anim_loop",
  };
}

function extractDisplayCompounds(text: string): string[] {
  const compounds: string[] = [];
  for (const match of text.matchAll(DISPLAY_START)) {
    compounds.push(text.slice(match.index, findMatchingSnbtDelimiter(text, match.index, "{", "}") + 1));
  }
  return compounds;
}

function readNodeTag(compound: string, namespace: string): string | null {
  const tags = readSnbtRawField(compound, "Tags");
  if (!tags) return null;
  const expected = new RegExp(`"(${escapeRegExp(namespace)}_\\d+)"`);
  return expected.exec(tags)?.[1] ?? null;
}

function readMatrix(text: string, label: string): Matrix16 {
  const raw = /transformation:\[(.*?)\]/s.exec(text)?.[1];
  if (!raw) return IDENTITY_MATRIX;
  return asMatrix16(raw.split(",").map((value) => Number.parseFloat(value.trim().replace(/[fd]$/i, ""))), label);
}

function remainingEntityNbt(compound: string): string | undefined {
  const owned = new Set(["id", "item", "item_display", "block_state", "text", "transformation", "interpolation_duration", "start_interpolation", "teleport_duration", "Tags", "Pos", "Rotation"]);
  return omitSnbtFields(compound, owned);
}

function parseTextComponent(raw: string | null): unknown {
  if (!raw) return { text: "" };
  try { return JSON.parse(raw); } catch { return { text: raw }; }
}

function commandLines(text: string): string[] {
  return text.split(/\r?\n/).map((line) => line.trim()).filter((line) => line && !line.startsWith("#"));
}

function isZip(bytes: Uint8Array): boolean {
  return bytes.length >= 4 && bytes[0] === 0x50 && bytes[1] === 0x4b;
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}
