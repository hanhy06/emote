import type { ImportAdapter, ImportAdapterLoader } from "./adapter";
import type { ImportSource } from "../domain/conversionSeed";

export const IMPORT_ADAPTERS: readonly ImportAdapterLoader[] = [
  lazyAdapter("bd_datapack", "BD Engine datapack", ["zip"], () => import("./bdDatapack/bdDatapackAdapter").then((module) => module.bdDatapackAdapter)),
  lazyAdapter("animated_java_blueprint", "Animated Java project", ["ajblueprint"], () => import("./animatedJava/animatedJavaBlueprintAdapter").then((module) => module.animatedJavaBlueprintAdapter)),
  lazyAdapter("geckolib_bbmodel", "GeckoLib Blockbench project", ["bbmodel"], () => import("./geckoLib/geckoLibBbmodelAdapter").then((module) => module.geckoLibBbmodelAdapter)),
  lazyAdapter("bedrock_animation_json", "Bedrock player animation JSON", ["json"], () => import("./bedrockAnimation/bedrockAnimationAdapter").then((module) => module.bedrockAnimationAdapter)),
  lazyAdapter("emotecraft_binary", "Emotecraft binary", ["emotecraft"], () => import("./emotecraft/emotecraftAdapter").then((module) => module.emotecraftAdapter)),
];

function lazyAdapter(
  id: ImportSource,
  label: string,
  extensions: readonly string[],
  load: () => Promise<ImportAdapter>,
): ImportAdapterLoader {
  return { id, label, extensions, load };
}
