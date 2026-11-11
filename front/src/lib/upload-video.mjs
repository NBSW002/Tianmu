export const CHUNK_BYTES = 5 * 1024 * 1024;

export async function postUploadPart(part, blob, signal) {
  const body = new FormData();
  for (const [key, value] of Object.entries(part.fields)) body.append(key, value);
  body.append('file', blob);
  const response = await fetch(part.url, { method: 'POST', body, signal, credentials: 'omit' });
  if (!response.ok) throw new Error('分片上传失败，请重试');
}

export async function uploadVideo(file, fileHash, api, signal, onProgress) {
  const fileType = file.name.split('.').pop().toLowerCase();
  if (!file.size || file.size > 3 * 1024 ** 3 || !['mp4', 'mov', 'avi', 'webm'].includes(fileType)) {
    throw new Error('请选择 MP4、MOV、AVI 或 WebM 视频，大小不超过3GB');
  }
  const check = () => signal.throwIfAborted();
  check();
  const session = await api.start({ fileHash, sizeBytes: file.size, fileType }, signal);
  check();
  if (session.status === 'COMPLETE') {
    onProgress(100);
    return session.fileUrl;
  }
  // A previous merge can still be running after the browser lost its response.
  if (session.status === 'MERGING') {
    const url = await api.merge(session.uploadId, signal);
    check();
    onProgress(100);
    return url;
  }
  const count = Math.ceil(file.size / CHUNK_BYTES);
  const present = new Set(await api.progress(session.uploadId, signal));
  check();
  let completed = present.size;
  onProgress(Math.floor(completed / count * 95));
  for (let offset = 0; offset < count; offset += 32) {
    check();
    const end = Math.min(count, offset + 32);
    const pending = Array.from({ length: end - offset }, (_, index) => offset + index)
      .filter(index => !present.has(index));
    if (!pending.length) continue;
    const parts = await api.urls(session.uploadId, offset, end - offset, signal);
    check();
    for (let start = 0; start < pending.length; start += 4) {
      const results = await Promise.allSettled(pending.slice(start, start + 4).map(async index => {
        check();
        await api.put(parts[index - offset], file.slice(index * CHUNK_BYTES, (index + 1) * CHUNK_BYTES), signal);
        check();
        completed++;
        onProgress(Math.floor(completed / count * 95));
      }));
      const failed = results.find(result => result.status === 'rejected');
      if (failed) throw failed.reason;
    }
  }
  check();
  const url = await api.merge(session.uploadId, signal);
  check();
  onProgress(100);
  return url;
}
