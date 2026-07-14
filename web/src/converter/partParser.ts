import type { LoadedDatapack } from "./packFileSystem";
import { isSkinPartId, type SkinPartId } from "./skinMapping";

const ITEM_DISPLAY_PATTERN = /\{id:"minecraft:item_display",item:\{(.*?)\},.*?Tags:\[[^\]]*?"([a-z0-9_.-]+)_(\d+)"[^\]]*?\]\}/gs;
const TRANSFORMATION_PATTERN = /transformation:\[(.*?)\]/s;
const CREATE_FUNCTION_PATTERN = /^data\/([^/]+)\/(function|functions)\/_\/create\.mcfunction$/;

export interface Vector3Value {
  x: number;
  y: number;
  z: number;
}

export interface PlayerHeadPart {
  partIndex: number;
  namespace: string;
  matrix: readonly number[];
  scale: Vector3Value;
  anchor: Vector3Value;
  existingAssignment: SkinPartId | null;
}

export interface ParsedEmoteModel {
  namespace: string;
  sourceTagNamespace: string;
  createFilePath: string;
  previewAnimation: string | null;
  parts: PlayerHeadPart[];
}

export function findEmoteModels(datapack: LoadedDatapack): ParsedEmoteModel[] {
  const decoder = new TextDecoder();
  const models: ParsedEmoteModel[] = [];

  for (const [path, data] of datapack.files) {
    const pathMatch = CREATE_FUNCTION_PATTERN.exec(path);
    if (!pathMatch) {
      continue;
    }

    const namespace = pathMatch[1];
    const createFunctionText = decoder.decode(data);
    const sourceTagNamespace = findEntityTagNamespace(createFunctionText);
    if (!sourceTagNamespace) continue;
    const parts = parsePlayerHeadParts(createFunctionText, sourceTagNamespace);
    if (parts.length > 0) {
      models.push(applyFirstAnimationFrame(datapack, {
        namespace,
        sourceTagNamespace,
        createFilePath: path,
        previewAnimation: null,
        parts,
      }));
    }
  }

  return models.sort((first, second) => first.namespace.localeCompare(second.namespace));
}

function applyFirstAnimationFrame(datapack: LoadedDatapack, model: ParsedEmoteModel): ParsedEmoteModel {
  const functionFolder = model.createFilePath.split("/")[2];
  const animationPrefix = `data/${model.namespace}/${functionFolder}/a/`;
  const animationNames = [...datapack.files.keys()]
    .filter((path) => path.startsWith(animationPrefix) && path.endsWith("/play_anim_loop.mcfunction"))
    .map((path) => path.slice(animationPrefix.length).split("/")[0])
    .sort();
  const decoder = new TextDecoder();

  for (const animationName of animationNames) {
    const framePath = `data/${model.namespace}/${functionFolder}/k/${animationName}/keyframe_0.mcfunction`;
    const frameData = datapack.files.get(framePath);
    if (!frameData) continue;

    const frameMatrices = parseKeyframeMatrices(decoder.decode(frameData), model.sourceTagNamespace);
    if (frameMatrices.size === 0) continue;
    return {
      ...model,
      previewAnimation: animationName,
      parts: model.parts.map((part) => {
        const matrix = frameMatrices.get(part.partIndex);
        return matrix ? createPlayerHeadPart(part.partIndex, part.namespace, matrix, part.existingAssignment) : part;
      }),
    };
  }

  return model;
}

export function parseKeyframeMatrices(keyframeText: string, namespace: string): Map<number, readonly number[]> {
  const matrices = new Map<number, readonly number[]>();
  for (const line of keyframeText.split(/\r?\n/)) {
    if (!line.includes("transformation:[")) continue;
    const targetMatch = /tag="?([a-z0-9_.-]+)_(\d+)"?(?:,|\])/.exec(line);
    if (!targetMatch || targetMatch[1] !== namespace) continue;
    matrices.set(Number.parseInt(targetMatch[2], 10), readTransformationValues(line));
  }
  return matrices;
}

function findEntityTagNamespace(createFunctionText: string): string | null {
  const namespaces = new Set<string>();
  for (const match of createFunctionText.matchAll(ITEM_DISPLAY_PATTERN)) {
    if (match[1].includes('id:"minecraft:player_head"')) namespaces.add(match[2]);
  }
  if (namespaces.size > 1) throw new Error("create.mcfunction에 서로 다른 조각 네임스페이스가 있습니다.");
  return [...namespaces][0] ?? null;
}

export function parsePlayerHeadParts(createFunctionText: string, namespace: string): PlayerHeadPart[] {
  const parts: PlayerHeadPart[] = [];

  for (const match of createFunctionText.matchAll(ITEM_DISPLAY_PATTERN)) {
    if (match[2] !== namespace || !match[1].includes('id:"minecraft:player_head"')) {
      continue;
    }

    parts.push(createPlayerHeadPart(
      Number.parseInt(match[3], 10),
      namespace,
      readTransformationValues(match[0]),
      readExistingAssignment(match[1]),
    ));
  }

  return parts.sort((first, second) => first.partIndex - second.partIndex);
}

function createPlayerHeadPart(
  partIndex: number,
  namespace: string,
  matrix: readonly number[],
  existingAssignment: SkinPartId | null,
): PlayerHeadPart {
  return {
    partIndex,
    namespace,
    matrix,
    scale: {
      x: axisLength(matrix, 0, 4, 8),
      y: axisLength(matrix, 1, 5, 9),
      z: axisLength(matrix, 2, 6, 10),
    },
    anchor: {
      x: matrix[3] + matrix[1] * 0.5,
      y: matrix[7] + matrix[5] * 0.5,
      z: matrix[11] + matrix[9] * 0.5,
    },
    existingAssignment,
  };
}

function readExistingAssignment(itemData: string): SkinPartId | null {
  const markerMatch = /name\s*:\s*"emote:([a-z_]+)"/.exec(itemData);
  return markerMatch && isSkinPartId(markerMatch[1]) ? markerMatch[1] : null;
}

export function readTransformationValues(itemDisplayText: string): readonly number[] {
  const transformationMatch = TRANSFORMATION_PATTERN.exec(itemDisplayText);
  if (!transformationMatch) {
    return [
      1, 0, 0, 0,
      0, 1, 0, 0,
      0, 0, 1, 0,
      0, 0, 0, 1,
    ];
  }

  const values = transformationMatch[1].split(",").map(parseMatrixNumber);
  if (values.length !== 16 || values.some((value) => !Number.isFinite(value))) {
    throw new Error("player_head 변환 행렬은 유효한 숫자 16개여야 합니다.");
  }
  return values;
}

function parseMatrixNumber(value: string): number {
  return Number.parseFloat(value.trim().replace(/[fd]$/i, ""));
}

function axisLength(values: readonly number[], first: number, second: number, third: number): number {
  return Math.hypot(values[first], values[second], values[third]);
}
