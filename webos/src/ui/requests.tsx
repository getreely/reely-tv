import { useEffect, useRef, useState } from "preact/hooks";
import type { RequestTitle } from "../api/reely";
import { librariesFor, requestKey } from "../api/reely";
import type { App, AppState } from "../app/store";
import { requestBadge, shownRequestRows } from "../app/store";
import { Card, Pill, Row, Spinner, useRescue } from "./parts";

const statusWords: Record<string, string> = {
  pending: "You asked for this. It's waiting to be approved.",
  approved: "You asked for this. It's been approved and is on its way.",
  denied: "You asked for this. It was declined.",
};

function Field(props: { label: string; value: string; onInput: (v: string) => void; onEnter?: () => void; autofocus?: boolean }) {
  return (
    <input
      class="field"
      data-focus
      data-autofocus={props.autofocus ? "" : undefined}
      placeholder={props.label}
      value={props.value}
      onInput={(e) => props.onInput((e.target as HTMLInputElement).value)}
      onKeyDown={(e) => { if (e.key === "Enter") props.onEnter?.(); }}
    />
  );
}

export function Requests(props: { app: App; state: AppState }) {
  const { app, state } = props;
  const r = state.requests;
  const [address, setAddress] = useState("");
  const [query, setQuery] = useState(r.query);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  useRescue([!!r.address, r.rows.length > 0]);
  useEffect(() => () => { if (timer.current) clearTimeout(timer.current); }, []);

  if (!r.address) {
    return (
      <div class="center">
        <h1 class="big">Ask for movies and shows</h1>
        <p class="note">Requests go to Reely, the server owner's app for adding movies and shows. Enter its address; you're signed in with your Plex account.</p>
        {r.error ? <p class="note error">{r.error}</p> : null}
        <Field label="Reely address, like 192.168.1.5:8788" value={address} onInput={setAddress} onEnter={() => void app.connectReely(address)} autofocus />
        <Pill label={r.connecting ? "Connecting…" : "Connect"} primary onPress={() => void app.connectReely(address)} />
      </div>
    );
  }

  const search = (q: string) => {
    setQuery(q);
    if (timer.current) clearTimeout(timer.current);
    timer.current = setTimeout(() => void app.searchRequests(q), 400);
  };
  const card = (t: RequestTitle, autofocus = false) => (
    <Card key={requestKey(t)} autofocus={autofocus} title={t.title} sub={t.year ? String(t.year) : null} image={t.poster ?? null}
      tag={requestBadge(r, t) ?? null} onPress={() => app.navigate({ name: "requestTitle", title: t })} />
  );
  const rows = shownRequestRows(r);
  const mine = r.mine.map((m) => m.title).filter((t, i, all) => all.findIndex((x) => requestKey(x) === requestKey(t)) === i);
  let first = true;
  const auto = () => { const was = first; first = false; return was; };
  return (
    <div>
      <div class="toolbar">
        <Field label="Search movies and shows to request" value={query} onInput={search} />
      </div>
      {r.error ? <p class="note error" style={{ margin: "0 3rem" }}>{r.error}</p> : null}
      {query.trim() ? (
        r.searching && !r.results.length ? <div class="center" style={{ height: "12rem" }}><Spinner /></div>
          : r.results.length ? <Row title="Results">{r.results.map((t) => card(t))}</Row>
          : <p class="note" style={{ margin: "1rem 3rem" }}>Nothing matched "{query}".</p>
      ) : (
        <>
          {r.loading && !rows.length ? <div class="center" style={{ height: "12rem" }}><Spinner /></div> : null}
          {mine.length ? <Row title="Your requests">{mine.map((t) => card(t, auto()))}</Row> : null}
          {rows.map((row) => <Row key={row.id} title={row.title}>{row.titles.map((t) => card(t, auto()))}</Row>)}
        </>
      )}
    </div>
  );
}

export function RequestTitlePage(props: { app: App; state: AppState; title: RequestTitle }) {
  const { app, state, title } = props;
  const page = state.requestPage && requestKey(state.requestPage.title) === requestKey(title) ? state.requestPage : null;
  useRescue([page?.busy, page?.outcome]);
  const detail = page?.detail ?? null;
  const status = state.requests.mine.find((m) => requestKey(m.title) === requestKey(title))?.status;
  const held = detail?.inLibraries.length ?? 0;
  const addable = detail && page?.places ? librariesFor(page.places, detail.title, detail.inLibraries) : [];
  const canAsk = !!detail && (page?.places ? addable.length > 0 : !detail.inLibrary);
  const show = title.kind === "show";
  const verb = page?.places?.adds ? "Add" : "Request";
  const label = page?.sending
    ? verb === "Add" ? "Adding…" : "Requesting…"
    : !show || !detail?.seasons.length ? verb
    : page!.chosen.length === detail.seasons.length ? `${verb} all seasons`
    : page!.chosen.length === 1 ? `${verb} 1 season` : `${verb} ${page!.chosen.length} seasons`;
  return (
    <div>
      <div class="hero">
        <div class="backdrop" style={detail?.backdrop || title.poster ? { backgroundImage: `url("${detail?.backdrop ?? title.poster}")` } : undefined} />
        <h1>{title.title}</h1>
        <div class="facts">
          {[title.year, show ? "Show" : "Movie", detail?.runtime ? `${Math.floor(detail.runtime / 60)}h ${detail.runtime % 60}m`.replace(/^0h /, "") : null, detail?.status,
            detail?.genres.slice(0, 3).join(", ") || null].filter(Boolean).join("  ·  ")}
        </div>
        {(detail?.title.overview ?? title.overview) ? <div class="summary">{detail?.title.overview ?? title.overview}</div> : null}
        {!page || page.busy ? <Spinner /> : null}
        {page?.error ? <p class="note error">{page.error}</p> : null}
        {detail && !canAsk ? <p class="note">{held > 1 ? `Already in ${held} libraries: there's nowhere left for it to go.` : "Already in your library."}</p> : null}
        {detail && canAsk && held > 0 ? <p class="note">{held === 1 ? "Already in a library. It can go in another as well." : `Already in ${held} libraries. It can go in another as well.`}</p> : null}
        {status ? <p class="note">{statusWords[status] ?? ""}</p> : null}
        {page?.outcome ? <p class="note" style={{ color: "var(--chalk)" }}>{page.outcome}</p> : null}
        {detail && canAsk ? <div class="actions"><Pill label={label} primary autofocus onPress={() => void app.submitRequest()} /></div> : null}
      </div>
      {detail && canAsk && addable.length > 1 ? (
        <div class="toolbar">
          {addable.map((l) => <Pill key={l.id} label={l.name} on={page?.libraryId === l.id} onPress={() => app.chooseRequestLibrary(l.id)} />)}
        </div>
      ) : null}
      {detail && canAsk && show && detail.seasons.length ? (
        <div class="toolbar">
          {detail.seasons.map((s) => <Pill key={s.number} label={s.name} on={page!.chosen.includes(s.number)} onPress={() => app.toggleRequestSeason(s.number)} />)}
        </div>
      ) : null}
    </div>
  );
}
