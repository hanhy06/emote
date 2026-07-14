import JSZip from "jszip";
import type { LoadedDatapack } from "./packFileSystem";
import type { ParsedEmoteModel } from "./partParser";
import { applySkinMarkers } from "./markerWriter";
import type { PartAssignments } from "./skinMapping";

const encoder = new TextEncoder();
const decoder = new TextDecoder();

export interface ConversionOptions {
  name: string;
  description: string;
  commandName: string;
  hidePlayer: boolean;
}

export interface ConvertedTarget {
  namespace: string;
  entrypoint: string;
}

export interface ConversionResult {
  blob: Blob;
  fileName: string;
  targets: ConvertedTarget[];
}

export async function convertDatapack(
  datapack: LoadedDatapack,
  models: ParsedEmoteModel[],
  assignments: Record<string, PartAssignments>,
  options: ConversionOptions,
): Promise<ConversionResult> {
  validateOptions(options);
  const files = cloneFiles(datapack.files);

  for (const model of models) {
    const originalText = readText(files, model.createFilePath);
    files.set(
      model.createFilePath,
      encoder.encode(applySkinMarkers(originalText, model.sourceTagNamespace, assignments[model.namespace] ?? {})),
    );
    normalizeNamespaceContents(files, model.namespace, model.sourceTagNamespace, model.namespace);
  }

  const targets = models.flatMap((model) => splitAnimationNamespace(files, model));
  writeMetadata(files, targets, options);

  const zip = new JSZip();
  for (const [path, data] of [...files].sort(([first], [second]) => first.localeCompare(second))) {
    zip.file(path, data);
  }
  const bytes = await zip.generateAsync({ type: "uint8array", compression: "DEFLATE" });
  const zipBuffer = bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer;
  return {
    blob: new Blob([zipBuffer], { type: "application/zip" }),
    fileName: `emote.${outputStem(datapack.fileName)}.zip`,
    targets,
  };
}

function splitAnimationNamespace(files: Map<string, Uint8Array>, model: ParsedEmoteModel): ConvertedTarget[] {
  const functionFolder = model.createFilePath.split("/")[2];
  const animationPrefix = `data/${model.namespace}/${functionFolder}/a/`;
  const animationNames = new Set<string>();
  for (const path of files.keys()) {
    if (!path.startsWith(animationPrefix) || !path.endsWith("/play_anim_loop.mcfunction")) continue;
    const relativePath = path.slice(animationPrefix.length);
    const [animationName, ...rest] = relativePath.split("/");
    if (rest.join("/") === "play_anim_loop.mcfunction") animationNames.add(animationName);
  }

  const sortedNames = [...animationNames].sort();
  if (sortedNames.length <= 1) {
    return [{
      namespace: model.namespace,
      entrypoint: sortedNames.length === 1 ? `a/${sortedNames[0]}/play_anim_loop` : "a/default/play_anim_loop",
    }];
  }

  const targets: ConvertedTarget[] = [];
  sortedNames.forEach((animationName, index) => {
    const targetNamespace = `${model.namespace}_${index + 1}`;
    if (hasNamespace(files, targetNamespace)) {
      throw new Error(`생성할 네임스페이스가 이미 존재합니다: ${targetNamespace}`);
    }
    copyNamespace(files, model.namespace, targetNamespace);
    normalizeNamespaceContents(files, targetNamespace, model.namespace, targetNamespace);
    removeOtherAnimations(files, targetNamespace, functionFolder, animationName);
    targets.push({ namespace: targetNamespace, entrypoint: `a/${animationName}/play_anim_loop` });
  });
  removeNamespace(files, model.namespace);
  return targets;
}

function writeMetadata(files: Map<string, Uint8Array>, targets: ConvertedTarget[], options: ConversionOptions): void {
  const multiple = targets.length > 1;
  targets.forEach((target, index) => {
    const suffix = index + 1;
    const metadata = {
      schema_version: 3,
      name: multiple ? `${options.name} ${suffix}` : options.name,
      description: options.description,
      command_name: multiple ? `${sanitizeCommandName(options.commandName)}_${suffix}` : sanitizeCommandName(options.commandName),
      entrypoint: target.entrypoint,
      hide_player: options.hidePlayer,
    };
    files.set(`data/${target.namespace}/emote.json`, encoder.encode(`${JSON.stringify(metadata, null, 2)}\n`));
  });
  files.delete("emote-datapack.json");
}

function normalizeNamespaceContents(
  files: Map<string, Uint8Array>,
  namespacePath: string,
  sourceNamespace: string,
  targetNamespace: string,
): void {
  if (sourceNamespace === targetNamespace) return;
  const prefix = `data/${namespacePath}/`;
  const pattern = new RegExp(`(?<![a-z0-9_.-])${escapeRegExp(sourceNamespace)}(?=[:_]|(?![a-z0-9_.-]))`, "g");
  for (const [path, data] of files) {
    if (path.startsWith(prefix) && path.endsWith(".mcfunction")) {
      files.set(path, encoder.encode(decoder.decode(data).replace(pattern, targetNamespace)));
    }
  }
}

function copyNamespace(files: Map<string, Uint8Array>, sourceNamespace: string, targetNamespace: string): void {
  const sourcePrefix = `data/${sourceNamespace}/`;
  const targetPrefix = `data/${targetNamespace}/`;
  for (const [path, data] of [...files]) {
    if (path.startsWith(sourcePrefix)) {
      files.set(targetPrefix + path.slice(sourcePrefix.length), data.slice());
    }
  }
}

function removeOtherAnimations(
  files: Map<string, Uint8Array>,
  namespace: string,
  functionFolder: string,
  selectedAnimation: string,
): void {
  for (const path of [...files.keys()]) {
    for (const folder of ["a", "k"]) {
      const prefix = `data/${namespace}/${functionFolder}/${folder}/`;
      if (!path.startsWith(prefix)) continue;
      const animationName = path.slice(prefix.length).split("/")[0];
      if (animationName !== selectedAnimation) files.delete(path);
    }
  }
}

function hasNamespace(files: Map<string, Uint8Array>, namespace: string): boolean {
  const prefix = `data/${namespace}/`;
  return [...files.keys()].some((path) => path.startsWith(prefix));
}

function removeNamespace(files: Map<string, Uint8Array>, namespace: string): void {
  const prefix = `data/${namespace}/`;
  for (const path of [...files.keys()]) {
    if (path.startsWith(prefix)) files.delete(path);
  }
}

function cloneFiles(files: Map<string, Uint8Array>): Map<string, Uint8Array> {
  return new Map([...files].map(([path, data]) => [path, data.slice()]));
}

function readText(files: Map<string, Uint8Array>, path: string): string {
  const data = files.get(path);
  if (!data) throw new Error(`필수 파일이 없습니다: ${path}`);
  return decoder.decode(data);
}

function validateOptions(options: ConversionOptions): void {
  if (!options.name.trim() || !options.description.trim() || !options.commandName.trim()) {
    throw new Error("이름, 설명, 명령어 이름을 모두 입력하세요.");
  }
}

export function sanitizeCommandName(value: string): string {
  return value.toLowerCase().replace(/[^a-z0-9_-]+/g, "_").replace(/^_+|_+$/g, "") || "emote";
}

function outputStem(fileName: string): string {
  return sanitizeCommandName(fileName.replace(/\.zip$/i, "").replace(/^emote\./i, ""));
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}
