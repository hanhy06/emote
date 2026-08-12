export const TICKS_PER_SECOND = 20;
export const MAX_ANIMATION_DURATION_TICKS = TICKS_PER_SECOND * 60 * 10;

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
