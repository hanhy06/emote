export interface MinecraftVersionProfile {
  blockState: { idKey: string; propertiesKey: string };
  itemStack: { idKey: string; countKey: string; componentsKey: string };
}

const classic: MinecraftVersionProfile = {
  blockState: { idKey: "Name", propertiesKey: "Properties" },
  itemStack: { idKey: "id", countKey: "count", componentsKey: "components" },
};

export const MINECRAFT_VERSION_PROFILES: Readonly<Record<string, MinecraftVersionProfile>> = {
  "26.1": classic,
  "26.2": classic,
  // Block state fields changed in 26.3 Snapshot 7.
  "26.3": { ...classic, blockState: { idKey: "id", propertiesKey: "properties" } },
};

export function minecraftVersionProfile(version: string): MinecraftVersionProfile {
  const profile = Object.hasOwn(MINECRAFT_VERSION_PROFILES, version) ? MINECRAFT_VERSION_PROFILES[version] : undefined;
  if (!profile) throw new Error(`No output profile is registered for Minecraft ${version}.`);
  return profile;
}
