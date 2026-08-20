export interface ConversionIssue {
  severity: "warning" | "error";
  code: string;
  message: string;
  sourcePath?: string;
}

export interface ConversionIssueGroup {
  code: string;
  label: string;
  issues: ConversionIssue[];
}

export function groupConversionWarnings(issues: readonly ConversionIssue[]): ConversionIssueGroup[] {
  const groups = new Map<string, ConversionIssue[]>();
  for (const issue of issues) {
    if (issue.severity !== "warning") continue;
    const group = groups.get(issue.code);
    if (group) group.push(issue);
    else groups.set(issue.code, [issue]);
  }
  return [...groups].map(([code, groupedIssues]) => ({
    code,
    label: code.split("_").map((word) => word === "molang" ? "Molang" : word[0].toUpperCase() + word.slice(1)).join(" "),
    issues: groupedIssues,
  }));
}

export class ConversionError extends Error {
  readonly code: string;
  readonly sourcePath?: string;

  constructor(code: string, message: string, sourcePath?: string, options?: ErrorOptions) {
    super(message, options);
    this.name = "ConversionError";
    this.code = code;
    this.sourcePath = sourcePath;
  }

  static fromIssue(issue: ConversionIssue): ConversionError {
    return new ConversionError(issue.code, issue.message, issue.sourcePath);
  }

  static fromUnknown(reason: unknown, code: string, fallbackMessage: string, sourcePath?: string): ConversionError {
    if (reason instanceof ConversionError) return reason;
    return new ConversionError(code, reason instanceof Error ? reason.message : fallbackMessage, sourcePath, {
      cause: reason,
    });
  }
}

export function conversionErrorMessage(reason: unknown, fallbackMessage: string): string {
  if (!(reason instanceof Error)) return fallbackMessage;
  if (reason instanceof ConversionError && reason.sourcePath) return `${reason.message} (${reason.sourcePath})`;
  return reason.message;
}
