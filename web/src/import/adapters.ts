import type { ImportAdapter, ImportAdapterLoader } from "./adapter";
import type { ImportSource } from "./types";

export const IMPORT_ADAPTERS: readonly ImportAdapterLoader[] = [
  lazyAdapter("bd_project", "BD Engine project", ["bdengine"], () => import("./bdProject/bdProjectAdapter").then((module) => module.bdProjectAdapter)),
  lazyAdapter("bd_datapack", "BD Engine datapack", ["zip"], () => import("./bdDatapack/bdDatapackAdapter").then((module) => module.bdDatapackAdapter)),
  lazyAdapter("animated_java_json", "Animated Java project", ["ajblueprint", "json"], () => import("./animatedJava/animatedJavaJsonAdapter").then((module) => module.animatedJavaJsonAdapter)),
  lazyAdapter("geckolib_bbmodel", "GeckoLib Blockbench project", ["bbmodel"], () => import("./geckoLibBbmodel/geckoLibBbmodelAdapter").then((module) => module.geckoLibBbmodelAdapter)),
  lazyAdapter("emote_json", "Emote animation JSON", ["json"], () => import("./emoteJson/emoteJsonAdapter").then((module) => module.emoteJsonAdapter)),
];

function lazyAdapter(
  id: ImportSource,
  label: string,
  extensions: readonly string[],
  load: () => Promise<ImportAdapter>,
): ImportAdapterLoader {
  return { id, label, extensions, load };
}
