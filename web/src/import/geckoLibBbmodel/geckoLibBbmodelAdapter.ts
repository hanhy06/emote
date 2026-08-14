import type { ImportAdapter, ImportInput, ProbeResult } from "../adapter";
import { importBlockbenchCubeProject } from "../blockbench/cubeProjectImporter";
import { requireBlockbenchCubeProject } from "../blockbench/cubeProjectSchema";
import { parseInputJson } from "../inputCache";
import type { ImportedProject } from "../types";

export const geckoLibBbmodelAdapter: ImportAdapter = {
  id: "geckolib_bbmodel",
  label: "GeckoLib Blockbench project",
  extensions: ["bbmodel"],

  probe(input: ImportInput): ProbeResult {
    try {
      const value = parseInputJson(input) as { meta?: { model_format?: unknown } };
      return value.meta?.model_format === "geckolib_model"
        ? { confidence: 100, reason: "matches a GeckoLib Blockbench project" }
        : { confidence: 0, reason: "not a GeckoLib Blockbench project" };
    } catch {
      return { confidence: 0, reason: "not JSON" };
    }
  },

  async import(input: ImportInput): Promise<ImportedProject> {
    return importBlockbenchCubeProject(requireBlockbenchCubeProject(parseInputJson(input)), input.name);
  },
};
