// ==UserScript==
// @name         Acfun 视频下载
// @description  视频下载
// @author       girl-dream
// @version      1.0.0
// @license      The Unlicense
// @namespace    https://github.com/girl-dream/
// @match        https://www.acfun.cn/v/ac*
// @icon         https://cdn.aixifan.com/ico/favicon.ico
// ==/UserScript==
 
(async () => {
    const title = videoInfo.title
    const video_info = JSON.parse(videoInfo.currentVideoInfo.ksPlayJson)
    const data_list = video_info.adaptationSet[0].representation
 
    const pop = document.createElement('div')
    pop.style.cssText = `
        position: fixed;
        top: 0;
        left: 0;
        width: 100%;
        height: 100%;
        background: rgba(0,0,0,0.5);
        display: flex;
        justify-content: center;
        align-items: center;
        z-index: 999999;`
 
    const content = document.createElement('div')
    content.style.cssText = `
            padding: 20px;
            border-radius: 8px;
            width: 200px;
            max-width: 90%;
            max-height: 80vh;
            overflow-y: auto;
            background: #fff;`
    content.innerHTML = `
    <div style="font-size: 1.5rem;margin-bottom: 10px;">选择分辨率</div>
        <select></select>
        <div style="display: flex; justify-content: flex-end; gap: 10px;">
        <button id="cancel-btn" style="padding: 8px 16px; border: none; border-radius: 4px; cursor: pointer;">
            取消
        </button>
        <button id="confirm-btn" style="padding: 8px 16px; border: none; border-radius: 4px; cursor: pointer;">
            下载
        </button>
    </div>`
 
    const select = content.querySelector('select')
    data_list.forEach(e => {
        const op = document.createElement('option')
        op.textContent = e.qualityLabel
        op.value = e.url
        select.append(op)
    })
    pop.append(content)
    const div = document.createElement('div')
    div.title = '下载音频'
    div.innerHTML = '<svg viewBox="0 0 1024 1024" version="1.1" xmlns="http://www.w3.org/2000/svg"><path d="M544.256 605.184l244.224-244.224a31.744 31.744 0 0 1 45.056 45.056l-295.424 295.424a36.864 36.864 0 0 1-51.2 0L190.464 406.528a31.744 31.744 0 1 1 45.056-45.056l244.224 244.224V111.104a32.256 32.256 0 1 1 64 0zM153.6 902.656a32.256 32.256 0 0 1 0-64h716.8a32.256 32.256 0 0 1 0 64z" fill="#5A5A68" p-id="1650"></path></svg>'
    div.style.cssText = `
        width: 35px;
        height: 35px;`
    div.onclick = () => {
        document.body.append(pop)
    }
    content.querySelector('#cancel-btn').onclick = () => {
        pop.remove()
    }
    content.querySelector('#confirm-btn').onclick = () => {
        const url = select.options[select.selectedIndex].value
        pop.remove()
        download(url)
    }
    const progressDiv = document.createElement('div')
    progressDiv.style.cursor = 'default'
    progressDiv.style.fontSize = '1rem'
    document.querySelector('.right-area').prepend(progressDiv, div)
 
    async function download(url) {
        async function parseTsUrls(m3u8Url) {
            const content = await fetch(m3u8Url).then(e => e.text())
            const lines = content.split('\n')
            let currentBase = m3u8Url.substring(0, m3u8Url.lastIndexOf('/') + 1)
            const tsList = []
 
            // 检测是否是主播放列表（多码率）
            const isMasterPlaylist = lines.some(line =>
                line.includes('CODECS') || line.includes('RESOLUTION')
            )
 
            if (isMasterPlaylist) {
                console.log('检测到主播放列表，正在选择最高画质...')
                let bestBandwidth = 0
                let bestUrl = ''
 
                for (let i = 0; i < lines.length; i++) {
                    if (lines[i].includes('BANDWIDTH')) {
                        const bwMatch = lines[i].match(/BANDWIDTH=(\d+)/)
                        const bandwidth = bwMatch ? parseInt(bwMatch[1]) : 0
                        if (bandwidth > bestBandwidth && lines[i + 1]) {
                            bestBandwidth = bandwidth
                            let subUrl = lines[i + 1].trim()
                            if (!subUrl.startsWith('http')) {
                                subUrl = currentBase + subUrl
                            }
                            bestUrl = subUrl
                        }
                    }
                }
                if (bestUrl) {
                    return parseTsUrls(bestUrl)  // 递归解析子播放列表
                }
            }
 
            // 解析当前M3U8中的TS文件和嵌套M3U8
            for (let line of lines) {
                line = line.trim()
                if (line && !line.startsWith('#')) {
                    if (line.endsWith('.ts') || line.includes('.ts?')) {
                        let tsUrl = line
                        if (!tsUrl.startsWith('http')) {
                            tsUrl = currentBase + tsUrl
                        }
                        tsList.push(tsUrl)
                    } else if (line.endsWith('.m3u8') || line.includes('.m3u8?')) {
                        let subUrl = line
                        if (!subUrl.startsWith('http')) {
                            subUrl = currentBase + subUrl
                        }
                        const subTsList = await parseTsUrls(subUrl)
                        tsList.push(...subTsList)
                    }
                }
            }
            return tsList
        }
 
        // 步骤3：下载TS文件（并发控制）
        async function downloadTsFiles(tsUrls, concurrency = 5) {
            const chunks = new Array(tsUrls.length)
            let downloaded = 0
 
            async function downloadOne(index) {
                try {
                    const response = await fetch(tsUrls[index])
                    const blob = await response.blob()
                    chunks[index] = blob
                    downloaded++
                    progressDiv.textContent = `下载进度: ${downloaded}/${tsUrls.length} (${Math.round(downloaded / tsUrls.length * 100)}%)`
                    return true
                } catch (error) {
                    alert(`TS ${index} 下载失败:`, error)
                    chunks[index] = null
                    downloaded++
                    return false
                }
            }
 
            // 并发下载控制
            let index = 0
            const workers = []
 
            for (let i = 0; i < Math.min(concurrency, tsUrls.length); i++) {
                workers.push((async () => {
                    while (index < tsUrls.length) {
                        const currentIndex = index++
                        await downloadOne(currentIndex)
                        // 添加小延迟避免请求过快
                        await new Promise(r => setTimeout(r, 50))
                    }
                })())
            }
 
            await Promise.all(workers)
 
            // 过滤失败的文件
            const validChunks = chunks.filter(chunk => chunk !== null)
            if (validChunks.length === 0) throw new Error('所有TS文件下载失败')
            if (validChunks.length !== tsUrls.length) {
                console.warn(`成功下载 ${validChunks.length}/${tsUrls.length} 个TS文件`)
            }
 
            return validChunks
        }
 
        progressDiv.textContent = '下载进度: 0%'
        progressDiv.style.visibility = 'visible'
        const tsUrls = await parseTsUrls(url)
        if (tsUrls.length === 0) {
            throw new Error('未找到任何TS文件')
        }
        const mergedBlob = new Blob(await downloadTsFiles(tsUrls), { type: 'video/mp2t' })
        const temp_url = URL.createObjectURL(mergedBlob)
        const a = document.createElement('a')
        a.href = temp_url
        a.download = title + '.ts'
        document.body.appendChild(a)
        a.click()
        document.body.removeChild(a)
        URL.revokeObjectURL(temp_url)
        progressDiv.style.visibility = 'hidden'
    }
})();