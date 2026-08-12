import type { ImportAdapter, ImportAdapterLoader } from "./adapter";
import type { ImportSource } from "./types";

export const IMPORT_ADAPTERS: readonly ImportAdapterLoader[] = [
  adapter("bd_project", "BD Engine project", ["bdengine"], () => import("./bdProject/bdProjectAdapter").then((module) => module.bdProjectAdapter)),
  adapter("bd_datapack", "BD Engine datapack", ["zip"], () => import("./bdDatapack/bdDatapackAdapter").then((module) => module.bdDatapackAdapter)),
  adapter("animated_java_json", "Animated Java plugin blueprint", ["ajblueprint", "json"], () => import("./animatedJava/animatedJavaJsonAdapter").then((module) => module.animatedJavaJsonAdapter)),
  adapter("geckolib_bbmodel", "GeckoLib Blockbench project", ["bbmodel"], () => import("./geckoLibBbmodel/geckoLibBbmodelAdapter").then((module) => module.geckoLibBbmodelAdapter)),
  adapter("emote_json", "Emote animation JSON", ["json"], () => import("./emoteJson/emoteJsonAdapter").then((module) => module.emoteJsonAdapter)),
];

function adapter(
  id: ImportSource,
  label: string,
  extensions: readonly string[],
  load: () => Promise<ImportAdapter>,
): ImportAdapterLoader {
  return { id, label, extensions, load };
}
