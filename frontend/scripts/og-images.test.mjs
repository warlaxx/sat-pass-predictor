import { test } from 'node:test';
import assert from 'node:assert/strict';
import { eventDate, eventHeading, previewCard, previewPath, renderPreview, HEIGHT, WIDTH } from './og-images.mjs';

const child = (name, parent = 'S60322') => ({ id: 'S100685', name, evidence: { parent } });
const release = {
  id: 'S100685', kind: 'RELEASE', childCount: 1, parent: { name: 'USA 396' }, children: [child('USA 667')],
  date: { text: '2026 Sep?', at: '2026-09-01T00:00:00Z', precision: 'MONTH', uncertain: true },
};

test('the preview says what the page heading says, in both languages', () => {
  assert.equal(eventHeading(release, 'en'), 'USA 396 released USA 667');
  assert.equal(eventHeading(release, 'fr'), 'USA 396 a libéré USA 667');
  const breakup = { ...release, kind: 'FRAGMENTATION', childCount: 3, parent: null, children: [child('deb', 'S40340*')] };
  assert.equal(eventHeading(breakup, 'en'), '3 fragments separated from S40340');
  assert.equal(eventHeading({ ...release, childCount: 12 }, 'fr'), 'USA 396 a libéré 12 objets');
});

test('the date keeps the precision it was recorded with', () => {
  assert.equal(eventDate(release.date, 'en'), 'September 2026');
  assert.equal(eventDate(release.date, 'fr'), 'septembre 2026');
  assert.equal(eventDate({ text: '2026 Sep 22', at: '2026-09-22T00:00:00Z', precision: 'DAY' }, 'en'), '22 September 2026');
});

test('each event has its address, and the card its size', () => {
  assert.equal(previewPath('S100685', 'fr'), '/og/separations/S100685.fr.png');
  const card = previewCard(release, 'en');
  assert.equal(card.props.style.width, WIDTH);
  assert.equal(card.props.style.height, HEIGHT);
});

test('renders a 1200×630 PNG', async () => {
  const png = await renderPreview(release, 'fr');
  assert.deepEqual([...png.subarray(1, 4)].map((c) => String.fromCharCode(c)).join(''), 'PNG');
  assert.equal(png.readUInt32BE(16), 1200);
  assert.equal(png.readUInt32BE(20), 630);
});
