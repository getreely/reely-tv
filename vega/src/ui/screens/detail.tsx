import React, { useRef, useState } from 'react';
import { ScrollView, View } from 'react-native';
import { TVFocusGuideView } from '@amazon-devices/react-native-kepler';
import type { PlexItem } from '../../api/plex';
import * as plex from '../../api/plex';
import { IPTV_SOURCE, sourceTag } from '../../api/vod';
import { emptyItem } from '../../app/store';
import { formatAirDate, formatDuration } from '../../core/quality';
import { CastCircle, EpisodeTile, PosterCard } from '../cards';
import { ChoicePanel, type ChoiceRequest } from '../choice';
import { ActionButton, Chip, ErrorNote } from '../controls';
import { FocusRow } from '../focus';
import { BookmarkGlyph, CheckGlyph, RestartGlyph, TrailerGlyph } from '../glyphs';
import { hasItemMenu } from '../itemmenu';
import { HeroBackdrop, HeroText, IconAction, PlayAction } from '../surfaces';
import { ExpandableSummary } from '../summary';
import { Text } from '../text';
import { color, dp, EDGE, type } from '../theme';
import { ThemeMusic } from '../../player/theme';
import type { ScreenProps } from './home';

/*
 * A title's page (DetailScreen.kt): its picture behind everything, the logo or name, the
 * scores and details, what it's about, Play and the round buttons, a show's seasons and
 * its episodes as pictures, the credits, the cast and more like it. The text at the top
 * always describes what has focus: the show, or the episode the cursor is on.
 */

export function Detail(props: ScreenProps) {
  const { app, state } = props;
  const page = state.detail;
  const [choosing, setChoosing] = useState<ChoiceRequest | null>(null);
  const versionButton = useRef<View>(null);
  const page$ = useRef<ScrollView>(null);

  if (!page || (!page.detail && page.busy)) return <DetailPlaceholder />;
  if (!page.detail) {
    // Something to press: with only the message, the cursor had nowhere to be.
    return (
      <View style={{ flex: 1, padding: EDGE, gap: dp(16), alignItems: 'flex-start' }}>
        <ErrorNote message={page.error ?? "Couldn't load that title."} />
        <ActionButton label="Try again" emphasised preferred onPress={() => props.retry()} />
      </View>
    );
  }

  const d = page.detail;
  const base = page.serverBase;
  const show = plex.isShow(d);
  const isCollection = d.type === 'collection';
  const episode = show ? page.focused : null;
  const logo = d.logo ? app.image(base, d.logo, 900, 320) : null;
  const backdrop = app.image(base, episode?.thumb ?? d.art ?? d.thumb, 1920, 1080);
  // What Play would resume. For a show that's the part-watched episode.
  const resumeFrom = episode ? episode.viewOffsetMs : show ? page.episodes.find((e) => plex.resumeFraction(e) != null)?.viewOffsetMs ?? 0 : d.viewOffsetMs;
  const filmItem = (): PlexItem => ({
    ...emptyItem(d.ratingKey, d.title, d.type, base), durationMs: d.durationMs, viewOffsetMs: d.viewOffsetMs, thumb: d.thumb, art: d.art,
  });
  const target = (): PlexItem | null => episode ?? (show ? null : filmItem());
  const play = (resume: boolean) => {
    const t = target();
    if (t) props.play(t, resume);
  };
  const watchedNow = episode ? plex.isWatched(episode) : d.viewCount > 0;
  const watchlisted = d.guid && base !== IPTV_SOURCE ? state.plex.watchlist.has(d.guid) : null;
  const caption = episode ? plex.caption(episode) : null;

  const facts = episode
    ? [plex.caption(episode), formatAirDate(episode.airDate ?? null), formatDuration(episode.durationMs) || null]
    : [
      d.year != null ? String(d.year) : null,
      show && d.childCount > 0 ? (d.childCount === 1 ? '1 season' : `${d.childCount} seasons`) : null,
      isCollection && d.childCount > 0 ? (d.childCount === 1 ? '1 title' : `${d.childCount} titles`) : null,
      !show ? formatDuration(d.durationMs) || null : null,
      d.studio,
    ];
  const summary = episode?.summary ?? d.summary;
  const credits: Array<[string, string]> = [];
  if (d.genres.length) credits.push([d.genres.length === 1 ? 'Genre' : 'Genres', d.genres.slice(0, 4).join(', ')]);
  if (d.directors.length) credits.push(['Directed by', d.directors.slice(0, 3).join(', ')]);
  if (d.writers.length) credits.push(['Written by', d.writers.slice(0, 3).join(', ')]);
  if (d.studio) credits.push([show ? 'Network' : 'Studio', d.studio]);
  const hold = (item: PlexItem) => (hasItemMenu(item) ? () => props.hold(item) : undefined);
  // Arriving on a show lands on its episode; on anything else, on Play.
  const landOnEpisode = show && !!page.focused && page.episodes.some((e) => e.ratingKey === page.focused?.ratingKey);
  const theme = show && state.prefs.themeLevel >= 0 ? app.mediaUrl(base, d.theme) : null;

  return (
    <View style={{ flex: 1 }}>
      <HeroBackdrop url={backdrop} />
      {theme ? <ThemeMusic url={theme} level={state.prefs.themeLevel} /> : null}
      <ScrollView ref={page$} showsVerticalScrollIndicator={false} contentContainerStyle={{ paddingTop: dp(14), paddingBottom: dp(160), gap: dp(16) }}>
        {show && page.seasons.length ? (
          <TVFocusGuideView autoFocus>
            <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ paddingHorizontal: EDGE, gap: dp(8), paddingVertical: dp(4) }}>
              {page.seasons.map((s) => (
                <Chip key={s.ratingKey} focusKey={`season:${s.ratingKey}`} label={s.title} selected={page.season?.ratingKey === s.ratingKey}
                  onPress={() => void app.selectSeason(s)} />
              ))}
            </ScrollView>
          </TVFocusGuideView>
        ) : null}

        <View style={{ paddingHorizontal: EDGE, gap: dp(14) }}>
          {page.error ? <ErrorNote message={page.error} /> : null}
          {/* The show's logo stays at the top, with the focused episode's title under it.
              With no logo: the show and season above, the episode's title as the heading. */}
          <HeroText
            eyebrow={!episode ? null : logo ? page.season?.title ?? null : [episode.grandparentTitle, page.season?.title].filter(Boolean).join('  ·  ')}
            title={logo ? d.title : episode?.title ?? d.title}
            logo={logo}
            subtitle={logo ? episode?.title ?? null : null}
            critic={episode ? null : d.rating}
            audience={episode ? null : d.audienceRating}
            content={episode ? null : d.contentRating}
            facts={facts.filter((f): f is string => !!f)}
            qualities={episode?.qualities ?? d.qualities}
            style={{ maxWidth: dp(780) }}
          />
          {summary ? <ExpandableSummary text={summary} maxWidth={640} /> : null}
          {/* A collection has nothing of its own to play: its titles do. */}
          {!isCollection ? (
            <TVFocusGuideView autoFocus style={{ flexDirection: 'row', alignItems: 'flex-start', gap: dp(6) }}>
              <View style={{ paddingRight: dp(10), paddingTop: dp(0) }}>
                <PlayAction first={!landOnEpisode} focusKey="play" label={[resumeFrom > 0 ? 'Resume' : 'Play', caption].filter(Boolean).join(' ')}
                  onPress={() => (episode || !show ? play(true) : void playShow())} />
              </View>
              {/* Only worth offering when Play would pick up part-way through. */}
              {resumeFrom > 0 ? (
                <IconAction label="Restart" focusKey="restart" onPress={() => (episode || !show ? play(false) : void playShow(false))} glyph={(c) => <RestartGlyph color={c} size={20} />} />
              ) : null}
              {!show || episode ? (
                <IconAction label={watchedNow ? 'Unwatch' : 'Watched'} focusKey="watched" onPress={() => void app.toggleWatched()} glyph={(c) => <CheckGlyph color={c} size={20} />} />
              ) : null}
              {/* A title in more than one file (a 4K copy and a 1080p one): which one Play uses. */}
              {d.versions.length > 1 && !episode && !show ? (
                <View ref={versionButton}>
                  <IconAction label="Quality" focusKey="quality"
                    onPress={() => setChoosing({
                      title: 'Quality',
                      options: d.versions.map((v) => ({ label: v.label, detail: v.detail })),
                      selected: page.versionIndex,
                      onPick: (i) => app.chooseVersion(i),
                      returnTo: versionButton,
                    })}
                    glyph={(c) => (
                      <Text numberOfLines={1} style={{ color: c, fontSize: dp(12), lineHeight: dp(14), fontWeight: '700' }}>
                        {(d.versions[page.versionIndex] ?? d.versions[0]).label.split(' ')[0]}
                      </Text>
                    )} />
                </View>
              ) : null}
              {watchlisted != null && !episode ? (
                <IconAction label="Watchlist" focusKey="watchlist" onPress={() => void app.toggleWatchlist()} glyph={(c) => <BookmarkGlyph color={c} filled={watchlisted} size={20} />} />
              ) : null}
              {/* Only when the server actually has a trailer to play. */}
              {page.trailers.length && !episode ? (
                <IconAction label="Trailer" focusKey="trailer" glyph={(c) => <TrailerGlyph color={c} size={20} />}
                  onPress={() => {
                    const t = page.trailers[0];
                    props.play({ ...emptyItem(t.ratingKey, t.title, 'clip', base), durationMs: t.durationMs }, false);
                  }} />
              ) : null}
            </TVFocusGuideView>
          ) : null}
        </View>

        {show && page.busy && !page.episodes.length ? <EpisodeRailPlaceholder /> : null}
        {show && page.episodes.length ? (
          <FocusRow>
            <TVFocusGuideView autoFocus>
              <ScrollView horizontal showsHorizontalScrollIndicator={false}
                contentContainerStyle={{ paddingHorizontal: dp(36), gap: dp(14), paddingVertical: dp(14) }} style={{ marginVertical: -dp(14) }}>
                {page.episodes.map((e) => {
                  const item = { ...e, serverBase: e.serverBase ?? base };
                  return (
                    <EpisodeTile key={e.ratingKey} focusKey={`episode:${e.ratingKey}`} first={landOnEpisode && page.focused?.ratingKey === e.ratingKey}
                      number={e.index != null ? String(e.index) : ''} title={e.title} duration={formatDuration(e.durationMs) || null}
                      image={app.image(item.serverBase, e.thumb, 320, 180)} progress={plex.resumeFraction(e)} watched={plex.isWatched(e)}
                      selected={page.focused?.ratingKey === e.ratingKey}
                      onFocus={() => app.focusEpisode(e)} onPress={() => props.play(item, true)} onHold={hold(item)} />
                  );
                })}
              </ScrollView>
            </TVFocusGuideView>
          </FocusRow>
        ) : null}

        {isCollection && state.list?.items.length ? (
          <TitleRow heading="In this collection" titles={state.list.items} app={app} open={props.open} hold={hold} claim />
        ) : null}

        {/* The credits, the way a film's end card lists them: only the lines there's something for. */}
        {credits.length ? (
          <View style={{ paddingHorizontal: EDGE, gap: dp(6) }}>
            {credits.map(([label, value]) => (
              <View key={label} style={{ flexDirection: 'row', gap: dp(16) }}>
                <Text style={[type.label, { color: color.faint, width: dp(96) }]}>{label}</Text>
                <Text numberOfLines={1} style={[type.label, { color: color.muted, flex: 1 }]}>{value}</Text>
              </View>
            ))}
          </View>
        ) : null}

        {d.roles.length ? (
          <View style={{ gap: dp(10) }}>
            <Text style={[type.section, { paddingHorizontal: EDGE }]}>Cast</Text>
            <TVFocusGuideView autoFocus>
              <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ paddingHorizontal: EDGE, gap: dp(16), paddingVertical: dp(4) }}>
                {d.roles.slice(0, 24).map((r, i) => (
                  <CastCircle key={`${r.id ?? r.name}:${i}`} focusKey={`cast:${r.id ?? r.name}:${i}`} name={r.name} role={r.role} image={app.image(base, r.thumb, 160, 160)}
                    onPress={r.id ? () => props.openRoute({ name: 'person', person: { id: r.id!, name: r.name, thumb: r.thumb, serverBase: base } }) : undefined} />
                ))}
              </ScrollView>
            </TVFocusGuideView>
          </View>
        ) : null}

        {page.related.length ? <TitleRow heading="More like this" titles={page.related} app={app} open={props.open} hold={hold} width={120} /> : null}
      </ScrollView>
      {choosing ? <ChoicePanel request={choosing} onClose={() => setChoosing(null)} /> : null}
    </View>
  );

  /** Play on a show's page with no episode in hand: the one it's up to. */
  async function playShow(resume = true) {
    const n = await app.nextEpisodeOf(emptyItem(d.ratingKey, d.title, 'show', base));
    if (n) props.play(n.episode, resume, n.queue);
  }
}

/** A heading and a row of posters, each opening its own page. */
function TitleRow(props: {
  heading: string; titles: PlexItem[]; app: ScreenProps['app']; open: (item: PlexItem) => void;
  hold: (item: PlexItem) => (() => void) | undefined; width?: number;
  /** The first title takes the cursor on arriving: a collection's page, which has no Play. */
  claim?: boolean;
}) {
  return (
    <View style={{ gap: dp(14) }}>
      <Text style={[type.section, { paddingHorizontal: EDGE }]}>{props.heading}</Text>
      <FocusRow>
        <TVFocusGuideView autoFocus>
          <ScrollView horizontal showsHorizontalScrollIndicator={false}
            contentContainerStyle={{ paddingHorizontal: dp(36), gap: dp(14), paddingVertical: dp(14) }} style={{ marginVertical: -dp(14) }}>
            {props.titles.map((t, i) => (
              <PosterCard key={plex.listKey(t)} focusKey={`${props.heading}:${plex.listKey(t)}`} width={props.width} first={!!props.claim && i === 0}
                title={t.title} subtitle={plex.caption(t)} image={props.app.image(t.serverBase, t.thumb, 300, 450)} tag={sourceTag(t)}
                progress={plex.resumeFraction(t)} watched={plex.isWatched(t)} onPress={() => props.open(t)} onHold={props.hold(t)} />
            ))}
          </ScrollView>
        </TVFocusGuideView>
      </FocusRow>
    </View>
  );
}

/** A title page's shape while it's on its way. */
function DetailPlaceholder() {
  const block = (width: number | string, height: number, radius = dp(8)) => (
    <View style={{ width: width as number, height, borderRadius: radius, backgroundColor: 'rgba(242, 244, 247, 0.06)' }} />
  );
  return (
    <View style={{ flex: 1, paddingHorizontal: EDGE, paddingTop: dp(30), gap: dp(14) }}>
      {block(dp(360), dp(48))}
      {block(dp(260), dp(18))}
      {block(dp(620), dp(16))}
      {block(dp(580), dp(16))}
      {block(dp(420), dp(16))}
      <View style={{ flexDirection: 'row', gap: dp(12), marginTop: dp(6) }}>
        {block(dp(150), dp(44), dp(22))}
        {block(dp(44), dp(44), dp(22))}
        {block(dp(44), dp(44), dp(22))}
      </View>
    </View>
  );
}

function EpisodeRailPlaceholder() {
  return (
    <View style={{ flexDirection: 'row', gap: dp(14), paddingHorizontal: dp(36) }}>
      {Array.from({ length: 4 }, (_, i) => (
        <View key={i} style={{ width: dp(208), gap: dp(8) }}>
          <View style={{ width: '100%', aspectRatio: 16 / 9, borderRadius: dp(14), backgroundColor: 'rgba(242, 244, 247, 0.06)' }} />
          <View style={{ width: '70%', height: dp(10), borderRadius: dp(5), backgroundColor: 'rgba(242, 244, 247, 0.06)' }} />
        </View>
      ))}
    </View>
  );
}

