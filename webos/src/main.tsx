import { render } from "preact";
import { App } from "./app/store";
import { installKeys } from "./ui/focus";
import { leave, Root } from "./ui/root";
import "./ui/styles.css";

const app = new App();
// Listening before anything is drawn: a press the moment the app opens still counts.
installKeys(() => {
  if (!app.goBack()) leave();
});
render(<Root app={app} />, document.getElementById("root")!);
