import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { LabelPlacer, LabelTips, ScreenPoint } from './label-overlay';

interface Item { id: string; }

describe('LabelTips', () => {
  let tips: LabelTips<Item>;
  let anchor: HTMLElement;
  let label: HTMLButtonElement;
  let outside: HTMLButtonElement;
  const a: Item = { id: 'a' };
  const escape = () => document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideZonelessChangeDetection()] });
    tips = TestBed.runInInjectionContext(() => new LabelTips<Item>((i) => i.id));
    anchor = document.createElement('div');
    anchor.dataset['id'] = 'a';
    label = document.createElement('button');
    anchor.append(label);
    outside = document.createElement('button');
    document.body.append(anchor, outside);
  });
  afterEach(() => {
    anchor.remove();
    outside.remove();
    vi.restoreAllMocks();
  });

  it('opens on show and closes when the pointer leaves, unless the label still has focus', () => {
    tips.show(a);
    expect(tips.open()).toBe(a);
    expect(tips.isOpen({ id: 'a' })).toBe(true);
    label.focus();
    tips.leave(anchor);
    expect(tips.open()).toBe(a);
    outside.focus();
    tips.leave(anchor);
    expect(tips.open()).toBeNull();
  });

  it('closes when focus leaves the anchor, but not when it moves inside it or another tip is open', () => {
    tips.show(a);
    tips.blur(anchor, new FocusEvent('focusout', { relatedTarget: label }));
    expect(tips.open()).toBe(a);
    tips.blur(anchor, new FocusEvent('focusout', { relatedTarget: outside }));
    expect(tips.open()).toBeNull();
    tips.show({ id: 'b' });
    tips.blur(anchor, new FocusEvent('focusout', { relatedTarget: outside })); // anchor a's focus leaving must not shut b
    expect(tips.open()?.id).toBe('b');
  });

  it('closes an open tip on Escape anywhere, listening only while one is open', () => {
    const add = vi.spyOn(document, 'addEventListener');
    const remove = vi.spyOn(document, 'removeEventListener');
    TestBed.tick();
    expect(add.mock.calls.filter(([type]) => type === 'keydown').length).toBe(0); // nothing open, nothing listening

    tips.show(a); // opened by hover: no focus inside it
    TestBed.tick();
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    expect(tips.open()).toBe(a);
    escape();
    expect(tips.open()).toBeNull();
    TestBed.tick();
    const added = add.mock.calls.filter(([type]) => type === 'keydown').map(([, fn]) => fn);
    expect(added.length).toBe(1);
    expect(remove.mock.calls.filter(([type]) => type === 'keydown').map(([, fn]) => fn)).toEqual(added);
  });

  it('stops listening when its owner is destroyed with a tip open', () => {
    const remove = vi.spyOn(document, 'removeEventListener');
    tips.show(a);
    TestBed.tick();
    TestBed.resetTestingModule();
    expect(remove.mock.calls.some(([type]) => type === 'keydown')).toBe(true);
  });

  it('sends focus back to the label before closing, so Escape inside a tip does not drop focus', () => {
    const link = document.createElement('a');
    link.href = '#';
    anchor.append(link);
    tips.show(a);
    link.focus();
    tips.closeTo(label);
    expect(document.activeElement).toBe(label);
    expect(tips.open()).toBeNull();
  });
});

describe('LabelPlacer', () => {
  const W = 800;
  let placer: LabelPlacer;
  let anchors: { nativeElement: HTMLElement }[];
  let points: Map<string, ScreenPoint>;
  const el = (id: string) => anchors.find((r) => r.nativeElement.dataset['id'] === id)!.nativeElement;
  const dx = (id: string) => el(id).style.getPropertyValue('--dx');

  beforeEach(() => {
    placer = new LabelPlacer({ width: 220, flipBelow: 176 });
    anchors = ['a', 'b', 'c'].map((id) => {
      const nativeElement = document.createElement('div');
      nativeElement.dataset['id'] = id;
      return { nativeElement };
    });
    points = new Map([['a', { x: 400, y: 300, z: 10 }], ['b', { x: 20, y: 100, z: 20 }]]);
  });

  it('pins each anchor to its point, marks it placed, and skips anchors without one', () => {
    placer.place(anchors, points, W, { tips: true, moving: false, force: false });
    expect(el('a').style.transform).toBe('translate(400.0px, 300.0px)');
    expect(el('a').classList.contains('placed')).toBe(true);
    expect(el('a').style.getPropertyValue('--z')).toBe('10');
    expect(el('c').classList.contains('placed')).toBe(false);
    expect(el('c').style.transform).toBe('');
  });

  it('rewrites nothing for a move of half a pixel or less, and only the rank when that alone changed', () => {
    placer.place(anchors, points, W, { tips: true, moving: false, force: false });
    el('a').style.transform = 'none'; // sentinels: a rewrite would replace them
    el('a').style.setProperty('--dx', 'x');
    points.get('a')!.x += 0.4;
    placer.place(anchors, points, W, { tips: true, moving: false, force: false });
    expect(el('a').style.transform).toBe('none');
    expect(dx('a')).toBe('x');
    points.get('a')!.z = 11;
    placer.place(anchors, points, W, { tips: true, moving: false, force: false });
    expect(el('a').style.getPropertyValue('--z')).toBe('11');
    expect(el('a').style.transform).toBe('none');
    points.get('a')!.x += 1;
    placer.place(anchors, points, W, { tips: true, moving: false, force: false });
    expect(el('a').style.transform).toBe('translate(401.4px, 300.0px)');
  });

  it('keeps the tip inside the stage and flips it below a label near the top', () => {
    placer.place(anchors, points, W, { tips: true, moving: false, force: false });
    expect(dx('a')).toBe('-110.0px'); // centred over its label
    expect(dx('b')).toBe('-12.0px'); // pushed right, 8px from the stage edge
    expect(el('a').classList.contains('below')).toBe(false);
    expect(el('b').classList.contains('below')).toBe(true);
  });

  it('leaves the tip offsets while moving, except on the open anchor, and catches up when forced', () => {
    placer.place(anchors, points, W, { tips: true, moving: false, force: false });
    el('b').classList.add('on'); // b's tip is open
    points.get('a')!.x = 700;
    points.get('b')!.x = 300;
    placer.place(anchors, points, W, { tips: true, moving: true, force: false });
    expect(el('a').style.transform).toBe('translate(700.0px, 300.0px)');
    expect(dx('a')).toBe('-110.0px'); // stale until the motion stops
    expect(dx('b')).toBe('-110.0px'); // the open one follows at once
    placer.place(anchors, points, W, { tips: true, moving: false, force: true });
    expect(dx('a')).toBe('-128.0px');
  });

  it('never writes tip offsets for anchors without a tip', () => {
    placer.place(anchors, points, W, { tips: false, moving: false, force: true });
    expect(dx('a')).toBe('');
    expect(el('a').classList.contains('placed')).toBe(true);
  });
});
