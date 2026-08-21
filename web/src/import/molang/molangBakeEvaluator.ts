import MolangParser from "molangjs/dist/molang.esm.js";
import { TICKS_PER_SECOND } from "../../format/time";
import { findUnsupportedMolangFunction } from "../../format/molangFunctions";
import { ConversionError } from "../../foundation/diagnostics";
import { PREVIEW_PLAYER_STATE_QUERIES } from "../runtimeMolangQueries";

export interface MolangBakeContext {
  animationTime: number;
  keyframeLerpTime: number;
  lifeTime?: number;
  deltaTime?: number;
}

export interface MolangBakeError {
  code: string;
  message(expression: string, path: string): string;
  nondeterministicMessage?(expression: string, path: string): string;
}

export interface MolangBakeOptions {
  error: MolangBakeError;
  rejectNondeterministic?: boolean;
}

const NUMERIC_LITERAL = /^[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?$/;
const NONDETERMINISTIC_FUNCTION = /math\.(?:random|random_integer|die_roll|die_roll_integer)\b/i;
export function requireSupportedMolangFunctions(expression: string, path: string): void {
  const functionName = findUnsupportedMolangFunction(expression);
  if (!functionName) return;
  throw new ConversionError(
    "unsupported_molang_function",
    `Molang function ${functionName} is not supported by the Emote mod.`,
    path,
  );
}

export class MolangBakeEvaluator {
  private readonly parser = new MolangParser();

  constructor(private readonly options: MolangBakeOptions) {
    this.parser.variableHandler = (key) => {
      throw new Error(`references runtime Molang variable ${key}`);
    };
  }

  evaluate(expression: string | number, context: MolangBakeContext, path: string): number {
    if (typeof expression === "number") return this.requireFinite(expression, expression, path);
    if (NUMERIC_LITERAL.test(expression.trim())) return this.requireFinite(Number(expression), expression, path);
    requireSupportedMolangFunctions(expression, path);
    if (this.options.rejectNondeterministic && NONDETERMINISTIC_FUNCTION.test(expression)) {
      const message = this.options.error.nondeterministicMessage?.(expression, path)
        ?? this.options.error.message(expression, path);
      throw new ConversionError(this.options.error.code, message, path);
    }

    try {
      const variables: Record<string, number> = {
        ...PREVIEW_PLAYER_STATE_QUERIES,
        "query.anim_time": context.animationTime,
        "q.anim_time": context.animationTime,
        "query.delta_time": context.deltaTime ?? 1 / TICKS_PER_SECOND,
        "q.delta_time": context.deltaTime ?? 1 / TICKS_PER_SECOND,
        "query.key_frame_lerp_time": context.keyframeLerpTime,
        "q.key_frame_lerp_time": context.keyframeLerpTime,
        "global.key_frame_lerp_time": context.keyframeLerpTime,
      };
      if (context.lifeTime !== undefined) {
        variables["query.life_time"] = context.lifeTime;
        variables["q.life_time"] = context.lifeTime;
      }
      return this.requireFinite(this.parser.parse(expression, variables), expression, path);
    } catch (error) {
      if (error instanceof ConversionError) throw error;
      throw new ConversionError(this.options.error.code, this.options.error.message(expression, path), path, { cause: error });
    }
  }

  private requireFinite(value: number, expression: string | number, path: string): number {
    if (Number.isFinite(value)) return value;
    throw new ConversionError(
      this.options.error.code,
      this.options.error.message(String(expression), path),
      path,
      { cause: new Error("result is not finite") },
    );
  }
}
