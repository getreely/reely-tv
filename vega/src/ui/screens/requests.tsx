import React, { useEffect, useRef, useState } from 'react';
import { ScrollView, View } from 'react-native';
import { TVFocusGuideView } from '@amazon-devices/react-native-kepler';
import type { RequestTitle } from '../../api/reely';
import { askedIn, holdingAll, librariesFor, requestKey, seasonsLeft } from '../../api/reely';
import { requestBadge, shownRequestRows } from '../../app/store';
import { PosterCard } from '../cards';
import { ActionButton, Chip, EmptyNote, ErrorNote, SectionHeading, TextField } from '../controls';
import { PosterRow, Rows } from '../rows';
import { glass, HeroBackdrop } from '../surfaces';
import { ExpandableSummary } from '../summary';
import { Text } from '../text';
import { color, dp, EDGE, type } from '../theme';
import type { ScreenProps } from './home';

/*
 * Requests (RequestsScreen.kt and RequestTitleScreen.kt): asking for what the server
 * doesn't have, through Reely, the owner's requesting app. Search, rows of what's
 * trending and popular, and what this account has asked for; a title's page to ask for
 * it, with which library and which seasons.
 */

const statusWords: Record<string, string> = {
  pending: "You asked for this. It's waiting to be approved.",
  approved: "You asked for this. It's been approved and is on its way.",
  denied: 'You asked for this. It was declined.',
};

/** Before Reely's address is known: where it is, and a word on what it's for. */
function ConnectReely(props: ScreenProps) {
  const { app, state } = props;
  const r = state.requests;
  const [address, setAddress] = useState('');
  return (
    <View style={{ flex: 1, flexDirection: 'row', paddingHorizontal: dp(48), paddingVertical: dp(27), gap: dp(48) }}>
      <View style={{ flex: 1, paddingTop: dp(12), gap: dp(14) }}>
        <SectionHeading text="Requests" />
        <Text style={[type.display, { fontSize: dp(30), lineHeight: dp(36) }]}>Ask for movies and shows</Text>
        <Text style={[type.body, { color: color.muted }]}>
          Requests go to Reely, the server owner's app for adding movies and shows. Enter its address; you're signed in with your Plex account.
        </Text>
        {r.error ? <ErrorNote message={r.error} /> : null}
      </View>
      <TVFocusGuideView autoFocus style={[glass(), { width: dp(440), padding: dp(20), gap: dp(14), alignSelf: 'flex-start' }]}>
        <TextField label="Reely address" value={address} onChange={setAddress} placeholder="reely.example.com" keyboard="url" first focusKey="address"
          onSubmit={() => void app.connectReely(address)} />
        <ActionButton label={r.connecting ? 'Connecting…' : 'Connect'} emphasised onPress={() => void app.connectReely(address)} style={{ marginTop: dp(4) }} />
      </TVFocusGuideView>
    </View>
  );
}

export function Requests(props: ScreenProps) {
  const { app, state } = props;
  const r = state.requests;
  const [query, setQuery] = useState(r.query);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(() => () => { if (timer.current) clearTimeout(timer.current); }, []);
  if (!r.address) return <ConnectReely {...props} />;

  const search = (q: string) => {
    setQuery(q);
    if (timer.current) clearTimeout(timer.current);
    timer.current = setTimeout(() => void app.searchRequests(q), 400);
  };
  const card = (row: string, t: RequestTitle) => (
    <PosterCard key={requestKey(t)} focusKey={`${row}:${requestKey(t)}`} title={t.title} subtitle={t.year != null ? String(t.year) : null}
      image={t.poster ?? null} tag={requestBadge(r, t) ?? null} onPress={() => props.openRequest(t)} />
  );
  const rows = shownRequestRows(r);
  // Once each: a show asked for a season at a time is one show.
  const mine = r.mine.map((m) => m.title).filter((t, i, all) => all.findIndex((x) => requestKey(x) === requestKey(t)) === i);
  return (
    <Rows style={{ flex: 1 }} header={
      <View style={{ paddingHorizontal: EDGE, paddingTop: dp(12), gap: dp(10) }}>
        <TextField label="Search" value={query} onChange={search} placeholder="Movies and shows to request" focusKey="request-search" style={{ maxWidth: dp(620) }} />
        {r.error ? <ErrorNote message={r.error} />
          : r.searching ? <EmptyNote text="Searching…" />
          : query.trim() && !r.results.length ? <EmptyNote text={`Nothing matched "${query}".`} />
          : r.loading && !rows.length ? <EmptyNote text="Loading…" />
          : null}
      </View>
    }>
      {query.trim() ? (
        r.results.length ? <PosterRow title="Results">{r.results.map((t) => card('result', t))}</PosterRow> : null
      ) : (
        <>
          {mine.length ? <PosterRow title="Your requests">{mine.map((t) => card('mine', t))}</PosterRow> : null}
          {rows.map((row) => <PosterRow key={row.id} title={row.title}>{row.titles.map((t) => card(row.id, t))}</PosterRow>)}
        </>
      )}
    </Rows>
  );
}

/** One title in Requests: what it is, and asking for it. */
export function RequestTitlePage(props: ScreenProps & { title: RequestTitle }) {
  const { app, state, title } = props;
  const page = state.requestPage && requestKey(state.requestPage.title) === requestKey(title) ? state.requestPage : null;
  const detail = page?.detail ?? null;
  const status = state.requests.mine.find((m) => requestKey(m.title) === requestKey(title))?.status;
  const held = detail?.inLibraries.length ?? 0;
  const addable = detail && page?.places ? librariesFor(page.places, detail.title, holdingAll(detail)) : [];
  const canAsk = !!detail && (page?.places ? addable.length > 0 : !detail.inLibrary);
  const show = title.kind === 'show';
  const verb = page?.places?.adds ? 'Add' : 'Request';
  // Of a show partly here, only the seasons it hasn't got are offered.
  const offered = detail ? seasonsLeft(detail, page?.libraryId) : [];
  const here = detail ? detail.seasons.filter((s) => askedIn(detail, page?.libraryId).includes(s.number)) : [];
  const picked = offered.filter((s) => page?.chosen.includes(s.number)).length;
  const label = page?.sending ? (verb === 'Add' ? 'Adding…' : 'Requesting…')
    : !show || !offered.length ? verb
    : picked === offered.length && !here.length ? `${verb} all seasons`
    : picked === offered.length && offered.length > 1 ? `${verb} the other ${offered.length} seasons`
    : picked === 1 ? `${verb} 1 season` : `${verb} ${picked} seasons`;
  const partNote = here.length && offered.length ? `${here.length === 1 ? here[0].name : `${here.length} seasons`} here already. Pick more to ask for.` : null;
  const facts = [title.year, show ? 'Show' : 'Movie', detail?.runtime ? `${Math.floor(detail.runtime / 60)}h ${detail.runtime % 60}m`.replace(/^0h /, '') : null,
    detail?.status, detail?.genres.slice(0, 3).join(', ') || null].filter(Boolean).join('  ·  ');
  const overview = detail?.title.overview ?? title.overview;
  const allChosen = picked === offered.length;
  const toggleAll = () => offered.forEach((s) => {
    const on = page?.chosen.includes(s.number);
    if (allChosen ? on : !on) app.toggleRequestSeason(s.number);
  });

  return (
    <View style={{ flex: 1 }}>
      <HeroBackdrop url={detail?.backdrop ?? title.poster ?? null} />
      <ScrollView showsVerticalScrollIndicator={false} contentContainerStyle={{ paddingHorizontal: EDGE, paddingVertical: dp(20), gap: dp(14), paddingBottom: dp(160) }}>
        <Text style={type.display}>{title.title}</Text>
        <Text style={[type.body, { color: color.muted }]}>{facts}</Text>
        {overview ? <ExpandableSummary text={overview} maxWidth={700} /> : null}
        {!page || page.busy ? <EmptyNote text="Loading…" />
          : page.error ? <ErrorNote message={page.error} />
          : !detail ? null
          : !canAsk ? <EmptyNote text={held > 1 ? `Already in ${held} libraries: there's nowhere left for it to go.` : 'Already in your library.'} />
          : partNote ? <EmptyNote text={partNote} />
          : held === 1 ? <EmptyNote text="Already in a library. It can go in another as well." />
          : held > 1 ? <EmptyNote text={`Already in ${held} libraries. It can go in another as well.`} />
          : null}
        {status && statusWords[status] ? <EmptyNote text={statusWords[status]} /> : null}

        {detail && canAsk && addable.length > 1 ? (
          <View style={{ gap: dp(8), paddingTop: dp(6) }}>
            <SectionHeading text="Library" />
            <ChipRow>{addable.map((l) => <Chip key={l.id} label={l.name} selected={l.id === page?.libraryId} onPress={() => app.chooseRequestLibrary(l.id)} />)}</ChipRow>
          </View>
        ) : null}
        {detail && canAsk && show && offered.length ? (
          <View style={{ gap: dp(8), paddingTop: dp(6) }}>
            <SectionHeading text="Seasons" />
            <ChipRow>
              {offered.length > 1 ? <Chip label={here.length ? 'All the others' : 'All seasons'} selected={allChosen} onPress={toggleAll} /> : null}
              {offered.map((s) => <Chip key={s.number} label={s.name} selected={!!page?.chosen.includes(s.number)} onPress={() => app.toggleRequestSeason(s.number)} />)}
            </ChipRow>
          </View>
        ) : null}

        {/* Already here: something to press, and the thing most wanted. */}
        {detail && (detail.inLibrary || held > 0) && !canAsk ? (
          <View style={{ flexDirection: 'row', paddingTop: dp(8) }}>
            <ActionButton label="Watch" emphasised first focusKey="watch" onPress={() => void app.openReady(title)} />
          </View>
        ) : null}
        {detail && canAsk ? (
          <View style={{ flexDirection: 'row', paddingTop: dp(8) }}>
            <ActionButton label={label} emphasised first focusKey="ask" onPress={() => void app.submitRequest()} />
          </View>
        ) : null}
        {page?.outcome ? <Text style={[type.body, { maxWidth: dp(700) }]}>{page.outcome}</Text> : null}
      </ScrollView>
    </View>
  );
}

/** A row of choices, walked left and right. */
function ChipRow(props: { children: React.ReactNode }) {
  return (
    <TVFocusGuideView autoFocus>
      <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ gap: dp(8), paddingRight: EDGE, paddingVertical: dp(4) }}>
        {props.children}
      </ScrollView>
    </TVFocusGuideView>
  );
}
