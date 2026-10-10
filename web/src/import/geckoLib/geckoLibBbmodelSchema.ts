import type { BlockbenchCubeProject } from "../common/blockbenchCubeSchema";

export interface GeckoLibBbmodelProject extends BlockbenchCubeProject {
  meta: { format_version: string; model_format: string };
  geckolib_modid?: string;
}
