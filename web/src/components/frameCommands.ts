import type { ImportedAnimation, ImportedTimelineEvent } from "../domain/conversionSeed";

export interface FrameCommand {
  eventIndex: number;
  commandIndex: number;
  command: string;
  event: ImportedTimelineEvent;
}

export function frameCommands(animation: ImportedAnimation, tick: number): FrameCommand[] {
  return animation.events.timeline.flatMap((event, eventIndex) => event.tick === tick
    ? event.commands.map((command, commandIndex) => ({ eventIndex, commandIndex, command, event }))
    : []);
}

export function addFrameCommand(animation: ImportedAnimation, tick: number): ImportedAnimation {
  const event: ImportedTimelineEvent = {
    tick,
    source: { type: "server" },
    origin: { type: "root" },
    commands: [""],
  };
  return withTimelineEvents(animation, [...animation.events.timeline, event]
    .sort((first, second) => first.tick - second.tick));
}

export function updateFrameCommand(
  animation: ImportedAnimation,
  eventIndex: number,
  commandIndex: number,
  command: string,
): ImportedAnimation {
  const timeline = animation.events.timeline.map((event, index) => index === eventIndex
    ? { ...event, commands: event.commands.map((current, currentIndex) => currentIndex === commandIndex ? command : current) }
    : event);
  return withTimelineEvents(animation, timeline);
}

export function removeFrameCommand(
  animation: ImportedAnimation,
  eventIndex: number,
  commandIndex: number,
): ImportedAnimation {
  const timeline = animation.events.timeline.flatMap((event, index) => {
    if (index !== eventIndex) return [event];
    const commands = event.commands.filter((_, currentIndex) => currentIndex !== commandIndex);
    return commands.length === 0 ? [] : [{ ...event, commands }];
  });
  return withTimelineEvents(animation, timeline);
}

function withTimelineEvents(animation: ImportedAnimation, timeline: ImportedTimelineEvent[]): ImportedAnimation {
  return { ...animation, events: { ...animation.events, timeline } };
}
