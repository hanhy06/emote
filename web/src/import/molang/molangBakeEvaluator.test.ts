import { describe, expect, it } from "vitest";
import { ConversionError } from "../../foundation/diagnostics";
import { MolangBakeEvaluator } from "./molangBakeEvaluator";

function evaluator(rejectNondeterministic = false): MolangBakeEvaluator {
  return new MolangBakeEvaluator({
    rejectNondeterministic,
    error: {
      code: "unsupported_test_molang",
      message: (expression, path) => `${path} cannot bake ${expression}`,
      nondeterministicMessage: (expression, path) => `${path} is nondeterministic: ${expression}`,
    },
  });
}

describe("MolangBakeEvaluator", () => {
  it("evaluates numeric literals and shared time aliases", () => {
    const context = { animationTime: 2, lifeTime: 3, keyframeLerpTime: 0.25 };

    expect(evaluator().evaluate(" 1.5e1 ", context, "numeric")).toBe(15);
    expect(evaluator().evaluate(
      "q.anim_time + query.life_time + global.key_frame_lerp_time + q.delta_time",
      context,
      "aliases",
    )).toBeCloseTo(5.3);
  });

  it("supplies preview player-state queries", () => {
    expect(evaluator().evaluate("q.ground_speed + query.is_sneaking + q.is_on_ground", {
      animationTime: 0,
      keyframeLerpTime: 0,
    }, "queries")).toBe(1);
  });

  it("wraps runtime variables and non-finite results in the configured conversion error", () => {
    for (const expression of ["v.speed", "1 / 0"]) {
      try {
        evaluator().evaluate(expression, { animationTime: 0, keyframeLerpTime: 0 }, "animation.position");
        expect.fail("Expected Molang evaluation to fail");
      } catch (error) {
        expect(error).toBeInstanceOf(ConversionError);
        expect(error).toMatchObject({ code: "unsupported_test_molang", sourcePath: "animation.position" });
      }
    }
  });

  it.each([
    "health", "max_health", "is_alive", "is_spectator",
    "head_is_in_water", "is_in_lava", "is_in_water_or_rain",
    "hurt_time", "death_ticks", "invulnerable_ticks", "player_level",
    "item_in_use_duration", "item_remaining_use_duration", "item_max_use_duration",
  ])("bakes %s with both query prefixes and synthetic state", (name) => {
    expect(evaluator().evaluate(`q.${name} + query.${name} + 1`, {
      animationTime: 0,
      keyframeLerpTime: 0,
    }, "queries")).toBe(1);
  });

  it("can reject nondeterministic functions before evaluation", () => {
    expect(() => evaluator(true).evaluate("math.random(0, 1)", {
      animationTime: 0,
      keyframeLerpTime: 0,
    }, "animation.position")).toThrow("animation.position is nondeterministic");
  });
});
