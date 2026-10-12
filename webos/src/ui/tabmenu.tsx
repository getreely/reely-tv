import { Fragment } from "preact";
import { useEffect, useLayoutEffect, useRef } from "preact/hooks";
import type { App, AppState, Kind } from "../app/store";
import type { LibraryChoice } from "../app/home";
import { isIptvChoice } from "../app/store";
import { focus, onKeys } from "./focus";
import { MenuIcon } from "./menu";

/**
 * What OK on Movies or TV Shows drops, as on the Fire TV (TabMenu.kt): where in the
 * library to go (its home, the whole library, its collections), and, when there's more
 * than one, which library. It hangs from the tab it belongs to; Back puts it away.
 */
export function TabMenu(props: { app: App; state: AppState; kind: Kind; left: number; top: number; onPicked: () => void; onClose: () => void }) {
  const { app, state, kind } = props;
  const browse = state.browse[kind];
  const libraries = app.librariesOf(kind);
  const onIptv = isIptvChoice(browse.choice);
  const panel = useRef<HTMLDivElement>(null);
  // Before anything else can happen: a quick press after OK goes to the menu, not the page.
  useLayoutEffect(() => {
    const rows = panel.current?.querySelectorAll<HTMLElement>(".option");
    focus(panel.current?.querySelector<HTMLElement>(".option.on") ?? rows?.[0]);
  }, []);
  useEffect(() => onKeys((key) => {
    if (key !== "back") return false;
    props.onClose();
    return true;
  }), []);

  const title = kind === "movie" ? "Movies" : "TV Shows";
  const go = (view: "home" | "grid" | "collections") => {
    // The provider's library is a grid only: its home and collections are a Plex library's.
    if (onIptv) {
      const plexOne = libraries.find((l) => !isIptvChoice(l));
      if (plexOne) void app.openLibrary(kind, plexOne);
    }
    app.setLibraryView(kind, view);
    props.onPicked();
  };
  const pick = (choice: LibraryChoice) => {
    void app.openLibrary(kind, choice);
    app.setLibraryView(kind, "grid");
    props.onPicked();
  };
  const named = (l: LibraryChoice) =>
    isIptvChoice(l) ? `IPTV ${title}`
      // Which server a library is on, only when there's more than one to tell apart.
      : state.plex.servers.length > 1 ? `${l.section.title} — ${l.serverName}` : l.section.title;

  const groups: Array<[string, Array<{ label: string; on: boolean; run: () => void }>]> = [
    [`In ${title}`, [
      { label: "Home", on: !onIptv && browse.view === "home", run: () => go("home") },
      { label: "Library", on: !onIptv && browse.view === "grid", run: () => go("grid") },
      { label: "Collections", on: !onIptv && browse.view === "collections", run: () => go("collections") },
    ]],
    ["Libraries", libraries.length > 1 ? libraries.map((l) => ({ label: named(l), on: browse.choice === l, run: () => pick(l) })) : []],
  ];
  return (
    <div class="tab-menu" data-layer ref={panel} style={{ left: `${props.left}px`, top: `${props.top}px` }}>
      {groups.map(([heading, rows]) => rows.length ? (
        <Fragment key={heading}>
          <h3 class="menu-section">{heading}</h3>
          {rows.map((row) => (
            <button key={row.label} class={"option" + (row.on ? " on" : "")} data-focus onClick={row.run}>
              <span class="option-text">{row.label}</span>
              {row.on ? <span class="tick end"><MenuIcon name="check" /></span> : null}
            </button>
          ))}
        </Fragment>
      ) : null)}
    </div>
  );
}
