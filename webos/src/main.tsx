import { render } from "preact";
import { App } from "./app/store";
import { installKeys } from "./ui/focus";
import { watchForProblems } from "./core/crash";
import { leave, Root } from "./ui/root";
import "./ui/styles.css";

const app = new App();
// What goes wrong, kept for Settings' problem report.
watchForProblems(app.store);
// Listening before anything is drawn: a press the moment the app opens still counts.
installKeys(() => {
  if (!app.goBack()) leave();
});
render(<Root app={app} />, document.getElementById("root")!);
