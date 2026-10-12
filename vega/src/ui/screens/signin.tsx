import React from 'react';
import { View } from 'react-native';
import Svg, { Path } from '@amazon-devices/react-native-svg';
import qrcode from 'qrcode-generator';
import type { App, AppState } from '../../app/store';
import { ActionButton, ErrorNote, SectionHeading, Spinner } from '../controls';
import { useAccent } from '../focus';
import { glass } from '../surfaces';
import { Text } from '../text';
import { color, dp, type } from '../theme';

/*
 * Signing in to Plex (PlexSignInPanel.kt): what this is on the left, the one thing to do
 * on the right. A code to type at plex.tv/link, and the same as a QR code to scan.
 */

/** A QR code, drawn. */
export function QrCode(props: { text: string; size: number }) {
  const code = qrcode(0, 'M');
  code.addData(props.text);
  code.make();
  const n = code.getModuleCount();
  let d = '';
  for (let y = 0; y < n; y++) for (let x = 0; x < n; x++) if (code.isDark(y, x)) d += `M${x + 2} ${y + 2}h1v1h-1z`;
  const s = dp(props.size);
  return (
    <View style={{ width: s, height: s, backgroundColor: '#FFFFFF', borderRadius: dp(10), overflow: 'hidden' }}>
      <Svg width={s} height={s} viewBox={`0 0 ${n + 4} ${n + 4}`}>
        <Path d={d} fill="#08090B" />
      </Svg>
    </View>
  );
}

/** A numbered instruction: the number in a small disc, what to do beside it. */
function Step(props: { number: number; children: React.ReactNode }) {
  const accent = useAccent();
  return (
    <View style={{ flexDirection: 'row', gap: dp(14) }}>
      <View style={{ width: dp(28), height: dp(28), borderRadius: dp(14), backgroundColor: accent.color, alignItems: 'center', justifyContent: 'center' }}>
        <Text style={[type.label, { color: accent.on, fontWeight: '700' }]}>{props.number}</Text>
      </View>
      <View style={{ gap: dp(6) }}>{props.children}</View>
    </View>
  );
}

/** plex.tv/link and the code to type there: one tile a character, easier to read across a room. */
function LinkSteps(props: { code: string }) {
  return (
    <View style={{ gap: dp(18) }}>
      <Step number={1}>
        <Text style={type.meta}>On your phone or computer, go to</Text>
        <Text style={type.headline}>plex.tv/link</Text>
      </Step>
      <Step number={2}>
        <Text style={type.meta}>Enter this code</Text>
        <View style={{ flexDirection: 'row', gap: dp(8) }}>
          {[...props.code].map((c, i) => (
            <View key={i} style={{ width: dp(52), height: dp(64), borderRadius: dp(12), backgroundColor: color.surfaceHigh, alignItems: 'center', justifyContent: 'center' }}>
              <Text style={{ color: color.chalk, fontSize: dp(34), lineHeight: dp(40), fontWeight: '700' }}>{c}</Text>
            </View>
          ))}
        </View>
      </Step>
    </View>
  );
}

const Intro = (props: { heading: string; body: string; error?: React.ReactNode }) => (
  <View style={{ flex: 1, paddingTop: dp(12), gap: dp(14) }}>
    <SectionHeading text="Plex" />
    <Text style={[type.display, { fontSize: dp(30), lineHeight: dp(36) }]}>{props.heading}</Text>
    <Text style={[type.body, { color: color.muted }]}>{props.body}</Text>
    {props.error}
  </View>
);

export function PlexSignIn(props: { app: App; state: AppState }) {
  const { app, state } = props;
  const { signIn, plex } = state;
  // Signed in, and on the way to a server or unable to reach one.
  if (plex.token && !signIn.code) {
    const looking = plex.finding;
    return (
      <View style={{ flex: 1, flexDirection: 'row', gap: dp(48), paddingHorizontal: dp(48), paddingVertical: dp(27) }}>
        <Intro
          heading={looking ? 'Finding a Plex server' : "Couldn't reach a Plex server"}
          body={looking
            ? `You're signed in${plex.user ? ` as ${plex.user.title}` : ''}. Looking for the servers you can use, your own or shared with you, at home first, then over the internet.`
            : "Any server this account owns or has been given access to will do. Make sure it's on. Reely keeps looking, or try again now."}
          error={plex.error && !looking ? <ErrorNote message={plex.error} /> : null}
        />
        <View style={[glass(), { width: dp(440), padding: dp(24), gap: dp(18), alignSelf: 'flex-start' }]}>
          {looking ? (
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: dp(12) }}>
              <Spinner size={20} />
              <Text style={type.label}>Connecting…</Text>
            </View>
          ) : (
            <>
              <ActionButton label="Try again" emphasised first onPress={() => void app.connect(plex.token!)} />
              <ActionButton label="Use a different Plex account" onPress={() => app.signOut()} />
            </>
          )}
        </View>
      </View>
    );
  }
  const code = signIn.code;
  const scan = code ? signIn.url : null;
  return (
    <View style={{ flex: 1, flexDirection: 'row', gap: dp(48), paddingHorizontal: dp(48), paddingVertical: dp(27) }}>
      <Intro heading="Sign in to watch your library" body="Your movies and shows from Plex, including libraries shared with you."
        error={signIn.error ? <ErrorNote message={signIn.error} /> : null} />
      <View style={[glass(), { width: dp(scan ? 560 : 440), padding: dp(24), gap: dp(18), alignSelf: 'flex-start' }]}>
        {!code ? (
          <>
            <Text style={type.meta}>You'll get a short code to enter on your phone or computer.</Text>
            <ActionButton label={signIn.busy ? 'Getting a code…' : 'Sign in with Plex'} emphasised first onPress={() => void app.startSignIn()} />
          </>
        ) : (
          <>
            <View style={{ flexDirection: 'row', gap: dp(24) }}>
              {scan ? (
                <View style={{ alignItems: 'center', gap: dp(10) }}>
                  <QrCode text={scan} size={136} />
                  <Text style={[type.label, { color: color.muted }]}>Scan with your phone</Text>
                </View>
              ) : null}
              <View style={{ gap: dp(18) }}>
                {scan ? <Text style={type.label}>Or type the code instead</Text> : null}
                <LinkSteps code={code} />
              </View>
            </View>
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: dp(12) }}>
              <Spinner size={20} />
              <Text style={[type.label, { color: color.muted, flex: 1 }]}>Waiting for you to sign in…</Text>
              <ActionButton label="Cancel" first onPress={() => app.cancelSignIn()} />
            </View>
          </>
        )}
      </View>
    </View>
  );
}
