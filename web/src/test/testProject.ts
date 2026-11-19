import { readFileSync } from "node:fs";

export const TEST_MINECRAFT_VERSION = readFileSync(new URL("../../../gradle.properties", import.meta.url), "utf8")
  .match(/^minecraft_version=(.+)$/m)![1].trim();
