import supportedMathFunctions from "../../../molang-functions.json";

const FUNCTION_CALL = /\b([a-z_][a-z0-9_.]*)\s*\(/gi;
const SUPPORTED_MATH_FUNCTIONS = new Set(Object.keys(supportedMathFunctions));

export function findUnsupportedMolangFunction(expression: string): string | undefined {
  for (const match of expression.matchAll(FUNCTION_CALL)) {
    const functionName = match[1].toLowerCase();
    if (functionName === "loop") continue;
    if (functionName.startsWith("math.") && SUPPORTED_MATH_FUNCTIONS.has(functionName.slice("math.".length))) continue;
    return functionName;
  }
  return undefined;
}
