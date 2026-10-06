import type { ComponentChildren } from "preact";
import { useState } from "preact/hooks";
import * as plex from "../api/plex";
import type { PlexItem, PlexPerson } from "../api/plex";
import type { App, AppState } from "../app/store";
import { Card, Pill, Row, Spinner, useRescue } from "./parts";
import { open } from "./screens";

/*
 * Search, as the Fire TV has it: channels first, then the movies and shows that match by
 * name, collections, Plex's other guesses, and the people last, unless it's a person's
 * name that was typed. Recent searches while the box is empty.
 */

const episodeLine = (i: PlexItem) =>
  i.type === "episode" ? [i.parentIndex != null ? `S${i.parentIndex}` : null, i.index != null ? `E${i.index}` : null, i.title].filter(Boolean).join(" · ") : plex.caption(i);

const posterOf = (i: PlexItem) => (i.type === "episode" ? i.grandparentThumb ?? i.thumb : i.thumb);

export function Search(props: { app: App; state: AppState }) {
  const { app, state } = props;
  const s = state.search;
  const [text, setText] = useState(s.query);
  // The box is the screen's first thing: the cursor arrives on it, ready to type.
  useRescue([s.busy, s.results.length, s.recent.length]);

  const type = (q: string) => {
    setText(q);
    void app.setQuery(q);
  };
  const pick = (item: PlexItem) => {
    app.rememberSearch();
    if (item.type === "collection") app.navigate({ name: "collection", item });
    else open(app, item);
  };
  const person = (p: PlexPerson) => {
    app.rememberSearch();
    app.navigate({ name: "person", person: p });
  };
  const poster = (i: PlexItem, key: string) => (
    <Card key={key} item={i} title={plex.rowTitle(i)} sub={episodeLine(i)} image={app.image(i.serverBase, posterOf(i), 300, 450)}
      progress={plex.resumeFraction(i)} watched={plex.isWatched(i)} onPress={() => pick(i)} />
  );
  const nothing = !s.results.length && !s.more.length && !s.people.length && !s.collections.length && !s.channels.length;
  const summary = [
    s.results.length + s.more.length ? `${s.results.length + s.more.length} in your library` : null,
    s.channels.length ? `${s.channels.length} live channels` : null,
    s.people.length ? (s.people.length === 1 ? "1 person" : `${s.people.length} people`) : null,
    s.collections.length ? (s.collections.length === 1 ? "1 collection" : `${s.collections.length} collections`) : null,
  ].filter(Boolean).join("  ·  ");

  const peopleRow = s.people.length ? (
    <section class="row">
      <h2>People</h2>
      <div class="strip">
        {s.people.map((p) => <PersonButton key={`p:${p.id}`} app={app} person={p} onPress={() => person(p)} />)}
      </div>
    </section>
  ) : null;

  return (
    <div>
      <div class="toolbar">
        <input
          class="field"
          data-focus
          placeholder="Movies, shows, people, collections and channels"
          value={text}
          onInput={(e) => type((e.target as HTMLInputElement).value)}
        />
      </div>
      <div class="search-note">
        {!text.trim() ? (
          s.recent.length ? null : <p class="note">Search movies, shows, people, collections and live channels.</p>
        ) : s.busy && nothing ? (
          <Spinner />
        ) : nothing ? (
          <p class="note">{s.unreachable ? "Couldn't reach your Plex server to search. Try again in a moment." : `Nothing matched "${s.query}".`}</p>
        ) : (
          <p class="note">{summary}</p>
        )}
      </div>
      {!text.trim() && s.recent.length ? (
        <section class="row">
          <h2>Recent searches</h2>
          <div class="toolbar">
            {s.recent.map((r) => <Pill key={r} label={r} onPress={() => type(r)} />)}
            <Pill label="Clear" onPress={() => app.clearRecentSearches()} />
          </div>
        </section>
      ) : null}
      {text.trim() ? (
        <>
          {s.channels.length ? (
            <Row title="Live TV">
              {s.channels.map((ch) => (
                <Card key={`ch:${ch.streamId}`} wide title={ch.name} sub={ch.number != null ? `Channel ${ch.number}` : null} image={ch.icon ?? null}
                  onPress={() => { app.rememberSearch(); app.watchFound(ch); }} />
              ))}
            </Row>
          ) : null}
          {s.peopleFirst ? peopleRow : null}
          {s.results.length ? (
            <section class="row">
              <h2>Movies and shows</h2>
              <div class="grid">{s.results.map((i) => poster(i, plex.listKey(i)))}</div>
            </section>
          ) : null}
          {s.collections.length ? (
            <Row title="Collections">
              {s.collections.map((i) => (
                <Card key={`c:${plex.listKey(i)}`} title={i.title} sub={plex.caption(i)} image={app.image(i.serverBase, i.thumb, 300, 450)} onPress={() => pick(i)} />
              ))}
            </Row>
          ) : null}
          {s.more.length ? (
            <section class="row">
              <h2>Other results</h2>
              <div class="grid">{s.more.map((i) => poster(i, `more:${plex.listKey(i)}`))}</div>
            </section>
          ) : null}
          {s.peopleFirst ? null : peopleRow}
        </>
      ) : null}
    </div>
  );
}

export function PersonButton(props: { app: App; person: Pick<PlexPerson, "name" | "thumb" | "serverBase">; role?: string | null; onPress: () => void }) {
  const { person } = props;
  const image = person.thumb ? (/^https?:/.test(person.thumb) ? person.thumb : props.app.image(person.serverBase, person.thumb, 160, 160)) : null;
  return (
    <button class="person" data-focus onClick={props.onPress}>
      <div class="avatar">{image ? <img src={image} alt="" loading="lazy" /> : person.name.slice(0, 1)}</div>
      <div class="name">{person.name}</div>
      {props.role ? <div class="facts">{props.role}</div> : null}
    </button>
  );
}

/** Everything someone's in, or everything in a collection: a grid of titles. */
export function ListPageView(props: { app: App; state: AppState; title: string; listKey: string; empty: string; actions?: ComponentChildren }) {
  const { app, state } = props;
  const page = state.list && state.list.key === props.listKey ? state.list : null;
  useRescue([page?.busy, page?.items.length]);
  return (
    <div>
      <div class="hero short">
        <h1>{props.title}</h1>
        {page && !page.busy ? <div class="facts">{page.items.length === 1 ? "1 title" : `${page.items.length} titles`}</div> : null}
        {page && !page.busy && page.items.length && props.actions ? <div class="actions">{props.actions}</div> : null}
      </div>
      {!page || page.busy ? <div class="center" style={{ height: "12rem" }}><Spinner /></div> : null}
      {page?.error ? <p class="note error" style={{ margin: "0 3rem" }}>{page.error}</p> : null}
      {page && !page.busy && !page.error && !page.items.length ? <p class="note" style={{ margin: "0 3rem" }}>{props.empty}</p> : null}
      <div class="grid">
        {(page?.items ?? []).map((i, index) => (
          <Card key={plex.listKey(i)} item={i} autofocus={index === 0 && !props.actions} title={i.title} sub={plex.caption(i)} image={app.image(i.serverBase, i.thumb, 300, 450)}
            progress={plex.resumeFraction(i)} watched={plex.isWatched(i)} onPress={() => open(app, i)} />
        ))}
      </div>
    </div>
  );
}

export const personKey = (p: PlexPerson) => `person:${p.serverBase ?? ""}|${p.id}`;
export const collectionKey = (i: PlexItem) => `collection:${plex.listKey(i)}`;
