import type { ImportAdapter, ImportAdapterLoader } from "./adapter";
import type { ImportSource } from "../domain/conversionSeed";

export const IMPORT_ADAPTERS: readonly ImportAdapterLoader[] = [
  lazyAdapter("emote_sequence", "Emote sequence", ["json"], "Migrates an existing Emote sequence JSON to the current schema and downloads it immediately.", () => import("./emoteJson/sequenceJsonAdapter").then((module) => module.sequenceJsonAdapter)),
  lazyAdapter("bd_datapack", "BD Engine", ["zip"], "In BD Engine, open Get Command and export the animation as a datapack.", () => import("./bdDatapack/bdDatapackAdapter").then((module) => module.bdDatapackAdapter)),
  lazyAdapter("animated_java_blueprint", "Animated Java", ["ajblueprint"], "Use the original Animated Java blueprint project. Model, animation, and skin data are imported.", () => import("./animatedJava/animatedJavaBlueprintAdapter").then((module) => module.animatedJavaBlueprintAdapter)),
  lazyAdapter("geckolib_bbmodel", "GeckoLib", ["bbmodel"], "Use the original GeckoLib Blockbench project. Model, animation, and skin data are imported.", () => import("./geckoLibBbmodel/geckoLibBbmodelAdapter").then((module) => module.geckoLibBbmodelAdapter)),
  lazyAdapter("bedrock_animation_json", "Bedrock Edition", ["json"], "Imports Bedrock player animation JSON. Unsupported Molang expressions may limit the preview.", () => import("./bedrockAnimation/bedrockAnimationAdapter").then((module) => module.bedrockAnimationAdapter)),
  lazyAdapter("emotecraft_binary", "Emotecraft", ["emotecraft"], "Imports an Emotecraft binary animation and maps its player pose to Emote nodes.", () => import("./emotecraft/emotecraftAdapter").then((module) => module.emotecraftAdapter)),
  lazyAdapter("emote_json", "Emote animation", ["json"], "Opens an existing Emote animation for review, settings changes, and re-export.", () => import("./emoteJson/emoteJsonAdapter").then((module) => module.emoteJsonAdapter)),
];

function lazyAdapter(
  id: ImportSource,
  label: string,
  extensions: readonly string[],
  description: string,
  load: () => Promise<ImportAdapter>,
): ImportAdapterLoader {
  return { id, label, extensions, description, load };
}
