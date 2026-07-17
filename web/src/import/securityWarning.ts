import type { ImportedProject } from "./types";

export function countImportedCommands(project: ImportedProject | null): number {
  if (!project) return 0;

  let commandCount = 0;
  for (const animation of project.animations) {
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
