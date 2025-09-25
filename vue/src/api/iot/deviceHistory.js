import request from '@/utils/request'

export function listDeviceHistory(query) {
  return request({
    url: '/iot/deviceLog/history',
    method: 'get',
    params: query,
  })
}
