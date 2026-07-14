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
  createFilePath: string;
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
    const parts = parsePlayerHeadParts(decoder.decode(data), namespace);
    if (parts.length > 0) {
      models.push({ namespace, createFilePath: path, parts });
    }
  }

  return models.sort((first, second) => first.namespace.localeCompare(second.namespace));
}

export function parsePlayerHeadParts(createFunctionText: string, namespace: string): PlayerHeadPart[] {
  const parts: PlayerHeadPart[] = [];

  for (const match of createFunctionText.matchAll(ITEM_DISPLAY_PATTERN)) {
    if (match[2] !== namespace || !match[1].includes('id:"minecraft:player_head"')) {
      continue;
    }

    const matrix = readTransformationValues(match[0]);
    parts.push({
      partIndex: Number.parseInt(match[3], 10),
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
      existingAssignment: readExistingAssignment(match[1]),
    });
  }

  return parts.sort((first, second) => first.partIndex - second.partIndex);
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
