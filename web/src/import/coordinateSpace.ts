/**
 * Canonical emote transforms use Minecraft/JOML axes, blocks, right-handed rotations, and -Z model forward.
 * Bedrock JSON reflects X and expresses X/Y Euler rotations with the opposite sign at this boundary.
 */
export function bedrockPositionToCanonical<T>(values: readonly T[], negate: (value: T) => T): [T, T, T] {
  return [negate(values[0]), values[1], values[2]];
}

export function bedrockRotationToCanonical<T>(values: readonly T[], negate: (value: T) => T): [T, T, T] {
  return [negate(values[0]), negate(values[1]), values[2]];
}

export function bedrockBoundsToCanonical(
  from: readonly number[],
  to: readonly number[],
): { from: [number, number, number]; to: [number, number, number] } {
  return {
    from: [-to[0], from[1], from[2]],
    to: [-from[0], to[1], to[2]],
  };
}

export const blockbenchPositionToCanonical = bedrockPositionToCanonical;
export const blockbenchRotationToCanonical = bedrockRotationToCanonical;
export const blockbenchBoundsToCanonical = bedrockBoundsToCanonical;
