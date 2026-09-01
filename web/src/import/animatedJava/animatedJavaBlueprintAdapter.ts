import type { ImportedProject } from "../../domain/conversionSeed";
import type { ImportAdapter, ImportInput, ProbeResult } from "../adapter";
import { parseInputJson, probeParsedInput } from "../inputCache";
import { importAnimatedJavaProject } from "./animatedJavaProjectImporter";
import { isAnimatedJavaProject, requireAnimatedJavaProject } from "./animatedJavaProjectSchema";

export const animatedJavaBlueprintAdapter: ImportAdapter<ImportedProject> = {
  id: "animated_java_blueprint",
  label: "Animated Java project",
  extensions: ["ajblueprint"],

  probe(input: ImportInput): ProbeResult {
    return probeParsedInput(input, parseInputJson, (value) => isAnimatedJavaProject(value)
        ? { confidence: 100, reason: "matches an Animated Java blueprint project" }
        : { confidence: 0, reason: "does not match an Animated Java blueprint project" }, "not JSON");
  },

  async import(input: ImportInput): Promise<ImportedProject> {
    const project = requireAnimatedJavaProject(parseInputJson(input));
    return importAnimatedJavaProject(input, project);
  },
};
