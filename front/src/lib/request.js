// 对于axios进行二次封装

import axios from 'axios'
import { useUserStore } from "@/store/user";

// 利用axios.create方法创建一个axios实例，并设置请求基础路径，超时时间
const request = axios.create({
    baseURL: "/api",
})


// 设置请求拦截器
request.interceptors.request.use((config) => {
    if (config.authUserId != null && String(useUserStore().user.userId) !== String(config.authUserId)) {
        throw new axios.CanceledError("登录账号已变更");
    }
    const token = useUserStore().user.token
    if (token && token !== '') {
        config.headers['Authorization'] = token;
    }
    return config;
})


// 设置响应拦截器
request.interceptors.response.use((response) => {
    // 如果登录信息已过期，提示重新登录，清除本地的用户信息
    if ([401, 40100, 50005, 50004].includes(response.data.code)) {
        const userStore = useUserStore()
        const expired = userStore.expireSession(response.config?.headers?.Authorization)
        return Promise.reject(expired ? response.data.message : '登录信息已更新，请重试本次操作')
    }
    // 状态码为60000时表示视频未上传过
    if (response.data && response.data.code !== 60000 && response.data.code !== 200) {
        return Promise.reject(response.data.message)
    }
    //响应拦截器成功的回调,一般会进行简化数据
    return response.data
}, (error) => {
    if (error.response?.status === 401) {
        const expired = useUserStore().expireSession(error.config?.headers?.Authorization)
        if (!expired) return Promise.reject(new Error('登录信息已更新，请重试本次操作'))
    }
    return Promise.reject(error)
})

export default request
