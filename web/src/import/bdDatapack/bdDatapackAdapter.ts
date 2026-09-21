import type { ImportedProject } from "../../domain/conversionSeed";
import type { ImportAdapter, ImportInput, ProbeResult } from "../adapter";
import { projectBdDatapack } from "./bdDatapackProjector";
import { readBdDatapackSource } from "./bdDatapackSource";

export const bdDatapackAdapter: ImportAdapter<ImportedProject> = {
  id: "bd_datapack",
  label: "BD Engine datapack",
  extensions: ["zip"],

  probe(input: ImportInput): ProbeResult {
    try {
      readBdDatapackSource(input);
      return { confidence: 100, reason: "contains a BD Engine create function and animation keyframes" };
    } catch {
      return { confidence: 0, reason: "not a BD Engine datapack" };
    }
  },

  async import(input: ImportInput): Promise<ImportedProject> {
    return projectBdDatapack(readBdDatapackSource(input), input.name);
  },
};
