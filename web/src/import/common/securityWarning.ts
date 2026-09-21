import type { ImportedAnimation, ImportedProject } from "../../domain/conversionSeed";
import type { ConversionAnimationEvents } from "../../domain/conversionDocument";

type CommandSource = ImportedProject | { animations: ReadonlyArray<{ source: ImportedAnimation; events: ConversionAnimationEvents }> };

export function countImportedCommands(project: CommandSource | null): number {
  if (!project) return 0;

  let commandCount = 0;
  for (const entry of project.animations) {
    const animation = "source" in entry ? entry.source : entry;
    const events = "source" in entry ? entry.events : animation.events;
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
