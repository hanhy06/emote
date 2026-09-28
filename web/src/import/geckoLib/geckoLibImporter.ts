import type { ImportedProject } from "../../domain/conversionSeed";
import { createDefaultPlayerBehavior } from "../../format/emoteAnimation";
import { ConversionError } from "../../foundation/diagnostics";
import { importBlockbenchCubeContent } from "../common/blockbenchCubeImporter";
import type { GeckoLibBbmodelProject } from "./geckoLibBbmodelSchema";
import { createGeckoLibRuntime } from "./geckoLibRuntime";
import { GECKOLIB_BBMODEL_TRANSFORMS } from "./geckoLibCubeTransform";
import { GECKOLIB_CHANNELS } from "./geckoLibAnimationPolicy";

export function importGeckoLibProject(project: GeckoLibBbmodelProject, sourceName: string): ImportedProject {
  if (project.meta.model_format !== "geckolib_model") throw new Error(`Unsupported Blockbench model format: ${project.meta.model_format}`);
  if (project.elements.some((element) => element.type && element.type !== "cube" && element.type !== "locator")) {
    throw new ConversionError("unsupported_geckolib_element", "GeckoLib meshes and non-cube elements are not supported.", "elements");
  }

  const imported = importBlockbenchCubeContent(project, sourceName, {
    transforms: GECKOLIB_BBMODEL_TRANSFORMS,
    formatLabel: "GeckoLib",
    diagnosticPrefix: "geckolib",
    runtimeSceneId: "geckolib_scene",
    channels: GECKOLIB_CHANNELS,
    createNativeRuntime: createGeckoLibRuntime,
    namespace: project.geckolib_modid,
  });
  return {
    source: "geckolib_bbmodel",
    sourceName,
    suggestedMetadata: { name: imported.sourceStem, description: `${imported.sourceStem} emote.` },
    suggestedPlayer: createDefaultPlayerBehavior(),
    suggestedNamespace: imported.namespace,
    nodes: imported.nodes,
    animations: imported.animations,
    diagnostics: imported.diagnostics,
    resources: imported.resources,
  };
}
