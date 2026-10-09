export const RESOURCE_LOCATION_PATTERN = /^([a-z0-9_.-]+):([a-z0-9_./-]+)$/;

export function isResourceLocation(value: string): boolean {
  return RESOURCE_LOCATION_PATTERN.test(value);
}

export function normalizeResourceLocation(value: string, defaultNamespace = "minecraft"): string {
  const normalized = value.trim().toLowerCase();
  return normalized.includes(":") ? normalized : `${defaultNamespace}:${normalized}`;
}

export function sanitizeResourcePath(value: string, fallback = "emote"): string {
  return value.toLowerCase().replace(/[^a-z0-9_./-]+/g, "_").replace(/^_+|_+$/g, "") || fallback;
}

export function sanitizeNamespace(value: string, fallback = "emote"): string {
  return value.toLowerCase().replace(/[^a-z0-9_.-]+/g, "_").replace(/^_+|_+$/g, "") || fallback;
}
