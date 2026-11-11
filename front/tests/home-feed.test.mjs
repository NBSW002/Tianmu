import test from 'node:test';
import assert from 'node:assert/strict';
import { splitHomeFeed, uniqueVideos } from '../src/lib/home-feed.mjs';

const videos = count => Array.from({ length: count }, (_, i) => ({ videoId: String(i + 1) }));

test('deduplicates mixed ID types and overlapping pages, preserving the first record', () => {
  const original = { videoId: '1', title: 'first' };
  assert.deepEqual(uniqueVideos([original, { videoId: 1 }, null, {}, { videoId: '' }, ...videos(3)]), [original, { videoId: '2' }, { videoId: '3' }]);
});

test('carousel and cards remain disjoint after refreshing and appending pages', () => {
  for (const offset of [0, 6, 12, 18]) {
    const { featured, cards } = splitHomeFeed([...videos(15), ...videos(30)], offset);
    assert.equal(featured.length, 6);
    assert.equal(cards.length, 24);
    assert.equal(new Set([...featured, ...cards].map(video => video.videoId)).size, 30);
    assert.deepEqual(cards.slice(-15), videos(30).slice(15));
  }
});

test('small catalogues retain ordinary cards without inventing or losing videos', () => {
  for (const count of [0, 1, 2, 6, 8, 12]) {
    const { featured, cards } = splitHomeFeed(videos(count));
    assert.equal(featured.length + cards.length, count);
    assert.ok(featured.length <= 6);
    if (count > 1) assert.ok(cards.length > 0);
    if (count >= 7) assert.ok(cards.length >= 6);
  }
});

test('twenty real videos reach a fourth row at five and six columns', () => {
  for (const columns of [5, 6]) {
    const capacity = columns * 4 - 4;
    const { featured, cards } = splitHomeFeed(videos(20), 0, capacity);
    assert.equal(new Set([...featured, ...cards].map(video => video.videoId)).size, 20);
    assert.ok(cards.length > columns * 3 - 4);
    assert.ok(featured.length >= 1);
  }
});
