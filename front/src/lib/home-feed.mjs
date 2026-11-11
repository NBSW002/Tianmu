export function uniqueVideos(videos) {
  const seen = new Set();
  return videos.filter(video => {
    if (video?.videoId == null || String(video.videoId).trim() === '') return false;
    const id = String(video.videoId);
    if (seen.has(id)) return false;
    seen.add(id);
    return true;
  });
}

export function splitHomeFeed(videos, offset = 0) {
  const unique = uniqueVideos(videos);
  const first = unique.slice(0, 15);
  const start = first.length ? offset % first.length : 0;
  const ordered = [...first.slice(start), ...first.slice(0, start), ...unique.slice(15)];
  // Keep up to six ordinary cards when the catalogue is small.
  const featuredCount = Math.min(6, Math.max(1, ordered.length - 6));
  return { featured: ordered.slice(0, featuredCount), cards: ordered.slice(featuredCount) };
}
