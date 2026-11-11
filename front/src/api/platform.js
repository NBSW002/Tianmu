import request from "@/lib/request"
import { postUploadPart } from "@/lib/upload-video.mjs"

export const uploadApi = {
    start: (data, signal) => request({ url: '/file/uploads', method: 'POST', data, signal }).then(res => res.data),
    progress: (id, signal) => request({ url: '/file/uploads/' + id + '/progress', signal }).then(res => res.data),
    urls: (id, offset, limit, signal) => request({ url: '/file/uploads/' + id + '/urls', params: { offset, limit }, signal }).then(res => res.data),
    merge: (id, signal) => request({ url: '/file/uploads/' + id + '/merge', method: 'POST', signal }).then(res => res.data),
    put: postUploadPart,
}

// 投稿
export const submitFile = (videoInfo) => {
    return request({
        url: "/video/submit",
        method: "POST",
        data: videoInfo,
        headers: {
            'Content-Type': 'multipart/form-data',
        }
    })
}
