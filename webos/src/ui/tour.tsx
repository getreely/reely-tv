import { useEffect, useState } from "preact/hooks";
import { onKeys } from "./focus";
import { Pill, useRescue, useReturnFocus } from "./parts";

/*
 * How to get around with the remote, a step at a time, as the Fire TV's tour: what the
 * buttons do where it isn't obvious. Written from what this app does on an LG, so it
 * can't promise what isn't so.
 */

export const TOUR: Array<{ title: string; body: string; key: string | null }> = [
  { title: "Welcome to Reely", body: "A quick look at getting around with your remote. It takes a minute, and you can skip it.", key: null },
  {
    title: "The top row",
    body: "Press Up to reach the tabs: Search, Home, Movies, TV Shows, Live TV and Request. Settings is the gear on the right. Moving onto a tab opens it.",
    key: "Up",
  },
  {
    title: "Hold OK for more",
    body: "Hold OK on any poster for more: carry on or start again, mark it watched, or go to its page. On a show, it plays the next episode.",
    key: "Hold OK",
  },
  {
    title: "While you watch",
    body: "Left and Right skip back and forward, with a picture of where you'll land; hold them to go faster. Down brings up subtitles, sound, chapters and the sleep timer. Back puts them away.",
    key: "Left · Right",
  },
  {
    title: "Live TV",
    body: "Left and Right change channel, and Down opens the guide. In the guide, OK on something to come reminds you when it starts. The green key adds a channel to Favorites; yellow starts a programme over.",
    key: "Down",
  },
  {
    title: "Can't find something?",
    body: "The Request tab finds movies and shows your server doesn't have yet and asks for them. The first time, enter your Reely address there.",
    key: null,
  },
  { title: "You're all set", body: "You can take this tour again from Settings, under About.", key: null },
];

export function Tour(props: { onDone: () => void }) {
  const [step, setStep] = useState(0);
  const last = step === TOUR.length - 1;
  useRescue([step]);
  useReturnFocus();
  useEffect(() => onKeys((a) => {
    if (a !== "back") return false;
    if (step === 0) props.onDone(); else setStep(step - 1);
    return true;
  }), [step]);
  const s = TOUR[step];
  return (
    <div class="layer" data-layer>
      <div class="panel tour">
        <div class="facts">{step + 1} of {TOUR.length}</div>
        <h1 class="big">{s.title}</h1>
        {s.key ? <div class="tour-key">{s.key}</div> : null}
        <p class="note">{s.body}</p>
        <div class="actions">
          <Pill key={`next${step}`} label={last ? "Done" : "Next"} primary autofocus onPress={() => (last ? props.onDone() : setStep(step + 1))} />
          {step > 0 ? <Pill label="Back" onPress={() => setStep(step - 1)} /> : null}
          {!last ? <Pill label="Skip tour" onPress={props.onDone} /> : null}
        </div>
      </div>
    </div>
  );
}
