import MolangParser from "molangjs/dist/molang.esm.js";
import { TICKS_PER_SECOND } from "../format/time";
import { ConversionError, PreviewUnavailableError } from "../foundation/diagnostics";
import { PREVIEW_RUNTIME_QUERY_VALUES, previewRuntimeQueryFunction, usesRuntimeMolangState } from "../format/molang/runtimeAnalysis";

export interface PreviewMolangContext {
  animationTime: number;
  keyframeLerpTime: number;
  lifeTime?: number;
}

const NUMERIC_LITERAL = /^[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?$/;
const NONDETERMINISTIC_FUNCTION = /math\.(?:random|random_integer|die_roll|die_roll_integer)\b/i;

export class PreviewMolangEvaluator {
  private readonly parser = new MolangParser();

  constructor() {
    this.parser.variableHandler = (key, _variables, args) => {
      if (args) {
        const value = previewRuntimeQueryFunction(key);
        if (value !== undefined) return value;
      }
      throw new Error(`references runtime Molang variable ${key}`);
    };
  }

  evaluate(expression: string | number, context: PreviewMolangContext): number {
    if (typeof expression === "number") return this.requireFinite(expression, expression);
    if (NUMERIC_LITERAL.test(expression.trim())) return this.requireFinite(Number(expression), expression);
    if (usesRuntimeMolangState(expression) || NONDETERMINISTIC_FUNCTION.test(expression)) throw this.error(expression);

    try {
      const variables: Record<string, number> = {
        ...PREVIEW_RUNTIME_QUERY_VALUES,
        "query.anim_time": context.animationTime,
        "q.anim_time": context.animationTime,
        "query.delta_time": 1 / TICKS_PER_SECOND,
        "q.delta_time": 1 / TICKS_PER_SECOND,
        "query.key_frame_lerp_time": context.keyframeLerpTime,
        "q.key_frame_lerp_time": context.keyframeLerpTime,
        "global.key_frame_lerp_time": context.keyframeLerpTime,
      };
      if (context.lifeTime !== undefined) {
        variables["query.life_time"] = context.lifeTime;
        variables["q.life_time"] = context.lifeTime;
      }
      return this.requireFinite(this.parser.parse(expression, variables), expression);
    } catch (error) {
      if (error instanceof ConversionError) throw error;
      throw this.error(expression, error);
    }
  }

  private requireFinite(value: number, expression: string | number): number {
    if (Number.isFinite(value)) return value;
    throw this.error(expression, new Error("result is not finite"));
  }

  private error(expression: string | number, cause?: unknown): PreviewUnavailableError {
    return new PreviewUnavailableError("animation_preview", `Expression uses its base component: ${expression}`, "preview", cause === undefined ? undefined : { cause });
  }
}
