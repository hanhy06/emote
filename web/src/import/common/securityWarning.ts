import type { ImportedProject } from "../../domain/conversionSeed";
import type { ConversionDocument } from "../../domain/conversionDocument";

type CommandSource = ImportedProject | ConversionDocument;

export function countImportedCommands(project: CommandSource | null): number {
  if (!project) return 0;

  let commandCount = 0;
  for (const entry of project.animations) {
    const events = entry.events;
    commandCount += events.start.reduce(countEventCommands, 0);
    commandCount += events.timeline.reduce(countEventCommands, 0);
    commandCount += events.loop.reduce(countEventCommands, 0);
    commandCount += events.stop.reduce(countEventCommands, 0);
  }
  return commandCount;
}

function countEventCommands(total: number, event: { commands: readonly string[] }): number {
  return total + event.commands.length;
}
