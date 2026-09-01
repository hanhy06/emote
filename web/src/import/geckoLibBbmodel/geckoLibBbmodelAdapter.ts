import type { ImportAdapter, ImportInput, ProbeResult } from "../adapter";
import { importBlockbenchCubeProject } from "../blockbench/cubeProjectImporter";
import { requireBlockbenchCubeProject } from "../blockbench/cubeProjectSchema";
import { parseInputJson, probeParsedInput } from "../inputCache";
import type { ImportedProject } from "../../domain/conversionSeed";

export const geckoLibBbmodelAdapter: ImportAdapter<ImportedProject> = {
  id: "geckolib_bbmodel",
  label: "GeckoLib Blockbench project",
  extensions: ["bbmodel"],

  probe(input: ImportInput): ProbeResult {
    return probeParsedInput(input, parseInputJson, (value) => (value as { meta?: { model_format?: unknown } }).meta?.model_format === "geckolib_model"
        ? { confidence: 100, reason: "matches a GeckoLib Blockbench project" }
        : { confidence: 0, reason: "not a GeckoLib Blockbench project" }, "not JSON");
  },

  async import(input: ImportInput): Promise<ImportedProject> {
    return importBlockbenchCubeProject(requireBlockbenchCubeProject(parseInputJson(input)), input.name);
  },
};
