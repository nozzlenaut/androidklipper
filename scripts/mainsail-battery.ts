/** Optional telemetry must never hold up printer temperature updates. */
export function createBatterySampler(
    request: typeof fetch = fetch,
    clock: () => number = Date.now
): () => number | null {
    let value: number | null = null
    let sampledAt = 0
    let nextPoll = 0
    let pending = false
    return () => {
        const now = clock()
        if (!pending && now >= nextPoll) {
            pending = true
            nextPoll = now + 5000
            const controller = new AbortController()
            // Abort AND settle even if an older WebView ignores abort signals.
            let timer: ReturnType<typeof setTimeout>
            const timeout = new Promise<never>((_, reject) => {
                timer = setTimeout(() => {
                    controller.abort()
                    reject(new Error('Battery telemetry timeout'))
                }, 2000)
            })
            const poll = async () => {
                const response = await request('/androidklipper/battery', {
                    cache: 'no-store', signal: controller.signal,
                })
                if (!response.ok) throw new Error('Battery telemetry unavailable')
                return response.json()
            }
            void Promise.race([poll(), timeout]).then((battery) => {
                value = battery.present !== false && typeof battery.level === 'number' &&
                    Number.isFinite(battery.level) && battery.level >= 0 && battery.level <= 100
                    ? battery.level / 100 : null
                sampledAt = clock()
            }).catch(() => {
                // Keep a recent good sample through a transient failure only.
            }).finally(() => {
                clearTimeout(timer)
                pending = false
            })
        }
        return now - sampledAt <= 30000 ? value : null
    }
}
