import type { ImportedAnimation, ImportedProject } from "../domain/conversionSeed";

type CommandSource = ImportedProject | { animations: ReadonlyArray<{ source: ImportedAnimation }> };

export function countImportedCommands(project: CommandSource | null): number {
  if (!project) return 0;

  let commandCount = 0;
  for (const entry of project.animations) {
    const animation = "source" in entry ? entry.source : entry;
    commandCount += animation.events.start.reduce(countEventCommands, 0);
    commandCount += animation.events.timeline.reduce(countEventCommands, 0);
    commandCount += animation.events.loop.reduce(countEventCommands, 0);
    commandCount += animation.events.stop.reduce(countEventCommands, 0);
  }
  return commandCount;
}

function countEventCommands(total: number, event: { commands: readonly string[] }): number {
  return total + event.commands.length;
}
