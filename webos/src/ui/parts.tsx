import qrcode from "qrcode-generator";
import type { ComponentChildren } from "preact";
import { useEffect, useMemo } from "preact/hooks";
import { rescue } from "./focus";

/** A poster or a wide picture: pressed to open, with what's known about it on top. */
export function Card(props: {
  title: string;
  sub?: string | null;
  image?: string | null;
  wide?: boolean;
  progress?: number | null;
  watched?: boolean;
  tag?: string | null;
  badge?: number | null;
  autofocus?: boolean;
  onPress: () => void;
}) {
  const badge = props.badge != null && props.badge > 1 ? badgeText(props.badge) : null;
  return (
    <button
      class={"card" + (props.wide ? " wide" : "")}
      data-focus
      data-autofocus={props.autofocus ? "" : undefined}
      onClick={props.onPress}
    >
      <div class="art">
        {props.image ? <img src={props.image} alt="" loading="lazy" /> : null}
        {props.tag ? <span class="tag">{props.tag}</span> : null}
        {badge ? <span class={"badge" + (badge.length > 2 ? " long" : "")}>{badge}</span> : null}
        {!badge && props.watched ? <span class="check">✓</span> : null}
        {props.progress != null && props.progress > 0 && !props.watched ? (
          <span class="progress">
            <i style={{ width: `${Math.round(Math.min(1, props.progress) * 100)}%` }} />
          </span>
        ) : null}
      </div>
      <div class="title">{props.title}</div>
      {props.sub ? <div class="sub">{props.sub}</div> : null}
    </button>
  );
}

/** As the Fire TV's: the number as it is up to 999, then "1K". */
export const badgeText = (count: number) => (count < 1000 ? String(count) : `${Math.floor(count / 1000)}K`);

export function Row(props: { title: string; children: ComponentChildren }) {
  return (
    <section class="row">
      <h2>{props.title}</h2>
      <div class="strip">{props.children}</div>
    </section>
  );
}

export function Pill(props: { label: string; on?: boolean; primary?: boolean; autofocus?: boolean; onPress: () => void }) {
  return (
    <button
      class={"pill" + (props.on ? " on" : "") + (props.primary ? " primary" : "")}
      data-focus
      data-autofocus={props.autofocus ? "" : undefined}
      onClick={props.onPress}
    >
      {props.label}
    </button>
  );
}

export const Spinner = () => <div class="spinner" role="progressbar" />;

/** A QR code drawn as SVG, for signing in from a phone. */
export function Qr(props: { text: string }) {
  const svg = useMemo(() => {
    const code = qrcode(0, "M");
    code.addData(props.text);
    code.make();
    return code.createSvgTag({ cellSize: 4, margin: 0, scalable: true });
  }, [props.text]);
  return <div class="qr" dangerouslySetInnerHTML={{ __html: svg }} />;
}

/** Puts the cursor on the screen once it has something to take it. */
export function useRescue(deps: unknown[]) {
  useEffect(() => {
    const t = setTimeout(rescue, 0);
    return () => clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);
}
