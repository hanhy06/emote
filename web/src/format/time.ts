export const TICKS_PER_SECOND = 20;
export const MAX_ANIMATION_DURATION_TICKS = TICKS_PER_SECOND * 60 * 10;

const JAVA_INT_MAX = 2_147_483_647;
const TIME_PATTERN = /^(?:\d+(?:\.\d*)?|\.\d+)([dst]?)$/;

export function secondsToTicks(seconds: number, label: string): number {
  const ticks = seconds * TICKS_PER_SECOND;
  const rounded = Math.round(ticks);
  if (!Number.isFinite(seconds) || seconds < 0 || Math.abs(ticks - rounded) > 1e-7) {
    throw new Error(`${label} does not fall on a ${TICKS_PER_SECOND} TPS tick: ${seconds}`);
  }
  return rounded;
}

export function requireTick(tick: number, label: string): number {
  if (!Number.isInteger(tick) || tick < 0) throw new Error(`${label} must be a non-negative integer tick: ${tick}`);
  return tick;
}

export function requireAnimationDurationTicks(ticks: number, label: string): number {
  if (!Number.isInteger(ticks) || ticks <= 0 || ticks > MAX_ANIMATION_DURATION_TICKS) {
    throw new Error(`${label} must be between 1 and ${MAX_ANIMATION_DURATION_TICKS} ticks.`);
  }
  return ticks;
}

export function parseMinecraftTime(value: string, minimumTicks = 0): number {
  const match = TIME_PATTERN.exec(value);
  if (!match) throw new Error("must be a Minecraft time string using d, s, t, or bare ticks");
  const number = Number.parseFloat(value);
  const multiplier = match[1] === "d" ? 24_000 : match[1] === "s" ? 20 : 1;
  const ticks = Math.floor(number * multiplier + 0.5);
  if (!Number.isSafeInteger(ticks) || ticks < minimumTicks || ticks > JAVA_INT_MAX) {
    throw new Error(`must resolve to ${minimumTicks}..${JAVA_INT_MAX} ticks`);
  }
  return ticks;
}

export function formatMinecraftTime(ticks: number): string {
  if (!Number.isInteger(ticks) || ticks < 0 || ticks > JAVA_INT_MAX) {
    throw new Error("ticks must be a non-negative Java integer");
  }
  return `${ticks}t`;
}
