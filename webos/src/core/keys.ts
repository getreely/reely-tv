/**
 * The remote as the browser sees it. Arrows and OK arrive as the usual keys. On an LG TV,
 * Back is webOS's own 461 and the coloured and media keys have codes of their own; on a
 * Fire TV running Vega, Back is 27 ("GoBack") and its media keys are 179, 227 and 228.
 */
export type Action =
  | "up" | "down" | "left" | "right" | "ok" | "back"
  | "play" | "pause" | "playPause" | "stop" | "rewind" | "forward"
  | "channelUp" | "channelDown" | "info" | "red" | "green" | "yellow" | "blue"
  | { digit: number };

const CODES: Record<number, Action> = {
  37: "left", 38: "up", 39: "right", 40: "down",
  13: "ok",
  461: "back", 8: "back", 27: "back",
  415: "play", 19: "pause", 463: "playPause", 179: "playPause",
  413: "stop", 412: "rewind", 417: "forward", 227: "rewind", 228: "forward",
  33: "channelUp", 34: "channelDown",
  457: "info",
  403: "red", 404: "green", 405: "yellow", 406: "blue",
};

export function actionOf(event: Pick<KeyboardEvent, "keyCode" | "key">): Action | null {
  const code = event.keyCode;
  if (code >= 48 && code <= 57) return { digit: code - 48 };
  if (code >= 96 && code <= 105) return { digit: code - 96 };
  const known = CODES[code];
  if (known) return known;
  switch (event.key) {
    case "ArrowLeft": return "left";
    case "ArrowRight": return "right";
    case "ArrowUp": return "up";
    case "ArrowDown": return "down";
    case "Enter": return "ok";
    case "Escape": case "Backspace": case "GoBack": return "back";
    case "MediaPlayPause": return "playPause";
    case "MediaRewind": return "rewind";
    case "MediaFastForward": return "forward";
  }
  return null;
}

export const isDirection = (a: Action | null): a is "up" | "down" | "left" | "right" =>
  a === "up" || a === "down" || a === "left" || a === "right";
