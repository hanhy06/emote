export interface PlayerHeadPart {
  nodeId: string;
  partIndex: number;
  matrix: readonly number[];
  conversionMatrix?: readonly number[];
}

export function createPlayerHeadPart(
  nodeId: string,
  partIndex: number,
  matrix: readonly number[],
  conversionMatrix?: readonly number[],
): PlayerHeadPart {
  return {
    nodeId,
    partIndex,
    matrix,
    ...(conversionMatrix ? { conversionMatrix } : {}),
  };
}
