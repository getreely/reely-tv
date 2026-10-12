import React from 'react';
import type { View } from 'react-native';
import { focusWhenReady } from './focus';
import { MenuHeading, MenuItem, MenuLayer } from './menus';

/*
 * Choosing one value from a list (ChoiceList.kt's ChoicePanel): a sort, a decade, a
 * setting. The panel opens on what's chosen now; picking closes it, and the cursor goes
 * back to whatever opened it.
 */

export interface ChoiceRequest {
  title: string;
  options: Array<{ label: string; detail?: string | null }>;
  selected: number;
  onPick: (index: number) => void;
  /** Where the cursor goes back to when the list closes: whatever opened it. */
  returnTo?: React.RefObject<View | null>;
}

export function ChoicePanel(props: { request: ChoiceRequest; onClose: () => void }) {
  const { request } = props;
  const close = () => {
    props.onClose();
    if (request.returnTo) focusWhenReady(request.returnTo);
  };
  return (
    <MenuLayer onClose={close}>
      <MenuHeading title={request.title} />
      {request.options.map((o, i) => (
        <MenuItem key={`${i}:${o.label}`} label={o.label} detail={o.detail} checked={i === request.selected} preferred={i === request.selected}
          onPress={() => { request.onPick(i); close(); }} />
      ))}
    </MenuLayer>
  );
}
