import test from 'node:test';
import assert from 'node:assert/strict';
import { imageVariantUrl } from '../src/lib/media-image.mjs';

test('image variants use bounded sizes and preserve existing query parameters', () => {
  const path='/api/media/12345678-1234-abcd.png';
  assert.equal(imageVariantUrl(path,320),`${path}?w=320`);
  assert.equal(imageVariantUrl(`${path}?v=1&w=960`,640),`${path}?v=1&w=640`);
  for(const size of [0,1,400,1920,'640',undefined]) assert.equal(imageVariantUrl(path,size),path);
});
test('variant requests cannot rewrite video, remote, arbitrary or nested paths', () => {
  for(const path of ['/api/media/videos/12345678.mp4','https://example.org/12345678.png','/api/media/../secret.png','/api/media/nested/12345678.png','/static/12345678.png'])
    assert.equal(imageVariantUrl(path,640),path);
});
