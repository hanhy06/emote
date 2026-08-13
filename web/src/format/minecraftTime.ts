const JAVA_INT_MAX = 2_147_483_647;
const TIME_PATTERN = /^(?:\d+(?:\.\d*)?|\.\d+)([dst]?)$/;

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
